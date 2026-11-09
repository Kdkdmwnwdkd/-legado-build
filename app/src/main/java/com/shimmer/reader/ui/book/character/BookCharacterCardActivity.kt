package com.shimmer.reader.ui.book.character

import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import com.shimmer.reader.R
import com.shimmer.reader.base.BaseActivity
import com.shimmer.reader.data.appDb
import com.shimmer.reader.data.entities.BookCharacter
import com.shimmer.reader.help.readaloud.ReadAloudConfigChangeNotifier
import com.shimmer.reader.help.readaloud.speech.SpeechVoiceCatalogRepository
import com.shimmer.reader.ui.book.character.compose.CharacterCardScreen
import com.shimmer.reader.ui.book.character.compose.CharacterSpeechEngineUi
import com.shimmer.reader.utils.startActivity
import com.shimmer.reader.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookCharacterCardActivity : BaseActivity<ViewBinding>(
    fullScreen = false,
    imageBg = false
) {

    private lateinit var composeView: ComposeView
    override val binding: ViewBinding by lazy {
        composeView = ComposeView(this)
        SimpleViewBinding(composeView)
    }

    private var bookUrl: String = ""
    private var characterBookKey: String = ""
    private var characterId: Long = 0L
    private var character by mutableStateOf<BookCharacter?>(null)
    private var speechEngines by mutableStateOf<List<CharacterSpeechEngineUi>>(emptyList())

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        bookUrl = intent.getStringExtra(BookCharacterManageActivity.EXTRA_BOOK_URL).orEmpty()
        characterBookKey = intent.getStringExtra(BookCharacterManageActivity.EXTRA_CHARACTER_BOOK_KEY).orEmpty()
        characterId = intent.getLongExtra(BookCharacterManageActivity.EXTRA_CHARACTER_ID, 0L)
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        composeView.setContent {
            CharacterCardScreen(
                character = character,
                onBack = ::finish,
                onEdit = ::openEdit,
                speechEngines = speechEngines,
                onSpeechRouteChange = ::updateSpeechRoute
            )
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        if (characterId <= 0L) {
            toastOnUi(R.string.toast_character_missing)
            finish()
            return
        }
        lifecycleScope.launch {
            val data = withContext(IO) {
                appDb.bookCharacterDao.getCharacter(characterId) to
                    SpeechVoiceCatalogRepository
                        .allGroups(applicationContext, appDb.httpTTSDao.all)
                        .map { CharacterSpeechEngineUi(it) }
            }
            val item = data.first
            if (item == null) {
                toastOnUi(R.string.toast_character_missing)
                finish()
                return@launch
            }
            character = item
            speechEngines = data.second
        }
    }

    private fun updateSpeechRoute(routeJson: String) {
        val item = character ?: return
        lifecycleScope.launch {
            val updated = item.copy(
                speechRouteJson = routeJson.trim(),
                updatedAt = System.currentTimeMillis()
            )
            withContext(IO) {
                appDb.bookCharacterDao.updateCharacter(updated)
            }
            ReadAloudConfigChangeNotifier.notifySpeech()
            character = updated
            toastOnUi(R.string.toast_character_voice_updated)
        }
    }

    private fun openEdit() {
        val item = character ?: return
        startActivity<BookCharacterEditActivity> {
            putExtra(BookCharacterManageActivity.EXTRA_BOOK_URL, bookUrl.ifBlank { item.bookUrl })
            putExtra(BookCharacterManageActivity.EXTRA_CHARACTER_BOOK_KEY, characterBookKey.ifBlank { item.bookUrl })
            putExtra(BookCharacterManageActivity.EXTRA_CHARACTER_ID, item.id)
        }
    }
}
