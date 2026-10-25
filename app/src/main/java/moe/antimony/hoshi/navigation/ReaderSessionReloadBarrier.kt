package moe.antimony.hoshi.navigation

/**
 * A reader must stop producing writes before a sync import, then finish its final queued
 * saves before the new reader loads a base. Retired sessions cannot resume or save on dispose.
 */
internal class ReaderSessionReloadBarrier {
    private data class Session(val retire: () -> Unit, val awaitWrites: suspend () -> Unit)
    private var current: Session? = null
    private val pending = mutableListOf<Session>()

    fun attach(retire: (() -> Unit)?, awaitWrites: suspend () -> Unit = {}) {
        current = retire?.let { Session(it, awaitWrites) }
    }

    fun retire() {
        val session = current ?: return
        current = null
        session.retire()
        pending += session
    }

    suspend fun awaitWrites() {
        val retiring = pending.toList()
        for (session in retiring) session.awaitWrites()
        pending.removeAll(retiring.toSet())
    }

    suspend fun awaitCurrentWrites() {
        awaitWrites()
        current?.awaitWrites?.invoke()
    }
}
