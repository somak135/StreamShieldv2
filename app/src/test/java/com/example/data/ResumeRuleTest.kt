package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where an opened video continues (pure Kotlin, no Android). */
class ResumeRuleTest {

    @Test
    fun `a partly watched video continues where it stopped`() {
        assertEquals(463, ResumeRule.resumeAt(463, 1200, 0.39f, isMusic = false))
    }

    @Test
    fun `from the start when barely started, nearly finished, music or live`() {
        assertEquals(0, ResumeRule.resumeAt(8, 1200, 0.01f, isMusic = false))
        assertEquals(0, ResumeRule.resumeAt(1090, 1200, 0.91f, isMusic = false))
        assertEquals(0, ResumeRule.resumeAt(195, 200, 0.5f, isMusic = false))   // within 10 s of the end
        assertEquals(0, ResumeRule.resumeAt(120, 240, 0.5f, isMusic = true))
        assertEquals(0, ResumeRule.resumeAt(600, 0, 0f, isMusic = false))       // live: no length
    }

    @Test
    fun `links`() {
        assertTrue(ResumeRule.isWatchLink("https://m.youtube.com/watch?v=abc123def45"))
        assertTrue(ResumeRule.isWatchLink("https://www.youtube.com/watch?v=abc123def45&list=PL1"))
        assertFalse(ResumeRule.isWatchLink("https://m.youtube.com/shorts/abc123def45"))
        assertFalse(ResumeRule.isWatchLink("https://music.youtube.com/watch?v=abc123def45"))
        assertFalse(ResumeRule.isWatchLink("https://m.youtube.com/results?search_query=watch"))
        assertTrue(ResumeRule.hasStartTime("https://m.youtube.com/watch?v=abc123def45&t=83s"))
        assertTrue(ResumeRule.hasStartTime("https://www.youtube.com/watch?start=5&v=abc123def45"))
        assertFalse(ResumeRule.hasStartTime("https://m.youtube.com/watch?v=abc123def45&list=PLt=1"))
        assertEquals("https://m.youtube.com/watch?v=abc123def45&t=463s", ResumeRule.withStart("https://m.youtube.com/watch?v=abc123def45", 463))
        assertEquals("https://m.youtube.com/watch?v=x&t=5s#c", ResumeRule.withStart("https://m.youtube.com/watch?v=x#c", 5))
    }
}
