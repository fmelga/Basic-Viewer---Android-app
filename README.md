# Basic Viewer

A simple, fast Android app for **viewing, editing and creating** many file types — with
native rendering (no WebView) and PDF signing.

## Features

- **Markdown** rendering (headings, tables, task lists, code, links…) via
  [Markwon](https://github.com/noties/Markwon).
- **Universal text/code editor** — open, edit and create `.txt`, `.json`, `.xml`, `.yaml`,
  `.html`, `.css`, `.js`, `.kt`, `.java`, `.py`, and more, with lightweight syntax highlighting.
- **PDF viewing** with pinch-to-zoom (native `PdfRenderer`).
- **PDF signing** — draw a reusable signature once, then place/scale it on a page and save a
  new signed PDF ([PdfBox-Android](https://github.com/TomRoush/PdfBox-Android)).
- **Recent files** dashboard, **in-app folder browser** (SAF), **rename**, and **share**.
- **Material 3** light/dark theme with a manual toggle, Poppins typography, custom adaptive icon.

## Tech

- Kotlin, classic XML Views + AppCompat (no Compose)
- minSdk / targetSdk / compileSdk 34
- AGP 8.6.0 · Gradle 8.7 · Kotlin 1.9.0

## Build

The build needs a JDK 17 (e.g. the one bundled with Android Studio):

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`. Or open the project in Android Studio
and Run.

## License

Bundled font: Poppins (SIL Open Font License).
