package io.legado.app.ui.autoTask

import android.app.Application
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.importHttpClient as okHttpClient
import io.legado.app.lib.cronet.CronetInterceptor
import io.legado.app.model.AutoTask
import io.legado.app.model.AutoTaskImport
import io.legado.app.model.AutoTaskRule
import io.legado.app.ui.association.readLimitedImportTextWithGzip
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isUri
import io.legado.app.utils.readBytes
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ImportAutoTaskItem(
    val rule: AutoTaskRule,
    val existing: AutoTaskRule?,
    val state: AutoTaskImport.State,
    val selected: Boolean
)

sealed interface ImportAutoTaskState {
    data object Loading : ImportAutoTaskState
    data class Ready(val items: List<ImportAutoTaskItem>) : ImportAutoTaskState
    data class Saving(val items: List<ImportAutoTaskItem>) : ImportAutoTaskState
    data object Imported : ImportAutoTaskState
    data class Error(val message: String) : ImportAutoTaskState
}

class ImportAutoTaskViewModel(application: Application) : BaseViewModel(application) {

    // This endpoint returns an invalid identity representation when Cronet
    // strips an explicitly negotiated encoding. Keep the workaround local to
    // auto-task imports instead of changing the application's network policy.
    private val importHttpClient by lazy {
        okHttpClient.newBuilder().apply {
            interceptors().removeAll { it is CronetInterceptor }
        }.build()
    }

    private val _state = MutableStateFlow<ImportAutoTaskState>(ImportAutoTaskState.Loading)
    val state = _state.asStateFlow()

    private var loadJob: Job? = null
    private var localSnapshot: List<AutoTaskRule> = emptyList()
    private var loadGeneration = 0L

    fun load(source: String?) {
        val value = source?.trim().orEmpty()
        loadJob?.cancel()
        val generation = ++loadGeneration
        if (value.isBlank()) {
            _state.value = ImportAutoTaskState.Error(context.getString(R.string.wrong_format))
            return
        }
        _state.value = ImportAutoTaskState.Loading
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = readSource(value)
                val imported = AutoTaskImport.parse(text).getOrThrow()
                val local = AutoTask.all()
                localSnapshot = local
                val compared = AutoTaskImport.compare(imported, local)
                val items = compared.map { entry ->
                    ImportAutoTaskItem(
                        rule = entry.rule,
                        existing = entry.existing,
                        state = entry.state,
                        selected = entry.state != AutoTaskImport.State.EXISTING
                    )
                }
                if (generation == loadGeneration) {
                    _state.value = ImportAutoTaskState.Ready(items)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation == loadGeneration) {
                    _state.value = ImportAutoTaskState.Error(
                        error.localizedMessage?.takeIf { it.isNotBlank() }
                            ?: context.getString(R.string.wrong_format)
                    )
                }
            }
        }
    }

    fun toggle(index: Int, checked: Boolean) {
        updateReady { items ->
            if (index !in items.indices) items else items.toMutableList().apply {
                this[index] = this[index].copy(selected = checked)
            }
        }
    }

    fun toggleAll() {
        updateReady { items ->
            val selectAll = items.any { !it.selected }
            items.map { it.copy(selected = selectAll) }
        }
    }

    fun updateRuleFromJson(index: Int, code: String): Boolean {
        val ready = _state.value as? ImportAutoTaskState.Ready ?: return false
        val parsed = AutoTaskImport.parse(code).getOrElse { error ->
            context.toastOnUi(error.localizedMessage ?: context.getString(R.string.wrong_format))
            return false
        }
        if (parsed.size != 1) {
            context.toastOnUi(R.string.wrong_format)
            return false
        }
        if (index !in ready.items.indices) return false
        val duplicate = ready.items.withIndex()
            .filter { (itemIndex, _) -> itemIndex != index }
            .any { (_, item) -> item.rule.id == parsed.single().id }
        if (duplicate) {
            context.toastOnUi(R.string.auto_task_import_duplicate_id)
            return false
        }
        val entry = AutoTaskImport.compare(parsed, localSnapshot).single()
        val current = ready.items[index]
        val replacement = ImportAutoTaskItem(
            rule = entry.rule,
            existing = entry.existing,
            state = entry.state,
            // Editing an already selected row should not unexpectedly deselect it.
            selected = current.selected || entry.state != AutoTaskImport.State.EXISTING
        )
        _state.value = ImportAutoTaskState.Ready(
            ready.items.toMutableList().apply { this[index] = replacement }
        )
        return true
    }

    fun importSelected(
        onSuccess: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val ready = _state.value as? ImportAutoTaskState.Ready ?: return
        val selected = ready.items.filter { it.selected }.map { it.rule }
        if (selected.isEmpty()) {
            context.toastOnUi(R.string.auto_task_import_select_none)
            return
        }
        _state.value = ImportAutoTaskState.Saving(ready.items)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { AutoTask.upsert(selected) }
                .onSuccess {
                    _state.value = ImportAutoTaskState.Imported
                    withContext(Dispatchers.Main.immediate) { onSuccess() }
                }
                .onFailure { error ->
                    _state.value = ImportAutoTaskState.Ready(ready.items)
                    withContext(Dispatchers.Main.immediate) { onError(error) }
                }
        }
    }

    override fun onCleared() {
        loadJob?.cancel()
        super.onCleared()
    }

    private fun updateReady(transform: (List<ImportAutoTaskItem>) -> List<ImportAutoTaskItem>) {
        val ready = _state.value as? ImportAutoTaskState.Ready ?: return
        _state.value = ImportAutoTaskState.Ready(transform(ready.items))
    }

    private suspend fun readSource(source: String): String {
        return when {
            source.isAbsUrl() -> {
                importHttpClient.newCallResponseBody {
                    // This endpoint serves an invalid identity representation;
                    // explicitly negotiate gzip and let the local import reader
                    // handle either an encoded or already-decoded response.
                    header("Accept-Encoding", "gzip")
                    if (source.endsWith("#requestWithoutUA")) {
                        url(source.substringBeforeLast("#requestWithoutUA"))
                        header(AppConst.UA_NAME, "null")
                    } else {
                        url(source)
                    }
                }.use { body -> body.readLimitedImportTextWithGzip(AutoTaskImport.MAX_IMPORT_BYTES) }
            }
            source.isUri() -> {
                source.toUri()
                    .readBytes(context, AutoTaskImport.MAX_IMPORT_BYTES)
                    .toString(Charsets.UTF_8)
            }
            else -> source
        }
    }
}
