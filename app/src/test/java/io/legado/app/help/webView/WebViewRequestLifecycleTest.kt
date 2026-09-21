package io.legado.app.help.webView

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewRequestLifecycleTest {
    @Test
    fun navigationCancelsOldRequestsAndAcceptsOnlyTheNewDocument() {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val lease = WebViewRequestLifecycle(parent)
            val oldPage = lease.currentPage()!!
            val response = CompletableDeferred<String>()
            val delivered = mutableListOf<String>()
            val oldRequest = oldPage.scope.launch {
                val value = response.await()
                if (oldPage.isCurrent) delivered += value
            }

            val newPage = lease.startPage()!!
            response.complete("old document")
            newPage.scope.launch {
                if (newPage.isCurrent) delivered += "new document"
            }

            assertTrue(oldRequest.isCancelled)
            assertFalse(oldPage.isCurrent)
            assertTrue(newPage.isCurrent)
            assertEquals(listOf("new document"), delivered)
            assertTrue(parent.isActive)
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun closeCancelsBothInitialLoadAndDocumentRequestsWithoutCancellingHost() {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val lease = WebViewRequestLifecycle(parent)
            val page = lease.currentPage()!!
            val neverCompleted = CompletableDeferred<Unit>()
            val initialLoad = lease.scope.launch { neverCompleted.await() }
            val bridgeRequest = page.scope.launch { neverCompleted.await() }

            lease.close()
            lease.close()

            assertTrue(initialLoad.isCancelled)
            assertTrue(bridgeRequest.isCancelled)
            assertFalse(page.isCurrent)
            assertFalse(lease.isActive)
            assertNull(lease.currentPage())
            assertNull(lease.startPage())
            assertTrue(parent.isActive)
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun lateUncooperativeResultCannotWriteToTheNextLease() = runBlocking {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val oldLease = WebViewRequestLifecycle(parent)
            val oldPage = oldLease.currentPage()!!
            val completedOutsideCoroutine = CompletableDeferred<Unit>()
            val delivered = mutableListOf<String>()
            val oldRequest = oldPage.scope.launch {
                // Simulate a queued callback or native operation that ignores cancel.
                withContext(NonCancellable) {
                    completedOutsideCoroutine.await()
                    if (oldPage.isCurrent) delivered += "stale result"
                }
            }
            oldLease.close()
            val newLease = WebViewRequestLifecycle(parent)
            val newPage = newLease.currentPage()!!
            completedOutsideCoroutine.complete(Unit)
            oldRequest.join()
            newPage.scope.launch {
                if (newPage.isCurrent) delivered += "current result"
            }.join()

            // Page numbers can repeat across leases; identity must still isolate them.
            assertEquals(oldPage.generation, newPage.generation)
            assertEquals(listOf("current result"), delivered)
            assertTrue(newLease.isActive)
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun closingOneDialogDoesNotCancelAnotherDialogOrItsRequests() {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val closed = WebViewRequestLifecycle(parent)
            val open = WebViewRequestLifecycle(parent)
            val openPage = open.currentPage()!!
            val response = CompletableDeferred<String>()
            val delivered = mutableListOf<String>()
            val request = openPage.scope.launch {
                val value = response.await()
                if (openPage.isCurrent) delivered += value
            }

            closed.close()
            response.complete("still open")

            assertFalse(request.isCancelled)
            assertEquals(listOf("still open"), delivered)
            assertTrue(openPage.isCurrent)
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun destroyedViewParentInvalidatesEvenWithoutAnExplicitClose() {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val lease = WebViewRequestLifecycle(parent)
        val page = lease.currentPage()!!
        parent.cancel()

        assertFalse(lease.isActive)
        assertFalse(page.isCurrent)
        assertNull(lease.currentPage())
        assertNull(lease.startPage())
    }
}
