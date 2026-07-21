package com.novelforge.android.domain

import kotlin.math.max

/** Upload-Obergrenzen zum Schutz des KDP-Kontos (identisch zu macOS/Windows). */
data class FactoryLimits(val perDay: Int = 3, val perWeek: Int = 10, val perMonth: Int = 40)

/** Upload-Kalender: an welchen Tagen/Uhrzeiten und in welchem Mindestabstand hochgeladen wird. */
data class FactorySchedule(
    val active: Boolean = false,
    val weekdays: Set<Int> = setOf(1, 2, 3, 4, 5), // Mo=1 .. So=7
    val startHour: Int = 9,
    val endHour: Int = 21,
    val minHoursBetween: Int = 2,
)

/** Ein erfolgter Upload (für die rollierenden Drossel-Fenster). */
data class UploadRecord(val projectId: String, val uploadedAt: Long) // epoch millis

enum class QueueStage { WAITING, WAITING_SLOT, PREPARING, UPLOADING, DONE, FAILED }

data class QueueEntry(
    val projectId: String,
    val priceEUR: Double = 4.99,
    var stage: QueueStage = QueueStage.WAITING,
    var note: String = "",
    var draftUrl: String = "",
)

/**
 * Reine Drossel-/Kalender-/Queue-Logik der KDP-Fabrik – deterministischer Port aus macOS
 * `KDPFactory`. Die aktuelle Zeit wird als Parameter übergeben (kein `System.currentTimeMillis`
 * intern), damit die gesamte Logik ohne Android/Uhr unit-testbar ist.
 */
class FactoryLogic(
    var limits: FactoryLimits = FactoryLimits(),
    var schedule: FactorySchedule = FactorySchedule(),
) {
    // MARK: - Rollierende Drossel-Fenster ------------------------------------------

    private fun uploadsIn(history: List<UploadRecord>, windowMs: Long, now: Long): Int =
        history.count { val age = now - it.uploadedAt; age in 0..windowMs }

    fun usedToday(h: List<UploadRecord>, now: Long) = uploadsIn(h, DAY, now)
    fun usedThisWeek(h: List<UploadRecord>, now: Long) = uploadsIn(h, 7 * DAY, now)
    fun usedThisMonth(h: List<UploadRecord>, now: Long) = uploadsIn(h, 30 * DAY, now)

    fun slotsToday(h: List<UploadRecord>, now: Long) = max(0, limits.perDay - usedToday(h, now))
    fun slotsThisWeek(h: List<UploadRecord>, now: Long) = max(0, limits.perWeek - usedThisWeek(h, now))
    fun slotsThisMonth(h: List<UploadRecord>, now: Long) = max(0, limits.perMonth - usedThisMonth(h, now))

    /** Freie Slots = Minimum aller drei Fenster. */
    fun freeSlots(h: List<UploadRecord>, now: Long) =
        minOf(slotsToday(h, now), slotsThisWeek(h, now), slotsThisMonth(h, now))

    /** Grund, warum die Drossel gerade sperrt (null = frei). */
    fun throttleReason(h: List<UploadRecord>, now: Long): String? = when {
        slotsToday(h, now) <= 0 -> "Tageslimit erreicht (${limits.perDay}/Tag)."
        slotsThisWeek(h, now) <= 0 -> "Wochenlimit erreicht (${limits.perWeek}/Woche)."
        slotsThisMonth(h, now) <= 0 -> "Monatslimit erreicht (${limits.perMonth}/Monat)."
        else -> null
    }

    // MARK: - Upload-Kalender ------------------------------------------------------

    /**
     * Erlaubt der Kalender JETZT einen Upload? null = ja, sonst der Grund.
     * `weekdayMon1` (1=Mo..7=So) und `hour` (0..23) werden vom Aufrufer aus der Uhr bestimmt
     * (siehe [nowWeekdayMon1]/[nowHour]) – so bleibt die Logik testbar.
     */
    fun scheduleReason(h: List<UploadRecord>, now: Long, weekdayMon1: Int, hour: Int): String? {
        if (!schedule.active) return null
        val names = listOf("", "Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
        if (weekdayMon1 !in schedule.weekdays) {
            val allowed = schedule.weekdays.sorted().joinToString(", ") { names.getOrElse(it) { "?" } }
            return "Heute (${names.getOrElse(weekdayMon1) { "?" }}) ist kein Upload-Tag. Geplant: $allowed."
        }
        if (hour < schedule.startHour || hour >= schedule.endHour) {
            return "Außerhalb des Zeitfensters (${schedule.startHour}–${schedule.endHour} Uhr)."
        }
        val last = h.maxOfOrNull { it.uploadedAt }
        if (last != null) {
            val elapsedH = (now - last) / 3_600_000.0
            if (elapsedH < schedule.minHoursBetween) {
                val waitMin = ((schedule.minHoursBetween - elapsedH) * 60).toInt()
                return "Mindestabstand ${schedule.minHoursBetween} h – nächster Upload in ${waitMin / 60} h ${waitMin % 60} min."
            }
        }
        return null
    }

    /** Darf jetzt ein Upload starten? (Drossel UND Kalender frei). */
    fun canUploadNow(h: List<UploadRecord>, now: Long, weekdayMon1: Int, hour: Int): Boolean =
        throttleReason(h, now) == null && scheduleReason(h, now, weekdayMon1, hour) == null

    companion object {
        const val DAY = 24L * 3600L * 1000L

        /** Wochentag als Mo=1..So=7 aus einem Calendar-Wochentag (1=So..7=Sa). */
        fun weekdayMon1FromCalendar(calWeekdaySun1: Int): Int = ((calWeekdaySun1 + 5) % 7) + 1
    }
}
