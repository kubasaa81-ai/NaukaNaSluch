package pl.kuba.nauka

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File

/**
 * Rozpoznawanie tekstu ze zdjęć stron (OCR) – działa w telefonie, bez internetu.
 * Model rozpoznawania jest wbudowany w aplikację (ML Kit, alfabet łaciński – polskie znaki też).
 */
object Ocr {

    private const val MAX_SIDE = 2000   // wystarczy do czytelnego tekstu, a nie zapcha pamięci

    /** Rozpoznaje tekst z kilku zdjęć po kolei. Strony rozdziela pustą linią. */
    fun fromImages(c: Context, uris: List<Uri>, progress: (Int, Int) -> Unit = { _, _ -> }): String {
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val pages = mutableListOf<String>()
            uris.forEachIndexed { i, u ->
                progress(i + 1, uris.size)
                val bmp = loadBitmap(c, u) ?: throw IllegalStateException("nie da się otworzyć zdjęcia nr ${i + 1}")
                val t = recognize(rec, InputImage.fromBitmap(bmp.first, bmp.second))
                bmp.first.recycle()
                if (t.isNotBlank()) pages.add(t)
            }
            return pages.joinToString("\n\n")
        } finally { rec.close() }
    }

    /** PDF bez warstwy tekstowej (skan) – renderuje strony i rozpoznaje tekst. */
    fun fromScannedPdf(c: Context, uri: Uri, progress: (Int, Int) -> Unit = { _, _ -> }): String {
        val tmp = File(c.cacheDir, "skan_tmp.pdf")
        c.contentResolver.openInputStream(uri)?.use { inp -> tmp.outputStream().use { inp.copyTo(it) } } ?: return ""
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { pdf ->
                    val pages = mutableListOf<String>()
                    for (i in 0 until pdf.pageCount) {
                        progress(i + 1, pdf.pageCount)
                        pdf.openPage(i).use { page ->
                            val scale = MAX_SIDE.toFloat() / maxOf(page.width, page.height)
                            val w = (page.width * scale).toInt().coerceAtLeast(1)
                            val h = (page.height * scale).toInt().coerceAtLeast(1)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val t = recognize(rec, InputImage.fromBitmap(bmp, 0))
                            bmp.recycle()
                            if (t.isNotBlank()) pages.add(t)
                        }
                    }
                    return pages.joinToString("\n\n")
                }
            }
        } finally {
            rec.close(); tmp.delete()
        }
    }

    private fun recognize(rec: com.google.mlkit.vision.text.TextRecognizer, img: InputImage): String {
        val res = Tasks.await(rec.process(img))
        // bloki tekstu (akapity) od góry do dołu, każdy blok oddzielony pustą linią
        val blocks = res.textBlocks.sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
        return blocks.joinToString("\n\n") { b -> b.lines.joinToString("\n") { it.text } }.trim()
    }

    /** Wczytuje zdjęcie pomniejszone (oszczędność pamięci) + obrót z EXIF. */
    private fun loadBitmap(c: Context, u: Uri): Pair<Bitmap, Int>? {
        val cr = c.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bmp = cr.openInputStream(u)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        // dokładne dopasowanie do MAX_SIDE (mniej pamięci, szybciej, ML Kit lubi ok. 2000 px)
        val big = maxOf(bmp.width, bmp.height)
        val fitted = if (big > MAX_SIDE) {
            val k = MAX_SIDE.toFloat() / big
            Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true).also { if (it != bmp) bmp.recycle() }
        } else bmp
        val rot = try {
            cr.openInputStream(u)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (_: Exception) { 0 }
        return fitted to rot
    }
}
