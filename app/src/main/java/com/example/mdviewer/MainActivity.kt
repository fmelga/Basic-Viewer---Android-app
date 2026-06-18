package com.example.mdviewer

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.FrameLayout
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.File
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TableAwareMovementMethod
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.movement.MovementMethodPlugin

private const val PREFS_NAME = "mdviewer_prefs"
private const val PREF_NIGHT_MODE = "night_mode"
private const val PREF_RECENT_URIS = "recent_uris"
private const val PREF_RECENT_NAMES = "recent_names"
private const val RECENT_MAX = 5

class MainActivity : AppCompatActivity() {

    private lateinit var markdownView: TextView
    private lateinit var markdownEditor: EditText
    private lateinit var previewScroll: NestedScrollView
    private lateinit var editScroll: NestedScrollView
    private lateinit var fab: FloatingActionButton
    private lateinit var dashboard: View
    private lateinit var recentRecycler: RecyclerView
    private lateinit var recentEmpty: TextView
    private val recentsAdapter = RecentsAdapter()

    private var currentMenu: Menu? = null
    private var currentUri: Uri? = null
    private var currentText: String = ""
    private var currentName: String = ""
    private var currentIsMarkdown: Boolean = true
    private var isEditing: Boolean = false

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    private val markwon: Markwon by lazy {
        Markwon.builder(this)
            .usePlugin(TablePlugin.create(this))
            .usePlugin(TaskListPlugin.create(this))
            .usePlugin(MovementMethodPlugin.create(TableAwareMovementMethod.create()))
            .build()
    }

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                // Take read (and, where granted, write) permission for future reopen/save.
                try {
                    contentResolver.takePersistableUriPermission(
                        it,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: SecurityException) {
                    try {
                        contentResolver.takePersistableUriPermission(
                            it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: SecurityException) { }
                }
                openUri(it)
            }
        }

