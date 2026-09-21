package io.legado.app.ui.autoTask

import android.app.Application
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.model.AutoTask
import io.legado.app.model.AutoTaskRule
import io.legado.app.service.AutoTaskService
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Owns task mutations; the Room flow remains the single list source of truth. */
class AutoTaskViewModel(application: Application) : BaseViewModel(application) {

    val rulesFlow = flow {
        emitAll(AutoTask.flowAll())
    }
        .catch { error ->
            context.toastOnUi(error.localizedMessage.orEmpty())
            emit(emptyList())
        }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList()
        )

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { AutoTask.all() }
                .onFailure { context.toastOnUi(it.localizedMessage.orEmpty()) }
        }
    }

    fun toggleEnabled(id: String, enabled: Boolean) {
        mutate {
            check(AutoTask.setEnabled(id, enabled)) { "Task not found" }
        }
    }

    fun updateEnabled(ids: Collection<String>, enabled: Boolean) {
        val selected = ids.toSet()
        if (selected.isEmpty()) return
        mutate {
            val updated = AutoTask.all().map { rule ->
                if (rule.id in selected) rule.copy(enable = enabled) else rule
            }
            AutoTask.upsert(updated)
        }
    }

    fun updateCron(ids: Collection<String>, cron: String) {
        val selected = ids.toSet()
        if (selected.isEmpty()) return
        mutate {
            val updated = AutoTask.all().map { rule ->
                if (rule.id in selected) rule.copy(cron = cron) else rule
            }
            AutoTask.upsert(updated)
        }
    }

    fun delete(ids: Collection<String>) {
        val distinct = ids.filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty()) return
        mutate { AutoTask.delete(*distinct.toTypedArray()) }
    }

    fun reorder(ordered: List<AutoTaskRule>) {
        if (ordered.isEmpty()) return
        mutate { AutoTask.reorder(ordered.map(AutoTaskRule::id)) }
    }

    fun runNow(id: String) {
        AutoTaskService.runNow(context, id)
    }

    private fun mutate(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching(block).onFailure { error ->
                context.toastOnUi(error.localizedMessage.orEmpty())
            }
        }
    }
}
