package io.legado.app.help.webView

import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity

/** Empty debug-only host for real DialogFragment and multiple-WebView lifecycle tests. */
class WebViewDialogLifecycleTestActivity : AppCompatActivity() {
    lateinit var testRoot: FrameLayout
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        testRoot = FrameLayout(this)
        setContentView(testRoot)
    }
}
