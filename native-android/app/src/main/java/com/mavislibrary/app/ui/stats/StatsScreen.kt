package com.mavislibrary.app.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mavislibrary.app.data.stats.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun StatsScreen(repository: StatsRepository, onBack: () -> Unit) {
    val stats by repository.state.collectAsState(initial = ReadingStats())
    val goal by repository.weeklyGoal.collectAsState(initial = 150)
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30000); today = LocalDate.now() } }
    val recap = stats.recap(today)
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("Reading stats", style = MaterialTheme.typography.headlineMedium)
        Text("Private, on this device. Reading and listening count together. A reading day is at least one minute.")
        Text("Today: ${stats.days[today]?.minutes ?: 0} minutes", style = MaterialTheme.typography.titleLarge)
        Text("Current streak: ${stats.currentStreak(today)} days")
        Text("Longest streak: ${stats.longestStreak(today)} days")
        Text("Last 7 days", style = MaterialTheme.typography.titleMedium)
        val dates = stats.week(today)
        val values = dates.map { stats.days[it]?.minutes ?: 0 }
        val labels = dates.zip(values).joinToString("; ") { (date, minutes) -> "$date: $minutes minutes" }
        val color = MaterialTheme.colorScheme.primary
        Canvas(Modifier.fillMaxWidth().height(140.dp).semantics { contentDescription = labels }) {
            val slot = size.width / 7
            val max = values.maxOrNull()?.coerceAtLeast(1) ?: 1
            values.forEachIndexed { index, minutes ->
                val height = size.height * minutes.toFloat() / max
                drawRect(color, Offset(index * slot + slot * .15f, size.height - height), Size(slot * .7f, height))
            }
        }
        Text(dates.zip(values).joinToString(" · ") { (date, minutes) -> "${date.dayOfWeek.name.take(3)} $minutes" }, style = MaterialTheme.typography.bodySmall)
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This week · Monday–today", style = MaterialTheme.typography.titleLarge)
                Text("${recap.minutes} minutes · ${recap.daysRead} days read · ${recap.booksTouched} books touched")
                Text("Weekly goal: $goal minutes")
                LinearProgressIndicator(progress = { goalProgress(recap.minutes, goal) }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${recap.minutes} of $goal minutes" })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(60, 150, 300).forEach { value ->
                        FilterChip(selected = goal == value, onClick = {
                            scope.launch {
                                try { repository.setWeeklyGoal(value); message = null }
                                catch (_: Exception) { message = "Couldn't save your weekly goal. Please try again." }
                            }
                        }, label = { Text("$value min") })
                    }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        Text("Tracking starts with this version. Background audio is counted while the app process and its shell remain alive.", style = MaterialTheme.typography.bodySmall)
    }
}
