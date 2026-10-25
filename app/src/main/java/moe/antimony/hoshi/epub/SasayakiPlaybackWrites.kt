package moe.antimony.hoshi.epub

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pending playback writes belong to a book, so closing a reader cannot detach their ordering. */
internal object SasayakiPlaybackWrites {
    private val pending = mutableMapOf<Any, CompletableDeferred<Unit>>()

    fun bookKey(bookRoot: File): String = bookRoot.canonicalPath

    fun enqueue(key: Any, scope: CoroutineScope, write: suspend () -> Unit): Job {
        val completion = CompletableDeferred<Unit>()
        // Reserve the place before dispatching: a newly opened reader must see this write
        // even when its old reader's coroutine has not reached the IO dispatcher yet.
        val previous = synchronized(pending) { pending.put(key, completion) }
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(NonCancellable) {
                    previous?.join()
                    write()
                }
            } finally {
                synchronized(pending) {
                    if (pending[key] === completion) pending.remove(key)
                }
                completion.complete(Unit)
            }
        }
    }

    suspend fun await(key: Any) {
        while (true) {
            val write = synchronized(pending) { pending[key] } ?: return
            write.join()
        }
    }
}
