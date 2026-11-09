package com.shimmer.reader.ui.autoTask

import androidx.appcompat.app.AppCompatActivity
import com.shimmer.reader.R
import com.shimmer.reader.data.entities.Book
import com.shimmer.reader.databinding.DialogBookAutoTaskBinding
import com.shimmer.reader.help.book.isLocal
import com.shimmer.reader.help.config.LocalConfig
import com.shimmer.reader.lib.dialogs.alert
import com.shimmer.reader.model.AutoTask
import com.shimmer.reader.model.AutoTaskBookConfig
import com.shimmer.reader.model.AutoTaskBookSettings
import com.shimmer.reader.utils.toastOnUi

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
