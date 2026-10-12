package com.mavislibrary.app.data.stats

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
import org.json.JSONArray
import org.json.JSONObject

private val Context.adultStatsStore by preferencesDataStore("adult_reading_stats")
fun Context.statsRepository() = StatsRepository(applicationContext.adultStatsStore)
data class ReadingDay(val milliseconds: Long = 0, val books: Set<String> = emptySet()) {
    val minutes get() = milliseconds / 60000
}
data class WeeklyRecap(val minutes: Long, val daysRead: Int, val booksTouched: Int)
data class ReadingStats(val days: Map<LocalDate, ReadingDay> = emptyMap()) {
    fun week(today: LocalDate) = (6 downTo 0).map { today.minusDays(it.toLong()) }
    fun currentStreak(today: LocalDate): Int {
        var date = if (read(today)) today else today.minusDays(1)
        var count = 0
        while (read(date)) { count++; date = date.minusDays(1) }
        return count
    }
    private fun read(date: LocalDate) = (days[date]?.milliseconds ?: 0) >= 60000
    fun longestStreak(today: LocalDate): Int {
        var best = 0; var run = 0; var previous: LocalDate? = null
        days.keys.filter { it <= today && read(it) }.sorted().forEach { date ->
            run = if (previous?.plusDays(1) == date) run + 1 else 1
            best = maxOf(best, run); previous = date
        }
        return best
    }
    /** Calendar week, Monday through today. The chart separately shows a rolling seven days. */
    fun recap(today: LocalDate): WeeklyRecap {
        val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val rows = days.filterKeys { it in start..today }.values
        return WeeklyRecap(rows.sumOf { it.milliseconds } / 60000, rows.count { it.milliseconds >= 60000 }, rows.flatMap { it.books }.toSet().size)
    }
}
fun goalProgress(minutes: Long, goal: Int): Float = if (goal <= 0) 0f else (minutes.toFloat() / goal).coerceIn(0f, 1f)

class StatsRepository(private val store: DataStore<Preferences>) {
    private val goalKey = intPreferencesKey("weekly_goal_minutes")
    val weeklyGoal = store.data.map { it[goalKey] ?: 150 }
    suspend fun setWeeklyGoal(minutes: Int) { require(minutes in 1..10080); store.edit { it[goalKey] = minutes } }
    private val log = stringPreferencesKey("adult_days")
    val state = store.data.map { decode(it[log]) }
    suspend fun addTime(durations: Map<LocalDate, Long>, books: Set<String>) {
        store.edit { p ->
            val days = decode(p[log]).days.toMutableMap()
            durations.filterValues { it > 0 }.forEach { (date, ms) ->
                val old = days[date] ?: ReadingDay()
                days[date] = ReadingDay(old.milliseconds + ms, old.books + books)
            }
            val json = JSONObject()
            days.toSortedMap().forEach { (date, day) -> json.put(date.toString(), JSONObject().put("ms", day.milliseconds).put("books", JSONArray(day.books.toList()))) }
            p[log] = json.toString()
        }
    }
    private fun decode(raw: String?): ReadingStats {
        val json = JSONObject(raw ?: "{}")
        return ReadingStats(json.keys().asSequence().associate { key ->
            val day = json.getJSONObject(key); val books = day.getJSONArray("books")
            LocalDate.parse(key) to ReadingDay(day.getLong("ms"), (0 until books.length()).map { books.getString(it) }.toSet())
        })
    }
}
