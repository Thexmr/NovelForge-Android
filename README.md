# NovelForge für Android

KI-Romangenerator für Amazon KDP – die Android-Portierung der macOS-App.
**Kotlin · Jetpack Compose · Material 3 (Dark „Studio Noir") · Koroutinen.**

## Status

Voll funktionsfähige App mit weitgehender Feature-Parität zur macOS-Version. Erzeugt
komplette deutschsprachige Romane vollautomatisch und exportiert sie für den KDP-Upload.

- **Autonome Generierungs-Pipeline** (Koroutinen): Genre-Direktive → Konzept → Plot →
  Kapitelplan → Figurenensemble → Kapitel (mit Qualitäts-Gates) → „Blick ins Buch"-
  Optimierung → KDP-Metadaten → virale Titel-Maschine → Cover-Prompt.
- **Auto-Modus & Hintergrund-Betrieb**: ein Foreground-Service (`GenerationService`)
  produziert fortlaufend Bücher, auch wenn die App im Hintergrund ist; Pacing +
  Circuit-Breaker gegen Heißlauf.
- **Persistenz**: Bücher überleben App-Neustarts (JSON-Schnappschuss via `ProjectJson`,
  atomar geschrieben, korrupte Datei wird gesichert statt überschrieben).
- **Qualität & Sicherheit** (aus macOS portiert): Anti-KI-Klang/Archaik-Heuristik,
  Prompt-Artefakt-Bereinigung, Echo-Dedup, harter Kinderschutz-Filter
  (`ContentSafetyFilter`), Copyright-Denylist (`CopyrightFilter`).
- **Stil-DNA** (`NarrativeSignature`): einzigartige Erzähl-Signatur pro Buch gegen
  Amazons „Programmatic Content"-Erkennung.
- **Export**: Manuskript-Text, KDP-Verkaufsblatt, **EPUB 3** (mit Titelseite),
  **PDF** und **DOCX/Google Docs** – ohne externe Libraries. Speichern via Storage
  Access Framework, Teilen via Share-Intent, KDP-Blatt in die Zwischenablage.
- **KI-Client**: nativer Ollama-Pfad (`/api/chat`) und OpenAI-kompatibel, mit
  Retry/Backoff; API-Key lokal in DataStore.
- **UI**: adaptive Navigation (Bottom-Bar im Hochformat, Rail im Querformat),
  responsive Layouts, Studio-Noir-Branding, voller Kapitel-Reader.

## Architektur

```
domain/     Models, SpiceLevel, NarrativeSignature (Stil-DNA), Genres,
            ContentQuality, ContentSafetyFilter, CopyrightFilter
ai/         AiClient (OkHttp, Ollama + OpenAI-kompatibel), PromptFactory (dt. Prompts)
generator/  NovelGenerator (Pipeline + Parser), GenerationController (StateFlows)
service/    GenerationService (Foreground-Service, Auto-Modus)
data/       SettingsStore (DataStore), ProjectRepository + ProjectJson (Persistenz)
export/     ExportBuilder (EPUB/PDF/DOCX/TXT + KDP-Blatt)
ui/         AppRoot (adaptive Navigation), Screens, AppViewModel, theme
```

## Build & Test

- Lokal: `./gradlew assembleDebug` (JDK 17 + Android SDK 34).
- Unit-Tests: `./gradlew testDebugUnitTest` (JVM, `app/src/test/`).
- CI: GitHub Actions (`.github/workflows/android.yml`) baut die Debug-APK **und**
  führt die Unit-Tests aus.

## Offene Parität zu macOS

- **Cover-Bild-Generierung** (macOS hat mehrere Bild-Provider + Compositing); Android
  liefert bislang nur einen Cover-Prompt und eine typografische EPUB-Titelseite.
- **Lektor-Chat / Repair-Workflow** (interaktive Nachbearbeitung) fehlt noch.
- **Interaktives KDP-Sales-Sheet** mit Einzel-Copy/Override (aktuell nur als Export).
- **Manuskript-Edit-Modus** (Android zeigt Kapitel read-only).
