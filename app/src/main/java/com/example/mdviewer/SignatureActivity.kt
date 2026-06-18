package com.example.mdviewer

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.button.MaterialButton

/** Captures and manages a reusable handwritten signature via [SignatureStore]. */
class SignatureActivity : AppCompatActivity() {

    private lateinit var pad: SignaturePadView
    private lateinit var currentGroup: LinearLayout
    private lateinit var preview: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signature)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        pad = findViewById(R.id.signaturePad)
        currentGroup = findViewById(R.id.currentSignatureGroup)
        preview = findViewById(R.id.currentSignaturePreview)

        findViewById<MaterialButton>(R.id.btnClear).setOnClickListener { pad.clear() }

        findViewById<MaterialButton>(R.id.btnDeleteSignature).setOnClickListener {
            confirmDelete()
        }

        findViewById<MaterialButton>(R.id.btnSaveSignature).setOnClickListener {
            val bitmap = pad.exportTrimmedBitmap()
            if (bitmap == null) {
                Toast.makeText(this, R.string.signature_empty, Toast.LENGTH_SHORT).show()
            } else {
                SignatureStore.save(this, bitmap)
                setResult(RESULT_OK)
                finish()
            }
        }

        refreshCurrent()
    }

    private fun refreshCurrent() {
        val existing = SignatureStore.load(this)
        if (existing != null) {
            currentGroup.visibility = View.VISIBLE
            preview.setImageBitmap(existing)
        } else {
            currentGroup.visibility = View.GONE
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_signature_title)
            .setMessage(R.string.delete_signature_message)
            .setPositiveButton(R.string.action_delete_signature) { _, _ ->
                SignatureStore.delete(this)
                Toast.makeText(this, R.string.signature_deleted, Toast.LENGTH_SHORT).show()
                refreshCurrent()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}
