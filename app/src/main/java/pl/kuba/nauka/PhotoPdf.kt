package pl.kuba.nauka

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Zapisuje zdjęcia stron jako jeden plik PDF w Pobrane / Nauka na słuch. */
object PhotoPdf {

    private const val MAX_SIDE = 1800

    /** Zwraca opis, gdzie zapisano plik (do pokazania użytkownikowi). */
    fun save(c: Context, uris: List<Uri>, title: String): String {
        val safe = title.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(60).ifBlank { "Strony" }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
        val name = "${safe}_$stamp.pdf"

        val doc = PdfDocument()
        try {
            uris.forEachIndexed { i, u ->
                val bmp = load(c, u) ?: return@forEachIndexed
                // strona PDF w punktach (72 dpi) – szerokość jak A4, wysokość wg proporcji zdjęcia
                val pw = 595
                val ph = (bmp.height.toFloat() / bmp.width * pw).toInt().coerceAtLeast(1)
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
                val m = Matrix().apply { setScale(pw.toFloat() / bmp.width, ph.toFloat() / bmp.height) }
                page.canvas.drawBitmap(bmp, m, Paint(Paint.FILTER_BITMAP_FLAG))
                doc.finishPage(page)
                bmp.recycle()
            }
            return if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Nauka na słuch")
                }
                val out = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                    ?: throw IllegalStateException("brak dostępu do folderu Pobrane")
                c.contentResolver.openOutputStream(out)!!.use { s: OutputStream -> doc.writeTo(s) }
                "Pobrane / Nauka na słuch / $name"
            } else {
                val dir = File(c.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "Nauka na słuch").apply { mkdirs() }
                File(dir, name).outputStream().use { doc.writeTo(it) }
                "${dir.absolutePath}/$name"
            }
        } finally { doc.close() }
    }

    private fun load(c: Context, u: Uri): Bitmap? {
        val cr = c.contentResolver
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, b) } ?: return null
        if (b.outWidth <= 0) return null
        var s = 1
        while (maxOf(b.outWidth, b.outHeight) / (s * 2) >= MAX_SIDE) s *= 2
        var bmp = cr.openInputStream(u)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })
        } ?: return null
        val rot = try {
            cr.openInputStream(u)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (_: Exception) { 0f }
        if (rot != 0f) {
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
            if (r != bmp) bmp.recycle()
            bmp = r
        }
        return bmp
    }
}
