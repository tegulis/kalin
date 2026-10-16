package net.tegulis.kalin.engine.epoll

import java.lang.foreign.Arena

/** Availability of the native epoll transport. */
internal object Epoll {
    private val reason: String? by lazy { probe() }

    private fun probe(): String? {
        val os = System.getProperty("os.name").lowercase()
        if (!os.contains("linux")) return "not Linux ($os)"
        val arch = System.getProperty("os.arch")
        if (arch != "amd64" && arch != "x86_64" && arch != "aarch64") return "unsupported architecture $arch"
        if (!Epoll::class.java.module.isNativeAccessEnabled) {
            return "native access is not enabled (run with --enable-native-access=ALL-UNNAMED or the jar manifest entry Enable-Native-Access: ALL-UNNAMED)"
        }
        return try {
            Arena.ofConfined().use { arena ->
                val s = Native.Scratch(arena)
                val epfd = Native.epollCreate(s)
                Native.close(s, epfd)
            }
            null
        } catch (t: Throwable) {
            "native calls failed: $t"
        }
    }

    /** True if the epoll transport can be used. */
    fun available(): Boolean = reason == null

    /** Why epoll is unavailable, or null. */
    fun unavailabilityReason(): String? = reason
}
