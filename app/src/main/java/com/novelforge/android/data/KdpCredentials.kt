package com.novelforge.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Ablage der Amazon-/KDP-Zugangsdaten für die autonome Anmeldung.
 *
 * Gespeichert wird ausschließlich verschlüsselt (EncryptedSharedPreferences mit einem
 * Schlüssel aus dem Android-Keystore, der das Gerät nie verlässt). Die Daten werden
 * nirgends protokolliert, nirgends versendet und ausschließlich in Amazons eigenes
 * Anmeldeformular eingetragen.
 *
 * Sie sind OPTIONAL: ohne hinterlegte Daten nutzt die App die bestehende Anmeldung im
 * Chrome des Nutzers – das ist der sicherere Normalfall. Das Passwort ist nur der
 * Rückfallweg, wenn die Sitzung abgelaufen ist.
 */
object KdpCredentials {

    private const val DATEI = "kdp_zugang"
    private const val K_EMAIL = "email"
    private const val K_PASSWORT = "passwort"

    private fun prefs(context: Context): SharedPreferences? = runCatching {
        val schluessel = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, DATEI, schluessel,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrNull()

    fun speichern(context: Context, email: String, passwort: String) {
        prefs(context)?.edit()
            ?.putString(K_EMAIL, email.trim())
            ?.putString(K_PASSWORT, passwort)
            ?.apply()
    }

    /** Liefert Zugangsdaten oder null, wenn nichts hinterlegt ist. */
    fun lesen(context: Context): Pair<String, String>? {
        val p = prefs(context) ?: return null
        val mail = p.getString(K_EMAIL, "").orEmpty()
        val pass = p.getString(K_PASSWORT, "").orEmpty()
        return if (mail.isNotBlank() && pass.isNotBlank()) mail to pass else null
    }

    fun vorhanden(context: Context): Boolean = lesen(context) != null

    fun loeschen(context: Context) {
        prefs(context)?.edit()?.clear()?.apply()
    }
}
