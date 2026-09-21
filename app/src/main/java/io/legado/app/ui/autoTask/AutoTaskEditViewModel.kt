package io.legado.app.ui.autoTask

import android.app.Application
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.model.AutoTask
import io.legado.app.model.AutoTaskRule
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AutoTaskEditViewModel(application: Application) : BaseViewModel(application) {

    private val _task = MutableStateFlow<AutoTaskRule?>(null)
    val task = _task.asStateFlow()

    fun load(id: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            _task.value = AutoTask.get(id.orEmpty()) ?: AutoTaskRule()
        }
    }

    fun save(rule: AutoTaskRule, onSuccess: (AutoTaskRule) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { AutoTask.upsert(rule) }
                .onSuccess { saved ->
                    withContext(Dispatchers.Main.immediate) { onSuccess(saved) }
                }
                .onFailure { error ->
                    context.toastOnUi(error.localizedMessage.orEmpty())
                }
        }
    }
}
