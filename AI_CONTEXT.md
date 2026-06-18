# Project Context — Basic Viewer (Android multi-format viewer/editor)

> Renamed from "md viewer" (v8). `app_name` = "Basic Viewer". Bundled **Poppins** font
> (`res/font/poppins_{regular,medium,semibold}.ttf` + `poppins.xml` family, OFL). Applied app-wide
> via `fontFamily` in both themes; toolbar titles use `TextAppearance.App.ToolbarTitle`
> (Poppins SemiBold); dashboard header uses poppins_semibold directly.
> **Launcher icon redesigned** (v8): adaptive icon — `drawable/ic_launcher_background.xml`
> (indigo gradient) + `drawable/ic_launcher_foreground.xml` (white dog-eared page + text lines
> + amber magnifier) + `drawable/ic_launcher_monochrome.xml` (themed-icon silhouette). Both
> `mipmap-anydpi-v26/ic_launcher{,_round}.xml` now reference the drawables (foreground was
> `@mipmap`) and add `<monochrome>`. Legacy density webp icons are unused (minSdk 34 → adaptive).


> Handoff document for an AI assistant. Read this first to understand the project,
> the decisions made, and the current state.

## What this app is

A **simple, intuitive Android app for viewing Markdown (.md) files**. It started as an
empty Android Studio template and was built into a single-screen viewer.

### Features / fixes (v10)
- **Recents survive "Open with"** — VIEW-intent URIs (e.g. Telegram) have a non-persistable,
  transient grant → broke on reopen ("Couldn't open this file"). `durableUriFor` now persists
  the permission when possible, else copies the file into `filesDir/recents/` and serves it via
  **FileProvider** (`${applicationId}.fileprovider`, `xml/file_paths.xml`). Recents store the
  durable URI; dropped/removed entries delete their local copy (`deleteLocalCopy`). For text,
  `currentUri` becomes the durable copy (edits persist); PDFs are launched with the durable URI.
- **Subfolder navigation in browser** — `BrowseActivity` rewritten from a flat recursive scan
  to folder-by-folder navigation: a `stack` of (documentId, label), folders listed first, tap a
  folder to descend, toolbar/back to go up (finishes at root). Breadcrumb in `folderPath`.

### Features (v9, implemented)
- **Rename file** — `MainActivity.renameTo` / `PdfActivity.renameTo` via
  `DocumentsContract.renameDocument` (needs write perm; some providers return a new URI, some
  rename in place returning null). Menu item `action_rename` (shown when a doc is open); updates
  title, recents, markdown mode. Fails gracefully (`error_rename`) for providers without rename.
- **In-app folder browser** — `BrowseActivity` lists supported files inside a user-picked SAF
  document tree (`OPEN_DOCUMENT_TREE`, persisted in `browse_tree` pref), scanned recursively
  (`MAX_DEPTH 6`, `MAX_RESULTS 1000`) on a background thread, filtered by `SupportedTypes`.
  Tap returns the file URI; `MainActivity.browseFiles` opens it (routes PDFs to PdfActivity).
  Entry points: dashboard `btnBrowse` button + `action_browse` menu. NOTE: scoped storage —
  whole-device scan needs MANAGE_EXTERNAL_STORAGE (avoided); folder-grant is the compliant path.
- **`SupportedTypes`** — central registry of openable extensions (text/code set + pdf).

### Fixes (v8.1)
- **Dashboard FAB created .txt** — the home FAB called `enterEditMode()` directly (empty
  `currentName` → `mimeForName("")` = text/plain). Now: on the dashboard the FAB calls
  `newFile()` (type chooser); while editing it saves; otherwise it edits the open doc.
- **`.md` shared from WhatsApp opened as plain text** — providers that don't return a
  `DISPLAY_NAME` made the name fall back to the app name (no extension). New `isMarkdownDoc(uri,
  name)` also checks the MIME (`text/markdown`/`text/x-markdown`) and the decoded URI path.

