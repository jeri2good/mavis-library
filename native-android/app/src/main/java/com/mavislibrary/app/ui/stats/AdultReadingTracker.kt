package com.mavislibrary.app.ui.stats

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mavislibrary.app.AppContainer
import com.mavislibrary.app.data.stats.StatsAudioSource
import androidx.compose.ui.platform.LocalContext
import com.mavislibrary.app.data.kids.ReadingAccumulator
import com.mavislibrary.app.data.stats.StatsRepository
import com.mavislibrary.app.web.WebHost
import com.mavislibrary.app.web.isAppOrigin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URI
import java.net.URLDecoder

internal fun readerIdentity(url: String?): String? = runCatching {
    if (!isAppOrigin(url)) return null
    val hash = URI(url!!).rawFragment.orEmpty()
    when {
        hash.startsWith("/read/") -> "book:" + URLDecoder.decode(hash.removePrefix("/read/").substringBefore('?'), "UTF-8")
        hash.startsWith("/bible/") -> "bible:" + hash.removePrefix("/bible/").substringBefore('/')
        else -> null
    }
}.getOrNull()

/** Same accumulator/30s persistence cadence as Kids, with separate adult storage. */
@Composable
fun AdultReadingTracker(container: AppContainer, repository: StatsRepository, host: WebHost?, webVisible: Boolean) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val accumulator = remember { ReadingAccumulator() }
    val context = LocalContext.current
    var previousBooks by remember { mutableStateOf(emptySet<String>()) }
    var audioSource by remember { mutableStateOf<StatsAudioSource?>(null) }
    var ticks by remember { mutableIntStateOf(0) }
    val visible by rememberUpdatedState(webVisible)
    fun sample(stop: Boolean = false, flush: Boolean = true) {
        val audio = audioSource
        val reader = if (visible && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) readerIdentity(host?.webView?.url) else null
        val books = if (stop) emptySet() else buildSet {
            reader?.let { add(it) }
            if (audio?.playing == true) audio.bookKey?.let { add("book:$it") }
        }
        accumulator.update(books.isNotEmpty())
        if (flush || books != previousBooks) {
            val chunk = accumulator.drain()
            val attributed = previousBooks
            if (chunk.isNotEmpty()) container.scope.launch { repository.addTime(chunk, attributed) }
        }
        previousBooks = books
    }
    DisposableEffect(context) {
        val source = StatsAudioSource(context) { sample() }
        audioSource = source
        onDispose { sample(true); source.close(); audioSource = null }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> sample() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); sample(true) }
    }
    LaunchedEffect(webVisible) { sample() }
    LaunchedEffect(Unit) { while (true) { sample(flush = ++ticks % 30 == 0); delay(1000) } }
}
