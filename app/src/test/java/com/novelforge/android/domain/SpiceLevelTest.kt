package com.novelforge.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpiceLevelTest {

    @Test
    fun validRangeIsOneToFive() {
        assertFalse(SpiceLevel.isValid(0))
        assertTrue(SpiceLevel.isValid(1))
        assertTrue(SpiceLevel.isValid(5))
        assertFalse(SpiceLevel.isValid(6))
    }

    @Test
    fun labelsCoverAllLevels() {
        assertEquals("Dezent", SpiceLevel.label(1))
        assertEquals("Sehr explizit", SpiceLevel.label(5))
        assertEquals("Nicht angegeben", SpiceLevel.label(0))
    }

    @Test
    fun directiveOnlyForValidLevels() {
        assertTrue(SpiceLevel.generationDirective(0).isEmpty())
        assertTrue(SpiceLevel.generationDirective(4).contains("SINNLICHKEITSGRAD"))
        assertTrue(SpiceLevel.generationDirective(4).contains("erwachsen"))
    }

    @Test
    fun kdpGuidanceIsEmptyForUnset() {
        assertTrue(SpiceLevel.kdpGuidance(0).isEmpty())
        assertTrue(SpiceLevel.kdpGuidance(5).contains("5/5"))
    }
}