### Features (v8, implemented)
- **Choose type on "New file"** — `newFile()` shows a dialog of creatable types (`newTypes`:
  md/txt/html/css/js/json/xml/yaml/csv/py) → `startNewFile` sets the default name + markdown
  mode. The create launcher was switched from `CreateDocument(fixedMime)` to a manual
  `ACTION_CREATE_DOCUMENT` (`buildCreateIntent`) so the MIME varies by type (`mimeForName`).

### Features (v7, implemented)
- **Universal text/code editor** — `MainActivity` now opens *any* text file, not just Markdown.
  `isMarkdownName` decides rendering: `.md/.markdown` → Markwon; everything else → monospace
  raw text with lightweight syntax highlighting via `SyntaxHighlighter` (dependency-free,
  generic: comments/strings/numbers/keywords, comment style chosen by extension; theme-aware
  via `isNightMode`). `renderPreview(text, title, isMarkdown)` carries the mode; `currentName`
  / `currentIsMarkdown` track it for save/discard re-render. Picker uses `text/*` + common
  code MIMEs; manifest VIEW filters add `text/*` and many code/data extension pathPatterns.
- **PDF share picker** — when a signed copy exists, `PdfActivity.sharePdf` shows a dialog to
  choose **Signed copy** vs **Original**; otherwise shares the open document directly
  (`sourceUri`). `sharePdfUri` does the actual `ACTION_SEND`.

### Features (v6, implemented)
- **Zoom reworked** — `ZoomableLayout` now scales the child about its top-left and keeps only
  a *horizontal* translation; vertical scrolling stays with the RecyclerView so you can move
  between pages while zoomed. It only intercepts clearly-horizontal drags (axis + touch-slop
  detection) or active pinches, so vertical scroll no longer feels hijacked.
- **Signature sizing via slider** — pinch-on-overlay removed (imprecise); the sign bar has a
  `SeekBar` (`applySignatureSize`, 0–100 → minW..pageWidth). Overlay touch is drag-only.
  Initial size reduced to ~25%, density-aware minimum (~24dp) so it fits small signature lines.

### Features (v5, implemented)
- **PDF pinch-to-zoom + pan** — `ZoomableLayout` wraps the PDF `RecyclerView`: transparent to
  touch at 1x (normal scroll), intercepts drags to pan once zoomed (1x–5x). Pages render at
  `renderQuality = 2f` × screen width so zoom stays sharp (LruCache reduced to 4). Zoom is
  reset + disabled while signing (coordinate mapping assumes untransformed pages).
- **Signature minimum size lowered** — pinch min width 80px → 24px so it fits small fields.
- **Back navigation** — in `MainActivity`, Back now returns to the recents dashboard when a
  doc is open (mirrors PDF behavior) instead of closing the app; only exits from the dashboard.
  Added `showDashboard()`.

### Features (v4, implemented)
- **Recents dashboard** — `MainActivity` shows a recent-files list (`RecyclerView`,
  `item_recent.xml`) on the home screen when no document is open; tap to open, ✕ to remove.
  Hidden once a doc renders / on edit; refreshed in `onResume` (e.g. after returning from a PDF).
- **Share** — `action_share` in `MainActivity` shares the current md doc (`ACTION_SEND` with
  the content URI + read grant); `PdfActivity` shares the last *signed* PDF via a toolbar item
  shown only after a successful save (`pdf_menu.xml`).
- **Signature management** — `SignatureActivity` now previews the saved signature and offers
  **Delete**; reachable via "Manage signature" (`MainActivity`) and "Change signature"
  (`PdfActivity`).
- **Signature capture bug FIXED** — `SignaturePadView` previously tracked bounds with a
  `RectF` whose `isEmpty()` is true for zero-size rects, so `union` never ran and the export
  cropped to ~1px ("only some pixels"). Now uses explicit min/max float bounds.

