package com.novelforge.android.shizuku

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader

/**
 * Anbindung an Shizuku. Shizuku erteilt der App ADB-Rechte (ohne Root) – damit kann
 * NovelForge Shell-Befehle im Systemkontext ausführen und so das ECHTE Chrome des
 * Nutzers steuern. Vorteil gegenüber der eingebauten WebView: die bei Amazon/KDP
 * bereits bestehende Anmeldung im normalen Chrome wird genutzt, kein zweiter Login.
 *
 * Alles hier ist optional: ist Shizuku nicht installiert oder nicht gestartet, meldet
 * [status] das, und die App nutzt weiterhin den WebView-Weg.
 */
object ShizukuBridge {

    const val PERMISSION_REQUEST_CODE = 4711
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    enum class State { NICHT_INSTALLIERT, NICHT_GESTARTET, KEINE_BERECHTIGUNG, BEREIT }

    data class Status(val state: State, val hinweis: String) {
        val bereit: Boolean get() = state == State.BEREIT
    }

    /** Ist die Shizuku-App überhaupt installiert? */
    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Läuft der Shizuku-Dienst (per ADB/Wireless-Debugging gestartet)? */
    fun isRunning(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    fun hasPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    /** Fragt die Berechtigung an; das Ergebnis kommt über Shizuku.addRequestPermissionResultListener. */
    fun requestPermission() {
        try { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) } catch (_: Throwable) { /* nicht verfügbar */ }
    }

    /** Aktueller Zustand mit verständlichem Hinweis für die Oberfläche. */
    fun status(context: Context): Status = when {
        !isInstalled(context) -> Status(State.NICHT_INSTALLIERT,
            "Shizuku ist nicht installiert. Ohne Shizuku läuft der KDP-Upload über die eingebaute Ansicht (dort einmalig anmelden).")
        !isRunning() -> Status(State.NICHT_GESTARTET,
            "Shizuku ist installiert, aber nicht gestartet. In der Shizuku-App über „Wireless-Debugging“ oder ADB starten.")
        !hasPermission() -> Status(State.KEINE_BERECHTIGUNG,
            "Shizuku läuft – NovelForge braucht noch deine Freigabe.")
        else -> Status(State.BEREIT,
            "Shizuku ist bereit: NovelForge kann dein normales Chrome mit deiner bestehenden KDP-Anmeldung steuern.")
    }

    /**
     * Führt einen Shell-Befehl mit ADB-Rechten aus und liefert die Ausgabe.
     * `Shizuku.newProcess` ist bewusst nicht öffentlich – deshalb per Reflexion,
     * wie es auch andere Shizuku-Anwendungen tun. Wirft bei fehlender Bereitschaft.
     */
    suspend fun exec(command: String, timeoutMs: Long = 30_000): String = withContext(Dispatchers.IO) {
        if (!isRunning()) throw IllegalStateException("Shizuku läuft nicht.")
        if (!hasPermission()) throw IllegalStateException("Keine Shizuku-Berechtigung.")
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
        ).apply { isAccessible = true }
        val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
        val ausgabe = StringBuilder()
        val ende = System.currentTimeMillis() + timeoutMs
        try {
            process.inputStream.bufferedReader().use { r: BufferedReader ->
                var zeile = r.readLine()
                while (zeile != null && System.currentTimeMillis() < ende) {
                    ausgabe.append(zeile).append('\n')
                    zeile = r.readLine()
                }
            }
            process.waitFor()
        } finally {
            runCatching { process.destroy() }
        }
        ausgabe.toString()
    }
}
