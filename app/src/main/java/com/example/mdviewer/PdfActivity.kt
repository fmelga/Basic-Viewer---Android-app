package com.example.mdviewer

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.LruCache
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.io.File

class PdfActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var zoomLayout: ZoomableLayout
    private lateinit var signatureOverlay: ImageView
    private lateinit var signBar: View
    private lateinit var signSizeSeek: android.widget.SeekBar
    private lateinit var fabSign: FloatingActionButton

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var cacheFile: File? = null
    private var sourceName: String = "document.pdf"

    private var isSigning = false
    private var sourceUri: Uri? = null
    private var lastSavedUri: Uri? = null

    // Parameters captured at "apply" time, used after the Save-as picker returns.
    private data class PendingStamp(
        val pageIndex: Int,
        val pdfX: Float,
        val pdfY: Float,
        val widthPts: Float,
        val heightPts: Float
    )
    private var pendingStamp: PendingStamp? = null

    // Render pages above screen resolution so moderate zoom stays sharp.
    private val renderQuality = 2f

    private val bitmapCache = object : LruCache<Int, Bitmap>(4) {
        override fun sizeOf(key: Int, value: Bitmap): Int = 1
    }

    private val createSignature =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) enterSignMode()
        }

    private val createDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            val stamp = pendingStamp
            pendingStamp = null
            if (uri == null || stamp == null) return@registerForActivityResult
            if (applyStampAndSave(uri, stamp)) {
                lastSavedUri = uri
                invalidateOptionsMenu()
                Toast.makeText(this, R.string.pdf_signed, Toast.LENGTH_SHORT).show()
                exitSignMode()
            } else {
                Toast.makeText(this, R.string.error_save, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        setContentView(R.layout.activity_pdf)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        recycler = findViewById(R.id.pdfRecycler)
        zoomLayout = findViewById(R.id.zoomLayout)
        signatureOverlay = findViewById(R.id.signatureOverlay)
        signBar = findViewById(R.id.signBar)
        signSizeSeek = findViewById(R.id.signSizeSeek)
        fabSign = findViewById(R.id.fabSign)

        recycler.layoutManager = LinearLayoutManager(this)

        val uri = intent?.data
        if (uri == null || !openPdf(uri)) {
            Toast.makeText(this, R.string.error_open, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        sourceUri = uri

        supportActionBar?.title = sourceName
        recycler.adapter = PageAdapter()

        fabSign.setOnClickListener {
            if (isSigning) return@setOnClickListener
            if (SignatureStore.exists(this)) enterSignMode() else promptCreateSignature()
        }
        findViewById<MaterialButton>(R.id.btnCancelSign).setOnClickListener { exitSignMode() }
        findViewById<MaterialButton>(R.id.btnConfirmSign).setOnClickListener { confirmSignature() }

        setupOverlayTouch()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.pdf_menu, menu)
        // Share is available for the open document (or the signed copy once saved).
        menu.findItem(R.id.action_share)?.isVisible = (lastSavedUri ?: sourceUri) != null
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_share -> {
                sharePdf()
                true
            }
            R.id.action_rename -> {
                showRenameDialog()
                true
            }
            R.id.action_change_signature -> {
                createSignature.launch(Intent(this, SignatureActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showRenameDialog() {
        val uri = sourceUri ?: return
        val input = android.widget.EditText(this).apply {
            setText(sourceName)
            setSelection(0, sourceName.substringBeforeLast('.', sourceName).length)
            setSingleLine()
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0) }
        container.addView(input)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.rename_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != sourceName) renameTo(uri, newName)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun renameTo(uri: Uri, newName: String) {
        // External PDFs are copies in private storage (FileProvider) — rename the file itself.
        if (uri.authority == "$packageName.fileprovider") {
            renameLocalCopy(uri, newName)
            return
        }
        val newUri = try {
            android.provider.DocumentsContract.renameDocument(contentResolver, uri, newName)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_rename, Toast.LENGTH_LONG).show()
            return
        }
        sourceUri = newUri ?: uri
        sourceName = newName
        supportActionBar?.title = newName
        Toast.makeText(this, R.string.renamed, Toast.LENGTH_SHORT).show()
    }

    private fun renameLocalCopy(uri: Uri, newName: String) {
        val recentsDir = File(filesDir, "recents")
        val rel = uri.pathSegments.drop(1).joinToString("/")
        val oldFile = File(recentsDir, rel)
        val safe = newName.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_').ifBlank { "file" }
        val newFile = File(recentsDir, safe)
        if (oldFile.absolutePath != newFile.absolutePath) {
            if (!oldFile.exists()) {
                Toast.makeText(this, R.string.error_rename, Toast.LENGTH_LONG).show()
                return
            }
            if (newFile.exists()) newFile.delete()
            if (!oldFile.renameTo(newFile)) {
                Toast.makeText(this, R.string.error_rename, Toast.LENGTH_LONG).show()
                return
            }
        }
        val newUri = androidx.core.content.FileProvider.getUriForFile(
            this, "$packageName.fileprovider", newFile
        )
        updateRecentsEntry(uri, newUri, newName)
        sourceUri = newUri
        sourceName = newName
        supportActionBar?.title = newName
        Toast.makeText(this, R.string.renamed, Toast.LENGTH_SHORT).show()
    }

    /** Keeps the shared recents list (owned by MainActivity) in sync after a copy is renamed. */
    private fun updateRecentsEntry(oldUri: Uri, newUri: Uri, newName: String) {
        val prefs = getSharedPreferences("mdviewer_prefs", MODE_PRIVATE)
        val uris = prefs.getString("recent_uris", "")!!.split("\n").filter { it.isNotBlank() }.toMutableList()
        val names = prefs.getString("recent_names", "")!!.split("\n").filter { it.isNotBlank() }.toMutableList()
        val i = uris.indexOf(oldUri.toString())
        if (i >= 0 && i < names.size) {
            uris[i] = newUri.toString()
            names[i] = newName
            prefs.edit()
                .putString("recent_uris", uris.joinToString("\n"))
                .putString("recent_names", names.joinToString("\n"))
                .apply()
        }
    }

    private fun sharePdf() {
        val signed = lastSavedUri
        if (signed != null) {
            // Let the user pick which version to share.
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.share_which_title)
                .setItems(
                    arrayOf(getString(R.string.share_signed), getString(R.string.share_original))
                ) { _, which ->
                    val uri = if (which == 0) signed else sourceUri
                    uri?.let { sharePdfUri(it) }
                }
                .show()
        } else {
            sourceUri?.let { sharePdfUri(it) }
        }
    }

    private fun sharePdfUri(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_chooser)))
    }

    override fun onDestroy() {
        super.onDestroy()
        try { renderer?.close() } catch (_: Exception) {}
        try { fileDescriptor?.close() } catch (_: Exception) {}
    }

    // ── PDF loading / rendering ───────────────────────────────────────────────

    private fun openPdf(uri: Uri): Boolean {
        return try {
            sourceName = displayName(uri) ?: "document.pdf"
            // Copy to a seekable cache file: PdfRenderer & PdfBox both need random access.
            val file = File(cacheDir, "viewing.pdf")
            contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { out -> input.copyTo(out) }
            } ?: return false
            cacheFile = file
            fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(fileDescriptor!!)
            true
        } catch (e: Exception) {
            false
        }
    }

    @Synchronized
    private fun renderPage(index: Int): Bitmap? {
        bitmapCache.get(index)?.let { return it }
        val r = renderer ?: return null
        return try {
            r.openPage(index).use { page ->
                val targetWidth = (resources.displayMetrics.widthPixels * renderQuality).toInt()
                val scale = targetWidth.toFloat() / page.width
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(targetWidth, height, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmapCache.put(index, bmp)
                bmp
            }
        } catch (e: Exception) {
            null
        }
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
            val v = layoutInflater.inflate(R.layout.item_pdf_page, parent, false)
            return PageVH(v)
        }

        override fun getItemCount(): Int = renderer?.pageCount ?: 0

        override fun onBindViewHolder(holder: PageVH, position: Int) {
            holder.image.setImageBitmap(renderPage(position))
            holder.image.tag = position
        }
    }

    private inner class PageVH(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.pageImage)
    }

    // ── Sign mode ─────────────────────────────────────────────────────────────

    private fun promptCreateSignature() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.no_signature_title)
            .setMessage(R.string.no_signature_message)
            .setPositiveButton(R.string.action_create_signature) { _, _ ->
                createSignature.launch(Intent(this, SignatureActivity::class.java))
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun enterSignMode() {
        val sig = SignatureStore.load(this) ?: run {
            promptCreateSignature()
            return
        }
        // Coordinate mapping assumes 1x, untransformed pages.
        zoomLayout.reset()
        zoomLayout.zoomEnabled = false

        isSigning = true
        signatureOverlay.setImageBitmap(sig)

        sigAspect = sig.height.toFloat() / sig.width
        // Start small (~25%) so it's easy to size up rather than down.
        signSizeSeek.progress = 25
        applySignatureSize(25)
        signatureOverlay.translationX = (recycler.width - signatureOverlay.layoutParams.width) / 2f
        signatureOverlay.translationY = recycler.height / 3f
        signatureOverlay.visibility = View.VISIBLE

        signBar.visibility = View.VISIBLE
        fabSign.hide()
    }

    private var sigAspect = 0.4f

    /** Maps a 0–100 slider value to a signature width between [minSignW] and the page width. */
    private fun applySignatureSize(progress: Int) {
        val minW = (24 * resources.displayMetrics.density).toInt()
        val maxW = recycler.width.coerceAtLeast(minW + 1)
        val w = minW + (maxW - minW) * progress / 100
        val lp = signatureOverlay.layoutParams
        lp.width = w
        lp.height = (w * sigAspect).toInt().coerceAtLeast(1)
        signatureOverlay.layoutParams = lp
    }

    private fun exitSignMode() {
        isSigning = false
        signatureOverlay.visibility = View.GONE
        signBar.visibility = View.GONE
        fabSign.show()
        zoomLayout.zoomEnabled = true
    }

    private var dragStartX = 0f
    private var dragStartY = 0f
    private var startTransX = 0f
    private var startTransY = 0f

    private fun setupOverlayTouch() {
        // Drag to position; sizing is handled by the slider for precision.
        signatureOverlay.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = event.rawX
                    dragStartY = event.rawY
                    startTransX = signatureOverlay.translationX
                    startTransY = signatureOverlay.translationY
                }
                MotionEvent.ACTION_MOVE -> {
                    signatureOverlay.translationX = startTransX + (event.rawX - dragStartX)
                    signatureOverlay.translationY = startTransY + (event.rawY - dragStartY)
                }
            }
            true
        }

        signSizeSeek.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                    if (isSigning) applySignatureSize(p)
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
            }
        )
    }

    private fun confirmSignature() {
        val stamp = computeStamp()
        if (stamp == null) {
            Toast.makeText(this, R.string.sign_place_on_page, Toast.LENGTH_LONG).show()
            return
        }
        pendingStamp = stamp
        val base = sourceName.substringBeforeLast('.', sourceName)
        createDocument.launch("$base-signed.pdf")
    }

    /**
     * Maps the on-screen signature overlay to PDF coordinates of whichever page
     * its center currently overlaps. Returns null if it isn't over any page.
     */
    private fun computeStamp(): PendingStamp? {
        val sigLoc = IntArray(2)
        signatureOverlay.getLocationInWindow(sigLoc)
        val sigLeft = sigLoc[0]
        val sigTop = sigLoc[1]
        val sigW = signatureOverlay.width
        val sigH = signatureOverlay.height
        val sigCenterX = sigLeft + sigW / 2
        val sigCenterY = sigTop + sigH / 2

        val lm = recycler.layoutManager as LinearLayoutManager
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return null

        for (pos in first..last) {
            val holder = recycler.findViewHolderForAdapterPosition(pos) ?: continue
            val img = holder.itemView.findViewById<ImageView>(R.id.pageImage)
            val pageLoc = IntArray(2)
            img.getLocationInWindow(pageLoc)
            val pageLeft = pageLoc[0]
            val pageTop = pageLoc[1]
            val pageW = img.width
            val pageH = img.height
            if (pageW == 0 || pageH == 0) continue

            val withinX = sigCenterX in pageLeft..(pageLeft + pageW)
            val withinY = sigCenterY in pageTop..(pageTop + pageH)
            if (!withinX || !withinY) continue

            val pagePts = pagePointSize(pos) ?: continue
            val (ptsW, ptsH) = pagePts

            // Displayed-pixels → page-points scale.
            val sx = ptsW / pageW
            val sy = ptsH / pageH

            val relLeft = (sigLeft - pageLeft).toFloat()
            val relTop = (sigTop - pageTop).toFloat()

            val stampW = sigW * sx
            val stampH = sigH * sy
            val pdfX = relLeft * sx
            // PdfBox origin is bottom-left; image is drawn from its bottom-left.
            val pdfY = ptsH - (relTop * sy) - stampH

            return PendingStamp(pos, pdfX, pdfY, stampW, stampH)
        }
        return null
    }

    private fun pagePointSize(index: Int): Pair<Float, Float>? {
        val r = renderer ?: return null
        return try {
            synchronized(this) {
                r.openPage(index).use { p -> p.width.toFloat() to p.height.toFloat() }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun applyStampAndSave(outUri: Uri, stamp: PendingStamp): Boolean {
        val src = cacheFile ?: return false
        val sig = SignatureStore.load(this) ?: return false
        return try {
            PDDocument.load(src).use { doc ->
                val page = doc.getPage(stamp.pageIndex)
                val image = LosslessFactory.createFromImage(doc, sig)
                PDPageContentStream(
                    doc, page, PDPageContentStream.AppendMode.APPEND, true, true
                ).use { cs ->
                    cs.drawImage(image, stamp.pdfX, stamp.pdfY, stamp.widthPts, stamp.heightPts)
                }
                contentResolver.openOutputStream(outUri)?.use { out -> doc.save(out) }
                    ?: return false
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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
