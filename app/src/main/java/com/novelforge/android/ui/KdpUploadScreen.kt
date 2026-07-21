package com.novelforge.android.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.novelforge.android.domain.Project
import com.novelforge.android.export.CoverArtService
import com.novelforge.android.export.ExportBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

private const val KDP_HOME = "https://kdp.amazon.com/"
private const val KDP_CREATE = "https://kdp.amazon.com/en_US/title-setup/kindle/new/details"

/**
 * Autonomer KDP-Upload per kontrollierter WebView (Puppeteer-Äquivalent – ohne Root/Shizuku).
 *
 * - Login bleibt über [CookieManager] erhalten: einmal einloggen, danach autonom.
 * - EPUB + Cover werden KDPs Datei-Dialog automatisch untergeschoben (kein Suchen im Dateisystem).
 * - Best-effort-Autofüllung von Titel/Untertitel/Beschreibung/Keywords; klickt NIE
 *   „Veröffentlichen" – der Entwurf bleibt Entwurf, den Preis bestätigt der Mensch.
 *
 * ⚠️ KDPs Formular-Selektoren ändern sich; die Autofüllung ist defensiv (mehrere Fallbacks)
 *    und wird manuell ausgelöst, damit sie an der echten Seite kalibrierbar bleibt.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun KdpUploadScreen(project: Project, onBack: () -> Unit) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("Bereite EPUB + Cover vor …") }
    var web by remember { mutableStateOf<WebView?>(null) }
    val epubFile = remember { mutableStateOf<File?>(null) }
    val coverFile = remember { mutableStateOf<File?>(null) }

    // EPUB + Cover in den Cache schreiben, damit der Datei-Dialog sie sofort liefern kann.
    LaunchedEffect(project.id) {
        withContext(Dispatchers.IO) {
            val blocker = ExportBuilder.exportBlocker(project)
            if (blocker != null) { status = "Upload gesperrt: $blocker"; return@withContext }
            val dir = File(context.cacheDir, "kdp").apply { mkdirs() }
            val epub = File(dir, "manuskript.epub")
            runCatching { epub.writeBytes(ExportBuilder.epubBytes(project)) }
                .onSuccess { epubFile.value = epub }
            val existing = CoverArtService.coverFile(context, project)
            coverFile.value = if (existing.exists()) existing
                else runCatching { CoverArtService.generate(context, project) }.getOrNull()
            status = if (epubFile.value != null)
                "Bereit. 1) Bei KDP anmelden  2) „Neues eBook“ öffnen  3) „Felder ausfüllen“"
            else "EPUB konnte nicht erzeugt werden."
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Kopfzeile mit Steuerung.
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("KDP-Upload · ${project.title}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Zurück") }
                OutlinedButton(onClick = { web?.loadUrl(KDP_CREATE) }, modifier = Modifier.weight(1f)) { Text("Neues eBook") }
                Button(
                    onClick = { web?.evaluateJavascript(autofillJs(project)) {} },
                    modifier = Modifier.weight(1f),
                ) { Text("Felder ausfüllen") }
            }
            Text("Speichert nur als ENTWURF. Preis bestätigst und veröffentlichst du selbst.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    web = this
                    val wv = this
                    CookieManager.getInstance().let { cm ->
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(wv, true)
                    }
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        javaScriptCanOpenWindowsAutomatically = true
                        // Desktop-UA: KDPs Buch-Anlage ist auf Desktop ausgelegt.
                        userAgentString = userAgentString.replace("; wv", "")
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        builtInZoomControls = true
                        displayZoomControls = false
                    }
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun log(msg: String) { status = msg }
                    }, "NF")

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            CookieManager.getInstance().flush() // Login über Neustarts hinweg halten
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onShowFileChooser(
                            view: WebView?,
                            callback: ValueCallback<Array<Uri>>?,
                            params: FileChooserParams?,
                        ): Boolean {
                            val accept = params?.acceptTypes?.joinToString(",")?.lowercase().orEmpty()
                            val wantsImage = listOf("image", "jpeg", "jpg", "png").any { accept.contains(it) }
                            val f = if (wantsImage) coverFile.value else epubFile.value
                            return if (f != null && f.exists()) {
                                callback?.onReceiveValue(arrayOf(Uri.fromFile(f)))
                                status = "Datei übergeben: ${f.name}"
                                true
                            } else {
                                // Nicht bereit → Nutzer wählt selbst (kein Absturz).
                                callback?.onReceiveValue(null)
                                false
                            }
                        }
                    }
                    loadUrl(KDP_HOME)
                }
            },
        )
    }
}

/** Baut die defensive Autofüll-JS mit sicher eingebetteten (JSON-quoteten) Werten. */
private fun autofillJs(project: Project): String {
    val p = project.profile
    fun q(s: String): String = JSONObject.quote(s)
    val title = q(p.kdpTitle.ifBlank { project.title })
    val subtitle = q(p.kdpSubtitle)
    val descPlain = q(p.kdpDescription)
    val descHtml = q(p.kdpDescription.replace("&", "&amp;").replace("<", "&lt;")
        .replace("\n\n", "</p><p>").replace("\n", "<br>").let { "<p>$it</p>" })
    val keywords = q(p.kdpKeywords)
    return """
      (function(){
        function setVal(el,val){
          if(!el) return false;
          try{
            var proto = el.tagName==='TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
            var setter = Object.getOwnPropertyDescriptor(proto,'value').set;
            setter.call(el,val);
          }catch(e){ el.value = val; }
          el.dispatchEvent(new Event('input',{bubbles:true}));
          el.dispatchEvent(new Event('change',{bubbles:true}));
          return true;
        }
        function find(sels){ for(var i=0;i<sels.length;i++){ var e=document.querySelector(sels[i]); if(e) return e; } return null; }
        var filled=[];
        var t=find(['#data-print-book-title','input[name="title"]','input[id*="title" i]','input[aria-label*="Titel" i]','input[aria-label*="title" i]']);
        if(t && setVal(t,$title)) filled.push('Titel');
        var s=find(['#data-print-book-subtitle','input[name="subtitle"]','input[id*="subtitle" i]']);
        if(s && $subtitle && setVal(s,$subtitle)) filled.push('Untertitel');
        var d=find(['textarea[name="description"]','textarea[id*="description" i]','textarea[aria-label*="Beschreibung" i]','div[contenteditable="true"]','#cke_1_contents div[contenteditable="true"]']);
        if(d){
          if(d.tagName==='DIV'){ d.innerHTML=$descHtml; d.dispatchEvent(new Event('input',{bubbles:true})); filled.push('Beschreibung'); }
          else if(setVal(d,$descPlain)) filled.push('Beschreibung');
        }
        var kws=($keywords||'').split(',').map(function(x){return x.trim();}).filter(Boolean).slice(0,7);
        var got=0;
        for(var k=0;k<kws.length;k++){
          var kf=find(['#data-print-book-keywords-'+k,'input[name="keywords['+k+']"]','input[id*="keyword'+(k+1)+'" i]']);
          if(kf && setVal(kf,kws[k])) got++;
        }
        if(got>0) filled.push('Keywords('+got+')');
        NF.log(filled.length? ('Ausgefüllt: '+filled.join(', ')+' — Cover/EPUB hochladen, Preis prüfen, dann speichern.')
                            : 'Keine Felder gefunden – bitte einmalig Selektoren kalibrieren (Seite geöffnet lassen).');
      })();
    """.trimIndent()
}
