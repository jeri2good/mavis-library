package com.mavislibrary.app.ui.kids

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mavislibrary.app.AppContainer
import com.mavislibrary.app.audio.PlaybackController
import com.mavislibrary.app.data.kids.*
import com.mavislibrary.app.data.model.Book
import com.mavislibrary.app.data.model.BookSource
import com.mavislibrary.app.ui.common.Cover
import com.mavislibrary.app.ui.theme.Look
import com.mavislibrary.app.ui.theme.MavisTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun KidsText(text: String, modifier: Modifier = Modifier) = Text(text, modifier, fontSize = 20.sp, lineHeight = 26.sp)
@Composable
private fun Heading(text: String) = Text(text, fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold)
@Composable
private fun BigButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, modifier.heightIn(min = 64.dp), enabled = enabled, shape = RoundedCornerShape(28.dp)) { KidsText(text) }
}

@Composable
fun KidsRoot(container: AppContainer, prefs: KidsPrefs, state: KidsState, playback: PlaybackController) {
    var screen by rememberSaveable { mutableStateOf("Home") }
    var route by rememberSaveable { mutableStateOf<String?>(null) }
    var pinOpen by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    var kidsAudio by rememberSaveable { mutableStateOf(false) }
    val audio by playback.state.collectAsState()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var today by remember { mutableStateOf(LocalDate.now()) }
    val accumulator = remember { ReadingAccumulator() }
    val active = (reading && foreground) || (kidsAudio && audio.playing)
    val currentActive by rememberUpdatedState(active)
    fun flush(activeNow: Boolean) {
        accumulator.update(activeNow)
        val chunk = accumulator.drain()
        if (chunk.isNotEmpty()) container.scope.launch { prefs.addTime(chunk) }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { foreground = true; today = LocalDate.now() }
            if (event == Lifecycle.Event.ON_PAUSE) { foreground = false; flush(kidsAudio && playback.state.value.playing) }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); flush(false) }
    }
    LaunchedEffect(active) { flush(active) }
    LaunchedEffect(Unit) { while (true) { delay(1000); today = LocalDate.now(); flush(currentActive) } }
    // Stop an adult queue on entry, including a service that connects after composition.
    LaunchedEffect(audio.connected) { if (audio.connected && !kidsAudio && audio.playing) playback.toggle() }
    val home = { reading = false; route = null; screen = "Home" }
    BackHandler { if (pinOpen) pinOpen = false else home() }
    MavisTheme(Look.KIDS) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (screen != "Home" || route != null) BigButton("Home", onClick = home)
                    else Text("★ Mavis Kids", Modifier.weight(1f), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { pinOpen = true }, modifier = Modifier.size(64.dp)) {
                        Icon(Icons.Outlined.Lock, "Grown-ups corner", Modifier.size(28.dp))
                    }
                }
                if (kidsAudio && audio.title.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        KidsText(audio.title, Modifier.weight(1f))
                        BigButton(if (audio.playing) "Pause" else "Play", enabled = audio.connected) { playback.toggle() }
                    }
                }
                if (route != null) {
                    key(route) { KidsWebReader(route!!, Modifier.weight(1f).fillMaxWidth()) { reading = it } }
                } else when (screen) {
                    "Home" -> BoxWithConstraints(Modifier.weight(1f)) {
                        LazyVerticalGrid(columns = GridCells.Fixed(if (maxWidth >= 568.dp) 2 else 1),
                            verticalArrangement = Arrangement.spacedBy(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            item(span = { GridItemSpan(maxLineSpan) }) { Heading(if (state.name.isBlank()) "Hi, reader!" else "Hi, ${state.name}!") }
                            item(span = { GridItemSpan(maxLineSpan) }) { Stars(state, today, false) }
                            items(listOf("Read", "Listen", "Bible stories", "My stars")) { label ->
                                val symbol = when(label) { "Read" -> "📚"; "Listen" -> "♫"; "Bible stories" -> "☀"; else -> "★" }
                                FilledTonalButton(onClick = { screen = label }, modifier = Modifier.fillMaxWidth().height(156.dp), shape = RoundedCornerShape(32.dp)) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Heading(symbol); Heading(label) }
                                }
                            }
                        }
                    }
                    "My stars" -> LazyVerticalGrid(GridCells.Fixed(1), Modifier.weight(1f)) {
                        item { Heading("My stars") }; item { Stars(state, today, true) }
                    }
                    "Bible stories" -> LazyVerticalGrid(GridCells.Fixed(1), Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { Heading("Bible stories") }
                        items(BIBLE_STORIES) { story -> BigButton(story.title, Modifier.fillMaxWidth()) { route = story.route } }
                    }
                    else -> KidsBooks(container, screen == "Listen", audio.connected, Modifier.weight(1f), onRead = { book ->
                        route = "#/book/" + java.net.URLEncoder.encode(book.key, "UTF-8").replace("+", "%20")
                    }, onPlay = { book, version ->
                        kidsAudio = true
                        scope.launch {
                            val resume = container.resumePositionStore.load(book.key)
                            playback.play(book, version, container.audioFiles, resume)
                        }
                    })
                }
            }
            if (pinOpen) PinDialog(onDismiss = { pinOpen = false }, onCheck = { pin ->
                val result = prefs.leave(pin)
                if (result == PinResult.Correct) {
                    flush(false)
                    if (playback.state.value.playing) playback.toggle()
                }
                result
            })
        }
    }
}

