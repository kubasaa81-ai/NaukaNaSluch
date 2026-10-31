package pl.kuba.nauka

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.nio.charset.Charset
import java.text.Normalizer

/** Jeden wers / zdanie do czytania. block = numer akapitu/zwrotki. */
data class Seg(val text: String, val block: Int)

object TextUtil {

    fun fileName(c: Context, uri: Uri): String {
        var name = "Materiał"
        try {
            c.contentResolver.query(uri, null, null, null, null)?.use { cur ->
                val i = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && cur.moveToFirst()) name = cur.getString(i) ?: name
            }
        } catch (_: Exception) { }
        return name.substringBeforeLast('.').replace('_', ' ').trim().ifEmpty { "Materiał" }
    }

    fun mime(c: Context, uri: Uri): String {
        val m = c.contentResolver.getType(uri) ?: ""
        if (m.isNotEmpty() && m != "application/octet-stream") return m
        val n = (uri.lastPathSegment ?: "").lowercase()
        return when {
            n.endsWith(".pdf") -> "application/pdf"
            n.endsWith(".txt") -> "text/plain"
            n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".m4b") ||
                n.endsWith(".ogg") || n.endsWith(".wav") || n.endsWith(".aac") -> "audio/*"
            else -> m
        }
    }

    /** Czyta TXT – UTF-8, a jeśli to nie UTF-8, to polskie Windows-1250. */
    fun readTxt(c: Context, uri: Uri): String {
        val bytes = c.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return ""
        val utf = String(bytes, Charsets.UTF_8)
        val txt = if (utf.contains('�')) String(bytes, Charset.forName("windows-1250")) else utf
        return txt.removePrefix("﻿")
    }

    fun readPdf(c: Context, uri: Uri): String {
        PDFBoxResourceLoader.init(c.applicationContext)
        c.contentResolver.openInputStream(uri)?.use { input ->
            PDDocument.load(input).use { doc ->
                val s = PDFTextStripper()
                s.setSortByPosition(true)
                s.setParagraphStart("\n")
                return s.getText(doc)
            }
        }
        return ""
    }

    /** Dzieli tekst na wersy (piosenki, wiersze) albo na zdania (zwykły tekst z PDF). */
    fun segments(raw: String): List<Seg> {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n')
            .replace(Regex("(\\p{L})-\\n(\\p{Ll})"), "$1$2")          // przeniesienia wyrazów
        val lines = text.split('\n').map { it.trim() }
            .filter { !it.matches(Regex("^(str\\.?\\s*)?\\d{1,4}$")) } // numery stron

        val nonEmpty = lines.filter { it.isNotEmpty() }
        if (nonEmpty.isEmpty()) return emptyList()
        val avg = nonEmpty.sumOf { it.length } / nonEmpty.size
        val noEnd = nonEmpty.count { !it.last().let { ch -> ch in ".!?:;\"”»" } }
        val prose = avg > 55 && noEnd > nonEmpty.size / 3

        // akapity / zwrotki rozdzielone pustą linią
        val blocks = mutableListOf<MutableList<String>>()
        var cur = mutableListOf<String>()
        for (l in lines) {
            if (l.isEmpty()) { if (cur.isNotEmpty()) { blocks.add(cur); cur = mutableListOf() } }
            else cur.add(l)
        }
        if (cur.isNotEmpty()) blocks.add(cur)

        val out = mutableListOf<Seg>()
        blocks.forEachIndexed { bi, b ->
            if (!prose) b.forEach { out.add(Seg(it, bi)) }
            else sentences(b.joinToString(" ")).forEach { out.add(Seg(it, bi)) }
        }
        return out
    }

    private fun sentences(p: String): List<String> {
        val parts = p.replace(Regex("\\s+"), " ")
            .split(Regex("(?<=[.!?…])\\s+(?=[\\p{Lu}\\d\"„«(])"))
            .map { it.trim() }.filter { it.isNotEmpty() }
        val res = mutableListOf<String>()
        for (s in parts) {
            if (s.length <= 160) { res.add(s); continue }
            // bardzo długie zdanie – tniemy po przecinkach na kawałki ~100 znaków
            var buf = StringBuilder()
            for (piece in s.split(Regex("(?<=[,;:])\\s+"))) {
                if (buf.isNotEmpty() && buf.length + piece.length > 110) { res.add(buf.toString()); buf = StringBuilder() }
                if (buf.isNotEmpty()) buf.append(' ')
                buf.append(piece)
            }
            if (buf.isNotEmpty()) res.add(buf.toString())
        }
        return res
    }

    // ---------- porównywanie odpowiedzi ----------

    /** małe litery, bez polskich znaków i interpunkcji */
    fun norm(s: String): String {
        val lower = s.lowercase().replace('ł', 'l')
        val noAcc = Normalizer.normalize(lower, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        return noAcc.replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    fun words(s: String) = norm(s).split(' ').filter { it.isNotEmpty() }

    fun lev(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return d[b.length]
    }

    /** podobieństwo dwóch słów 0..1 (toleruje drobne błędy rozpoznawania mowy) */
    fun wordSim(a: String, b: String): Double {
        if (a == b) return 1.0
        val m = maxOf(a.length, b.length)
        if (m == 0) return 1.0
        return 1.0 - lev(a, b).toDouble() / m
    }

    /** Jaka część słów z wzorca padła w odpowiedzi (z tolerancją na końcówki). 0..1 */
    fun coverage(expected: String, answer: String): Double {
        val exp = words(expected)
        val ans = words(answer)
        if (exp.isEmpty()) return 0.0
        var hit = 0
        for (w in exp) if (ans.any { wordSim(w, it) >= 0.75 }) hit++
        return hit.toDouble() / exp.size
    }

    /** Czy jedno konkretne słowo padło w odpowiedzi */
    fun saidWord(expected: String, answer: String): Boolean {
        val e = norm(expected)
        return words(answer).any { wordSim(e, it) >= 0.75 } || norm(answer).contains(e)
    }
}
