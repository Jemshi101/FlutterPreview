# Changelog

All notable changes to Flutter Preview. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and versions follow [Semantic Versioning](https://semver.org/).

## [0.6.1] - 2026-11-12

### Added
- Plugin icon, Marketplace description with images, and these change notes.
- A sample Flutter package (`sample/`) for trying the plugin.

### Changed
- Licensed under GPL-3.0, with an author-attribution term (see `NOTICE`).
- Vendor shown as Jemsheer K D.

### Fixed
- Cards, dialogs and other elevated widgets render with real shadows instead of a thin black outline.

## [0.6.0] - 2026-11-11

### Changed
- The setup hook moved to `.flutter_preview/setup.dart`. The first render in a package creates it from a commented template and adds `/.flutter_preview/` to the package's `.gitignore`. `test/flutter_preview_setup.dart` is no longer read.

### Added
- **Edit Preview Setup** toolbar action, and a one-time notification when the setup file is created.

## [0.5.0] - 2026-11-11

### Added
- **Generate Flutter Preview…** in the editor's right-click menu: adds a preview for the widget at the caret (its class, or a usage) to a new or existing file in `previews/`, reusing that file's `group:` and `wrapper:`.

### Fixed
- `size:`, `brightness:` and `textScaleFactor:` are evaluated in Dart, so constants such as `size: kPhoneSize` work. They were ignored before, and previews fell back to 400 × 800.
- Previews whose arguments use private names are flagged instead of breaking the whole file.

## [0.4.0] - 2026-11-11

### Changed
- New plugin ID `in.bleuphoenix.flutterpreview`. Uninstall older builds before installing.

## [0.3.3] - 2026-11-11

### Added
- **Open on GitHub** toolbar action and repository links.

## [0.3.2] - 2026-11-11

### Fixed
- While the code doesn't compile mid-edit, the last good previews stay visible with a red status, instead of the log taking over the panel.

## [0.3.1] - 2026-11-11

### Fixed
- Native plugin calls (for example `wakelock_plus`) no longer fail previews: calls that nothing answers get an empty success reply, and the status line lists what was stubbed.

## [0.3.0] - 2026-11-10

### Added
- All previews of a file render in one `flutter test` run and are cached, so selecting a preview is instant.
- Thumbnail grid view.
- Gutter icon on each `@Preview`, and the selection follows the caret.
- **Copy Image** and **Save Image As…**.
- **Create @Preview for this widget** intention (Alt+Enter).

## [0.2.0] - 2026-11-10

### Added
- Two-column layout, zoom in/out, 100 %, and fit all/width/height.

### Changed
- Repeating animations such as spinners are noted instead of being reported as errors, and settle faster.

## [0.1.1] - 2026-11-10

### Fixed
- Clicking a preview in Android Studio 2026.2 did nothing.

## [0.1.0] - 2026-11-10

### Added
- **Flutter Preview** tool window: lists the `@Preview` declarations of the focused Dart file and renders them headlessly with `flutter test`, without a browser. Re-renders on save, and shows layout problems and the test log.

[0.6.1]: https://github.com/Jemshi101/FlutterPreview/compare/v0.6.0...main
[0.6.0]: https://github.com/Jemshi101/FlutterPreview/releases/tag/v0.6.0
