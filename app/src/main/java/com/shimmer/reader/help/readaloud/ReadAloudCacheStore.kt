package com.shimmer.reader.help.readaloud

import com.shimmer.reader.utils.LogUtils
import splitties.init.appCtx
import java.io.File

/**
 * 朗读会往缓存目录里写两类东西：
 *
 * - `httpTTS/`：按句合成出来的 mp3。重听同一句、同一语速时直接复用，不再请求接口。
 * - `httpTTS_cache/`：ExoPlayer 的流式缓存，边下边播时落盘，按 LRU 上限淘汰。
 *
 * 两个目录的位置写在 [com.shimmer.reader.service.HttpReadAloudService] 里，
 * 这里只负责统计和清理，不参与播放逻辑。
 */
object ReadAloudCacheStore {

    private const val TAG = "ReadAloudCacheStore"

    private fun sentenceDir(): File = File(appCtx.cacheDir, "httpTTS")

    private fun streamDir(): File = File(appCtx.cacheDir, "httpTTS_cache")

    fun directories(): List<File> = listOf(sentenceDir(), streamDir())

    /**
     * 两个目录的合计占用。调用方自己放到 IO 线程执行。
     */
    fun sizeBytes(): Long = directories().sumOf { it.sizeRecursively() }

    /**
     * 清空两个目录。SimpleCache 的目录只有在朗读服务已经销毁之后才删得掉，
     * 因此调用方必须先停服务，失败时返回 false，由界面提示用户重试。
     */
    fun clear(): Boolean {
        var success = true
        directories().forEach { dir ->
            if (!dir.exists()) return@forEach
            if (!dir.deleteRecursively()) {
                LogUtils.d(TAG, "删除朗读缓存失败: ${dir.absolutePath}")
                success = false
            }
        }
        return success
    }

    private fun File.sizeRecursively(): Long {
        if (!exists()) return 0L
        return walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }
}
