package com.mavislibrary.app.ui.kids

import android.os.Bundle
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mavislibrary.app.audio.*
import com.mavislibrary.app.data.kids.StoryPacks

@Composable
fun KidsStoryPlayer(state: KidsStoryState, controller: KidsStoryController, modifier: Modifier = Modifier, onShelf: () -> Unit) {
    val page = state.pages.getOrNull(state.page)
    var answer by remember(state.story?.id) { mutableStateOf<Int?>(null) }
    var drag by remember { mutableFloatStateOf(0f) }
    val rounded = remember { FontFamily(android.graphics.Typeface.create("sans-serif-rounded", android.graphics.Typeface.NORMAL)) }
    Column(modifier.verticalScroll(rememberScrollState()).pointerInput(state.page) {
        detectHorizontalDragGestures(onDragStart = { drag = 0f }, onHorizontalDrag = { change, amount ->
            change.consume(); drag += amount
        }, onDragEnd = {
            if (drag < -80) controller.command(KidsStoryService.NEXT)
            if (drag > 80 && state.page > 0) controller.command(KidsStoryService.PREVIOUS)
        })
    }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.loading || state.story == null && state.error == null) CircularProgressIndicator(Modifier.padding(32.dp))
        state.error?.let { KidsText(it) }
        if (state.ended) {
            Text("The End", fontSize = 40.sp)
            val question = state.story?.question?.get("t3")?.takeIf { state.tier == "t3" }
            if (question != null) {
                KidsText(question.q)
                question.choices.forEachIndexed { index, choice ->
                    Button(onClick = { answer = index }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { KidsText(choice) }
                }
                answer?.let { KidsText(if (it == question.answer) "That's right! Well done." else "Good thinking! Have another try.") }
            }
            Button(onClick = onShelf, modifier = Modifier.heightIn(min = 72.dp)) { KidsText("Back to story shelf") }
        } else if (page != null && state.directory != null) {
            AsyncImage(StoryPacks.file(state.directory, page.img), "Story picture, page ${state.page + 1}",
                Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 440.dp).aspectRatio(4f / 3f), contentScale = ContentScale.Fit)
            Text(page.text[state.tier].orEmpty(), fontSize = 26.sp, lineHeight = 36.sp,
                fontFamily = rounded, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
            Text("Page ${state.page + 1} of ${state.pages.size}", fontSize = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { controller.command(KidsStoryService.PREVIOUS) }, enabled = state.page > 0,
                    modifier = Modifier.weight(1f).heightIn(min = 72.dp), contentPadding = PaddingValues(8.dp)) { Text("Previous") }
                Button(onClick = { controller.command(KidsStoryService.TOGGLE) }, modifier = Modifier.weight(1f).heightIn(min = 80.dp),
                    contentPadding = PaddingValues(8.dp)) { KidsText(if (state.playing) "Pause" else "Play") }
                OutlinedButton(onClick = { controller.command(KidsStoryService.NEXT) }, modifier = Modifier.weight(1f).heightIn(min = 72.dp),
                    contentPadding = PaddingValues(8.dp)) { Text("Next") }
            }
            KidsText("Sleep timer")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 10, 20).forEach { minutes ->
                    FilterChip(state.sleepMinutes == minutes, onClick = {
                        controller.command(KidsStoryService.SLEEP, Bundle().apply { putInt("minutes", minutes) })
                    }, label = { Text(if (minutes == 0) "Story end" else "$minutes min") }, modifier = Modifier.heightIn(min = 56.dp))
                }
            }
        } else if (state.error != null) {
            Button(onClick = onShelf) { KidsText("Back to story shelf") }
        }
    }
}
