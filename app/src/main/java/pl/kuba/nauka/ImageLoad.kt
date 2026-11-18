package pl.kuba.nauka

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface

/**
 * Wczytywanie zdjęć odporne na różne formaty (JPEG, HEIF/HEIC z Samsunga, PNG, WebP).
 * Zwraca bitmapę już obróconą prawidłowo i pomniejszoną do maxSide.
 */
object ImageLoad {

    fun load(c: Context, u: Uri, maxSide: Int): Bitmap {
        val size = try { c.contentResolver.openAssetFileDescriptor(u, "r")?.use { it.length } ?: -1L } catch (_: Exception) { -1L }
        if (size == 0L) throw IllegalStateException("aparat nie zapisał zdjęcia (pusty plik) – zrób zdjęcie jeszcze raz")

        var firstError: Throwable? = null
        // 1) nowoczesny dekoder systemu (Android 9+) – obsługuje HEIF i sam obraca zdjęcie
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                val src = ImageDecoder.createSource(c.contentResolver, u)
                return ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                    dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    dec.isMutableRequired = false
                    val w = info.size.width; val h = info.size.height
                    val big = maxOf(w, h)
                    if (big > maxSide) {
                        val k = maxSide.toFloat() / big
                        dec.setTargetSize((w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1))
                    }
                }
            } catch (e: Throwable) { firstError = e }
        }
        // 2) zapasowo: klasyczny dekoder + obrót z EXIF
        try {
            return classic(c, u, maxSide)
        } catch (e: Throwable) {
            val mime = c.contentResolver.getType(u) ?: "?"
            val why = (firstError ?: e).let { "${it.javaClass.simpleName}: ${it.message ?: ""}" }
            throw IllegalStateException("nie da się otworzyć zdjęcia (typ: $mime, rozmiar: ${if (size >= 0) "${size / 1024} KB" else "?"}) – $why")
        }
    }

    private fun classic(c: Context, u: Uri, maxSide: Int): Bitmap {
        val cr = c.contentResolver
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, b) } ?: throw IllegalStateException("brak dostępu do pliku")
        if (b.outWidth <= 0 || b.outHeight <= 0) throw IllegalStateException("nieobsługiwany format zdjęcia")
        var s = 1
        while (maxOf(b.outWidth, b.outHeight) / (s * 2) >= maxSide) s *= 2
        var bmp = cr.openInputStream(u)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })
        } ?: throw IllegalStateException("nie udało się zdekodować zdjęcia")
        val big = maxOf(bmp.width, bmp.height)
        if (big > maxSide) {
            val k = maxSide.toFloat() / big
            val r = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
            if (r != bmp) bmp.recycle()
            bmp = r
        }
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
