# FlutterPreview — Android Studio plugin plan

Goal: see a Flutter widget rendered inside Android Studio while editing its Dart file, without running the full app on a device.

## 0. Decision (2026-11-09): headless rendering, no Chromium

The official previewer rendered nothing on a real-world plugin package, so this plugin will not use Flutter Web or JCEF. The flow is:

1. Read the `@Preview` declarations in the focused Dart file (Dart PSI) and list them in a tool window.
2. When an item is clicked, generate `.dart_tool/flutter_preview/render_test.dart`. It loads the Roboto and MaterialIcons fonts from `$FLUTTER_ROOT/bin/cache/artifacts/material_fonts` inside `tester.runAsync`, pumps `wrapper(previewFn())` inside a `RepaintBoundary`, and writes a PNG with `toImage`.
3. Run `flutter test --timeout 60s <file>` and show the PNG in a Swing panel.

A spike (a single hand-written render test, since replaced by the generated harness) proved this works: a real app widget rendered with its real fonts and icon. The full `flutter test` run took 2.9 s and the render itself 161 ms.

* Font and image loading must happen inside `tester.runAsync`; outside it, the test hangs.
* The preview size comes from `@Preview(size:)`, with a default width when it isn't set.
* Possible optimization (not yet tested): keep a single `flutter run -d flutter-tester --machine` process running and hot-reload it, instead of starting `flutter test` for every click.

Sections 1–7 below describe the earlier JCEF-based approach and are kept for reference. Phase 5 (snapshot mode) is now the main path.

## 1. Is it possible?

Yes. Flutter already ships the hard part:

