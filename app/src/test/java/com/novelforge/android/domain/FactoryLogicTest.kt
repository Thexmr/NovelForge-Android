package com.novelforge.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FactoryLogicTest {

    private val now = 1_000_000_000_000L // fester Bezugszeitpunkt
    private val day = FactoryLogic.DAY

    private fun records(count: Int, agoMs: Long) =
        (1..count).map { UploadRecord("p$it", now - agoMs) }

    @Test
    fun freeSlotsFullWhenNoHistory() {
        val f = FactoryLogic()
        assertEquals(3, f.freeSlots(emptyList(), now))
    }

    @Test
    fun dayLimitBlocksAfterThreeToday() {
        val f = FactoryLogic()
        val h = records(3, 2 * 3600_000L) // 3 Uploads vor 2h
        assertEquals(0, f.slotsToday(h, now))
        assertNotNull(f.throttleReason(h, now))
        assertTrue(f.throttleReason(h, now)!!.contains("Tageslimit"))
    }

    @Test
    fun uploadsOlderThanWindowDoNotCount() {
        val f = FactoryLogic()
        val h = records(3, 25 * 3600_000L) // vor 25h → außerhalb des 24h-Fensters
        assertEquals(3, f.slotsToday(h, now))
        assertEquals(0, f.usedToday(h, now))
    }

    @Test
    fun weekLimitBlocksAtTen() {
        val f = FactoryLogic()
        val h = records(10, 2 * day) // 10 Uploads vor 2 Tagen (innerhalb 7d, außerhalb 24h)
        assertEquals(3, f.slotsToday(h, now))
        assertEquals(0, f.slotsThisWeek(h, now))
        assertTrue(f.throttleReason(h, now)!!.contains("Wochenlimit"))
    }

    @Test
    fun calendarInactiveNeverBlocks() {
        val f = FactoryLogic(schedule = FactorySchedule(active = false))
        assertNull(f.scheduleReason(emptyList(), now, weekdayMon1 = 7, hour = 3))
    }

    @Test
    fun calendarBlocksWrongWeekday() {
        val f = FactoryLogic(schedule = FactorySchedule(active = true, weekdays = setOf(1, 2, 3, 4, 5)))
        // Sonntag (7) ist nicht erlaubt.
        val r = f.scheduleReason(emptyList(), now, weekdayMon1 = 7, hour = 12)
        assertNotNull(r)
        assertTrue(r!!.contains("kein Upload-Tag"))
    }

    @Test
    fun calendarBlocksOutsideHours() {
        val f = FactoryLogic(schedule = FactorySchedule(active = true, weekdays = setOf(3), startHour = 9, endHour = 21))
        val r = f.scheduleReason(emptyList(), now, weekdayMon1 = 3, hour = 22)
        assertNotNull(r)
        assertTrue(r!!.contains("Zeitfensters"))
    }

    @Test
    fun calendarBlocksMinInterval() {
        val f = FactoryLogic(schedule = FactorySchedule(active = true, weekdays = setOf(3), startHour = 0, endHour = 24, minHoursBetween = 4))
        val h = listOf(UploadRecord("p", now - 1 * 3600_000L)) // letzter Upload vor 1h, Abstand 4h
        val r = f.scheduleReason(h, now, weekdayMon1 = 3, hour = 12)
        assertNotNull(r)
        assertTrue(r!!.contains("Mindestabstand"))
    }

    @Test
    fun calendarAllowsWhenAllConditionsMet() {
        val f = FactoryLogic(schedule = FactorySchedule(active = true, weekdays = setOf(3), startHour = 9, endHour = 21, minHoursBetween = 2))
        assertNull(f.scheduleReason(emptyList(), now, weekdayMon1 = 3, hour = 14))
        assertTrue(f.canUploadNow(emptyList(), now, weekdayMon1 = 3, hour = 14))
    }

    @Test
    fun weekdayConversionFromCalendar() {
        // Calendar: 1=So..7=Sa → Mo=1..So=7
        assertEquals(7, FactoryLogic.weekdayMon1FromCalendar(1)) // Sonntag
        assertEquals(1, FactoryLogic.weekdayMon1FromCalendar(2)) // Montag
        assertEquals(6, FactoryLogic.weekdayMon1FromCalendar(7)) // Samstag
    }
}