### Features (v3, implemented)
- **PDF viewing** — `PdfActivity` renders pages with Android's native `PdfRenderer`
  (no WebView) into a vertical `RecyclerView` (`LruCache` of page bitmaps). The source URI
  is copied to a seekable cache file (`cacheDir/viewing.pdf`) because both PdfRenderer and
  PdfBox need random access. `MainActivity.openUri` detects PDFs (MIME `application/pdf` or
  `.pdf` name) and routes to `PdfActivity`; picker + VIEW intent-filters accept PDFs too.
- **Visual signing (not cryptographic)** — a reusable signature is drawn once on
  `SignaturePadView` (finger drawing → trimmed transparent PNG via `SignatureStore` in
  `filesDir/signature.png`, captured by `SignatureActivity`). In `PdfActivity`'s sign mode a
  draggable/pinch-scalable `ImageView` overlay positions the signature; on "Place" the
  overlay's screen rect is mapped to PDF points (Y-flipped for PdfBox's bottom-left origin)
  of whichever page its center overlaps, then **PdfBox-Android** (`com.tom-roush:pdfbox-android:2.0.27.0`)
  stamps the image and saves a **new** PDF via `CreateDocument` (Save as…). `PDFBoxResourceLoader.init`
  is called in `PdfActivity.onCreate`.
- New deps: `androidx.recyclerview:recyclerview:1.3.2`, `com.tom-roush:pdfbox-android:2.0.27.0`
  (both added as direct coordinates in [app/build.gradle.kts](app/build.gradle.kts), NOT the catalog).

### Features (v2, implemented)
- **Dark/Light theme toggle** — toolbar icon; preference persisted in SharedPreferences
  (`night_mode`) and applied in `onCreate` before `setContentView` via
  `AppCompatDelegate.setDefaultNightMode`.
- **Recent files** — last 5 opened files (URI + display name) stored in SharedPreferences,
  shown in an overflow submenu. SAF opens take a persistable read/write URI permission.
- **Markdown editing** — a FAB toggles between rendered preview and a raw monospace editor
  (`EditText`). FAB becomes a Save button while editing. Saving writes UTF-8 back to the
  source URI (`openOutputStream(uri, "wt")`); if there's no writable backing document (new
  file or write rejected) it falls back to **Save as…** via `CreateDocument`. "New file"
  starts a blank document in edit mode. Unsaved-change guard (AlertDialog) on back/discard.
- **Branded Material3 theme** — indigo primary palette with dedicated `values-night` colors,
  `CoordinatorLayout` + FAB layout.

### Features (v1, implemented)
- **Open files two ways:**
  1. In-app **"Open"** toolbar action → system file picker (Storage Access Framework).
  2. **"Open with"** from other apps (file managers, email, browsers) via `ACTION_VIEW`
     intent-filters for `.md` / `.markdown`.
- **Renders** headings, bold/italic, lists, links, blockquotes, code blocks, horizontal
  rules, **GitHub-style tables**, and **task lists** (`- [x]`).
- Rendered into a **native, selectable `TextView`** (no WebView) using the **Markwon** library.
- **Material3 DayNight** theme → adapts to light/dark automatically.
- Toolbar title shows the opened file's name; a Toast appears on read errors.

## Tech stack

