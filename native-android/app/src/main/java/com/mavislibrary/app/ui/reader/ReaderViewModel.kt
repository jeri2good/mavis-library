package com.mavislibrary.app.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mavislibrary.app.data.podcast.PodcastDraft
import com.mavislibrary.app.data.reader.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Reader was asked to open. [siteId]/[storyId] are set when it came from a followed site, so it can be marked read. */
data class ReaderTarget(val title: String, val url: String, val siteName: String = "", val siteId: String? = null, val storyId: String? = null) {
    val draft get() = PodcastDraft.article(title, url)
}

sealed interface ReaderContent {
    data object Loading : ReaderContent
    data class Ready(val article: ReaderArticle) : ReaderContent
    /** The native reader couldn't extract this page (422 or network); the WebView shows the original instead. */
    data object Original : ReaderContent
}

data class ReaderUiState(val content: ReaderContent = ReaderContent.Loading, val settings: ReaderSettings = ReaderSettings())

class ReaderViewModel(
    val target: ReaderTarget,
    private val api: ReaderService?,
    private val prefs: ReaderPrefs,
    private val readState: ReadStateStore,
) : ViewModel() {
    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { prefs.settings.collect { s -> _state.update { it.copy(settings = s) } } }
        viewModelScope.launch {
            if (target.siteId != null && target.storyId != null) runCatching { readState.markRead(target.siteId, listOf(target.storyId)) }
            val content = try {
                ReaderContent.Ready(checkNotNull(api) { "Reader unavailable" }.article(target.url))
            } catch (e: CancellationException) { throw e } catch (_: Exception) { ReaderContent.Original }
            _state.update { it.copy(content = content) }
        }
    }

    fun setSize(size: ReaderTextSize) { viewModelScope.launch { prefs.setSize(size) } }
    fun setTheme(theme: ReaderTheme) { viewModelScope.launch { prefs.setTheme(theme) } }
}
