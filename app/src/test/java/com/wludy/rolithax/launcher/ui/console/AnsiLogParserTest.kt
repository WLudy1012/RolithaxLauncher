package com.wludy.rolithax.launcher.ui.console

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnsiLogParserTest {
    private val esc = "\u001B"

    @Test
    fun plainTextIsSingleSegment() {
        val segments = parseAnsiLog("hello")
        assertEquals(listOf(AnsiSegment("hello")), segments)
    }

    @Test
    fun basicBrightBoldAndReset() {
        val segments = parseAnsiLog("$esc[31ma$esc[1;92mb$esc[0mc")
        assertEquals(3, segments.size)
        assertEquals(Color(0xFFEF5350), segments[0].color)
        assertFalse(segments[0].bold)
        assertEquals(Color(0xFFB9F6CA), segments[1].color)
        assertTrue(segments[1].bold)
        assertNull(segments[2].color)
        assertFalse(segments[2].bold)
    }

    @Test
    fun emptySgrResets() {
        val segments = parseAnsiLog("$esc[31ma$esc[mb")
        assertNull(segments[1].color)
    }

    @Test
    fun color256() {
        val segments = parseAnsiLog("$esc[38;5;196mx$esc[38;5;244my$esc[38;5;1mz")
        assertEquals(Color(255, 0, 0), segments[0].color)
        assertEquals(Color(128, 128, 128), segments[1].color)
        assertEquals(Color(0xFFEF5350), segments[2].color)
    }

    @Test
    fun trueColorForegroundAndBackground() {
        val segments = parseAnsiLog("$esc[38;2;255;80;80;48;2;1;2;3mx")
        assertEquals(Color(255, 80, 80), segments[0].color)
        assertEquals(Color(1, 2, 3), segments[0].background)
    }

    @Test
    fun colonSeparatedExtendedColor() {
        val segments = parseAnsiLog("$esc[38:2::10:20:30mx$esc[48:5:21my")
        assertEquals(Color(10, 20, 30), segments[0].color)
        assertEquals(Color(0, 0, 255), segments[1].background)
    }

    @Test
    fun backgroundColors() {
        val segments = parseAnsiLog("$esc[41merr$esc[49mok$esc[103mhi")
        assertEquals(Color(0xFFEF5350), segments[0].background)
        assertNull(segments[1].background)
        assertEquals(Color(0xFFFFFF59), segments[2].background)
    }

    @Test
    fun nonSgrSequencesAreDropped() {
        val text = parseAnsiLog("a$esc[2Kb$esc[Hc$esc[1Gd$esc[?25le$esc[?25hf").joinToString("") { it.text }
        assertEquals("abcdef", text)
    }

    @Test
    fun oscAndShortSequencesAreDropped() {
        val text = parseAnsiLog("a$esc]0;title\u0007b$esc]8;;url$esc\\c${esc}7d${esc}(Be").joinToString("") { it.text }
        assertEquals("abcde", text)
    }

    @Test
    fun stateCarriesAcrossAppends() {
        val parser = AnsiStreamParser()
        val first = parser.append("${esc}[31mred")
        val second = parser.append("more$esc[0mplain")
        assertEquals(Color(0xFFEF5350), first[0].color)
        assertEquals(Color(0xFFEF5350), second[0].color)
        assertNull(second[1].color)
    }

    @Test
    fun incompleteSequenceAfterCompleteOneIsBuffered() {
        val parser = AnsiStreamParser()
        val first = parser.append("text${esc}[31mred${esc}[3")
        val second = parser.append("1mmore")
        assertEquals(listOf("text", "red"), first.map { it.text })
        assertEquals(listOf("more"), second.map { it.text })
        assertEquals(Color(0xFFEF5350), second[0].color)
    }

    @Test
    fun loneEscapeAtEndIsBuffered() {
        val parser = AnsiStreamParser()
        assertEquals(listOf("a"), parser.append("a$esc").map { it.text })
        val second = parser.append("[32mb")
        assertEquals("b", second.single().text)
        assertEquals(Color(0xFF66BB6A), second.single().color)
    }

    @Test
    fun resetDropsPendingAndStyle() {
        val parser = AnsiStreamParser()
        parser.append("${esc}[31mx${esc}[3")
        parser.reset()
        val segments = parser.append("1mtext")
        assertEquals("1mtext", segments.single().text)
        assertNull(segments.single().color)
    }

    @Test
    fun newlinesArePreserved() {
        val segments = AnsiStreamParser().append("a\nb")
        assertEquals("a\nb", segments.joinToString("") { it.text })
    }
}
