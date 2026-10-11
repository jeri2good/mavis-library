package com.mavislibrary.app.ui.theme

import java.time.LocalTime

/** The three selectable adult looks, plus the Kids mode theme. */
enum class Look(val label: String, val blurb: String) {
    PAPER("Paper", "Warm cream pages and serif type. A classic library."),
    MIDNIGHT("Midnight", "Deep, calm dark with gold accents. Easy on evening eyes."),
    EDITORIAL("Editorial", "White space, bold type and big cover tiles."),
    KIDS("Kids", "Bright colours, big round buttons. A happy place for stories.");

    companion object {
        val selectable = listOf(PAPER, MIDNIGHT, EDITORIAL)

        fun fromStored(value: String?): Look? = entries.firstOrNull { it.name == value && it != KIDS }
    }
}

/** What the user chose in Settings: a fixed look, or automatic (Paper by day, Midnight in the evening). */
sealed interface LookChoice {
    data object Automatic : LookChoice
    data class Fixed(val look: Look) : LookChoice

    fun resolve(now: LocalTime = LocalTime.now()): Look = when (this) {
        is Fixed -> look
        Automatic -> if (isEvening(now)) Look.MIDNIGHT else Look.PAPER
    }

    fun storedValue(): String = when (this) {
        Automatic -> AUTO
        is Fixed -> look.name
    }

    companion object {
        const val AUTO = "AUTO"

        fun parse(value: String?): LookChoice = Look.fromStored(value)?.let(::Fixed) ?: Automatic

        /** Evening runs from 19:00 to 06:59. */
        fun isEvening(t: LocalTime): Boolean = t.hour >= 19 || t.hour < 7
    }
}