- **Language:** Kotlin (no Compose — uses classic XML Views + AppCompat)
- **UI:** `MaterialToolbar` + `NestedScrollView` + `TextView`, Material3 theme
- **Markdown rendering:** [Markwon](https://github.com/noties/Markwon) `4.6.2`
  (`io.noties.markwon:core`, `:ext-tables`, `:ext-tasklist`)
- **Package / applicationId:** `com.example.mdviewer`
- **minSdk 34, targetSdk 34, compileSdk 34** (Android 14+)

## Build configuration — IMPORTANT GOTCHA

The Android Studio template was internally inconsistent: its version catalog pinned very new
androidx libraries (`core`/`core-ktx` 1.18.0, `activity` 1.13.0, `material` 1.13.0) that
require **compileSdk 36 + AGP 8.9.1 + Kotlin 2.1**, while the rest of the project used
**AGP 8.6.0 / compileSdk 34 / Kotlin 1.9.0**.

**The user's installed Android Studio only supports up to AGP 8.6.0.** So the fix was to
**pin the androidx dependencies DOWN** (not bump the toolchain up). Final compatible set in
[gradle/libs.versions.toml](gradle/libs.versions.toml):

| Item | Version |
|------|---------|
| AGP | 8.6.0 |
| Gradle wrapper | 8.7 |
| Kotlin | 1.9.0 |
| compileSdk / targetSdk / minSdk | 34 / 34 / 34 |
| androidx.core(-ktx) | 1.13.1 |
| androidx.appcompat | 1.7.0 |
| com.google.android.material | 1.12.0 |
| androidx.activity | 1.9.3 |
| androidx.constraintlayout | 2.1.4 |
| androidx.test.ext:junit / espresso-core | 1.2.1 / 3.6.1 |
| Markwon | 4.6.2 |

⚠️ **Do NOT bump androidx libs back to the 1.18.x/1.13.x line or raise AGP** unless the
user upgrades Android Studio — it breaks Gradle sync with the "incompatible AGP" error.

## Key files

- [app/src/main/java/com/example/mdviewer/MainActivity.kt](app/src/main/java/com/example/mdviewer/MainActivity.kt)
  — all logic: file picker launcher, `ACTION_VIEW` handling (`onCreate` + `onNewIntent`),
  UTF-8 file read via `contentResolver`, display-name lookup, Markwon rendering.
- [app/src/main/res/layout/activity_main.xml](app/src/main/res/layout/activity_main.xml)
  — toolbar + scrollable selectable TextView (root id `main`, used by edge-to-edge insets listener).
- [app/src/main/res/menu/main_menu.xml](app/src/main/res/menu/main_menu.xml) — "Open" action.
- [app/src/main/res/drawable/ic_open.xml](app/src/main/res/drawable/ic_open.xml) — folder icon.
- [app/src/main/AndroidManifest.xml](app/src/main/AndroidManifest.xml)
  — `launchMode="singleTop"` + `ACTION_VIEW` intent-filters (MIME-based and `.md`/`.markdown`
    pathPattern-based) so the app shows up in "Open with".
- [app/src/main/res/values/strings.xml](app/src/main/res/values/strings.xml)
  — `app_name`, `action_open`, `empty_hint`, `error_open`.
- [gradle/libs.versions.toml](gradle/libs.versions.toml) — version catalog (see table above).
- [app/build.gradle.kts](app/build.gradle.kts) — module config + Markwon deps.
- [sample.md](sample.md) — test document exercising every supported feature.

## Rendering details

A lazily-built singleton `Markwon` instance is configured with:
- `TablePlugin.create(this)` — pipe tables
- `TaskListPlugin.create(this)` — checkboxes
- `MovementMethodPlugin.create(TableAwareMovementMethod.create())` — clickable links +
  horizontally scrollable tables

`markwon.setMarkdown(markdownView, text)` renders into the TextView. No runtime storage
permission is needed — both the SAF picker and VIEW-intent URIs grant scoped read access.

## How to build / run

The machine has **no system Java**; use the JDK bundled with Android Studio:

```bash
# from the project root (Git Bash / PowerShell)
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat assembleDebug
```

Or just open the project in Android Studio → **Sync Project with Gradle Files** → **Run**.
APK output: `app/build/outputs/apk/debug/app-debug.apk`.

Installed Android SDK platforms: android-34, 35, 36. Build-tools: 33.0.1, 34.0.0, 35.0.0.

## Current state

✅ Builds successfully (`BUILD SUCCESSFUL`, APK ~13.8 MB).
✅ All v1 features implemented.
🔲 Not yet manually verified on a device/emulator (render correctness, "Open with" flow).

## Possible next steps (not yet done)

- Manual end-to-end test on an emulator/device using `sample.md`.
- Optional features deferred from v1: syntax-highlighted code, inline images, a
  recent-files list, and a raw/preview toggle.
