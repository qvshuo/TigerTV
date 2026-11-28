@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package art.anjing.tigertv.cli

import kotlinx.cinterop.*
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform
import platform.posix.*

actual fun initializeConsole() { signal(SIGPIPE, SIG_IGN) }
actual fun output(text: String, error: Boolean) {
    val bytes = text.encodeToByteArray()
    if (bytes.isEmpty()) return
    val stream = if (error) stderr else stdout
    bytes.usePinned {
        val size = bytes.size.toULong()
        if (fwrite(it.addressOf(0), 1u, size, stream) != size || fflush(stream) != 0) {
            terminate(if (posix_errno() == EPIPE) 0 else 1)
        }
    }
}

@OptIn(ExperimentalNativeApi::class)
actual fun cliLogPath(): String? {
    val macOS = Platform.osFamily == OsFamily.MACOSX
    if (!macOS) {
        val state = getenv("XDG_STATE_HOME")?.toKString()?.takeIf { it.startsWith('/') }
        if (state != null) return "${state.trimEnd('/')}/tigertv/tigertv.log"
    }
    val home = getenv("HOME")?.toKString()?.takeIf { it.startsWith('/') }
        ?: getpwuid(getuid())?.pointed?.pw_dir?.toKString()
        ?: return null
    val directory = if (macOS) "Library/Logs/TigerTV" else ".local/state/tigertv"
    return "${home.trimEnd('/')}/$directory/tigertv.log"
}

actual fun terminate(code: Int): Nothing { exit(code); error("unreachable") }
