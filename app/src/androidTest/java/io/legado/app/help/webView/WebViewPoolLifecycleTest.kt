package io.legado.app.help.webView

import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.help.webView.WebViewLifecycleTestSupport.Heartbeat
import io.legado.app.help.webView.WebViewLifecycleTestSupport.advancing
import io.legado.app.help.webView.WebViewLifecycleTestSupport.awaitMain
import io.legado.app.help.webView.WebViewLifecycleTestSupport.main
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebViewPoolLifecycleTest {
    private lateinit var host: WebViewDialogLifecycleTestActivity
    private lateinit var reader: WebView
    private var readerProbe = Heartbeat()
    private val acquired = mutableSetOf<PooledWebView>()

    @Before
    fun setUp() {
        host = WebViewLifecycleTestSupport.startHost()
        main {
            reader = WebViewLifecycleTestSupport.reader(host, readerProbe)
            // Isolate the precondition from another test's process-wide timer state.
            // No resumeTimers call is allowed between release and our assertions.
            reader.resumeTimers()
        }
        advancing(readerProbe, "initial reader")
    }

    @After
    fun tearDown() {
        main {
            if (::reader.isInitialized) reader.resumeTimers()
            acquired.toList().forEach(::release)
            if (::reader.isInitialized) WebViewLifecycleTestSupport.destroy(reader)
            if (::host.isInitialized) host.finish()
        }
    }

    @Test
    fun resettingEachPoolLeavesIndependentReaderRunningAndReacquiredViewActive() {
        WebViewPool.Scope.entries.forEach { scope ->
            lateinit var pooled: PooledWebView
            val firstProbe = Heartbeat()
            main {
                pooled = WebViewPool.acquire(host, scope).also(acquired::add)
                host.testRoot.addView(pooled.realWebView, ViewGroup.LayoutParams(120, 120))
                WebViewLifecycleTestSupport.load(pooled.realWebView, firstProbe)
            }
            advancing(firstProbe, "$scope first lease")
            main { release(pooled) }
            // Exercise the actual asynchronous about:blank reset completion.
            awaitMain("$scope did not finish resetting") { !pooled.isInUse }
            advancing(readerProbe, "reader after $scope reset")

            val reusedProbe = Heartbeat()
            lateinit var reused: PooledWebView
            main {
                reused = WebViewPool.acquire(host, scope).also(acquired::add)
                assertSame("fixture did not exercise pool reuse for $scope", pooled, reused)
                host.testRoot.addView(reused.realWebView, ViewGroup.LayoutParams(120, 120))
                // acquire itself must pair the instance's previous onPause.
                WebViewLifecycleTestSupport.load(reused.realWebView, reusedProbe)
            }
            advancing(reusedProbe, "$scope reused lease")
            main { release(reused) }
            awaitMain("$scope reused view did not finish resetting") { !reused.isInUse }
            advancing(readerProbe, "reader after reused $scope reset")
        }
    }

    @Test
    fun newIndependentReaderCanLoadAfterTheLastGlobalWebViewIsReturned() {
        lateinit var pooled: PooledWebView
        main {
            pooled = WebViewPool.acquire(host).also(acquired::add)
            release(pooled)
        }
        awaitMain("GLOBAL reset did not finish") { !pooled.isInUse }
        main {
            WebViewLifecycleTestSupport.destroy(reader)
            readerProbe = Heartbeat()
            reader = WebViewLifecycleTestSupport.reader(host, readerProbe)
        }
        advancing(readerProbe, "new reader after GLOBAL reset")
    }

    private fun release(pooled: PooledWebView) {
        if (!acquired.remove(pooled)) return
        pooled.realWebView.removeJavascriptInterface(WebViewLifecycleTestSupport.BRIDGE)
        WebViewPool.release(pooled)
    }
}
