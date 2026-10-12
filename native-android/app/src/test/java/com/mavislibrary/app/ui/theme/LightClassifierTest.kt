package com.mavislibrary.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class LightClassifierTest {
    @Test fun boundariesAndInitialClassification() {
        assertEquals(LightLevel.DARK, LightClassifier().classify(7.99f))
        assertEquals(LightLevel.EVENING, LightClassifier().classify(8f))
        assertEquals(LightLevel.EVENING, LightClassifier().classify(120f))
        assertEquals(LightLevel.DAY, LightClassifier().classify(120.01f))
    }

    @Test fun hysteresisAndDwellDoNotFlap() {
        var now = 0L
        val classifier = LightClassifier { now }
        assertEquals(LightLevel.DARK, classifier.classify(0f))
        now = 6_000
        assertEquals(LightLevel.DARK, classifier.classify(20f))
        assertEquals(LightLevel.DARK, classifier.classify(21f))
        now = 10_999
        assertEquals(LightLevel.DARK, classifier.classify(21f))
        now = 11_000
        assertEquals(LightLevel.EVENING, classifier.classify(21f))
        assertEquals(LightLevel.EVENING, classifier.classify(121f))
        now = 16_000
        assertEquals(LightLevel.DAY, classifier.classify(121f))
        now = 22_000
        assertEquals(LightLevel.DAY, classifier.classify(110f))
        assertEquals(LightLevel.DAY, classifier.classify(99f))
        now = 23_000
        assertEquals(LightLevel.DAY, classifier.classify(110f))
        now = 29_000
        assertEquals(LightLevel.DAY, classifier.classify(99f))
    }

    @Test fun missingSensorAndKidsKeepTheirLooks() {
        assertEquals(Look.EDITORIAL, lightLook(Look.EDITORIAL, false, true, false, LightLevel.DARK))
        assertEquals(Look.EDITORIAL, lightLook(Look.EDITORIAL, false, false, true, LightLevel.DARK))
        assertEquals(Look.EDITORIAL, lightLook(Look.EDITORIAL, false, true, true, null))
        assertEquals(Look.EDITORIAL, lightLook(Look.EDITORIAL, false, true, true, LightLevel.DAY))
        assertEquals(Look.MIDNIGHT, lightLook(Look.EDITORIAL, false, true, true, LightLevel.EVENING))
        assertEquals(Look.KIDS, lightLook(Look.PAPER, true, true, true, LightLevel.DARK))
    }
}