    // "Save as…" / create — we drive ACTION_CREATE_DOCUMENT manually so the MIME type can
    // vary per chosen file type (CreateDocument fixes the MIME at registration time).
    private val createDocument =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val uri = result.data?.data ?: return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) { }
            val text = markdownEditor.text.toString()
            if (writeToUri(uri, text)) {
                currentUri = uri
                onSaved(text, displayName(uri) ?: currentName)
            } else {
                Toast.makeText(this, R.string.error_save, Toast.LENGTH_LONG).show()
            }
        }

    // In-app folder browser: returns a chosen file URI to open.
    private val browseFiles =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            result.data?.data?.let { openUri(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        val savedMode = prefs.getInt(PREF_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(savedMode)

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        markdownView = findViewById(R.id.markdownView)
        markdownEditor = findViewById(R.id.markdownEditor)
        previewScroll = findViewById(R.id.scrollView)
        editScroll = findViewById(R.id.editScrollView)
        fab = findViewById(R.id.fab)
        dashboard = findViewById(R.id.dashboard)
        recentRecycler = findViewById(R.id.recentRecycler)
        recentEmpty = findViewById(R.id.recentEmpty)

        recentRecycler.layoutManager = LinearLayoutManager(this)
        recentRecycler.adapter = recentsAdapter
        populateRecents()

        findViewById<View>(R.id.btnBrowse).setOnClickListener {
            browseFiles.launch(Intent(this, BrowseActivity::class.java))
        }

        fab.setOnClickListener {
            when {
                isEditing -> saveCurrent()
                // On the dashboard (no document open) the FAB creates a new file.
                dashboard.visibility == View.VISIBLE -> newFile()
                else -> enterEditMode()
            }
        }

        onBackPressedDispatcher.addCallback(this) {
            when {
                isEditing -> confirmDiscardIfNeeded { exitEditMode(reRender = true) }
                dashboard.visibility != View.VISIBLE -> showDashboard()
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }

        handleViewIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Recents may have changed (e.g. after opening a PDF); refresh the dashboard.
        if (dashboard.visibility == View.VISIBLE) populateRecents()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        currentMenu = menu
        refreshRecentMenu(menu)
        refreshEditingMenu(menu)
        refreshShareMenu(menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_open -> {
                openDocument.launch(
                    arrayOf(
                        "text/*",
                        "application/pdf",
                        "application/json",
                        "application/xml",
                        "application/javascript",
                        "application/x-yaml",
                        "application/octet-stream",
                        "*/*"
                    )
                )
                true
            }
            R.id.action_new -> {
                confirmDiscardIfNeeded { newFile() }
                true
            }
            R.id.action_share -> {
                shareCurrent()
                true
            }
            R.id.action_copy_all -> {
                copyAllContent()
                true
            }
            R.id.action_rename -> {
                showRenameDialog()
                true
            }
            R.id.action_browse -> {
                browseFiles.launch(Intent(this, BrowseActivity::class.java))
                true
            }
            R.id.action_discard -> {
                confirmDiscardIfNeeded { exitEditMode(reRender = true) }
                true
            }
            R.id.action_toggle_theme -> {
                val current = prefs.getInt(PREF_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                val next = if (current == AppCompatDelegate.MODE_NIGHT_YES)
                    AppCompatDelegate.MODE_NIGHT_NO
                else
                    AppCompatDelegate.MODE_NIGHT_YES
                prefs.edit().putInt(PREF_NIGHT_MODE, next).apply()
                AppCompatDelegate.setDefaultNightMode(next)
                true
            }
            R.id.recent_0, R.id.recent_1, R.id.recent_2, R.id.recent_3, R.id.recent_4 -> {
                val index = listOf(
                    R.id.recent_0, R.id.recent_1, R.id.recent_2, R.id.recent_3, R.id.recent_4
                ).indexOf(item.itemId)
                val uris = loadRecentUris()
                if (index < uris.size) openUri(uris[index])
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { openUri(it) }
        }
    }

    // ── Open / render ─────────────────────────────────────────────────────────

    private fun openUri(uri: Uri) {
        if (isPdf(uri)) {
            val name = displayName(uri) ?: "document.pdf"
            val durable = durableUriFor(uri, name)
            addToRecents(durable, name)
            currentMenu?.let { refreshRecentMenu(it) }
            startActivity(
                Intent(this, PdfActivity::class.java).apply {
                    data = durable
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
            return
        }

        val text = try {
            contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader(Charsets.UTF_8).readText()
            }
        } catch (e: Exception) {
            null
        }

        if (text == null) {
            Toast.makeText(this, R.string.error_open, Toast.LENGTH_LONG).show()
            return
        }

        val name = displayName(uri) ?: getString(R.string.app_name)
        val durable = durableUriFor(uri, name)
        currentUri = durable
        if (isEditing) exitEditMode(reRender = false)
        renderPreview(text, name, isMarkdownDoc(durable, name))
        addToRecents(durable, name)
        currentMenu?.let { refreshRecentMenu(it) }
    }

    private fun renderPreview(text: String, title: String, isMarkdown: Boolean) {
        currentText = text
        currentName = title
        currentIsMarkdown = isMarkdown
        supportActionBar?.title = title
        if (isMarkdown) {
            markdownView.typeface = Typeface.DEFAULT
            markwon.setMarkdown(markdownView, text)
        } else {
            // Plain text / code: monospace, with light syntax highlighting where applicable.
            markdownView.typeface = Typeface.MONOSPACE
            markdownView.text = SyntaxHighlighter.highlight(text, title, isNightMode())
        }
        dashboard.visibility = View.GONE
        previewScroll.visibility = View.VISIBLE
        currentMenu?.let { refreshShareMenu(it) }
    }

    private fun isMarkdownName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".md") || lower.endsWith(".markdown")
    }

    /**
     * Robust markdown detection: some providers (e.g. WhatsApp) don't return a usable
     * display name, so fall back to the MIME type and the URI path.
     */
    private fun isMarkdownDoc(uri: Uri, name: String): Boolean {
        when (contentResolver.getType(uri)) {
            "text/markdown", "text/x-markdown" -> return true
        }
        if (isMarkdownName(name)) return true
        val path = Uri.decode(uri.toString()).lowercase()
        return path.endsWith(".md") || path.endsWith(".markdown") ||
            path.contains(".md?") || path.contains(".markdown?")
    }

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    // ── Edit mode ───────────────────────────────────────────────────────────

    private fun enterEditMode() {
        isEditing = true
        markdownEditor.setText(currentText)
        dashboard.visibility = View.GONE
        previewScroll.visibility = android.view.View.GONE
        editScroll.visibility = android.view.View.VISIBLE
        fab.setImageResource(R.drawable.ic_save)
        fab.contentDescription = getString(R.string.action_save)
        markdownEditor.requestFocus()
        currentMenu?.let { refreshEditingMenu(it) }
    }

    private fun exitEditMode(reRender: Boolean) {
        isEditing = false
        editScroll.visibility = android.view.View.GONE
        previewScroll.visibility = android.view.View.VISIBLE
        fab.setImageResource(R.drawable.ic_edit)
        fab.contentDescription = getString(R.string.action_edit)
        if (reRender) renderPreview(currentText, currentName, currentIsMarkdown)
        currentMenu?.let { refreshEditingMenu(it) }
    }

    private fun saveCurrent() {
        val text = markdownEditor.text.toString()
        val uri = currentUri
        if (uri != null && writeToUri(uri, text)) {
            onSaved(text, supportActionBar?.title?.toString() ?: getString(R.string.app_name))
        } else {
            // No backing document (new file) or write was rejected → Save as…
            val name = currentUri?.let { displayName(it) } ?: currentName
            createDocument.launch(buildCreateIntent(mimeForName(name), name))
        }
    }

    private fun buildCreateIntent(mime: String, suggestedName: String): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, suggestedName)
        }

    private fun onSaved(text: String, title: String) {
        renderPreview(text, title, isMarkdownName(title))
        exitEditMode(reRender = false)
        currentUri?.let { addToRecents(it, title); currentMenu?.let { m -> refreshRecentMenu(m) } }
        currentMenu?.let { refreshShareMenu(it) }
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
    }

    /** File types the app can create. label → (default name, MIME). */
    private data class NewType(val label: String, val fileName: String, val mime: String)

    private val newTypes by lazy {
        listOf(
            NewType("Markdown (.md)", "untitled.md", "text/markdown"),
            NewType("Plain text (.txt)", "untitled.txt", "text/plain"),
            NewType("HTML (.html)", "untitled.html", "text/html"),
            NewType("CSS (.css)", "untitled.css", "text/css"),
            NewType("JavaScript (.js)", "untitled.js", "text/javascript"),
            NewType("JSON (.json)", "untitled.json", "application/json"),
            NewType("XML (.xml)", "untitled.xml", "text/xml"),
            NewType("YAML (.yaml)", "untitled.yaml", "application/x-yaml"),
            NewType("CSV (.csv)", "untitled.csv", "text/csv"),
            NewType("Python (.py)", "untitled.py", "text/x-python")
        )
    }

    private fun newFile() {
        AlertDialog.Builder(this)
            .setTitle(R.string.new_file_title)
            .setItems(newTypes.map { it.label }.toTypedArray()) { _, which ->
                startNewFile(newTypes[which])
            }
            .show()
    }

    private fun startNewFile(type: NewType) {
        currentUri = null
        currentText = ""
        currentName = type.fileName
        currentIsMarkdown = isMarkdownName(type.fileName)
        supportActionBar?.title = currentName
        enterEditMode()
    }

    private fun mimeForName(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "md", "markdown" -> "text/markdown"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js" -> "text/javascript"
            "json" -> "application/json"
            "xml" -> "text/xml"
            "yaml", "yml" -> "application/x-yaml"
            "csv" -> "text/csv"
            "py" -> "text/x-python"
            else -> "text/plain"
        }
    }

    private fun writeToUri(uri: Uri, text: String): Boolean {
        return try {
            // "wt" truncates existing content before writing.
            contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            } ?: return false
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun hasUnsavedChanges(): Boolean =
        isEditing && markdownEditor.text.toString() != currentText

    private fun confirmDiscardIfNeeded(onProceed: () -> Unit) {
        if (!hasUnsavedChanges()) {
            onProceed()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.discard_title)
            .setMessage(R.string.discard_message)
            .setPositiveButton(R.string.action_discard) { _, _ -> onProceed() }
            .setNegativeButton(R.string.keep_editing, null)
            .show()
    }

    // ── Durable references for recents ────────────────────────────────────────

    private val fileProviderAuthority by lazy { "$packageName.fileprovider" }
    private val recentsDir by lazy { File(filesDir, "recents").apply { mkdirs() } }

    /**
     * Returns a URI that will still be readable later. SAF/picker URIs get a persisted
     * permission; transient "Open with" URIs (no persistable grant) are copied into private
     * storage and served via FileProvider, so recents keep working after the source app closes.
     */
    private fun durableUriFor(uri: Uri, name: String): Uri {
        // Already one of our internal copies — reuse it. Re-copying would open the same
        // file for read and write at once, truncating it to empty.
        if (uri.authority == fileProviderAuthority) return uri
        if (uri.scheme == "content" && uri.authority != fileProviderAuthority) {
            val alreadyPersisted = contentResolver.persistedUriPermissions
                .any { it.uri == uri && it.isReadPermission }
            if (alreadyPersisted) return uri
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                return uri
            } catch (_: SecurityException) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    return uri
                } catch (_: SecurityException) { }
            }
        }
        return copyToRecentsStore(uri, name) ?: uri
    }

    private fun copyToRecentsStore(uri: Uri, name: String): Uri? {
        return try {
            val file = File(recentsDir, sanitizeFileName(name))
            contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { input.copyTo(it) }
            } ?: return null
            FileProvider.getUriForFile(this, fileProviderAuthority, file)
        } catch (e: Exception) {
            null
        }
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_')
        return cleaned.ifBlank { "file" }
    }

    private fun deleteLocalCopy(uri: Uri) {
        if (uri.authority != fileProviderAuthority) return
        try {
            val rel = uri.pathSegments.drop(1).joinToString("/")
            if (rel.isNotBlank()) File(recentsDir, rel).delete()
        } catch (_: Exception) { }
    }

    // ── Recent files ────────────────────────────────────────────────────────

    private fun loadRecentUris(): List<Uri> {
        val raw = prefs.getString(PREF_RECENT_URIS, null) ?: return emptyList()
        return raw.split("\n").filter { it.isNotBlank() }.map { Uri.parse(it) }
    }

    private fun loadRecentNames(): List<String> {
        val raw = prefs.getString(PREF_RECENT_NAMES, null) ?: return emptyList()
        return raw.split("\n").filter { it.isNotBlank() }
    }

    private fun addToRecents(uri: Uri, name: String) {
        val uris = loadRecentUris().toMutableList()
        val names = loadRecentNames().toMutableList()

        val existing = uris.indexOfFirst { it == uri }
        if (existing >= 0) {
            uris.removeAt(existing)
            names.removeAt(existing)
        }

        uris.add(0, uri)
        names.add(0, name)

        if (uris.size > RECENT_MAX) {
            val dropped = uris.subList(RECENT_MAX, uris.size).toList()
            uris.subList(RECENT_MAX, uris.size).clear()
            names.subList(RECENT_MAX, names.size).clear()
            dropped.forEach { deleteLocalCopy(it) }
        }

        prefs.edit()
            .putString(PREF_RECENT_URIS, uris.joinToString("\n") { it.toString() })
            .putString(PREF_RECENT_NAMES, names.joinToString("\n"))
            .apply()
    }

    private fun refreshRecentMenu(menu: Menu) {
        val recentItem = menu.findItem(R.id.action_recent) ?: return
        val names = loadRecentNames()
        val ids = listOf(R.id.recent_0, R.id.recent_1, R.id.recent_2, R.id.recent_3, R.id.recent_4)
        val sub = recentItem.subMenu ?: return

        recentItem.isVisible = names.isNotEmpty()
        ids.forEachIndexed { i, id ->
            sub.findItem(id)?.apply {
                isVisible = i < names.size
                title = names.getOrNull(i) ?: ""
            }
        }
    }

    private fun refreshEditingMenu(menu: Menu) {
        menu.findItem(R.id.action_discard)?.isVisible = isEditing
    }

    private fun refreshShareMenu(menu: Menu) {
        val hasDoc = currentUri != null
        menu.findItem(R.id.action_share)?.isVisible = hasDoc
        menu.findItem(R.id.action_rename)?.isVisible = hasDoc
        // Copy is for text/markdown content (the preview), not the binary PDF flow.
        menu.findItem(R.id.action_copy_all)?.isVisible = hasDoc
    }

    private fun copyAllContent() {
        val content = if (isEditing) markdownEditor.text.toString() else currentText
        if (content.isEmpty()) return
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(currentName, content))
        Toast.makeText(this, R.string.copied_all, Toast.LENGTH_SHORT).show()
    }

    // ── Rename ────────────────────────────────────────────────────────────────

    private fun showRenameDialog() {
        val uri = currentUri ?: return
        val input = EditText(this).apply {
            setText(currentName)
            setSelection(0, currentName.substringBeforeLast('.', currentName).length)
            setSingleLine()
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0) }
        container.addView(input)

        AlertDialog.Builder(this)
            .setTitle(R.string.rename_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != currentName) renameTo(uri, newName)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun renameTo(uri: Uri, newName: String) {
        // Files we copied into private storage aren't SAF documents, so rename the file itself.
        if (uri.authority == fileProviderAuthority) {
            renameLocalCopy(uri, newName)
            return
        }

        val newUri = try {
            DocumentsContract.renameDocument(contentResolver, uri, newName)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_rename, Toast.LENGTH_LONG).show()
            return
        }

        // Some providers rename in place and return null (the original URI still valid).
        val effectiveUri = newUri ?: uri
        if (newUri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    newUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) { }
            removeRecent(uri)
        }
        applyRename(effectiveUri, newName)
    }

    private fun renameLocalCopy(uri: Uri, newName: String) {
        val rel = uri.pathSegments.drop(1).joinToString("/")
        val oldFile = File(recentsDir, rel)
        val newFile = File(recentsDir, sanitizeFileName(newName))
        if (oldFile.absolutePath == newFile.absolutePath) {
            applyRename(uri, newName)
            return
        }
        if (!oldFile.exists()) {
            Toast.makeText(this, R.string.error_rename, Toast.LENGTH_LONG).show()
            return
        }
        if (newFile.exists()) newFile.delete()
        if (!oldFile.renameTo(newFile)) {
            Toast.makeText(this, R.string.error_rename, Toast.LENGTH_LONG).show()
            return
        }
        removeRecent(uri) // old file already moved, so its deleteLocalCopy is a no-op
        applyRename(FileProvider.getUriForFile(this, fileProviderAuthority, newFile), newName)
    }

    private fun applyRename(newUri: Uri, newName: String) {
        currentUri = newUri
        currentName = newName
        currentIsMarkdown = isMarkdownDoc(newUri, newName)
        supportActionBar?.title = newName
        addToRecents(newUri, newName)
        if (!isEditing) renderPreview(currentText, newName, currentIsMarkdown)
        currentMenu?.let { refreshRecentMenu(it); refreshShareMenu(it) }
        Toast.makeText(this, R.string.renamed, Toast.LENGTH_SHORT).show()
    }

    // ── Dashboard (recents) ───────────────────────────────────────────────────

    private fun showDashboard() {
        currentUri = null
        currentText = ""
        previewScroll.visibility = View.GONE
        editScroll.visibility = View.GONE
        dashboard.visibility = View.VISIBLE
        supportActionBar?.title = getString(R.string.app_name)
        populateRecents()
        currentMenu?.let { refreshShareMenu(it) }
    }

    private fun populateRecents() {
        val uris = loadRecentUris()
        val names = loadRecentNames()
        val items = uris.indices
            .filter { it < names.size }
            .map { RecentItem(uris[it], names[it]) }
        recentsAdapter.submit(items)
        recentEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun removeRecent(uri: Uri) {
        val uris = loadRecentUris().toMutableList()
        val names = loadRecentNames().toMutableList()
        val index = uris.indexOfFirst { it == uri }
        if (index >= 0) {
            uris.removeAt(index)
            names.removeAt(index)
            prefs.edit()
                .putString(PREF_RECENT_URIS, uris.joinToString("\n") { it.toString() })
                .putString(PREF_RECENT_NAMES, names.joinToString("\n"))
                .apply()
            deleteLocalCopy(uri)
        }
        populateRecents()
        currentMenu?.let { refreshRecentMenu(it) }
    }

    private data class RecentItem(val uri: Uri, val name: String)

    private inner class RecentsAdapter : RecyclerView.Adapter<RecentVH>() {
        private val items = mutableListOf<RecentItem>()

        fun submit(newItems: List<RecentItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecentVH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_recent, parent, false)
            return RecentVH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: RecentVH, position: Int) {
            val item = items[position]
            holder.name.text = item.name
            holder.itemView.setOnClickListener { openUri(item.uri) }
            holder.remove.setOnClickListener { removeRecent(item.uri) }
        }
    }

    private inner class RecentVH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.recentName)
        val remove: ImageView = view.findViewById(R.id.recentRemove)
    }

    // ── Share ─────────────────────────────────────────────────────────────────

    private fun shareCurrent() {
        val uri = currentUri ?: run {
            Toast.makeText(this, R.string.nothing_to_share, Toast.LENGTH_SHORT).show()
            return
        }
        val mime = contentResolver.getType(uri) ?: "text/markdown"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_chooser)))
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun isPdf(uri: Uri): Boolean {
        if (contentResolver.getType(uri) == "application/pdf") return true
        return displayName(uri)?.endsWith(".pdf", ignoreCase = true) == true
    }

    private fun displayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) cursor.getString(index) else null
                    } else null
                }
        } catch (e: Exception) {
            null
        }
    }
}
