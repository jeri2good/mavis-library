package com.mavislibrary.app.data.podsub

import org.junit.Assert.*
import org.junit.Test

class DevotionalValidationTest {
    @Test fun defaultsAndIndependentSchedule() {
        val doc = SubscriptionDocument().validated()
        assertEquals("19:00", doc.devotionalSchedule.readyBy)
        assertEquals(subscriptionDays, doc.devotionalSchedule.days)
        assertFalse(doc.devotionalSchedule.enabled)
        assertTrue(runCatching { doc.copy(devotionalSchedule = doc.devotionalSchedule.copy(enabled = true, days = emptyList())).validated() }.isFailure)
        assertTrue(runCatching { doc.copy(devotionalSchedule = doc.devotionalSchedule.copy(readyBy = "24:00")).validated() }.isFailure)
    }
    @Test fun devotionalRulesMirrorServer() {
        val d = DevotionalRule("d", "topic", "Trust", topic = "trust")
        val doc = SubscriptionDocument(devotionals = listOf(d)).validated()
        listOf(d.copy(topic = ""), d.copy(topic = "x".repeat(121)), d.copy(kind = "bad"), d.copy(id = "../d"),
            d.copy(kind = "site", url = "http://example.com"), d.copy(kind = "site", url = "https://127.0.0.1"), d.copy(name = "")).forEach {
            assertTrue(runCatching { doc.copy(devotionals = listOf(it)).validated() }.isFailure)
        }
        assertTrue(runCatching { doc.copy(devotionals = listOf(d, d)).validated() }.isFailure)
        assertTrue(runCatching { doc.copy(devotionals = (1..6).map { d.copy(id = "$it") }).validated() }.isFailure)
        doc.copy(devotionals = listOf(d.copy(topic = "", surprise = true))).validated()
        doc.copy(devotionals = listOf(d.copy(kind = "site", url = "https://todaydevotional.com/daily-devotional-library"))).validated()
    }
}
