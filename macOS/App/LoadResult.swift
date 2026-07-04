import Foundation

enum LoadResult<T: Sendable>: Sendable {
    case success(T)
    case failure(any Error)
}
