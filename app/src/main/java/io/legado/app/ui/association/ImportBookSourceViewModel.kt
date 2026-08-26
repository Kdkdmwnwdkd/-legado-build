package io.legado.app.ui.association

import android.app.Application
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import com.jayway.jsonpath.JsonPath
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.RuleUpdate
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.inputStream
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.isUri
import io.legado.app.utils.splitNotBlank


internal data class BookSourceImportConflict(
    val localSource: BookSourcePart?,
    val selectedByDefault: Boolean,
    val isNew: Boolean,
    val isUpdate: Boolean
)

internal fun resolveBookSourceImportConflict(
    importedSource: BookSource,
    localCandidate: BookSourcePart?
): BookSourceImportConflict {
    val localSource = localCandidate?.takeIf {
        it.bookSourceUrl == importedSource.bookSourceUrl
    }
    val isNew = localSource == null
    val isUpdate = localSource != null && localSource.lastUpdateTime < importedSource.lastUpdateTime
    return BookSourceImportConflict(
        localSource = localSource,
        selectedByDefault = isNew || isUpdate,
        isNew = isNew,
        isUpdate = isUpdate
    )
}

internal data class BookSourceImportOptions(
    val keepName: Boolean,
    val keepGroup: Boolean,
    val keepEnable: Boolean,
    val groupName: String?,
    val addGroup: Boolean
)

internal fun prepareBookSourceForImport(
    importedSource: BookSource,
    localCandidate: BookSourcePart?,
    options: BookSourceImportOptions
): BookSource {
    val source = importedSource.copy()
    resolveBookSourceImportConflict(source, localCandidate).localSource?.let { localSource ->
        if (options.keepName) {
            source.bookSourceName = localSource.bookSourceName
        }
        if (options.keepGroup) {
            source.bookSourceGroup = localSource.bookSourceGroup
        }
        if (options.keepEnable) {
            source.enabled = localSource.enabled
            source.enabledExplore = localSource.enabledExplore
        }
        source.customOrder = localSource.customOrder
    }
    options.groupName?.trim()?.takeIf { it.isNotEmpty() }?.let { group ->
        if (options.addGroup) {
            val groups = linkedSetOf<String>()
            source.bookSourceGroup?.splitNotBlank(AppPattern.splitGroupRegex)?.let(groups::addAll)
            groups.add(group)
            source.bookSourceGroup = groups.joinToString(",")
        } else {
            source.bookSourceGroup = group
        }
    }
    return source
}


class ImportBookSourceViewModel(app: Application) : BaseViewModel(app) {
    var isAddGroup = false
    var groupName: String? = null
    val errorLiveData = MutableLiveData<String>()
    val successLiveData = MutableLiveData<Int>()

    val allSources = arrayListOf<BookSource>()
    val checkSources = arrayListOf<BookSourcePart?>()
    val selectStatus = androidx.compose.runtime.mutableStateListOf<Boolean>()
    val newSourceStatus = arrayListOf<Boolean>()
    val updateSourceStatus = arrayListOf<Boolean>()

    private val pendingConflictIndicesState =
        androidx.compose.runtime.mutableStateOf<Set<Int>>(emptySet())
    private val conflictRefreshErrorsState =
        androidx.compose.runtime.mutableStateOf<Map<Int, String>>(emptyMap())
    private val conflictRefreshTokens = mutableMapOf<Int, Long>()
    private var nextConflictRefreshToken = 0L

    val conflictRefreshPending: Boolean
        get() = pendingConflictIndicesState.value.isNotEmpty()

    val conflictRefreshError: String?
        get() = conflictRefreshErrorsState.value.values.firstOrNull()

    val isSelectAll: Boolean
        get() {
            selectStatus.forEach {
                if (!it) {
                    return false
                }
            }
            return true
        }

    val isSelectAllNew: Boolean
        get() {
            newSourceStatus.forEachIndexed { index, b ->
                if (b && !selectStatus[index]) {
                    return false
                }
            }
            return true
        }

    val isSelectAllUpdate: Boolean
        get() {
            updateSourceStatus.forEachIndexed { index, b ->
                if (b && !selectStatus[index]) {
                    return false
                }
            }
            return true
        }

    val selectCount: Int
        get() {
            var count = 0
            selectStatus.forEach {
                if (it) {
                    count++
                }
            }
            return count
        }

    fun importSelect(onSuccess: () -> Unit, onError: (Throwable) -> Unit) {
        executeLazy {
            val options = BookSourceImportOptions(
                keepName = AppConfig.importKeepName,
                keepGroup = AppConfig.importKeepGroup,
                keepEnable = AppConfig.importKeepEnable,
                groupName = groupName,
                addGroup = isAddGroup
            )
            val selectSource = arrayListOf<BookSource>()
            selectStatus.forEachIndexed { index, b ->
                if (b) {
                    val importedSource = allSources[index]
                    val localSource = appDb.bookSourceDao
                        .getBookSourcePart(importedSource.bookSourceUrl)
                    selectSource.add(
                        prepareBookSourceForImport(importedSource, localSource, options)
                    )
                }
            }
            SourceHelp.insertBookSource(*selectSource.toTypedArray())
            ContentProcessor.upReplaceRules()
        }.onSuccess {
            onSuccess()
        }.onError {
            AppLog.put("ImportError:${it.localizedMessage}", it)
            onError(it)
        }.start()
    }

