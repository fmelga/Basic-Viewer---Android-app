# Roadmap — Basic Viewer

A living plan for the app. Items are grouped by status and rough priority. Not a commitment;
order may change.

## ✅ Done

- Markdown rendering (tables, task lists, code, links) via Markwon.
- Universal text/code editor (open · edit · create) with lightweight syntax highlighting.
- PDF viewing (native `PdfRenderer`) with pinch-to-zoom and pan.
- PDF visual signing: reusable hand-drawn signature, place/scale, save signed copy.
- Recent files dashboard, in-app folder browser with subfolder navigation (SAF).
- Rename (incl. internally-copied external files), share (with original/signed choice).
- Robust handling of "Open with" files (copied to private storage so recents keep working).
- Copy-all-text to clipboard.
- Material 3 light/dark with manual toggle, Poppins font, custom adaptive icon.

## 🔜 Next (short term)

- **CSV/TSV as a table** — render in a scrollable grid, not just monospace text.
- **Image viewer** — `.png/.jpg/.webp/.gif` with zoom (reuse `ZoomableLayout`).
- **Per-language syntax highlighting** — improve accuracy beyond the generic tokenizer.
- **Find in file** — search within the open document (text and PDF).
- **Copy text from PDFs** — extract via PdfBox `PDFTextStripper` where the PDF has a text layer.

## 🧭 Medium term

- **HTML preview** — render `.html` (WebView) alongside the source editor.
- **Markdown math** — render LaTeX formulas in Markdown via Markwon `ext-latex`.
- **Multiple browse folders / favorites** — remember several granted folders.
- **Editor niceties** — line numbers, undo/redo, auto-indent, wrap toggle.
- **PDF signing polish** — handle rotated pages, multiple signatures per document, date stamp.
- **Background rendering** — render large PDFs / highlight large files off the main thread.

## 🌅 Longer term / ideas

- **Digital (cryptographic) PDF signatures** — certificate-based, not just visual.
- **EPUB reader** and **Jupyter notebook (.ipynb)** rendering.
- **Cloud/Drive integration** via SAF providers.
- **Split view** — source + preview side by side (tablets / landscape).
- **App widget / quick tile** for the most recent file.
- **Localization** (currently English strings) — start with Spanish.

## 🧹 Tech debt

- Unit/instrumented tests (currently only template stubs).
- Centralize the recents store (shared between `MainActivity` and `PdfActivity`).
- CI: GitHub Actions to build the debug APK on each push/PR.
