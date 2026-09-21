package io.legado.app.ui.autoTask

import androidx.appcompat.app.AppCompatActivity
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.databinding.DialogBookAutoTaskBinding
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.AutoTask
import io.legado.app.model.AutoTaskBookConfig
import io.legado.app.model.AutoTaskBookSettings
import io.legado.app.utils.toastOnUi

/** Shows the shared book-update task editor used by details and bookshelf entry points. */
fun AppCompatActivity.showBookAutoTaskDialog(
    book: Book,
    onSaved: (() -> Unit)? = null
) {
    if (book.isLocal || book.bookUrl.isBlank() || book.origin.isBlank()) return

    val fallbackHours = LocalConfig.bookAutoTaskIntervalHours
        .coerceIn(
            AutoTaskBookSettings.MIN_INTERVAL_HOURS,
            AutoTaskBookSettings.MAX_INTERVAL_HOURS
        )
    val settings = AutoTaskBookConfig.load(book.bookUrl, fallbackHours)
    val dialogBinding = DialogBookAutoTaskBinding.inflate(layoutInflater).apply {
        switchEnable.isChecked = settings.enabled
        switchNotify.isChecked = settings.notifyEnabled
        switchCache.isChecked = settings.cacheEnabled
        editInterval.setText(settings.intervalHours.toString())
    }
    val displayName = book.name.ifBlank { book.bookUrl }

    alert(R.string.auto_task_book_update) {
        customView { dialogBinding.root }
        okButton {
            val hours = dialogBinding.editInterval.text
                ?.toString()
                ?.trim()
                ?.toIntOrNull()
                ?.coerceIn(
                    AutoTaskBookSettings.MIN_INTERVAL_HOURS,
                    AutoTaskBookSettings.MAX_INTERVAL_HOURS
                )
                ?: fallbackHours
            val nextSettings = AutoTaskBookSettings(
                enabled = dialogBinding.switchEnable.isChecked,
                notifyEnabled = dialogBinding.switchNotify.isChecked,
                cacheEnabled = dialogBinding.switchCache.isChecked,
                intervalHours = hours
            )
            runCatching {
                AutoTask.upsert(
                    AutoTaskBookConfig.buildRule(
                        book = book,
                        settings = nextSettings,
                        name = getString(R.string.auto_task_book_update_name, displayName)
                    )
                )
                LocalConfig.bookAutoTaskIntervalHours = hours
            }.onSuccess {
                toastOnUi(
                    if (nextSettings.enabled) {
                        R.string.auto_task_book_update_saved
                    } else {
                        R.string.auto_task_book_update_deleted
                    }
                )
                onSaved?.invoke()
            }.onFailure { error ->
                toastOnUi(
                    getString(
                        R.string.auto_task_failed,
                        error.localizedMessage ?: getString(R.string.error)
                    )
                )
            }
        }
        cancelButton()
    }
}
