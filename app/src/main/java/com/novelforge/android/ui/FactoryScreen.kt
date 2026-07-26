package com.novelforge.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.novelforge.android.domain.FactorySchedule
import com.novelforge.android.domain.ProjectStatus

/**
 * Fabrik-Übersicht: An/Aus, freie Upload-Slots (Tag/Woche/Monat), Upload-Kalender und
 * Warteschlange. Der eigentliche KDP-Upload läuft nutzer-präsent über die WebView –
 * die Fabrik plant, drosselt (3/10/40) und hält die Historie.
 */
@Composable
fun FactoryScreen(vm: AppViewModel, onOpenProject: (String) -> Unit = {}) {
    val state by vm.factoryState.collectAsState()
    val projects by vm.projects.collectAsState()
    val dayNames = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Buchfabrik", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)

        // An/Aus
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Autonome Fabrik", fontWeight = FontWeight.SemiBold)
                    Text(if (state.enabled) "aktiv – reiht ein und plant nach Kalender" else "aus",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = state.enabled, onCheckedChange = { vm.factoryEnabled(it) })
            }
        }

        // Slots
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Freie Upload-Slots", fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SlotBox("Heute", state.freeToday, state.limits.perDay, Modifier.weight(1f))
                    SlotBox("Woche", state.freeWeek, state.limits.perWeek, Modifier.weight(1f))
                    SlotBox("Monat", state.freeMonth, state.limits.perMonth, Modifier.weight(1f))
                }
                state.throttleReason?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        // Kalender
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Upload-Kalender", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Switch(checked = state.schedule.active,
                        onCheckedChange = { vm.factorySchedule(state.schedule.copy(active = it)) })
                }
                Text("Tage", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..7).forEach { d ->
                        val on = d in state.schedule.weekdays
                        FilterChip(
                            selected = on,
                            onClick = {
                                val wd = state.schedule.weekdays.toMutableSet()
                                if (on) wd.remove(d) else wd.add(d)
                                vm.factorySchedule(state.schedule.copy(weekdays = wd))
                            },
                            label = { Text(dayNames[d - 1]) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                HourRow("Von", state.schedule.startHour) { vm.factorySchedule(state.schedule.copy(startHour = it)) }
                HourRow("Bis", state.schedule.endHour) { vm.factorySchedule(state.schedule.copy(endHour = it)) }
                IntervalRow(state.schedule.minHoursBetween) { vm.factorySchedule(state.schedule.copy(minHoursBetween = it)) }
                state.scheduleReason?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
                Text("Der finale KDP-Upload öffnet die WebView (einmal anmelden, dann Entwurf). Die Fabrik hält die Drossel ein.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Warteschlange
        if (state.queue.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Warteschlange", fontWeight = FontWeight.SemiBold)
                    state.queue.forEach { entry ->
                        val title = projects.firstOrNull { it.id == entry.projectId }?.title ?: entry.projectId
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(title, style = MaterialTheme.typography.bodyMedium)
                                Text("${entry.stage.name} · ${"%.2f".format(entry.priceEUR)} €",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            OutlinedButton(onClick = { onOpenProject(entry.projectId) }) { Text("Öffnen") }
                            Spacer(Modifier.height(0.dp))
                            OutlinedButton(onClick = { vm.factoryRemove(entry.projectId) }) { Text("Raus") }
                        }
                    }
                }
            }
        }

        // Shizuku: autonomer Upload über das ECHTE Chrome (bestehende KDP-Anmeldung)
        ShizukuCard(vm)

        // Einreihbare (fertige) Bücher
        val ready = projects.filter { it.status == ProjectStatus.COMPLETED && state.queue.none { q -> q.projectId == it.id } }
        if (ready.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Fertig – einreihen", fontWeight = FontWeight.SemiBold)
                    ready.forEach { p ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(p.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = { vm.factoryEnqueue(p.id) }) { Text("Einreihen") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SlotBox(label: String, free: Int, limit: Int, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$free", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                color = if (free > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Text("$label /$limit", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HourRow(label: String, hour: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$label:", modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { onChange((hour - 1).coerceIn(0, 23)) }) { Text("−") }
        Text("%02d:00".format(hour), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { onChange((hour + 1).coerceIn(0, 24)) }) { Text("+") }
    }
}

@Composable
private fun IntervalRow(hours: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Mindestabstand:", modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { onChange((hours - 1).coerceIn(0, 72)) }) { Text("−") }
        Text("$hours h", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { onChange((hours + 1).coerceIn(0, 72)) }) { Text("+") }
    }
}
