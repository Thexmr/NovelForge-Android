# NovelForge für Android

KI-Romangenerator für Amazon KDP – die Android-Portierung der macOS-App.
**Kotlin · Jetpack Compose · Material 3 (Dark „Studio Noir") · Koroutinen.**

## Status (v0.1 – Foundation)

Lauffähige Basis mit für Android adaptierter UI:

- **Studio-Noir-Theme** (dunkel, Indigo/Blau-Signatur) in Compose/Material 3.
- **Bottom-Navigation** (Android-Pattern statt macOS-Sidebar): Studio · Neues Buch · Einstellungen.
- **Generierungs-Pipeline** (Koroutinen): Konzept → Plot → Kapitelplan → Kapitel → KDP-Metadaten.
- Aus der macOS-App **portierte Kern-Logik**: PromptFactory, Sinnlichkeitsgrad, Stil-DNA (Einzigartigkeits-Engine), KDP-Metadaten.
- OpenAI-kompatibler **KI-Client** (Ollama Cloud u.a.), API-Key lokal in DataStore.

## Architektur

```
domain/      Models, SpiceLevel, NarrativeSignature (Stil-DNA), Genres
ai/          AiClient (OkHttp), PromptFactory (deutsche Prompts)
generator/   NovelGenerator (Koroutinen-Pipeline + Parser)
data/        SettingsStore (DataStore), ProjectRepository (in-memory)
ui/          AppRoot (NavHost + BottomBar), Screens, AppViewModel, theme
```

## Build

Lokal: `./gradlew assembleDebug` (JDK 17 + Android SDK 34).
CI: GitHub Actions (`.github/workflows/android.yml`) baut die Debug-APK.

## Roadmap (nächste Schritte)

- Room-Persistenz statt In-Memory-Repository.
- Kapitel-Reader, KDP-Blatt mit Kopier-Buttons, Cover-Prompt-Generator.
- Serien-/Read-Through-Flow, „Blick ins Buch"-Optimierer, Export (EPUB).
- Feature-Parität zur macOS-App.