* **Flutter Widget Previewer** (`flutter widget-preview start`) is in the SDK installed here (Flutter 3.50.0-pre, Dart 3.13.5). It finds functions/constructors annotated with `@Preview(...)` (from `package:flutter/widget_previews.dart`), generates a scaffold app in `.dart_tool/widget_preview_scaffold`, runs it on Flutter Web, and hot-reloads when files change.
* It has IDE-embedding flags: `--machine` (JSON events on stdout), `--web-server` (serve a URL instead of opening Chrome), `--no-launch-previewer`, `--dtd-url` (share the IDE's Dart Tooling Daemon), and `--no-devtools`.
* IntelliJ/Android Studio can show web content in a tool window or editor through **JCEF** (the built-in Chromium).

**Check this before you build anything:** the official Flutter plugin you have installed (`flutter-intellij` 98.0.0 in Android Studio 2026.2) already registers a **"Flutter Widget Preview"** tool window (`io.flutter.widgetpreview.WidgetPreviewToolWindowFactory`). Open a project with `@Preview` widgets and look at **View → Tool Windows → Flutter Widget Preview**. If it does what you need, you don't need a new plugin.

A custom plugin is still worth building if you want things the official one doesn't offer, such as:

* Previews of widgets that have **no `@Preview` annotation**, picked from the gutter or the caret.
* A **split editor** (code | preview), similar to Compose preview, instead of a separate tool window.
* **Snapshot mode**: static PNGs rendered headlessly with `flutter test`. It's fast, needs no browser, and can render a grid of themes, locales, text scales and screen sizes.
* Project-specific fakes, such as Riverpod `ProviderScope` overrides, Stacked view models, or stubbed method channels for the host app.

## 2. What you need

| Area | What |
|---|---|
| Language | Kotlin (JVM 21) |
| Build | Gradle + **IntelliJ Platform Gradle Plugin 2.x** (`org.jetbrains.intellij.platform`) |
| Target IDE | Android Studio 2026.2 (`androidStudio("2026.2.x")` in `intellijPlatform { }`), with `sinceBuild` matched to it |
| Plugin deps | `Dart` (required, for PSI and Dart SDK lookup); `io.flutter` (optional, to reuse the Flutter SDK path) |
| Platform APIs | `ToolWindowFactory`, `FileEditorProvider` / `TextEditorWithPreview` (split editor), `JBCefBrowser` (JCEF), `LineMarkerProvider` (gutter icons), `OSProcessHandler` / `GeneralCommandLine` (spawn `flutter`), `BulkFileListener` / `DocumentListener` (save → reload), `ProjectService` (one preview session per project), `PersistentStateComponent` (settings) |
| Flutter side | Flutter SDK with `widget-preview`; for snapshot mode, `flutter test` plus `matchesGoldenFile` / `RenderRepaintBoundary.toImage` |
| Tooling | IntelliJ Plugin Verifier (`verifyPlugin` task), `runIde` sandbox pointed at the local Android Studio install |

## 3. Architecture

```
Android Studio
├─ Editor (.dart)
│   ├─ Gutter icon on @Preview / StatelessWidget / StatefulWidget classes  ─┐
│   └─ Split editor: [ code | preview panel ]                               │
├─ Tool window "Flutter Preview"                                            │
│   └─ JBCefBrowser  ◄── URL ── PreviewSession (project service) ◄──────────┘
│                                   │
│                                   ├─ LiveBackend:     flutter widget-preview start --machine --web-server
│                                   │                   --no-launch-previewer [--dtd-url <ide dtd>]
│                                   ├─ HarnessBackend:  generated .dart_tool/flutter_preview/main.dart
│                                   │                   + flutter run -d web-server --machine (daemon protocol)
│                                   └─ SnapshotBackend: generated test → flutter test → PNGs → image panel
└─ Settings: SDK path, backend, device sizes, themes, auto-reload on save
```

**PreviewSession** owns a single long-lived `flutter` process for each project. It parses `--machine` JSON lines, exposes state (`Starting | Ready(url) | Reloading | Error(msg)`), and restarts the process if it crashes.

**Backends** sit behind a single interface, so the UI doesn't need to know which one is running:

```kotlin
interface PreviewBackend {
    suspend fun start(project: Project): PreviewTarget   // Url or Images
    suspend fun reload(changed: List<VirtualFile>)
    fun dispose()
}
```

## 4. Phased plan

### Phase 0: spike (½–1 day)
1. In a sample Flutter app, add `@Preview(name: 'Card') Widget card() => const MyCard();`.
2. Run `flutter widget-preview start --machine --web-server --no-launch-previewer` and record the JSON event that carries the served URL, along with the other event shapes.
3. Open that URL in a plain browser and confirm that saving a file triggers a hot reload.
4. Try the official "Flutter Widget Preview" tool window. List what it doesn't do; that list becomes this plugin's scope.

### Phase 1: plugin skeleton (1 day)
* `build.gradle.kts` with IntelliJ Platform Gradle Plugin 2.x, `androidStudio(...)`, and `bundledPlugin`/`plugin` deps on Dart.
* `plugin.xml`: id `in.bleuphoenix.flutterpreview`, an empty tool window, one action ("Open Flutter Preview").
* `./gradlew runIde` launches a sandbox Android Studio with the plugin loaded.

### Phase 2: SDK and process management (1–2 days)
* Resolve the Flutter SDK in this order: Flutter plugin setting → `FLUTTER_ROOT` → `which flutter` → a field in the plugin's settings.
* `PreviewSession` project service: start/stop `flutter widget-preview start ...` with `GeneralCommandLine`, set the working directory to the module containing `pubspec.yaml`, and stream stdout to a JSON-line parser.
* Show the process logs in a console tab of the tool window.
* Tear the process down on project close (`Disposable`).

### Phase 3: live preview in a JCEF panel (1–2 days)
* When the `Ready(url)` event arrives, load it in a `JBCefBrowser` inside the tool window.
* Show status, errors and a "Restart" button.
* Fall back gracefully if JCEF isn't supported (`JBCefApp.isSupported()`): offer "Open in browser".

### Phase 4: editor integration (2–3 days)
* `LineMarkerProvider` on `@Preview`-annotated declarations → click opens or focuses that preview.
* A split editor (`TextEditorWithPreview`) for `.dart` files that contain previews, with a toggle for Code / Split / Preview.
* Filter the preview page to the current file. The previewer UI supports filtering by the selected file over DTD (`--dtd-url`); if that isn't reachable, keep the full gallery and scroll to the selected preview.
* Clicking a preview navigates to its source, either through DTD events or a small JS bridge (`JBCefJSQuery`).

### Phase 5: snapshot mode (2–3 days, optional but high value)
* For the selected widget, generate `.dart_tool/flutter_preview/snapshot_test.dart`:
  * `testWidgets` pumps the widget inside `MaterialApp`, with optional theme, locale, `MediaQuery` size and text scale.
  * It writes PNGs with `expectLater(find.byType(...), matchesGoldenFile(...))` and `--update-goldens`, or with a custom `toImage` dump.
* Run `flutter test <file> --update-goldens`, then show the PNGs in a grid (Swing `JBImageIcon` panel).
* Re-run on save with a debounce. Useful for "all states at once" views, and it works without a browser.

### Phase 6: previews without `@Preview` (3–5 days, optional)
* Put the caret on a widget class and run **Preview this widget**.
* Use Dart PSI or the analysis server to read the constructor and generate a harness with default values for its parameters, asking the user for any required arguments that have no obvious default.
* Wrap the harness in project-specific fakes chosen from settings, such as a `ProviderScope(overrides: ...)` file or a Stacked locator setup.
* Run with `flutter run -d web-server --machine`, and drive reloads with the daemon `app.restart` (`fullRestart: false`) on save.

### Phase 7: polish and distribution (1–2 days)
* Settings page (`Configurable`): SDK path, default backend, device presets, auto-reload.
* `./gradlew verifyPlugin buildPlugin` → install `build/distributions/FlutterPreview-x.y.z.zip` via **Settings → Plugins → ⚙ → Install from disk**, or host it on a private plugin repository (`updatePlugins.xml`) for the team.
* Optional: signing and publishing to the JetBrains Marketplace.

## 5. Known limitations and risks

* **Web-only rendering.** Previews run on Flutter Web (or headless `flutter test`), so `dart:io`, platform channels, and native plugins such as camera, Firebase or the host app's method channels fail at runtime. Widgets that need them have to be given fakes.
* **Dependencies.** Widgets that read Riverpod providers, Stacked view models, `GetIt`, or localization need wrappers. Plan a "preview wrapper" hook from the start (see Phase 6).
* **Fonts and assets.** These must be declared in `pubspec.yaml` to load in the preview. Snapshot mode uses the test font (Ahem) unless you load the real fonts.
* **Startup cost.** The first web compile takes tens of seconds, so keep one long-lived process for each project and use hot reload after that.
* **API churn.** The `widget-preview` command and its `--machine` event format are still evolving between Flutter versions. Keep the JSON parser tolerant and gate features on `flutter --version --machine`.
* **Overlap with the official plugin.** `flutter-intellij` already has a Widget Preview tool window, so this plugin should add the split editor, snapshot mode, un-annotated previews and project fakes rather than duplicate what exists.

## 6. Suggested repo layout

```
FlutterPreview/
├─ build.gradle.kts
├─ settings.gradle.kts
├─ gradle.properties            # platformVersion, sinceBuild, pluginVersion
└─ src/main/
   ├─ kotlin/in/bleuphoenix/flutterpreview/
   │  ├─ sdk/FlutterSdkLocator.kt
   │  ├─ session/PreviewSession.kt        # project service, process lifecycle
   │  ├─ session/MachineEventParser.kt
   │  ├─ backend/LiveBackend.kt
   │  ├─ backend/SnapshotBackend.kt
   │  ├─ backend/HarnessBackend.kt
   │  ├─ ui/PreviewToolWindowFactory.kt
   │  ├─ ui/PreviewPanel.kt               # JBCefBrowser / image grid
   │  ├─ editor/DartPreviewEditorProvider.kt
   │  ├─ editor/PreviewLineMarkerProvider.kt
   │  └─ settings/PreviewSettings.kt
   └─ resources/
      ├─ META-INF/plugin.xml
      └─ templates/snapshot_test.dart.ft, harness_main.dart.ft
```

## 7. First milestone (definition of done)

Open a Flutter project in Android Studio and click the gutter icon next to a `@Preview` function. A "Flutter Preview" panel then shows the widget, and saving the file updates it within about 2 seconds.
