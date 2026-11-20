package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sleep timer and "Still watching?" (pure Kotlin, no Android). */
class SleepRuleTest {
    private val min = 60_000L

    @Test
    fun `asks after 45 minutes of playback without activity`() {
        assertFalse(SleepRule.shouldAsk(now = 44 * min, lastActivityAt = 0, minutes = 45, playing = true, asking = false))
        assertTrue(SleepRule.shouldAsk(now = 45 * min, lastActivityAt = 0, minutes = 45, playing = true, asking = false))
        assertFalse(SleepRule.shouldAsk(now = 90 * min, lastActivityAt = 0, minutes = 45, playing = false, asking = false))
        assertFalse(SleepRule.shouldAsk(now = 90 * min, lastActivityAt = 0, minutes = 0, playing = true, asking = false))
        assertFalse(SleepRule.shouldAsk(now = 90 * min, lastActivityAt = 0, minutes = 45, playing = true, asking = true))
        assertFalse(SleepRule.shouldAsk(now = 90 * min, lastActivityAt = 50 * min, minutes = 45, playing = true, asking = false))
    }

    @Test
    fun `pauses a minute after asking, unless the app was touched since`() {
        val asked = 45 * min
        val deadline = asked + SleepRule.ANSWER_MS
        assertFalse(SleepRule.pauseUnanswered(deadline - 1, deadline, asked, lastActivityAt = 0))
        assertTrue(SleepRule.pauseUnanswered(deadline, deadline, asked, lastActivityAt = 0))
        assertFalse(SleepRule.pauseUnanswered(deadline, deadline, asked, lastActivityAt = asked + 5_000))
        assertFalse(SleepRule.pauseUnanswered(deadline, 0, asked, lastActivityAt = 0))
    }

    @Test
    fun `minutes left round up`() {
        assertEquals(30, SleepRule.minutesLeft(0, 30 * min))
        assertEquals(30, SleepRule.minutesLeft(1, 30 * min))
        assertEquals(1, SleepRule.minutesLeft(30 * min - 1, 30 * min))
        assertEquals(1, SleepRule.minutesLeft(31 * min, 30 * min))
    }

    @Test
    fun `end of video timer`() {
        assertFalse(SleepRule.endReached("a", "a", ended = false))
        assertTrue(SleepRule.endReached("a", "a", ended = true))
        assertTrue(SleepRule.endReached("a", "b", ended = false))
        assertFalse(SleepRule.endReached("", "b", ended = true))
        assertFalse(SleepRule.endReached("a", "", ended = false))
    }
}
