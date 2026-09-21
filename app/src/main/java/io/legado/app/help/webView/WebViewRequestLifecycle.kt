package io.legado.app.help.webView

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive

/** One WebView lease, with cancellable requests for each main document. */
class WebViewRequestLifecycle(parentScope: CoroutineScope) {
    private val leaseJob = SupervisorJob(parentScope.coroutineContext[Job])
    val scope = CoroutineScope(parentScope.coroutineContext + leaseJob)
    private var nextPage = 0L

    @Volatile
    private var page: Page? = newPage()

    val isActive: Boolean
        get() = leaseJob.isActive

    fun currentPage(): Page? = page?.takeIf { it.isCurrent }

    @Synchronized
    fun startPage(): Page? {
        if (!isActive) return null
        val previous = page
        val current = newPage()
        page = current
        previous?.scope?.cancel()
        return current
    }

    @Synchronized
    fun close() {
        page = null
        leaseJob.cancel()
    }

    private fun newPage(): Page = Page(
        this,
        ++nextPage,
        CoroutineScope(scope.coroutineContext + SupervisorJob(leaseJob))
    )

    class Page internal constructor(
        private val owner: WebViewRequestLifecycle,
        val generation: Long,
        val scope: CoroutineScope
    ) {
        val isCurrent: Boolean
            get() = owner.page === this && owner.isActive && scope.isActive
    }
}
