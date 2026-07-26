package com.novelforge.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.novelforge.android.domain.ProjectStatus
import com.novelforge.android.shizuku.ShizukuBridge
import com.novelforge.android.shizuku.ShizukuKdpUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Steuerung für den autonomen KDP-Upload über das ECHTE Chrome des Nutzers (via Shizuku).
 * Zeigt ehrlich an, was gerade fehlt (Shizuku nicht installiert/gestartet/freigegeben) und
 * bietet nur dann den Ein-Klick-Upload an, wenn wirklich alles bereit ist.
 * Ohne Shizuku bleibt der Weg über die eingebaute Ansicht (Buch öffnen → „Autonom zu KDP").
 */
@Composable
fun ShizukuCard(vm: AppViewModel) {
    val context = LocalContext.current
    val projects by vm.projects.collectAsState()
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf(ShizukuBridge.status(context)) }
    var meldung by remember { mutableStateOf("") }
    var laeuft by remember { mutableStateOf(false) }
    var anteil by remember { mutableFloatStateOf(0f) }

    // Status beim Öffnen einmal frisch bestimmen (Shizuku kann zwischenzeitlich starten).
    LaunchedEffect(Unit) { status = ShizukuBridge.status(context) }

    val fertigeBuecher = projects.filter { it.status == ProjectStatus.COMPLETED }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Autonom über dein Chrome (Shizuku)", fontWeight = FontWeight.SemiBold)
            Text(
                status.hinweis,
                style = MaterialTheme.typography.bodySmall,
                color = if (status.bereit) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { status = ShizukuBridge.status(context) },
                    modifier = Modifier.weight(1f),
                ) { Text("Status prüfen") }

                if (status.state == ShizukuBridge.State.KEINE_BERECHTIGUNG) {
                    Button(
                        onClick = {
                            ShizukuBridge.requestPermission()
                            status = ShizukuBridge.status(context)
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Freigeben") }
                }
            }

            if (status.bereit && fertigeBuecher.isNotEmpty()) {
                Text("Fertiges Buch autonom als KDP-Entwurf anlegen:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                fertigeBuecher.forEach { p ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Button(
                            enabled = !laeuft,
                            onClick = {
                                laeuft = true; meldung = ""; anteil = 0f
                                scope.launch {
                                    val ergebnis = withContext(Dispatchers.IO) {
                                        runCatching {
                                            ShizukuKdpUploader.ladeHoch(context, p) { s ->
                                                anteil = s.anteil; meldung = s.text
                                            }
                                        }.getOrElse { "Fehlgeschlagen: ${it.message}" }
                                    }
                                    meldung = ergebnis
                                    anteil = 1f
                                    laeuft = false
                                    // Erfolgreicher Entwurf zählt gegen die Drossel (3/Tag · 10/Woche · 40/Monat).
                                    if (ergebnis.startsWith("KDP-Entwurf gespeichert")) vm.factoryMarkUploaded(p.id)
                                }
                            },
                        ) { Text("Hochladen") }
                    }
                }
            }

            if (laeuft) {
                LinearProgressIndicator(progress = { anteil }, modifier = Modifier.fillMaxWidth())
            }
            if (meldung.isNotBlank()) {
                Text(meldung, style = MaterialTheme.typography.bodySmall)
            }

            Text("Es wird immer nur ein ENTWURF gespeichert – veröffentlicht wird nichts automatisch.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
