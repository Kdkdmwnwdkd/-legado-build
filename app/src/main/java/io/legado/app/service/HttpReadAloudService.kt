package io.legado.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.script.ScriptException
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.HttpTTS
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.ai.AiReadAloudRoleService
import io.legado.app.help.book.characterBookKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.exoplayer.InputStreamDataSource
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.readaloud.ReadAloudLoudnessAudioProcessor
import io.legado.app.help.readaloud.ReadAloudPlaybackState
import io.legado.app.help.readaloud.ReadAloudSpeakerLoudnessManager
import io.legado.app.help.readaloud.ReadAloudSpeechPlan
import io.legado.app.help.readaloud.ReadAloudSpeechPlanItem
import io.legado.app.help.readaloud.ReadAloudSpeechPlanner
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.help.readaloud.speech.SpeechVoiceCatalogRepository
import io.legado.app.help.readaloud.speech.SpeechVoiceGroupRepository
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.entities.buildReadAloudCues
import io.legado.app.ui.book.read.page.entities.indexForChapterPosition
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import org.json.JSONObject
import org.mozilla.javascript.WrappedException
import splitties.init.appCtx
import java.io.File
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * 在线朗读
 */
@SuppressLint("UnsafeOptInUsageError")
class HttpReadAloudService : BaseReadAloudService(),
    Player.Listener {
    private val loudnessAudioProcessor by lazy {
        ReadAloudLoudnessAudioProcessor()
    }
    private val renderersFactory by lazy {
        object : DefaultRenderersFactory(this@HttpReadAloudService) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(loudnessAudioProcessor))
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }
        }
    }
    private val exoPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(this, renderersFactory).build()
    }
    private val ttsFolderPath: String by lazy {
        cacheDir.absolutePath + File.separator + "httpTTS" + File.separator
    }
    private val cache by lazy {
        SimpleCache(
            File(cacheDir, "httpTTS_cache"),
            LeastRecentlyUsedCacheEvictor(128 * 1024 * 1024),
            StandaloneDatabaseProvider(appCtx)
        )
    }
    private val cacheDataSinkFactory by lazy {
        CacheDataSink.Factory()
            .setCache(cache)
    }
    private val loadErrorHandlingPolicy by lazy {
        CustomLoadErrorHandlingPolicy()
    }
    private var speechRate: Int = AppConfig.speechRatePlay + 5
    private var downloadTask: Coroutine<*>? = null
    private var playIndexJob: Job? = null
    private var playErrorNo = 0
    private val downloadTaskActiveLock = Mutex()

    private data class NextChapterSpeechPlan(
        val chapter: TextChapter,
        val plan: ReadAloudSpeechPlan
    )

    private data class SpeakAudioRequest(
        val index: Int,
        val text: String,
        val speakText: String,
        val fileName: String,
        val route: SpeechRoute?,
        val speechItem: ReadAloudSpeechPlanItem?,
        val httpTts: HttpTTS?
    )

    private data class AudioDownloadTask(
        val request: SpeakAudioRequest,
        val downloader: Downloader
    )

    private data class SynthesisResult(
        val success: Boolean,
        val error: Throwable? = null
    )

    private fun suppressRouteTtsError(route: SpeechRoute?): Boolean {
        return AppConfig.aiReadAloudRoleEnabled && route?.isConfigured == true
    }

    override fun onCreate() {
        super.onCreate()
        exoPlayer.addListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadTask?.cancel()
        exoPlayer.release()
        cache.release()
        Coroutine.async {
            removeCacheFile()
        }
    }

    override fun play() {
        pageChanged = false
        exoPlayer.stop()
        if (!requestFocus()) return
        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            ReadBook.readAloud()
        } else {
            super.play()
            if (AppConfig.streamReadAloudAudio) {
                downloadAndPlayAudiosStream()
            } else {
                downloadAndPlayAudios()
            }
        }
    }

    override fun playStop() {
        downloadTask?.cancel()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        exoPlayer.volume = 1f
        loudnessAudioProcessor.setGain(1f)
        playIndexJob?.cancel()
    }

    private fun updateNextPos() {
        if (!moveToNextCue()) {
            nextChapter()
        }
    }

    protected override fun moveToCue(
        cueIndex: Int,
        chapterPosition: Int,
        expectedChapterIndex: Int,
        play: Boolean
    ) {
        if (expectedChapterIndex >= 0 && expectedChapterIndex != ReadBook.durChapterIndex) return
        val targetIndex = when {
            chapterPosition >= 0 -> readAloudCues.indexForChapterPosition(chapterPosition)
            cueIndex in contentList.indices -> cueIndex
            else -> -1
        }
        if (targetIndex !in contentList.indices) return
        val mediaIndex = (0 until exoPlayer.mediaItemCount).firstOrNull { index ->
            mediaItemCueIndex(exoPlayer.getMediaItemAt(index)) == targetIndex
        }
        if (mediaIndex == null) {
            playStop()
            syncToCueIndex(targetIndex)
            upTtsProgress(readAloudNumber + 1)
            if (play) {
                play()
            } else {
                pageChanged = true
                postReadAloudPlaybackPhase(ReadAloudPlaybackState.PHASE_PAUSED, playing = false)
            }
            return
        }
        playIndexJob?.cancel()
        syncToCueIndex(targetIndex)
        applySpeakerVolume(targetIndex)
        upTtsProgress(readAloudNumber + 1)
        exoPlayer.seekTo(mediaIndex, 0L)
        if (exoPlayer.playbackState == Player.STATE_IDLE) {
            exoPlayer.prepare()
        }
        if (play) {
            if (pause) {
                super.resumeReadAloud()
            }
            exoPlayer.play()
            postExoPlaybackPhase()
        } else {
            exoPlayer.pause()
            super.pauseReadAloud(abandonFocus = false)
            postReadAloudPlaybackPhase(ReadAloudPlaybackState.PHASE_PAUSED, playing = false)
        }
        upPlayPos()
    }

    private fun mediaItemCueIndex(mediaItem: MediaItem?): Int? {
        return (mediaItem?.localConfiguration?.tag as? Int)
            ?.takeIf { it in contentList.indices }
    }

    private fun syncToMediaItemCue(mediaItem: MediaItem? = exoPlayer.currentMediaItem): Int {
        val cueIndex = mediaItemCueIndex(mediaItem)
        if (cueIndex != null) {
            if (cueIndex != nowSpeak) {
                syncToCueIndex(cueIndex)
            }
            applySpeakerVolume(cueIndex)
            return cueIndex
        }
        return nowSpeak
    }

    private fun applySpeakerVolume(cueIndex: Int = nowSpeak) {
        exoPlayer.volume = 1f
        loudnessAudioProcessor.setGain(speakerLoudnessInfo(cueIndex).gain)
    }

    override fun onReadAloudAudioConfigChanged() {
        applySpeakerVolume()
    }

    private fun downloadAndPlayAudios() {
        exoPlayer.clearMediaItems()
        downloadTask?.cancel()
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                val httpTts = ReadAloud.httpTTS
                val requests = buildCurrentAudioRequests(httpTts)
                val chunkSize = maxSynthesisThreadCount(requests)
                requests.chunked(chunkSize).forEach { chunk ->
                    ensureActive()
                    if (!prepareAudioRequests(chunk, pauseOnFailure = true)) return@execute
                    launch(Main) {
                        chunk.forEach { request ->
                            val file = getSpeakFileAsMd5(request.fileName)
                            exoPlayer.addMediaItem(mediaItemForRequest(request, Uri.fromFile(file)))
                        }
                    }
                }
                preDownloadAudios(httpTts)
            }
        }.onError {
            if (it is CancellationException) return@onError
            AppLog.put("朗读下载出错\n${it.localizedMessage}", it, true)
        }
    }

    private suspend fun preDownloadAudios(httpTts: HttpTTS?) {
        val requests = waitForNextChapterAudioRequests(httpTts)
        prepareAudioRequests(requests, pauseOnFailure = false)
    }

    private fun downloadAndPlayAudiosStream() {
        exoPlayer.clearMediaItems()
        downloadTask?.cancel()
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                val httpTts = ReadAloud.httpTTS
                val requests = buildCurrentAudioRequests(httpTts)
                val workerCount = maxSynthesisThreadCount(requests)
                val downloaderChannel = Channel<AudioDownloadTask>((workerCount * 2).coerceAtLeast(4))
                repeat(workerCount) {
                    launch {
                        for (task in downloaderChannel) {
                            task.downloader.download(null)
                            learnSpeakerLoudness(task.request)
                        }
                    }
                }
                try {
                    requests.forEach { request ->
                        ensureActive()
                        val dataSourceFactory = createDataSourceFactory(
                            request.httpTts,
                            request.speakText,
                            request.route,
                            request.fileName
                        )
                        val downloader = createDownloader(dataSourceFactory, request.fileName)
                        downloaderChannel.send(AudioDownloadTask(request, downloader))
                        val mediaSource = createMediaSource(dataSourceFactory, request)
                        launch(Main) {
                            exoPlayer.addMediaSource(mediaSource)
                        }
                    }
                    preDownloadAudiosStream(httpTts, downloaderChannel)
                } finally {
                    downloaderChannel.close()
                }
            }
        }.onError {
            if (it is CancellationException) return@onError
            AppLog.put("朗读下载出错\n${it.localizedMessage}", it, true)
        }
    }

    private suspend fun preDownloadAudiosStream(
        httpTts: HttpTTS?,
        downloaderChannel: Channel<AudioDownloadTask>
    ) {
        waitForNextChapterAudioRequests(httpTts).forEach { request ->
            currentCoroutineContext().ensureActive()
            val dataSourceFactory = createDataSourceFactory(
                request.httpTts,
                request.speakText,
                request.route,
                request.fileName
            )
            val downloader = createDownloader(dataSourceFactory, request.fileName)
            downloaderChannel.send(AudioDownloadTask(request, downloader))
        }
    }

    private suspend fun waitForNextChapterAudioRequests(httpTts: HttpTTS?): List<SpeakAudioRequest> {
        val bookUrl = ReadBook.book?.bookUrl ?: return emptyList()
        val currentChapterIndex = ReadBook.durChapterIndex
        repeat(120) { attempt ->
            currentCoroutineContext().ensureActive()
            if (ReadBook.book?.bookUrl != bookUrl || ReadBook.durChapterIndex != currentChapterIndex) {
                return emptyList()
            }
            val requests = buildNextChapterAudioRequests(httpTts)
            if (requests.isNotEmpty()) return requests
            if (attempt < 119) delay(5_000)
        }
        return emptyList()
    }

    private fun buildCurrentAudioRequests(httpTts: HttpTTS?): List<SpeakAudioRequest> {
        return contentList.mapIndexedNotNull { index, content ->
            if (index < nowSpeak) return@mapIndexedNotNull null
            var text = content
            if (paragraphStartPos > 0 && index == nowSpeak) {
                text = text.substring(paragraphStartPos.coerceIn(0, text.length))
            }
            val route = speechRouteForIndex(index)
            val routeHttpTts = httpTtsForRoute(httpTts, route)
            buildAudioRequest(
                index = index,
                text = text,
                chapter = textChapter,
                route = route,
                speechItem = speechItems.getOrNull(index),
                httpTts = routeHttpTts
            )
        }
    }

    private fun buildNextChapterAudioRequests(httpTts: HttpTTS?): List<SpeakAudioRequest> {
        val nextPlan = buildNextChapterSpeechPlan() ?: return emptyList()
        return nextPlan.plan.cues.mapIndexed { index, cue ->
            val route = nextPlan.plan.routes.getOrNull(index)
            buildAudioRequest(
                index = index,
                text = cue.text,
                chapter = nextPlan.chapter,
                route = route,
                speechItem = nextPlan.plan.items.getOrNull(index),
                httpTts = httpTtsForRoute(httpTts, route)
            )
        }
    }

    private fun buildAudioRequest(
        index: Int,
        text: String,
        chapter: TextChapter?,
        route: SpeechRoute?,
        speechItem: ReadAloudSpeechPlanItem?,
        httpTts: HttpTTS?
    ): SpeakAudioRequest {
        val speakText = text.replace(AppPattern.notReadAloudRegex, "")
        if (speakText.isEmpty()) {
            AppLog.put("阅读段落内容为空，使用无声音频代替。\n朗读文本：$text")
        }
        return SpeakAudioRequest(
            index = index,
            text = text,
            speakText = speakText,
            fileName = md5SpeakFileName(text, chapter, route),
            route = route,
            speechItem = speechItem,
            httpTts = httpTts
        )
    }

    private suspend fun prepareAudioRequests(
        requests: List<SpeakAudioRequest>,
        pauseOnFailure: Boolean
    ): Boolean = coroutineScope {
        if (requests.isEmpty()) return@coroutineScope true
        val distinctRequests = requests.distinctBy { it.fileName }
        val engineSemaphores = distinctRequests
            .groupBy { it.engineLimitKey() }
            .mapValues { (_, items) ->
                Semaphore(items.maxOf { it.synthesisThreadCount() }.coerceIn(1, 8))
            }
        val globalSemaphore = Semaphore(maxSynthesisThreadCount(distinctRequests))
        distinctRequests.map { request ->
            async {
                globalSemaphore.withPermit {
                    engineSemaphores.getValue(request.engineLimitKey()).withPermit {
                        prepareAudioRequest(request, pauseOnFailure)
                    }
                }
            }
        }.awaitAll().all { it }
    }

    private suspend fun prepareAudioRequest(
        request: SpeakAudioRequest,
        pauseOnFailure: Boolean
    ): Boolean {
        if (request.speakText.isEmpty()) {
            createSilentSound(request.fileName)
            return true
        }
        if (hasSpeakFile(request.fileName)) {
            learnSpeakerLoudness(request)
            return true
        }
        return runCatching {
            val result = synthesizeRequestWithFallback(request)
            if (!result.success) {
                result.error?.let { throw it }
                createSilentSound(request.fileName)
            } else {
                learnSpeakerLoudness(request)
            }
            true
        }.getOrElse { throwable ->
            if (throwable is CancellationException) throw throwable
            if (pauseOnFailure) pauseReadAloud()
            false
        }
    }

    private fun learnSpeakerLoudness(request: SpeakAudioRequest) {
        if (request.speakText.isBlank()) return
        if (!ReadAloudSpeakerLoudnessManager.needsAnalysis(request.speechItem, request.route)) return
        lifecycleScope.launch(IO) {
            ReadAloudSpeakerLoudnessManager.analyzeAndRecord(
                file = getSpeakFileAsMd5(request.fileName),
                item = request.speechItem,
                route = request.route
            )
        }
    }

    private suspend fun synthesizeRequestWithFallback(request: SpeakAudioRequest): SynthesisResult {
        val primary = synthesizeToSpeakFile(
            fileName = request.fileName,
            speakText = request.speakText,
            route = request.route,
            httpTts = request.httpTts
        )
        if (primary.success || !AppConfig.aiReadAloudRoleEnabled) return primary
        val fallbackRoutes = fallbackRoutesFor(request.route)
        var lastError = primary.error
        fallbackRoutes.forEach { route ->
            currentCoroutineContext().ensureActive()
            val result = synthesizeToSpeakFile(
                fileName = request.fileName,
                speakText = request.speakText,
                route = route,
                httpTts = httpTtsForRoute(ReadAloud.httpTTS, route)
            )
            if (result.success) {
                SpeechVoiceGroupRepository.markInvalidRoute(request.route, reason = "合成失败")
                AppLog.putDebug("多角色 TTS 路由失败，已临时改用：${route.speakerName.ifBlank { route.engineType }}")
                return result
            }
            lastError = result.error ?: lastError
        }
        return SynthesisResult(false, lastError)
    }

    private suspend fun synthesizeToSpeakFile(
        fileName: String,
        speakText: String,
        route: SpeechRoute?,
        httpTts: HttpTTS?
    ): SynthesisResult {
        return runCatching {
            if (route?.engineType == SpeechRoute.ENGINE_SYSTEM || httpTts == null) {
                synthesizeSystemSpeakFile(fileName, speakText, route ?: defaultSystemRoute())
            } else {
                val inputStream = getSpeakStream(httpTts, speakText, route)
                if (inputStream != null) {
                    createSpeakFile(fileName, inputStream)
                    true
                } else {
                    false
                }
            }
        }.fold(
            onSuccess = { SynthesisResult(it) },
            onFailure = { throwable ->
                if (throwable is CancellationException) throw throwable
                SynthesisResult(false, throwable)
            }
        )
    }

    private fun maxSynthesisThreadCount(requests: List<SpeakAudioRequest>): Int {
        return requests.maxOfOrNull { it.synthesisThreadCount() }?.coerceIn(1, 8) ?: 1
    }

    private fun SpeakAudioRequest.synthesisThreadCount(): Int {
        if (route?.engineType == SpeechRoute.ENGINE_SYSTEM || httpTts == null) return 1
        return httpTts.synthesisThreadCount.coerceIn(1, 8)
    }

    private fun SpeakAudioRequest.engineLimitKey(): String {
        return if (route?.engineType == SpeechRoute.ENGINE_SYSTEM || httpTts == null) {
            "system:${route?.engineValue.orEmpty()}"
        } else {
            "http:${httpTts.id}"
        }
    }

    private fun buildNextChapterSpeechPlan(): NextChapterSpeechPlan? {
        val chapter = ReadBook.nextTextChapter?.takeIf { it.isCompleted } ?: return null
        val baseCues = chapter.buildReadAloudCues(readAloudByPage)
        if (baseCues.isEmpty()) return null
        val roleCacheKey = if (AppConfig.aiReadAloudRoleEnabled) {
            AiReadAloudRoleService.cacheForPlayback(
                ReadBook.book,
                chapter,
                baseCues.map { it.text }
            )?.cacheKey ?: return null
        } else {
            null
        }
        val plan = ReadAloudSpeechPlanner.build(
            bookUrl = ReadBook.book?.characterBookKey(),
            chapter = chapter,
            baseCues = baseCues,
            multiRoleEnabled = AppConfig.aiReadAloudRoleEnabled,
            roleCacheKey = roleCacheKey
        )
        return NextChapterSpeechPlan(chapter, plan)
    }

    private fun createDataSourceFactory(
        httpTts: HttpTTS?,
        speakText: String,
        route: SpeechRoute? = null,
        fileName: String? = null
    ): CacheDataSource.Factory {
        val upstreamFactory = DataSource.Factory {
            InputStreamDataSource {
                if (speakText.isEmpty()) {
                    resources.openRawResource(R.raw.silent_sound)
                } else if ((route?.engineType == SpeechRoute.ENGINE_SYSTEM || httpTts == null) && !fileName.isNullOrBlank()) {
                    val file = getSpeakFileAsMd5(fileName)
                    if (!hasSpeakFile(fileName)) {
                        runBlocking(lifecycleScope.coroutineContext[Job]!!) {
                            synthesizeSystemSpeakFile(fileName, speakText, route ?: defaultSystemRoute())
                        }
                    }
                    file.takeIf { it.exists() && it.length() > 0L }?.inputStream()
                        ?: resources.openRawResource(R.raw.silent_sound)
                } else {
                    kotlin.runCatching {
                        runBlocking(lifecycleScope.coroutineContext[Job]!!) {
                            val sourceHttpTts = httpTts ?: return@runBlocking resources.openRawResource(R.raw.silent_sound)
                            getSpeakStreamWithFallback(sourceHttpTts, speakText, route, fileName)
                        }
                    }.onFailure {
                        when (it) {
                            is InterruptedException,
                            is CancellationException -> Unit

                            else -> pauseReadAloud()
                        }
                    }.getOrThrow()
                } ?: resources.openRawResource(R.raw.silent_sound)
            }
        }
        val factory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheWriteDataSinkFactory(cacheDataSinkFactory)
        return factory
    }

    private suspend fun getSpeakStreamWithFallback(
        httpTts: HttpTTS,
        speakText: String,
        route: SpeechRoute?,
        fileName: String?
    ): InputStream? {
        val primary = runCatching {
            getSpeakStream(httpTts, speakText, route)
        }
        primary.getOrNull()?.let { return it }
        if (!AppConfig.aiReadAloudRoleEnabled) {
            primary.exceptionOrNull()?.let { throw it }
            return null
        }
        var lastError = primary.exceptionOrNull()
        fallbackRoutesFor(route).forEach { fallbackRoute ->
            currentCoroutineContext().ensureActive()
            val result = runCatching {
                if (fallbackRoute.engineType == SpeechRoute.ENGINE_SYSTEM) {
                    val targetFileName = fileName ?: return@runCatching null
                    if (synthesizeSystemSpeakFile(targetFileName, speakText, fallbackRoute)) {
                        getSpeakFileAsMd5(targetFileName).inputStream()
                    } else {
                        null
                    }
                } else {
                    val fallbackHttpTts = httpTtsForRoute(ReadAloud.httpTTS, fallbackRoute)
                        ?: return@runCatching null
                    getSpeakStream(fallbackHttpTts, speakText, fallbackRoute)
                }
            }
            result.getOrNull()?.let { stream ->
                SpeechVoiceGroupRepository.markInvalidRoute(route, reason = "合成失败")
                AppLog.putDebug("多角色 TTS 路由失败，已临时改用：${fallbackRoute.speakerName.ifBlank { fallbackRoute.engineType }}")
                return stream
            }
            lastError = result.exceptionOrNull() ?: lastError
        }
        lastError?.let { throw it }
        return null
    }

    private fun createDownloader(factory: CacheDataSource.Factory, fileName: String): Downloader {
        val uri = fileName.toUri()
        val request = DownloadRequest.Builder(fileName, uri).build()
        return DefaultDownloaderFactory(factory, okHttpClient.dispatcher.executorService)
            .createDownloader(request)
    }

    private fun mediaItemForRequest(request: SpeakAudioRequest, uri: Uri): MediaItem {
        return MediaItem.Builder()
            .setUri(uri)
            .setTag(request.index)
            .build()
    }

    private fun createMediaSource(factory: DataSource.Factory, request: SpeakAudioRequest): MediaSource {
        return DefaultMediaSourceFactory(this)
            .setDataSourceFactory(factory)
            .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            .createMediaSource(mediaItemForRequest(request, request.fileName.toUri()))
    }

    private suspend fun getSpeakStream(
        httpTts: HttpTTS,
        speakText: String,
        route: SpeechRoute? = null
    ): InputStream? {
        val suppressRouteError = suppressRouteTtsError(route)
        var downloadErrorNo = 0
        while (true) {
            try {
                val analyzeUrl = AnalyzeUrl(
                    httpTts.url,
                    speakText = speakText,
                    speakSpeed = speechRate,
                    currentToneID = route?.toneID,
                    currentSpeakerName = route?.speakerName,
                    currentEmotionName = route?.emotionName,
                    currentEmotionTag = route?.emotionTag,
                    currentSpeechRouteJson = route?.toJson(),
                    source = httpTts,
                    readTimeout = 300 * 1000L,
                    allowWebSocket = true,
                    coroutineContext = currentCoroutineContext()
                )
                val checkJs = httpTts.loginCheckJs
                val response = kotlin.runCatching {
                    analyzeUrl.getResponseAwait().let {
                        currentCoroutineContext().ensureActive()
                        if (!checkJs.isNullOrBlank()) {
                            analyzeUrl.evalJS(checkJs, it) as Response
                        } else {
                            it
                        }
                    }
                }.getOrElse { throwable ->
                    currentCoroutineContext().ensureActive()
                    if (!checkJs.isNullOrBlank()) {
                        val errResponse = analyzeUrl.getErrResponse(throwable)
                        try {
                            (analyzeUrl.evalJS(checkJs, errResponse) as Response).also {
                                if (it.code == 500) {
                                    throw throwable
                                }
                            }
                        } catch (_: Throwable) {
                            throw throwable
                        }
                    } else {
                        throw throwable
                    }
                }
                response.headers["Content-Type"]?.let { contentType ->
                    val contentType = contentType.substringBefore(";")
                    val ct = httpTts.contentType
                    if (contentType == "application/json" || contentType.startsWith("text/")) {
                        throw NoStackTraceException(response.peekBody(8_192).string())
                    } else if (ct?.isNotBlank() == true) {
                        if (!contentType.matches(ct.toRegex())) {
                            throw NoStackTraceException(
                                "TTS服务器返回错误：" + response.peekBody(8_192).string()
                            )
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                response.body.byteStream().let { stream ->
                    return stream
                }
            } catch (e: Exception) {
                when (e) {
                    is CancellationException -> throw e
                    is ScriptException, is WrappedException -> {
                        if (suppressRouteError) {
                            AppLog.putDebug("多角色 TTS 脚本失败，准备尝试备用发言人：${route?.speakerName.orEmpty()}", e)
                        } else {
                            AppLog.put("js错误\n${e.localizedMessage}", e, true)
                        }
                        e.printOnDebug()
                        throw e
                    }

                    is SocketTimeoutException, is ConnectException -> {
                        downloadErrorNo++
                        if (downloadErrorNo > 5) {
                            val msg = "tts超时或连接错误超过5次\n${e.localizedMessage}"
                            if (suppressRouteError) {
                                AppLog.putDebug("多角色 TTS 超时，准备尝试备用发言人：${route?.speakerName.orEmpty()}", e)
                            } else {
                                AppLog.put(msg, e, true)
                            }
                            throw e
                        }
                    }

                    else -> {
                        downloadErrorNo++
                        val msg = "tts下载错误\n${e.localizedMessage}"
                        if (suppressRouteError) {
                            AppLog.putDebug("多角色 $msg", e)
                        } else {
                            AppLog.put(msg, e)
                        }
                        e.printOnDebug()
                        if (downloadErrorNo > 5) {
                            val msg1 = "TTS服务器连续5次错误，已暂停阅读。"
                            if (suppressRouteError) {
                                AppLog.putDebug("多角色 TTS 连续失败，准备尝试备用发言人：${route?.speakerName.orEmpty()}", e)
                            } else {
                                AppLog.put(msg1, e, true)
                            }
                            throw e
                        } else {
                            if (!suppressRouteError) {
                                AppLog.put("TTS下载音频出错，使用无声音频代替。\n朗读文本：$speakText")
                            }
                            delay((downloadErrorNo * 800L).coerceAtMost(4000L))
                            continue
                        }
                    }
                }
            }
        }
        return null
    }

    private fun speechRouteForIndex(index: Int): SpeechRoute? {
        val defaultRoute = ReadAloud.speechRoute.takeIf { it.isConfigured }
        if (!AppConfig.aiReadAloudRoleEnabled) return defaultRoute
        val speechItem = speechItems.getOrNull(index)
        if (speechItem?.roleType == "character" || speechItem?.roleType == "thought") {
            return freshSpeechRouteForItem(speechItem) ?: defaultRoute
        }
        if (index in speechRoutes.indices) {
            return speechRoutes[index] ?: defaultRoute
        }
        val book = ReadBook.book ?: return null
        val chapter = textChapter ?: return null
        return AiReadAloudRoleService.routeForCue(
            bookUrl = book.characterBookKey(),
            chapterIndex = chapter.chapter.index,
            cueIndex = index,
            cueText = contentList.getOrNull(index)
        ) ?: defaultRoute
    }

    private fun freshSpeechRouteForItem(item: ReadAloudSpeechPlanItem?): SpeechRoute? {
        val speechItem = item ?: return null
        if (speechItem.roleType != "character" && speechItem.roleType != "thought") return null
        val bookKey = ReadBook.book?.characterBookKey() ?: return null
        val route = AiReadAloudRoleService.routeForSegment(
            bookUrl = bookKey,
            segment = AiReadAloudRoleService.Segment(
                paragraphIndex = speechItem.sourceCueIndex,
                start = speechItem.sourceStart,
                end = speechItem.sourceEnd.coerceAtLeast(speechItem.sourceStart + 1),
                roleType = speechItem.roleType,
                characterName = speechItem.characterName,
                characterId = speechItem.characterId,
                emotionName = speechItem.emotionName,
                emotionTag = speechItem.route?.emotionTag.orEmpty(),
                confidence = 1.0
            )
        )
        return route ?: speechItem.route?.takeIf { it.isConfigured && speechItem.characterId <= 0L }
    }

    private fun httpTtsForRoute(defaultHttpTts: HttpTTS?, route: SpeechRoute?): HttpTTS? {
        if (route?.engineType == SpeechRoute.ENGINE_SYSTEM) return null
        val id = route?.engineValue?.toLongOrNull() ?: return defaultHttpTts
        return appDb.httpTTSDao.get(id) ?: defaultHttpTts
    }

    private fun defaultSystemRoute(): SpeechRoute {
        val route = ReadAloud.speechRoute
        if (route.engineType == SpeechRoute.ENGINE_SYSTEM && route.isConfigured) return route
        return SpeechRoute(
            engineType = SpeechRoute.ENGINE_SYSTEM,
            engineValue = ReadAloud.ttsEngine.orEmpty(),
            speakerName = "系统默认",
            source = SpeechRoute.SOURCE_AUTO
        )
    }

    private fun fallbackRoutesFor(route: SpeechRoute?): List<SpeechRoute> {
        val httpTtsList = appDb.httpTTSDao.all
        val originalKey = route?.fallbackKey().orEmpty()
        val candidates = if (route?.isConfigured == true) {
            SpeechVoiceGroupRepository.assignableRoutes(httpTtsList)
                .ifEmpty { SpeechVoiceCatalogRepository.assignableRoutes(httpTtsList) }
        } else {
            emptyList()
        }
        return (candidates + defaultSystemRoute())
            .filter { it.isConfigured }
            .filterNot(SpeechVoiceGroupRepository::isBlockedRoute)
            .distinctBy { it.fallbackKey() }
            .filter { it.fallbackKey() != originalKey }
    }

    private fun SpeechRoute.fallbackKey(): String {
        return "$engineType|$engineValue|$toneID|$speakerName"
    }

    private suspend fun synthesizeSystemSpeakFile(
        fileName: String,
        speakText: String,
        route: SpeechRoute
    ): Boolean {
        val file = createSpeakFile(fileName)
        if (file.exists() && file.length() > 0L) return true
        return runCatching {
            val engine = resolveSystemTtsEngine(route.engineValue)
            val initResult = CompletableDeferred<Int>()
            val tts = withContext(Main) {
                if (engine.isNullOrBlank()) {
                    TextToSpeech(this@HttpReadAloudService) { status ->
                        initResult.complete(status)
                    }
                } else {
                    TextToSpeech(this@HttpReadAloudService, { status ->
                        initResult.complete(status)
                    }, engine)
                }
            }
            try {
                if (withTimeout(20_000L) { initResult.await() } != TextToSpeech.SUCCESS) {
                    return@runCatching false
                }
                if (!AppConfig.ttsFlowSys) {
                    tts.setSpeechRate((AppConfig.ttsSpeechRate + 5) / 10f)
                }
                val utteranceId = "mixed_tts_$fileName"
                val done = CompletableDeferred<Boolean>()
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        done.complete(true)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        done.complete(false)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        done.complete(false)
                    }
                })
                val params = Bundle()
                val result = tts.synthesizeToFile(speakText, params, file, utteranceId)
                if (result == TextToSpeech.ERROR) return@runCatching false
                withTimeout(120_000L) { done.await() } && file.exists() && file.length() > 0L
            } finally {
                withContext(Main) {
                    tts.stop()
                    tts.shutdown()
                }
            }
        }.onFailure {
            file.takeIf { it.exists() && it.length() <= 0L }?.delete()
            AppLog.put("系统 TTS 合成失败，使用静音占位\n${it.localizedMessage ?: it.javaClass.simpleName}", it)
        }.getOrDefault(false)
    }

    private fun resolveSystemTtsEngine(engineValue: String): String? {
        val value = engineValue.trim()
        if (value.isBlank()) return null
        return runCatching {
            JSONObject(value).optString("value").takeIf { it.isNotBlank() }
        }.getOrNull() ?: value
    }

    private fun md5SpeakFileName(
        content: String,
        textChapter: TextChapter? = this.textChapter,
        route: SpeechRoute? = null
    ): String {
        val routeKey = route?.takeIf { it.isConfigured }?.toJson().orEmpty()
        val engineKey = ReadAloud.httpTTS?.url ?: ReadAloud.ttsEngine.orEmpty()
        return MD5Utils.md5Encode16(textChapter.readAloudTitle()) + "_" +
                MD5Utils.md5Encode16("$engineKey-|-$routeKey-|-$speechRate-|-$content")
    }

    private fun TextChapter?.readAloudTitle(): String {
        return this?.chapter?.title?.takeIf { it.isNotBlank() }.orEmpty()
    }

    private fun createSilentSound(fileName: String) {
        val file = createSpeakFile(fileName)
        file.writeBytes(resources.openRawResource(R.raw.silent_sound).readBytes())
    }

    private fun hasSpeakFile(name: String): Boolean {
        return FileUtils.exist("${ttsFolderPath}$name.mp3")
    }

    private fun getSpeakFileAsMd5(name: String): File {
        return File("${ttsFolderPath}$name.mp3")
    }

    private fun createSpeakFile(name: String): File {
        return FileUtils.createFileIfNotExist("${ttsFolderPath}$name.mp3")
    }

    private fun createSpeakFile(name: String, inputStream: InputStream) {
        FileUtils.createFileIfNotExist("${ttsFolderPath}$name.mp3").outputStream().use { out ->
            inputStream.use {
                it.copyTo(out)
            }
        }
    }

    /**
     * 移除缓存文件
     */
    private fun removeCacheFile() {
        val titleMd5 = MD5Utils.md5Encode16(textChapter.readAloudTitle())
        FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach {
            val isSilentSound = it.length() == 2160L
            if ((!it.name.startsWith(titleMd5)
                        && System.currentTimeMillis() - it.lastModified() > 600000)
                || isSilentSound
            ) {
                FileUtils.delete(it.absolutePath)
            }
        }
    }


    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        kotlin.runCatching {
            playIndexJob?.cancel()
            exoPlayer.pause()
        }
    }

    override fun resumeReadAloud() {
        if (resumeBlockedReadAloudIfNeeded()) return
        super.resumeReadAloud()
        kotlin.runCatching {
            if (pageChanged) {
                play()
            } else {
                exoPlayer.play()
                postExoPlaybackPhase()
                upPlayPos()
            }
        }
    }

    private fun upPlayPos() {
        playIndexJob?.cancel()
        val textChapter = textChapter ?: return
        val cueIndex = syncToMediaItemCue()
        val cueText = contentList.getOrNull(cueIndex) ?: return
        val cueStartOffset = paragraphStartPos
            .takeIf { cueIndex == nowSpeak }
            ?.coerceIn(0, cueText.length)
            ?: 0
        playIndexJob = lifecycleScope.launch {
            upTtsProgress(readAloudNumber + cueStartOffset + 1)
            if (exoPlayer.duration <= 0) {
                return@launch
            }
            if (cueIndex != nowSpeak || contentList.getOrNull(cueIndex) != cueText) {
                return@launch
            }
            val speakTextLength = cueText.length - cueStartOffset
            if (speakTextLength <= 0) {
                return@launch
            }
            while (true) {
                if (cueIndex != nowSpeak || contentList.getOrNull(cueIndex) != cueText) {
                    return@launch
                }
                val duration = exoPlayer.duration
                if (duration <= 0L) return@launch
                val position = (speakTextLength * exoPlayer.currentPosition / duration)
                    .coerceIn(0L, speakTextLength.toLong())
                while (pageIndex + 1 < textChapter.pageSize &&
                    readAloudNumber + cueStartOffset + position >
                    textChapter.getReadLength(pageIndex + 1)
                ) {
                    pageIndex++
                    ReadBook.moveToNextPage(fromReadAloud = true)
                    upTtsProgress(readAloudNumber + cueStartOffset + position.toInt())
                }
                if (position >= speakTextLength) break
                delay(100L)
            }
        }
    }

    /**
     * 更新朗读速度
     */
    override fun upSpeechRate(reset: Boolean) {
        downloadTask?.cancel()
        exoPlayer.stop()
        speechRate = AppConfig.speechRatePlay + 5
        if (AppConfig.streamReadAloudAudio) {
            downloadAndPlayAudiosStream()
        } else {
            downloadAndPlayAudios()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        super.onPlaybackStateChanged(playbackState)
        when (playbackState) {
            Player.STATE_IDLE -> {
                // 空闲
                postExoPlaybackPhase()
            }

            Player.STATE_BUFFERING -> {
                postExoPlaybackPhase("音频加载中")
            }

            Player.STATE_READY -> {
                // 准备好
                if (pause) return
                exoPlayer.play()
                postExoPlaybackPhase()
                upPlayPos()
            }

            Player.STATE_ENDED -> {
                // 结束
                playErrorNo = 0
                updateNextPos()
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
            }
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        when (reason) {
            Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED -> {
                if (!timeline.isEmpty && exoPlayer.playbackState == Player.STATE_IDLE) {
                    exoPlayer.prepare()
                }
            }

            else -> {}
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            playErrorNo = 0
        }
        if (mediaItemCueIndex(mediaItem) != null) {
            syncToMediaItemCue(mediaItem)
        } else {
            updateNextPos()
        }
        postExoPlaybackPhase()
        upPlayPos()
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        AppLog.put("朗读错误\n${contentList.getOrNull(nowSpeak).orEmpty()}", error)
        deleteCurrentSpeakFile()
        playErrorNo++
        if (playErrorNo >= 5) {
            toastOnUi("朗读连续5次错误, 最后一次错误代码(${error.localizedMessage})")
            AppLog.put("朗读连续5次错误, 最后一次错误代码(${error.localizedMessage})", error)
            postReadAloudPlaybackPhase(ReadAloudPlaybackState.PHASE_ERROR, message = error.localizedMessage ?: "")
            pauseReadAloud()
        } else {
            postReadAloudPlaybackPhase(ReadAloudPlaybackState.PHASE_BUFFERING, message = "重试当前音频")
            retryCurrentCue()
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        super.onIsPlayingChanged(isPlaying)
        postExoPlaybackPhase()
        if (isPlaying) {
            upPlayPos()
        }
    }

    private fun retryCurrentCue() {
        playIndexJob?.cancel()
        downloadTask?.cancel()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        lifecycleScope.launch {
            delay((playErrorNo * 700L).coerceAtMost(3500L))
            if (pause || nowSpeak !in contentList.indices) return@launch
            syncReadAloudPositionToCue()
            upTtsProgress(readAloudNumber + 1)
            if (AppConfig.streamReadAloudAudio) {
                downloadAndPlayAudiosStream()
            } else {
                downloadAndPlayAudios()
            }
        }
    }

    private fun postExoPlaybackPhase(message: String = "") {
        val actualPlaying = exoPlayer.isPlaying
        val buffering = exoPlayer.playbackState == Player.STATE_BUFFERING ||
                (!pause && exoPlayer.playbackState == Player.STATE_READY && !actualPlaying)
        val phase = when {
            actualPlaying -> ReadAloudPlaybackState.PHASE_PLAYING
            pause -> ReadAloudPlaybackState.PHASE_PAUSED
            buffering -> ReadAloudPlaybackState.PHASE_BUFFERING
            exoPlayer.playbackState == Player.STATE_ENDED -> ReadAloudPlaybackState.PHASE_STOPPED
            else -> ReadAloudPlaybackState.PHASE_PREPARING
        }
        postReadAloudPlaybackPhase(
            phase = phase,
            cueIndex = syncToMediaItemCue(),
            message = message,
            playing = actualPlaying,
            buffering = buffering
        )
    }

    private fun deleteCurrentSpeakFile() {
        if (AppConfig.streamReadAloudAudio) {
            return
        }
        val mediaItem = exoPlayer.currentMediaItem ?: return
        val filePath = mediaItem.localConfiguration!!.uri.path!!
        File(filePath).delete()
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<HttpReadAloudService>(actionStr)
    }

    class CustomLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(0) {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            return C.TIME_UNSET
        }
    }

}
