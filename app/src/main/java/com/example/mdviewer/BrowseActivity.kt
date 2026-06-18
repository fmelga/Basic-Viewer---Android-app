package com.example.mdviewer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlin.concurrent.thread

private const val BROWSE_PREFS = "mdviewer_prefs"
private const val PREF_BROWSE_TREE = "browse_tree"

/**
 * Navigates a user-chosen SAF document tree folder by folder, listing subfolders and the
 * app's supported files. Scoped storage forbids scanning the whole device, so the user grants
 * one folder (remembered between sessions).
 */
class BrowseActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var status: TextView
    private lateinit var folderPath: TextView
    private val adapter = EntryAdapter()

    private val prefs by lazy { getSharedPreferences(BROWSE_PREFS, MODE_PRIVATE) }

    private var treeUri: Uri? = null
    // Stack of folders we've descended into: (documentId, displayName).
    private val stack = ArrayList<Pair<String, String>>()

    private val pickTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) {
                if (treeUri == null) showStatus(getString(R.string.browse_hint))
                return@registerForActivityResult
            }
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) { }
            prefs.edit().putString(PREF_BROWSE_TREE, uri.toString()).apply()
            openTree(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browse)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { goUpOrFinish() }

        recycler = findViewById(R.id.fileRecycler)
        status = findViewById(R.id.browseStatus)
        folderPath = findViewById(R.id.folderPath)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        findViewById<MaterialButton>(R.id.btnChooseFolder).setOnClickListener { pickTree.launch(null) }

        onBackPressedDispatcher.addCallback(this) { goUpOrFinish() }

        val saved = prefs.getString(PREF_BROWSE_TREE, null)
        if (saved != null) openTree(Uri.parse(saved)) else pickTree.launch(null)
    }

    private fun openTree(uri: Uri) {
        treeUri = uri
        stack.clear()
        val rootId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (e: Exception) {
            showStatus(getString(R.string.browse_hint)); return
        }
        stack.add(rootId to treeLabel(uri))
        loadCurrent()
    }

    private fun goUpOrFinish() {
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            loadCurrent()
        } else {
            finish()
        }
    }

    private fun loadCurrent() {
        val tree = treeUri ?: return
        val (docId, _) = stack.last()
        folderPath.text = stack.joinToString("  ›  ") { it.second }
        showStatus(getString(R.string.browse_scanning))
        adapter.submit(emptyList())
        thread {
            val out = ArrayList<Entry>()
            try {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
                contentResolver.query(
                    children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    ),
                    null, null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            out.add(Entry(name, isDir = true, docId = id, fileUri = null))
                        } else if (SupportedTypes.isSupported(name)) {
                            val fileUri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                            out.add(Entry(name, isDir = false, docId = id, fileUri = fileUri))
                        }
                    }
                }
            } catch (_: Exception) { }
            // Folders first, then files; alphabetical within each.
            out.sortWith(compareByDescending<Entry> { it.isDir }.thenBy { it.name.lowercase() })
            runOnUiThread {
                adapter.submit(out)
                if (out.isEmpty()) showStatus(getString(R.string.browse_empty)) else hideStatus()
            }
        }
    }

    private fun onEntryClick(entry: Entry) {
        if (entry.isDir) {
            stack.add(entry.docId to entry.name)
            loadCurrent()
        } else {
            entry.fileUri?.let {
                setResult(RESULT_OK, Intent().setData(it))
                finish()
            }
        }
    }

    private fun treeLabel(uri: Uri): String {
        return try {
            Uri.decode(DocumentsContract.getTreeDocumentId(uri)).substringAfterLast('/').substringAfterLast(':')
        } catch (e: Exception) {
            Uri.decode(uri.toString())
        }
    }

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        status.visibility = View.GONE
    }

    private data class Entry(
        val name: String,
        val isDir: Boolean,
        val docId: String,
        val fileUri: Uri?
    )

    private inner class EntryAdapter : RecyclerView.Adapter<EntryVH>() {
        private val items = mutableListOf<Entry>()

        fun submit(newItems: List<Entry>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryVH {
            val v = layoutInflater.inflate(R.layout.item_file, parent, false)
            return EntryVH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: EntryVH, position: Int) {
            val item = items[position]
            holder.name.text = item.name
            if (item.isDir) {
                holder.icon.setImageResource(R.drawable.ic_open)
                holder.meta.text = getString(R.string.folder_label)
            } else {
                holder.icon.setImageResource(R.drawable.ic_doc)
                holder.meta.text = SupportedTypes.extensionOf(item.name).uppercase()
            }
            holder.itemView.setOnClickListener { onEntryClick(item) }
        }
    }

    private inner class EntryVH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.fileIcon)
        val name: TextView = view.findViewById(R.id.fileName)
        val meta: TextView = view.findViewById(R.id.fileMeta)
    }
}