@Composable
private fun Stars(state: KidsState, today: LocalDate, large: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val minutes = state.minutes(today)
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(if (large) 220.dp else 156.dp)
            .semantics { contentDescription = "$minutes of ${state.goal} minutes today" }) {
            CircularProgressIndicator(progress = { (minutes.toFloat() / state.goal).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxSize(), strokeWidth = 12.dp,
                trackColor = MaterialTheme.colorScheme.primaryContainer)
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Heading("$minutes"); KidsText("of ${state.goal} min") }
        }
        KidsText(if (minutes >= state.goal) "You earned today’s star! ★" else "Read or listen to earn a star!")
        KidsText("${state.streak(today)} days in a row")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            state.week(today).forEach { day ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.semantics {
                    contentDescription = "${day.date}: ${day.minutes} minutes" + if (day.star) ", star earned" else ""
                }) {
                    Text(if (day.star) "★" else "☆", fontSize = 28.sp, color = MaterialTheme.colorScheme.primary)
                    Text(day.date.format(DateTimeFormatter.ofPattern("EEEEE")), fontSize = 20.sp)
                }
            }
        }
    }
}

@Composable
private fun KidsBooks(container: AppContainer, listen: Boolean, connected: Boolean, modifier: Modifier,
                      onRead: (Book) -> Unit, onPlay: (Book, com.mavislibrary.app.data.api.LibriVoxVersion) -> Unit) {
    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var retry by remember { mutableIntStateOf(0) }
    var looking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(retry) {
        loading = true; message = null
        try {
            books = container.searchRepository.booksByIds(KIDS_SHELF).value.filter { it.source == BookSource.GUTENBERG && it.sourceId.toIntOrNull() in KIDS_SHELF }
            if (books.isEmpty()) message = "No stories here yet. Try again soon."
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = "We couldn’t get the stories. Connect to the internet and try again." }
        finally { loading = false }
    }
    BoxWithConstraints(modifier) {
        val columns = if (maxWidth < 568.dp) 1 else (maxWidth.value / 180).toInt().coerceIn(3,5)
        LazyVerticalGrid(GridCells.Fixed(columns), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) { Heading(if (listen) "Listen" else "Read") }
            if (loading || looking) item(span = { GridItemSpan(maxLineSpan) }) { KidsText(if (loading) "Finding stories…" else "Finding a recording…") }
            message?.let { text -> item(span = { GridItemSpan(maxLineSpan) }) {
                Column { KidsText(text); if (books.isEmpty()) BigButton("Try again") { retry++ } }
            } }
            items(books, key = { it.key }) { book ->
                Card(onClick = {
                    if (!listen) onRead(book)
                    else if (!connected) message = "The player is getting ready. Try again in a moment."
                    else if (!looking) scope.launch {
                        looking = true; message = null
                        try {
                            val version = container.audiobookRepository.find(book.title, book.authors.firstOrNull().orEmpty(), book.sourceId.toIntOrNull())
                                .firstOrNull { it.sections.isNotEmpty() }
                            if (version == null) message = "No recording for this one yet" else onPlay(book, version)
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { message = "We couldn’t get the recording. Try again when you’re online." }
                        finally { looking = false }
                    }
                }, enabled = !looking, shape = RoundedCornerShape(28.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Cover(book, Modifier.fillMaxWidth().height(220.dp))
                        KidsText(book.title)
                    }
                }
            }
        }
    }
}

@Composable
private fun PinDialog(onDismiss: () -> Unit, onCheck: suspend (String) -> PinResult) {
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("Enter your 4-digit PIN.") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onDismiss, title = { Heading("Grown-ups") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            KidsText(message); Heading("●".repeat(pin.length).padEnd(4, '○'))
            listOf(listOf("1","2","3"), listOf("4","5","6"), listOf("7","8","9"), listOf("Clear","0","Go")).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { digit ->
                        Button(onClick = {
                            when (digit) {
                                "Clear" -> pin = ""
                                "Go" -> { busy = true; scope.launch {
                                    try {
                                        when (val r = onCheck(pin)) {
                                            PinResult.Correct -> onDismiss()
                                            PinResult.Wrong -> message = "That PIN isn’t right. Try again."
                                            is PinResult.Locked -> message = "Wait ${r.seconds} seconds, then try again."
                                        }
                                    } catch (_: Exception) { message = "Couldn’t check the PIN. Try again." }
                                    finally { pin = ""; busy = false }
                                } }
                                else -> if (pin.length < 4) pin += digit
                            }
                        }, enabled = !busy && (digit != "Go" || pin.length == 4), modifier = Modifier.weight(1f).heightIn(min = 64.dp), contentPadding = PaddingValues(2.dp)) {
                            Text(digit, fontSize = 20.sp)
                        }
                    }
                }
            }
        }
    }, confirmButton = { BigButton("Back", onClick = onDismiss) })
}
