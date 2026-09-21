package moe.antimony.hoshi.features.podcasts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Every screen/account transition cancels and joins the old polling and download observers. */
internal suspend fun observePodcastScreenSession(
    accounts: Flow<String?>,
    visible: Flow<Boolean>,
    session: suspend CoroutineScope.(String?) -> Unit,
) {
    combine(accounts, visible) { account, shown -> account.takeIf { shown } }
        .distinctUntilChanged().collectLatest { account -> coroutineScope { session(account) } }
}
