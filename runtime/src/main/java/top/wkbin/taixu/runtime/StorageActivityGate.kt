package top.wkbin.taixu.runtime

/** Commands may run concurrently; cleanup needs an idle runtime and prevents new launches. */
internal class StorageActivityGate {
    private val monitor = Any()
    private var active = 0
    private var cleaning = false

    suspend fun <T> activity(block: suspend () -> T): T {
        synchronized(monitor) {
            check(!cleaning) { "Storage cleanup is in progress. Start the task later." }
            active++
        }
        try { return block() } finally { synchronized(monitor) { active-- } }
    }

    suspend fun cleanup(block: suspend () -> Unit) {
        synchronized(monitor) {
            check(!cleaning && active == 0) { "A command or build is running. Finish it before cleaning storage." }
            cleaning = true
        }
        try { block() } finally { synchronized(monitor) { cleaning = false } }
    }
}
