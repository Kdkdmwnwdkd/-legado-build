package io.legado.app.help.webView

import android.webkit.WebView
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.webView.WebViewLifecycleTestSupport.Heartbeat
import io.legado.app.help.webView.WebViewLifecycleTestSupport.advancing
import io.legado.app.help.webView.WebViewLifecycleTestSupport.awaitMain
import io.legado.app.help.webView.WebViewLifecycleTestSupport.findWebView
import io.legado.app.help.webView.WebViewLifecycleTestSupport.main
import io.legado.app.ui.login.SourceLoginJsExtensions
import io.legado.app.ui.widget.dialog.BottomWebViewDialog
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class BottomWebViewDialogLifecycleTest {
    private lateinit var host: WebViewDialogLifecycleTestActivity
    private lateinit var reader: WebView
    private var readerProbe = Heartbeat()
    private val source = BookSource(
        bookSourceUrl = "https://webview-lifecycle.invalid/source/${System.nanoTime()}",
        bookSourceName = "WebView lifecycle test fixture"
    )

    @Before
    fun setUp() {
        appDb.bookSourceDao.insert(source)
        host = WebViewLifecycleTestSupport.startHost()
        main {
            reader = WebViewLifecycleTestSupport.reader(host, readerProbe)
            reader.resumeTimers()
        }
        advancing(readerProbe, "initial reader")
    }

    @After
    fun tearDown() {
        main {
            if (::reader.isInitialized) reader.resumeTimers()
            if (::host.isInitialized) {
                host.supportFragmentManager.fragments.filterIsInstance<BottomWebViewDialog>().forEach {
                    it.dismissAllowingStateLoss()
                }
                host.supportFragmentManager.executePendingTransactions()
            }
            if (::reader.isInitialized) WebViewLifecycleTestSupport.destroy(reader)
            if (::host.isInitialized) host.finish()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        appDb.bookSourceDao.delete(source)
    }

    @Test
    fun sourceBrowserCloseDoesNotFreezeReaderOrTheNextActivity() {
        repeat(3) { cycle ->
            val dialogProbe = Heartbeat()
            val (dialog, webView) = openDialog(probe = dialogProbe)
            advancing(dialogProbe, "source dialog $cycle")
            dismissAndReset(dialog, webView)
            advancing(readerProbe, "reader after dialog $cycle")

            main {
                WebViewLifecycleTestSupport.destroy(reader)
                host.finish()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            host = WebViewLifecycleTestSupport.startHost()
            readerProbe = Heartbeat()
            main { reader = WebViewLifecycleTestSupport.reader(host, readerProbe) }
            // Do not resume global timers here: this is the failing reentry sequence.
            advancing(readerProbe, "new Activity after source dialog $cycle")
        }
    }

    @Test
    fun recreatedDialogViewAcquiresAUsableLeaseWithoutAnotherFragmentAttach() {
        val originalProbe = Heartbeat()
        val (dialog, oldWebView) = openDialog(probe = originalProbe)
        val oldLease = pooledLease(dialog)
        advancing(originalProbe, "original dialog view")
        main {
            host.supportFragmentManager.beginTransaction()
                .setMaxLifecycle(dialog, Lifecycle.State.CREATED).commitNow()
        }
        awaitMain("old dialog view was not destroyed and reset") {
            dialog.view == null && (oldLease.isDestroyed || (!oldLease.isInUse && oldWebView.parent == null))
        }
        val newProbe = Heartbeat()
        main {
            host.supportFragmentManager.beginTransaction()
                .setMaxLifecycle(dialog, Lifecycle.State.RESUMED).commitNow()
            val newWebView = findWebView(dialog.view) ?: error("dialog view did not recreate its WebView")
            newWebView.addJavascriptInterface(newProbe, WebViewLifecycleTestSupport.BRIDGE)
        }
        advancing(newProbe, "recreated dialog view")
    }

    @Test
    fun lateInitialResponseCannotOverwriteAnotherDialogUsingTheReturnedWebView() {
        SlowDocumentServer().use { server ->
            val (oldDialog, oldWebView) = openDialog(url = server.url, html = null, probe = Heartbeat())
            assertTrue("dialog did not start the delayed document request", server.started.await(10, TimeUnit.SECONDS))
            dismissAndReset(oldDialog, oldWebView)
            val currentProbe = Heartbeat()
            val (_, newWebView) = openDialog(probe = currentProbe)
            assertSame("fixture must reuse the returned WebView", oldWebView, newWebView)
            advancing(currentProbe, "replacement dialog before old response")
            server.respond()
            advancing(currentProbe, "replacement dialog after old response")
            advancing(currentProbe, "replacement dialog remains current")
        }
    }

    private fun openDialog(
        url: String = WebViewLifecycleTestSupport.URL,
        html: String? = WebViewLifecycleTestSupport.html,
        probe: Heartbeat
    ): Pair<BottomWebViewDialog, WebView> {
        lateinit var dialog: BottomWebViewDialog
        lateinit var webView: WebView
        main {
            // Same default constructor route as an original image click; no pclick
            // callback and no CommentWebViewSession are supplied.
            SourceLoginJsExtensions(host, source, BookType.text).showBrowser(url, html)
            host.supportFragmentManager.executePendingTransactions()
            dialog = host.supportFragmentManager.fragments.filterIsInstance<BottomWebViewDialog>().single()
            webView = findWebView(dialog.view) ?: error("source dialog has no WebView")
            webView.addJavascriptInterface(probe, WebViewLifecycleTestSupport.BRIDGE)
        }
        return dialog to webView
    }

    private fun dismissAndReset(dialog: BottomWebViewDialog, webView: WebView) {
        val lease = pooledLease(dialog)
        main {
            webView.removeJavascriptInterface(WebViewLifecycleTestSupport.BRIDGE)
            dialog.dismiss()
            host.supportFragmentManager.executePendingTransactions()
        }
        awaitMain("dialog WebView was not returned and reset") {
            dialog.view == null && (lease.isDestroyed || (!lease.isInUse && webView.parent == null))
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun pooledLease(dialog: BottomWebViewDialog): PooledWebView {
        // Observe the real pool reset completion; a blank URL alone can predate
        // onPageFinished and would make the reuse regression timing-dependent.
        val field = BottomWebViewDialog::class.java.getDeclaredField("pooledWebView")
        field.isAccessible = true
        return field.get(dialog) as PooledWebView
    }

    private class SlowDocumentServer : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val responseReady = CountDownLatch(1)
        val started = CountDownLatch(1)
        val url = "http://127.0.0.1:${server.localPort}/delayed"
        private val worker = thread(name = "delayed-comment-fixture", isDaemon = true) {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    while (reader.readLine()?.isNotEmpty() == true) { /* Request headers. */ }
                    started.countDown()
                    responseReady.await(15, TimeUnit.SECONDS)
                    val bytes = "<html><body>Obsolete delayed dialog</body></html>".toByteArray()
                    val headers = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().apply {
                        write(headers.toByteArray(Charsets.US_ASCII))
                        write(bytes)
                        flush()
                    }
                }
            } catch (_: IOException) {
                // Cancelling a destroyed view is expected to close its socket.
            }
        }

        fun respond() = responseReady.countDown()

        override fun close() {
            responseReady.countDown()
            server.close()
            worker.join(1_000)
        }
    }
}