    fun updateEditedSource(index: Int, source: BookSource) {
        if (index !in allSources.indices ||
            index !in checkSources.indices ||
            index !in newSourceStatus.indices ||
            index !in updateSourceStatus.indices ||
            index !in selectStatus.indices
        ) {
            return
        }
        val resetSelection = allSources[index].bookSourceUrl != source.bookSourceUrl
        allSources[index] = source
        checkSources[index] = null
        newSourceStatus[index] = false
        updateSourceStatus[index] = false
        conflictRefreshErrorsState.value = conflictRefreshErrorsState.value - index
        pendingConflictIndicesState.value = pendingConflictIndicesState.value + index
        val token = ++nextConflictRefreshToken
        conflictRefreshTokens[index] = token
        executeLazy {
            appDb.bookSourceDao.getBookSourcePart(source.bookSourceUrl)
        }.onSuccess { localSource ->
            if (conflictRefreshTokens[index] != token || allSources.getOrNull(index) !== source) {
                return@onSuccess
            }
            applyConflict(index, resolveBookSourceImportConflict(source, localSource), resetSelection)
            conflictRefreshTokens.remove(index)
            pendingConflictIndicesState.value = pendingConflictIndicesState.value - index
            conflictRefreshErrorsState.value = conflictRefreshErrorsState.value - index
        }.onError {
            if (conflictRefreshTokens[index] != token || allSources.getOrNull(index) !== source) {
                return@onError
            }
            conflictRefreshTokens.remove(index)
            pendingConflictIndicesState.value = pendingConflictIndicesState.value - index
            conflictRefreshErrorsState.value = conflictRefreshErrorsState.value + (
                index to (it.localizedMessage ?: context.getString(R.string.unknown_error))
            )
        }.start()
    }

    fun importSource(text: String) {
        execute {
            val mText = text.trim()
            when {
                mText.isJsonObject() -> {
                    kotlin.runCatching {
                        val json = JsonPath.parse(mText)
                        json.read<List<String>>("$.sourceUrls")
                    }.onSuccess { listUrl ->
                        listUrl.forEach {
                            importSourceUrl(it)
                        }
                    }.onFailure {
                        GSON.fromJsonObject<BookSource>(mText).getOrThrow().let {
                            if (it.bookSourceUrl.isEmpty()) {
                                throw NoStackTraceException("不是书源")
                            }
                            allSources.add(it)
                        }
                    }
                }

                mText.isJsonArray() -> GSON.fromJsonArray<BookSource>(mText).getOrThrow()
                    .let { items ->
                        val source = items.firstOrNull() ?: return@let
                        if (source.bookSourceUrl.isEmpty()) {
                            throw NoStackTraceException("不是书源")
                        }
                        allSources.addAll(items)
                    }

                mText.isAbsUrl() -> {
                    importSourceUrl(mText)
                }

                mText.isUri() -> {
                    val uri = Uri.parse(mText)
                    uri.inputStream(context).getOrThrow().use { inputS ->
                        GSON.fromJsonArray<BookSource>(inputS).getOrThrow().let {
                            val source = it.firstOrNull() ?: return@let
                            if (source.bookSourceUrl.isEmpty()) {
                                throw NoStackTraceException("不是书源")
                            }
                            allSources.addAll(it)
                        }
                    }
                }

                else -> throw NoStackTraceException(context.getString(R.string.wrong_format))
            }
        }.onError {
            errorLiveData.postValue("ImportError:${it.localizedMessage}")
            AppLog.put("ImportError:${it.localizedMessage}", it)
        }.onSuccess {
            comparisonSource()
        }
    }

    private suspend fun importSourceUrl(url: String) {
        RuleUpdate.cacheBookSourceMap[url]?.also {
            allSources.addAll(it)
            RuleUpdate.cacheBookSourceMap.remove(url)
            return
        }
        okHttpClient.newCallResponseBody {
            if (url.endsWith("#requestWithoutUA")) {
                url(url.substringBeforeLast("#requestWithoutUA"))
                header(AppConst.UA_NAME, "null")
            } else {
                url(url)
            }
        }.useLimitedImportStream {
            GSON.fromJsonArray<BookSource>(it).getOrThrow().let { list ->
                val source = list.firstOrNull() ?: return@let
                if (source.bookSourceUrl.isEmpty()) {
                    throw NoStackTraceException("不是书源")
                }
                allSources.addAll(list)
            }
        }
    }

    private fun comparisonSource() {
        execute {
            allSources.forEach { importedSource ->
                val localSource = appDb.bookSourceDao.getBookSourcePart(importedSource.bookSourceUrl)
                val conflict = resolveBookSourceImportConflict(importedSource, localSource)
                checkSources.add(conflict.localSource)
                selectStatus.add(conflict.selectedByDefault)
                newSourceStatus.add(conflict.isNew)
                updateSourceStatus.add(conflict.isUpdate)
            }
            successLiveData.postValue(allSources.size)
        }
    }

    private fun applyConflict(
        index: Int,
        conflict: BookSourceImportConflict,
        resetSelection: Boolean
    ) {
        checkSources[index] = conflict.localSource
        newSourceStatus[index] = conflict.isNew
        updateSourceStatus[index] = conflict.isUpdate
        if (resetSelection) {
            selectStatus[index] = conflict.selectedByDefault
        }
    }

}
