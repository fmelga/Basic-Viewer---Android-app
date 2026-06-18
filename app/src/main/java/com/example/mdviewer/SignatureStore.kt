package com.example.mdviewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

/** Persists a single reusable signature PNG in the app's internal storage. */
object SignatureStore {

    private const val FILE_NAME = "signature.png"

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun exists(context: Context): Boolean = file(context).exists()

    fun save(context: Context, bitmap: Bitmap) {
        FileOutputStream(file(context)).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    fun load(context: Context): Bitmap? {
        val f = file(context)
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.absolutePath)
    }

    fun delete(context: Context) {
        file(context).delete()
    }
}
