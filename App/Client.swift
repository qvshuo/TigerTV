import Foundation

@MainActor
final class TigerTVClient: ObservableObject {
    @Published var isLoading = false
    @Published var errorMessage: String?

    private var cliPath: String? {
        Bundle.main.path(forResource: "tigertv-cli", ofType: "py")
    }

    private func runCLI(arguments: [String], timeout: TimeInterval, timeoutLabel: String) async throws -> Data {
        guard let cliPath else {
            throw TigerTVError.cliNotFound
        }

        guard let pythonPath = await findPython3() else {
            throw TigerTVError.pythonNotFound
        }

        let process = Process()
        process.executableURL = URL(fileURLWithPath: pythonPath)
        process.arguments = [cliPath] + arguments
        let processBox = ProcessBox(process)

        let pipe = Pipe()
        let errPipe = Pipe()
        process.standardOutput = pipe
        process.standardError = errPipe

        // Stream stdout/stderr asynchronously to avoid pipe buffer deadlock
        // when the child produces large outputs.
        let outBuffer = DataBuffer()
        let errBuffer = DataBuffer()

        pipe.fileHandleForReading.readabilityHandler = { handle in
            let chunk = handle.availableData
            if chunk.isEmpty {
                handle.readabilityHandler = nil
            } else {
                outBuffer.append(chunk)
            }
        }

        errPipe.fileHandleForReading.readabilityHandler = { handle in
            let chunk = handle.availableData
            if chunk.isEmpty {
                handle.readabilityHandler = nil
            } else {
                errBuffer.append(chunk)
            }
        }

        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                let gate = ResumeGate()

                let resumeOnce: @Sendable (Result<Data, Error>) -> Void = { result in
                    gate.resume {
                        switch result {
                        case .success(let data):
                            continuation.resume(returning: data)
                        case .failure(let error):
                            continuation.resume(throwing: error)
                        }
                    }
                }

                process.terminationHandler = { proc in
                    pipe.fileHandleForReading.readabilityHandler = nil
                    errPipe.fileHandleForReading.readabilityHandler = nil

                    outBuffer.append(pipe.fileHandleForReading.readDataToEndOfFile())
                    errBuffer.append(errPipe.fileHandleForReading.readDataToEndOfFile())

                    let data = outBuffer.consume()
                    let errBytes = errBuffer.consume()

                    if proc.terminationStatus == 0 {
                        resumeOnce(.success(data))
                    } else {
                        let err = String(data: errBytes, encoding: .utf8) ?? "未知错误"
                        resumeOnce(.failure(TigerTVError.cliError(err.trimmingCharacters(in: .whitespacesAndNewlines))))
                    }
                }

                do {
                    try process.run()
                    DispatchQueue.global().asyncAfter(deadline: .now() + timeout) {
                        guard process.isRunning else { return }
                        process.terminate()
                        resumeOnce(.failure(TigerTVError.commandTimeout(timeoutLabel)))
                    }
                } catch {
                    resumeOnce(.failure(error))
                }
            }
        } onCancel: {
            processBox.terminate()
        }
    }

    private nonisolated func findPython3() async -> String? {
        // /usr/bin/python3 is a shim that calls xcrun internally,
        // which fails inside App Sandbox. We must find the real interpreter.
        let candidates = [
            // Xcode bundled Python
            "/Applications/Xcode.app/Contents/Developer/Library/Frameworks/Python3.framework/Versions/Current/bin/python3",
            "/Applications/Xcode.app/Contents/Developer/Library/Frameworks/Python3.framework/Versions/3.9/bin/python3",
            // Command Line Tools Python
            "/Library/Developer/CommandLineTools/Library/Frameworks/Python3.framework/Versions/Current/bin/python3",
            "/Library/Developer/CommandLineTools/Library/Frameworks/Python3.framework/Versions/3.9/bin/python3",
            // System Python
            "/System/Library/Frameworks/Python.framework/Versions/3.9/bin/python3",
            "/System/Library/Frameworks/Python.framework/Versions/Current/bin/python3",
            // Homebrew
            "/opt/homebrew/bin/python3",
            "/usr/local/bin/python3",
            // Last resort: the shim itself
            "/usr/bin/python3",
        ]
        for path in candidates {
            if FileManager.default.isExecutableFile(atPath: path) {
                // If it's a symlink, try to resolve to the real path
                if let resolved = try? FileManager.default.destinationOfSymbolicLink(atPath: path) {
                    let absolute = (path as NSString).deletingLastPathComponent as NSString
                    let full = absolute.appendingPathComponent(resolved)
                    if FileManager.default.isExecutableFile(atPath: full) {
                        return full
                    }
                }
                return path
            }
        }

        // Fallback: search PATH directories directly to avoid `which` which
        // triggers xcrun and fails inside App Sandbox.
        let pathEnv = ProcessInfo.processInfo.environment["PATH"] ?? ""
        let paths = pathEnv.split(separator: ":").map(String.init)
        for dir in paths {
            let candidate = (dir as NSString).appendingPathComponent("python3")
            if FileManager.default.isExecutableFile(atPath: candidate) {
                return candidate
            }
        }
        return nil
    }

    func search(keyword: String) async throws -> [SearchResult] {
        isLoading = true
        defer { isLoading = false }

        let data = try await runCLI(arguments: ["search", keyword], timeout: 20, timeoutLabel: "搜索")
        let response = try JSONDecoder().decode(SearchResponse.self, from: data)
        return response.results
    }

    func fetch(site: String, vodID: Int) async throws -> FetchResponse {
        isLoading = true
        defer { isLoading = false }

        let data = try await runCLI(arguments: ["fetch", "--site", site, "--vod_id", String(vodID)], timeout: 20, timeoutLabel: "获取剧集")
        let response = try JSONDecoder().decode(FetchResponse.self, from: data)
        return response
    }
}

private final class DataBuffer: @unchecked Sendable {
    private let lock = NSLock()
    private var data = Data()

    func append(_ chunk: Data) {
        lock.lock()
        data.append(chunk)
        lock.unlock()
    }

    func consume() -> Data {
        lock.lock()
        let result = data
        lock.unlock()
        return result
    }
}

private final class ProcessBox: @unchecked Sendable {
    private let lock = NSLock()
    private weak var process: Process?

    init(_ process: Process) {
        self.process = process
    }

    func terminate() {
        lock.lock()
        let process = process
        lock.unlock()
        if process?.isRunning == true {
            process?.terminate()
        }
    }
}

private final class ResumeGate: @unchecked Sendable {
    private let lock = NSLock()
    private var resumed = false

    func resume(_ body: () -> Void) {
        lock.lock()
        defer { lock.unlock() }
        guard !resumed else { return }
        resumed = true
        body()
    }
}
