package io.legado.app.ui.book.read.epub

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Process
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.TextReadEnginePolicy
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebViewPool
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.widget.dialog.BottomWebViewDialog
import io.legado.app.utils.putPrefString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Uses the production book, source script, image click, dialog and Activity lifecycles. */
@RunWith(AndroidJUnit4::class)
class TextReaderCommentReentryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var activity: ReadBookActivity? = null
    private var savedEngine = TextReadEnginePolicy.NATIVE
    private var savedClickWay: String? = null
    private val processId = Process.myPid()
    private val fixtureId = SystemClock.uptimeMillis()
    private val source = BookSource(
        bookSourceUrl = "https://stability-$fixtureId.invalid",
        bookSourceName = "Reader stability fixture",
        enabledExplore = false
    )
    private val books = listOf("A", "B").map { label ->
        Book(
            bookUrl = "${source.bookSourceUrl}/$label",
            tocUrl = "${source.bookSourceUrl}/$label/toc",
            origin = source.bookSourceUrl,
            originName = source.bookSourceName,
            name = "Stability $label $fixtureId",
            author = "instrumentation",
            totalChapterNum = 3,
            canUpdate = false
        )
    }
    private val chapters = books.flatMap { book ->
        (0..2).map { index ->
            BookChapter(
                url = "${book.bookUrl}/$index", title = "Chapter $index",
                baseUrl = source.bookSourceUrl, bookUrl = book.bookUrl, index = index
            )
        }
    }

    @Before
    fun prepareBooks() {
        savedEngine = AppConfig.textReadEngine
        savedClickWay = AppConfig.clickImgWay
        AppConfig.textReadEngine = TextReadEnginePolicy.EPUB
        ApplicationProvider.getApplicationContext<Context>().putPrefString(PreferKey.clickImgWay, "0")
        resetTestTimers()
        appDb.bookSourceDao.insert(source)
        appDb.bookDao.insert(*books.toTypedArray())
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val image = "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP)
        chapters.forEach { chapter ->
            val book = books.first { it.bookUrl == chapter.bookUrl }
            val prefix = if (book === books[0]) {
                "<img src=\"$image\" style=\"text\" click=\"java.showBrowser('${source.bookSourceUrl}/comment','STABILITY_COMMENT')\">\n"
            } else ""
            BookHelp.saveText(book, chapter, prefix + (1..120).joinToString("\n") {
                "STABILITY_BODY ${book.name.take(11)} chapter ${chapter.index} line $it. 正文重入测试。"
            })
        }
    }

    @After
    fun cleanup() {
        try {
            closeReader()
        } finally {
            AppConfig.textReadEngine = savedEngine
            ApplicationProvider.getApplicationContext<Context>().putPrefString(PreferKey.clickImgWay, savedClickWay)
            chapters.forEach { chapter ->
                BookHelp.delContent(books.first { it.bookUrl == chapter.bookUrl }, chapter)
            }
            appDb.bookDao.delete(*books.toTypedArray())
            appDb.bookSourceDao.delete(source)
            resetTestTimers()
        }
    }

    @Test
    fun originalImageClickCommentThenSameProcessReaderReentryAndOtherBook() {
        // Explicit argument permits a short fixture smoke run before the full 30-round gate.
        val rounds = InstrumentationRegistry.getArguments().getString("stabilityReentryRounds")
            ?.toIntOrNull()?.coerceIn(1, 30) ?: 30
        openReader(books[0])
        closeReader()
        repeat(rounds) { round ->
            val first = openReader(books[0])
            tapSourceImage(first)
            val dialog = awaitComment()
            val returnedLease = assertGlobalComment(dialog)
            val commentView = awaitValue("comment WebView") { onMain { findWebViews(dialog.requireView()).firstOrNull() } }
            awaitCondition("comment HTML") { evaluate(commentView, "document.body.textContent").contains("STABILITY_COMMENT") }
            onMain { dialog.dismiss() }
            awaitCondition("comment view released") { onMain { dialog.view == null } }
            closeReader()
            // Deliberately keep the process alive: restarting would mask a global timer leak.
            val reentered = openReader(books[0])
            awaitPoolReset(returnedLease)
            verifyHeartbeatAndTurn(reentered)
            closeReader()
            if (round % 5 == 0 || round == rounds - 1) {
                verifyHeartbeatAndTurn(openReader(books[1]))
                closeReader()
            }
            Log.i("TextReaderStability", "comment/reentry completed round=${round + 1}/$rounds")
        }
        verifyHeartbeatAndTurn(openReader(books[0]))
    }

    @Test
    fun closingLoadingOriginalCommentThenImmediatelyReenteringIgnoresLateResponse() {
        SlowCommentServer().use { server ->
            val chapter = chapters.first { it.bookUrl == books[0].bookUrl && it.index == 0 }
            val content = BookHelp.getContent(books[0], chapter) ?: error("missing fixture content")
            val immediateClick = "java.showBrowser('${source.bookSourceUrl}/comment','STABILITY_COMMENT')"
            assertTrue("fixture contains the original source click", content.contains(immediateClick))
            BookHelp.saveText(books[0], chapter, content.replace(immediateClick, "java.showBrowser('${server.url}')"))

            val first = openReader(books[0])
            tapSourceImage(first)
            val dialog = awaitComment()
            val returnedLease = assertGlobalComment(dialog)
            assertTrue("source comment is still waiting for its response", server.started.await(20, TimeUnit.SECONDS))
            val exiting = activity!!
            onMain {
                dialog.dismiss()
                exiting.finish()
            }
            awaitCondition("reader closed while comment request was pending") { exiting.isDestroyed }
            activity = null

            // Start the next reader before waiting for the old asynchronous pool reset.
            val reentered = openReader(books[0])
            awaitPoolReset(returnedLease)
            verifyHeartbeatAndTurn(reentered)
            assertEquals("the old response stayed withheld until the reader reentered", 1L, server.finished.count)
            server.respond()
            assertTrue("late server response finished", server.finished.await(15, TimeUnit.SECONDS))
            verifyHeartbeatAndTurn(reentered)
            onMain {
                assertEquals("closed comment stays reset after its late response", WebViewPool.BLANK_HTML, returnedLease.realWebView.url)
                assertTrue("the old comment was not reopened", activity!!.supportFragmentManager.fragments
                    .filterIsInstance<BottomWebViewDialog>().none { it.view != null })
            }
            closeReader()
            verifyHeartbeatAndTurn(openReader(books[1]))
            closeReader()
            verifyHeartbeatAndTurn(openReader(books[0]))
        }
    }

    private fun openReader(book: Book): WebView {
        val intent = Intent(ApplicationProvider.getApplicationContext(), ReadBookActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("bookUrl", book.bookUrl).putExtra("inBookshelf", true)
        @Suppress("DEPRECATION")
        val opened = instrumentation.startActivitySync(intent) as ReadBookActivity
        activity = opened
        val web = awaitValue("production reader ready for ${book.name}") {
            val layer = onMain { findLayer(opened.window.decorView) } ?: return@awaitValue null
            onMain {
                if (!layer.hasVisibleDocument || layer.position?.chapterIndex != 0) null
                else readField(layer, "currentWebView") as? WebView
            }?.takeIf { evaluate(it, "document.body.textContent").contains("STABILITY_BODY") }
        }
        awaitCondition("reader layout settled") {
            val metrics = metrics(web)
            metrics.optBoolean("ready") && !metrics.optBoolean("layoutPending") &&
                metrics.has("sourceImagesPending") && metrics.optInt("sourceImagesPending") == 0
        }
        awaitNativeReady(web)
        assertEquals("reader remained in the same process", processId, Process.myPid())
        assertEquals(book.bookUrl, ReadBook.book?.bookUrl)
        assertTrue(onMain { web.isShown && web.alpha == 1f })
        return web
    }

    private fun tapSourceImage(web: WebView) {
        moveToFirstPage(web)
        val rect = JSONObject(evaluate(web, """
            JSON.stringify((function(){
              var i=document.querySelector('a[data-legado-image-action] img[data-legado-image-id]');
              if(!i)return {valid:false};
              var r=i.getBoundingClientRect(),x=r.left+r.width/2,y=r.top+r.height/2;
              return {valid:i.complete&&i.naturalWidth>0&&r.width>0&&r.height>0&&
                x>=0&&x<innerWidth&&y>=0&&y<innerHeight,x:x,y:y,w:innerWidth};
            })())
        """.trimIndent()))
        assertTrue("source image is decoded and inside the visible page: $rect", rect.optBoolean("valid"))
        val point = onMain {
            val location = IntArray(2)
            web.getLocationOnScreen(location)
            val scale = web.width / rect.getDouble("w")
            Pair((location[0] + rect.getDouble("x") * scale).toFloat(),
                (location[1] + rect.getDouble("y") * scale).toFloat())
        }
        val start = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
            val event = MotionEvent.obtain(start, start + index * 60, action, point.first, point.second, 0)
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
    }

    private fun awaitComment(): BottomWebViewDialog = awaitValue("original click opens production comment") {
        onMain { activity?.supportFragmentManager?.fragments?.filterIsInstance<BottomWebViewDialog>()?.firstOrNull { it.view != null } }
    }

    private fun verifyHeartbeatAndTurn(web: WebView) {
        evaluate(web, """
            (function(){
              var probe=window.__stabilityProbe={frames:0,timers:0};
              requestAnimationFrame(function beat(){if(++probe.frames<4)requestAnimationFrame(beat)});
              setTimeout(function tick(){if(++probe.timers<4)setTimeout(tick,30)},30);
              return true;
            })()
        """.trimIndent())
        awaitCondition("reader animation frames and timers continue after pooled comment release") {
            val probe = JSONObject(evaluate(web, "JSON.stringify(window.__stabilityProbe)"))
            probe.optInt("frames") >= 3 && probe.optInt("timers") >= 3
        }
        // Normal reentry first restores its own stored position. Move only after
        // it is visible and responsive, to prepare a deterministic turn assertion.
        moveToFirstPage(web)
        val layer = onMain { findLayer(activity!!.window.decorView)!! }
        assertTrue("fixture has a second page", metrics(web).optInt("pageCount") > 1)
        onMain { layer.nextPage(animate = false) }
        awaitCondition("new reader can turn pages") {
            onMain { layer.position?.pageIndex == 1 } && metrics(web).optInt("pageIndex") == 1
        }
    }

    private fun moveToFirstPage(web: WebView) {
        awaitNativeReady(web)
        val layer = onMain { findLayer(activity!!.window.decorView)!! }
        onMain { if (layer.position?.pageIndex != 0) layer.setPage(0, animate = false) }
        awaitCondition("first page prepared through the native reader") {
            onMain { layer.position?.pageIndex == 0 } && metrics(web).optInt("pageIndex") == 0
        }
        awaitNativeReady(web)
    }

    private fun awaitNativeReady(web: WebView) {
        awaitCondition("production reader accepts current-page actions") {
            onMain {
                val opened = activity ?: return@onMain false
                val layer = findLayer(opened.window.decorView) ?: return@onMain false
                if (opened.isFinishing || opened.isDestroyed || !opened.hasWindowFocus() ||
                    readField(layer, "currentWebView") !== web ||
                    readField(opened, "epubCoreLoading") == true ||
                    readField(opened, "epubCoreBoundaryTransition") == true) return@onMain false
                val ready = layer.javaClass.getDeclaredMethod("sourceImageViewReady", web.javaClass)
                    .apply { isAccessible = true }
                ready.invoke(layer, web) == true
            }
        }
    }

    private fun assertGlobalComment(dialog: BottomWebViewDialog): PooledWebView = onMain {
        assertNull("raw click must not use the pclick session", readField(dialog, "webViewSession"))
        val lease = readField(dialog, "pooledWebView") as PooledWebView
        assertEquals(WebViewPool.Scope.GLOBAL, lease.scope)
        lease
    }

    private fun awaitPoolReset(lease: PooledWebView) {
        awaitCondition("actual GLOBAL about:blank reset completed") { onMain { lease.isDestroyed || !lease.isInUse } }
        assertFalse("fixture must exercise reset completion, not the full-pool destroy shortcut", lease.isDestroyed)
    }

    private fun resetTestTimers() = onMain {
        // Precondition/cleanup only. Never called between a comment and reentry.
        val web = WebView(ApplicationProvider.getApplicationContext())
        web.resumeTimers()
        web.destroy()
    }

    private fun closeReader() {
        val opened = activity ?: return
        onMain { opened.finish() }
        awaitCondition("reader Activity destroyed") { opened.isDestroyed }
        activity = null
    }

    private fun metrics(web: WebView): JSONObject = JSONObject(evaluate(web,
        "JSON.stringify(window.__legadoEpub?window.__legadoEpub.metrics(true):{})"))

    private fun evaluate(web: WebView, script: String): String {
        val done = CountDownLatch(1)
        var result = "null"
        onMain { web.evaluateJavascript(script) { result = it ?: "null"; done.countDown() } }
        assertTrue("JavaScript callback arrived", done.await(15, TimeUnit.SECONDS))
        return JSONArray("[$result]").optString(0, "null")
    }

    private fun <T> onMain(block: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun <T : Any> awaitValue(label: String, block: () -> T?): T {
        val deadline = SystemClock.uptimeMillis() + 60_000
        do {
            block()?.let { return it }
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Timed out: $label")
    }

    private fun awaitCondition(label: String, block: () -> Boolean) {
        awaitValue(label) { true.takeIf { block() } }
    }

    private fun findLayer(view: View): EpubDirectWebLayer? {
        if (view is EpubDirectWebLayer) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findLayer(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun findWebViews(view: View): List<WebView> = buildList {
        if (view is WebView) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(findWebViews(view.getChildAt(index)))
    }

    private fun readField(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)

    private class SlowCommentServer : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val responseReady = CountDownLatch(1)
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val url = "http://127.0.0.1:${server.localPort}/pending-comment"
        private val worker = thread(name = "pending-reader-comment", isDaemon = true) {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 20_000
                    val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    while (reader.readLine()?.isNotEmpty() == true) { /* Request headers. */ }
                    started.countDown()
                    // The test's use block always releases this latch during cleanup.
                    // Never deliver a response just because a timeout elapsed.
                    responseReady.await()
                    val body = "<html><body>LATE_STABILITY_COMMENT</body></html>".toByteArray()
                    val headers = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().apply {
                        write(headers.toByteArray(Charsets.US_ASCII))
                        write(body)
                        flush()
                    }
                }
            } catch (_: IOException) {
                // The fixed view lifecycle cancels its outstanding HTTP request.
            } finally {
                finished.countDown()
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
