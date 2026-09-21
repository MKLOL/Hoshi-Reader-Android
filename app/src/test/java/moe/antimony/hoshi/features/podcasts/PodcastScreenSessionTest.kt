package moe.antimony.hoshi.features.podcasts

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PodcastScreenSessionTest {
    @Test fun leavingScreenOrChangingAccountCancelsEveryChildObserver() = runTest {
        val accounts = MutableStateFlow<String?>("account-a")
        val visible = MutableStateFlow(false)
        val active = mutableSetOf<String>()
        val events = mutableListOf<String>()
        val watcher = launch {
            observePodcastScreenSession(accounts, visible) { account ->
                if (account != null) {
                    launch {
                        active += account
                        events += "start-$account"
                        try { awaitCancellation() } finally { active -= account; events += "stop-$account" }
                    }
                    awaitCancellation()
                }
            }
        }
        runCurrent()
        assertTrue(active.isEmpty())
        visible.value = true
        runCurrent()
        assertEquals(setOf("account-a"), active)
        accounts.value = "account-b"
        runCurrent()
        assertEquals(setOf("account-b"), active)
        assertEquals(listOf("start-account-a", "stop-account-a", "start-account-b"), events)
        visible.value = false
        runCurrent()
        assertTrue(active.isEmpty())
        visible.value = true
        runCurrent()
        accounts.value = null
        runCurrent()
        assertTrue(active.isEmpty())
        watcher.cancel()
    }
}
