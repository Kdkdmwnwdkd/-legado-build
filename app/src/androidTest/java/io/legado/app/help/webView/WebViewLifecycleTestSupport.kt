package io.legado.app.help.webView

import android.annotation.SuppressLint
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.annotation.Keep
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import java.util.concurrent.atomic.AtomicInteger

internal object WebViewLifecycleTestSupport {
    const val BRIDGE = "__testWebViewHeartbeat"
    const val URL = "https://webview-lifecycle.invalid/document"
    val html = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
        <body><p>Visible WebView lifecycle fixture</p><script>
        (function(){
          var ticks=0,frames=0;
          function frame(){frames++;requestAnimationFrame(frame);}
          requestAnimationFrame(frame);
          setInterval(function(){
            ticks++;
            if(window.$BRIDGE)window.$BRIDGE.beat(ticks,frames);
          },40);
        })();
        </script></body></html>
    """.trimIndent()

    fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    fun startHost(): WebViewDialogLifecycleTestActivity {
        val intent = Intent(ApplicationProvider.getApplicationContext(), WebViewDialogLifecycleTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        @Suppress("DEPRECATION")
        return InstrumentationRegistry.getInstrumentation().startActivitySync(intent) as WebViewDialogLifecycleTestActivity
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun reader(host: WebViewDialogLifecycleTestActivity, probe: Heartbeat): WebView = WebView(host).apply {
        settings.javaScriptEnabled = true
        host.testRoot.addView(this, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        onResume()
        load(this, probe)
    }

    fun load(webView: WebView, probe: Heartbeat) {
        webView.addJavascriptInterface(probe, BRIDGE)
        webView.loadDataWithBaseURL(URL, html, "text/html", "utf-8", URL)
    }

    fun await(message: String, timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        assertTrue(message, condition())
    }

    fun awaitMain(message: String, condition: () -> Boolean) = await(message) {
        var ready = false
        main { ready = condition() }
        ready
    }

    fun advancing(probe: Heartbeat, label: String) {
        val ticks = probe.ticks.get()
        val frames = probe.frames.get()
        await("$label: JS timers or animation frames stopped (ticks=$ticks, frames=$frames)") {
            probe.ticks.get() >= ticks + 5 && probe.frames.get() >= frames + 5
        }
    }

    fun destroy(webView: WebView) {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.removeJavascriptInterface(BRIDGE)
        webView.stopLoading()
        webView.onPause()
        webView.destroy()
    }

    fun findWebView(view: View?): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findWebView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    @Keep
    class Heartbeat {
        val ticks = AtomicInteger()
        val frames = AtomicInteger()

        @JavascriptInterface
        fun beat(ticks: Int, frames: Int) {
            this.ticks.set(ticks)
            this.frames.set(frames)
        }
    }
}
