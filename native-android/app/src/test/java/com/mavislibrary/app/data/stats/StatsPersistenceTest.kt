package com.mavislibrary.app.data.stats

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StatsPersistenceTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun durationsAndDistinctBookKeysPersistAcrossRepositoryWrappers() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { java.io.File(tmp.root, "stats.preferences_pb") }
        val repository = StatsRepository(store)
        val day = LocalDate.of(2026,10,11)
        repository.addTime(mapOf(day to 45000L), setOf("book:a"))
        repository.addTime(mapOf(day to 45000L), setOf("book:a", "book:b"))
        repository.setWeeklyGoal(300)
        val reopened = StatsRepository(store)
        assertEquals(ReadingDay(90000, setOf("book:a", "book:b")), reopened.state.first().days[day])
        assertEquals(300, reopened.weeklyGoal.first())
        assertTrue(runCatching { reopened.setWeeklyGoal(0) }.isFailure)
    }
}
