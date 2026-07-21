package com.novelforge.android.data

import android.content.Context
import com.novelforge.android.domain.FactoryLimits
import com.novelforge.android.domain.FactoryLogic
import com.novelforge.android.domain.FactorySchedule
import com.novelforge.android.domain.QueueEntry
import com.novelforge.android.domain.QueueStage
import com.novelforge.android.domain.UploadRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/** Momentaufnahme des Fabrik-Zustands für die UI. */
data class FactoryState(
    val enabled: Boolean,
    val limits: FactoryLimits,
    val schedule: FactorySchedule,
    val queue: List<QueueEntry>,
    val freeToday: Int,
    val freeWeek: Int,
    val freeMonth: Int,
    val usedToday: Int,
    val usedWeek: Int,
    val usedMonth: Int,
    val throttleReason: String?,
    val scheduleReason: String?,
)

/**
 * Persistenter Fabrik-Zustand: Drossel (3/10/40), Upload-Kalender und Warteschlange.
 * Rechenlogik liegt in [FactoryLogic] (unit-getestet); hier kommen Uhr, Persistenz und
 * StateFlow für die UI hinzu.
 *
 * Hinweis: Der eigentliche KDP-Upload läuft auf Android über die WebView (nutzer-präsent);
 * diese Fabrik plant/drosselt und hält die Historie. `markUploaded` wird nach einem
 * erfolgreichen WebView-Entwurf aufgerufen.
 */
class FactoryStore(context: Context) {
    private val file = File(context.filesDir, "kdp_factory.json")
    private val logic = FactoryLogic()

    private var enabled = false
    private var history = mutableListOf<UploadRecord>()
    private var queue = mutableListOf<QueueEntry>()

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<FactoryState> = _state.asStateFlow()

    init { load(); publish() }

    // --- Öffentliche Aktionen -----------------------------------------------------

    fun setEnabled(on: Boolean) { enabled = on; save(); publish() }

    fun setLimits(l: FactoryLimits) { logic.limits = l; save(); publish() }

    fun setSchedule(s: FactorySchedule) { logic.schedule = s; save(); publish() }

    fun isQueued(projectId: String) = queue.any { it.projectId == projectId }

    fun enqueue(projectId: String, priceEUR: Double = 4.99) {
        if (isQueued(projectId)) return
        queue.add(QueueEntry(projectId, priceEUR))
        save(); publish()
    }

    fun remove(projectId: String) { queue.removeAll { it.projectId == projectId }; save(); publish() }

    /** Nach erfolgreichem WebView-Entwurf: in die Historie schreiben (zählt gegen die Drossel). */
    fun markUploaded(projectId: String, draftUrl: String = "") {
        history.add(UploadRecord(projectId, now()))
        queue.firstOrNull { it.projectId == projectId }?.let {
            it.stage = QueueStage.DONE; it.draftUrl = draftUrl
        }
        save(); publish()
    }

    /** Nächster Eintrag, der jetzt hochgeladen werden dürfte (Drossel + Kalender frei), sonst null. */
    fun nextEligible(): QueueEntry? {
        if (!enabled) return null
        if (!logic.canUploadNow(history, now(), weekdayMon1(), hour())) return null
        return queue.firstOrNull { it.stage == QueueStage.WAITING || it.stage == QueueStage.WAITING_SLOT }
    }

    // --- Intern -------------------------------------------------------------------

    private fun now() = System.currentTimeMillis()
    private fun hour() = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    private fun weekdayMon1() =
        FactoryLogic.weekdayMon1FromCalendar(Calendar.getInstance().get(Calendar.DAY_OF_WEEK))

    private fun snapshot(): FactoryState {
        val n = now()
        return FactoryState(
            enabled = enabled,
            limits = logic.limits,
            schedule = logic.schedule,
            queue = queue.toList(),
            freeToday = logic.slotsToday(history, n),
            freeWeek = logic.slotsThisWeek(history, n),
            freeMonth = logic.slotsThisMonth(history, n),
            usedToday = logic.usedToday(history, n),
            usedWeek = logic.usedThisWeek(history, n),
            usedMonth = logic.usedThisMonth(history, n),
            throttleReason = logic.throttleReason(history, n),
            scheduleReason = logic.scheduleReason(history, n, weekdayMon1(), hour()),
        )
    }

    private fun publish() { _state.value = snapshot() }

    // --- Persistenz (org.json) ----------------------------------------------------

    private fun save() {
        val root = JSONObject()
        root.put("enabled", enabled)
        root.put("limits", JSONObject().apply {
            put("perDay", logic.limits.perDay); put("perWeek", logic.limits.perWeek); put("perMonth", logic.limits.perMonth)
        })
        root.put("schedule", JSONObject().apply {
            put("active", logic.schedule.active)
            put("weekdays", JSONArray(logic.schedule.weekdays.toList()))
            put("startHour", logic.schedule.startHour)
            put("endHour", logic.schedule.endHour)
            put("minHoursBetween", logic.schedule.minHoursBetween)
        })
        root.put("history", JSONArray().apply {
            history.forEach { put(JSONObject().put("projectId", it.projectId).put("uploadedAt", it.uploadedAt)) }
        })
        root.put("queue", JSONArray().apply {
            queue.forEach {
                put(JSONObject().put("projectId", it.projectId).put("priceEUR", it.priceEUR)
                    .put("stage", it.stage.name).put("note", it.note).put("draftUrl", it.draftUrl))
            }
        })
        runCatching { file.writeText(root.toString()) }
    }

    private fun load() {
        val txt = runCatching { file.readText() }.getOrNull() ?: return
        val root = runCatching { JSONObject(txt) }.getOrNull() ?: return
        enabled = root.optBoolean("enabled", false)
        root.optJSONObject("limits")?.let {
            logic.limits = FactoryLimits(it.optInt("perDay", 3), it.optInt("perWeek", 10), it.optInt("perMonth", 40))
        }
        root.optJSONObject("schedule")?.let { s ->
            val wd = mutableSetOf<Int>()
            s.optJSONArray("weekdays")?.let { arr -> for (i in 0 until arr.length()) wd.add(arr.getInt(i)) }
            logic.schedule = FactorySchedule(
                active = s.optBoolean("active", false),
                weekdays = if (wd.isEmpty()) setOf(1, 2, 3, 4, 5) else wd,
                startHour = s.optInt("startHour", 9),
                endHour = s.optInt("endHour", 21),
                minHoursBetween = s.optInt("minHoursBetween", 2),
            )
        }
        root.optJSONArray("history")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                history.add(UploadRecord(o.optString("projectId"), o.optLong("uploadedAt")))
            }
        }
        root.optJSONArray("queue")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                queue.add(QueueEntry(
                    o.optString("projectId"), o.optDouble("priceEUR", 4.99),
                    runCatching { QueueStage.valueOf(o.optString("stage", "WAITING")) }.getOrDefault(QueueStage.WAITING),
                    o.optString("note"), o.optString("draftUrl"),
                ))
            }
        }
    }
}
