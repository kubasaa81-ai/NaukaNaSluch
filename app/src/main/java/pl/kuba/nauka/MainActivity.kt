package pl.kuba.nauka

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.slider.Slider
import pl.kuba.nauka.Store.listensForQuiz
import pl.kuba.nauka.Store.quizSize
import pl.kuba.nauka.Store.rate
import pl.kuba.nauka.Ui.dp
import pl.kuba.nauka.Ui.margins
import java.io.File
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private val main = Handler(Looper.getMainLooper())

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importUris(uris, persist = true)
    }

    // ---------- zdjęcia stron ----------
    /** Zdjęcia stron zrobione w tej serii (kolejność = kolejność czytania). */
    private val pages = ArrayList<Uri>()
    private var pendingPhoto: Uri? = null

    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = pendingPhoto
        pendingPhoto = null
        if (ok && u != null) pages.add(u)
        if (pages.isEmpty()) return@registerForActivityResult
        nextPageDialog()
    }

    private val gallery = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) ocrAndSave(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = Ui.vbox(this).apply { setBackgroundColor(Ui.bg(this@MainActivity)) }
        root.addView(Ui.header(this, "Nauka na słuch", "Słuchaj, powtarzaj, sprawdzaj się"))

        val scroll = ScrollView(this)
        val content = Ui.vbox(this).apply { setPadding(dp(16), dp(14), dp(16), dp(24)) }

        val row1 = Ui.hbox(this)
        row1.addView(Ui.button(this, "➕ Dodaj plik", Ui.BLUE) { pick() }, Ui.lp(0, -2, 1f))
        row1.addView(Ui.button(this, "📋 Wklej tekst", Ui.PURPLE) { pasteDialog() }.also {
            it.layoutParams = Ui.lp(0, -2, 1f)
        }.margins(this, l = 8))
        content.addView(row1)
        content.addView(Ui.button(this, "📷 Zdjęcia stron (książka, zeszyt)", Ui.TEAL) { photoMenu() }.margins(this, t = 8))
        content.addView(Ui.text(this, "PDF, TXT, zdjęcia stron albo audiobook (MP3, M4A…). Możesz też w innej aplikacji wybrać „Udostępnij” → Nauka na słuch.",
            13f, color = Ui.muted(this)).margins(this, t = 6, b = 4))
        content.addView(Ui.button(this, "⚙ Ustawienia", Ui.GREY) { settings() }.margins(this, t = 4, b = 12))

        list = Ui.vbox(this)
        content.addView(list)
        scroll.addView(content)
        root.addView(scroll, Ui.lp(-1, 0, 1f))
        setContentView(root)

        savedInstanceState?.let { st ->
            st.getStringArrayList("pages")?.forEach { pages.add(Uri.parse(it)) }
            st.getString("pendingPhoto")?.let { pendingPhoto = Uri.parse(it) }
        }
        seedSample()
        handleShare(intent)
    }

    /** Przy pierwszym uruchomieniu dodaje przykładowy materiał (Pieśni legionowe). */
    private fun seedSample() {
        val p = getSharedPreferences("ustawienia", MODE_PRIVATE)
        if (p.getBoolean("seeded", false)) return
        p.edit().putBoolean("seeded", true).apply()
        try {
            val t = assets.open("piesni_legionowe.txt").bufferedReader(Charsets.UTF_8).readText()
            if (Store.all(this).isEmpty()) Store.addText(this, "Pieśni legionowe", t)
        } catch (_: Exception) { }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList("pages", ArrayList(pages.map { it.toString() }))
        pendingPhoto?.let { outState.putString("pendingPhoto", it.toString()) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun pick() = picker.launch(arrayOf("application/pdf", "text/plain", "audio/*", "image/*"))

    private fun photoMenu() {
        AlertDialog.Builder(this).setTitle("Zdjęcia stron")
            .setItems(arrayOf("📷 Zrób zdjęcia aparatem (strona po stronie)", "🖼 Wybierz gotowe zdjęcia z galerii")) { _, w ->
                if (w == 0) { pages.clear(); takePhoto() } else gallery.launch("image/*")
            }.setNegativeButton("Anuluj", null).show()
    }

    private fun takePhoto() {
        val dir = File(cacheDir, "strony").apply { mkdirs() }
        if (pages.isEmpty()) dir.listFiles()?.forEach { it.delete() }   // stare zdjęcia z poprzedniej serii
        val f = File(dir, "strona_${System.currentTimeMillis()}.jpg")
        val u = FileProvider.getUriForFile(this, "$packageName.pliki", f)
        pendingPhoto = u
        try { camera.launch(u) }
        catch (e: Exception) { pendingPhoto = null; Toast.makeText(this, "Nie udało się otworzyć aparatu", Toast.LENGTH_LONG).show() }
    }

    /** Po każdym zdjęciu: następna strona albo koniec. */
    private fun nextPageDialog() {
        val n = pages.size
        val word = if (n == 1) "strona" else if (n in 2..4) "strony" else "stron"
        AlertDialog.Builder(this).setTitle("Masz $n $word")
            .setMessage("Zrób zdjęcie kolejnej strony albo zakończ – aplikacja odczyta tekst i będzie mogła go czytać na głos.\n\n" +
                    "Wskazówka: strona płasko, dobrze oświetlona, cała w kadrze.")
            .setPositiveButton("✅ Gotowe – odczytaj") { _, _ -> val l = ArrayList(pages); pages.clear(); ocrAndSave(l) }
            .setNeutralButton("📷 Następna strona") { _, _ -> takePhoto() }
            .setNegativeButton("Anuluj") { _, _ -> pages.clear() }
            .setCancelable(false).show()
    }

    /** Zapisuje zdjęcia jako PDF (Pobrane / Nauka na słuch), odczytuje tekst i dodaje jako materiał. */
    private fun ocrAndSave(uris: List<Uri>) {
        val wait = progress("Zapisuję zdjęcia do PDF…")
        thread {
            val pdfInfo = try { "📄 PDF zapisany: " + PhotoPdf.save(this, uris, "Zdjęcia stron") }
                          catch (e: Exception) { "⚠ Nie udało się zapisać PDF: ${e.message ?: e.javaClass.simpleName}" }
            var err: String? = null
            val t = try {
                Ocr.fromImages(this, uris) { n, all -> main.post { wait.setMsg("Odczytuję tekst: zdjęcie $n z $all…") } }
            } catch (e: Throwable) { err = (e.cause ?: e).let { "${it.javaClass.simpleName}: ${it.message ?: ""}" }; "" }
            main.post {
                wait.dismiss()
                if (t.isBlank()) AlertDialog.Builder(this).setTitle("Nie udało się odczytać tekstu")
                    .setMessage(pdfInfo + "\n\n" +
                        (if (err != null) "Błąd odczytu tekstu:\n$err\n\nPrześlij ten komunikat (zrzut ekranu), to poprawię."
                         else "Na zdjęciach nie widać tekstu. Zrób zdjęcie jeszcze raz – bliżej, w lepszym świetle, bez cienia na kartce."))
                    .setPositiveButton("OK", null).show()
                else {
                    Toast.makeText(this, pdfInfo, Toast.LENGTH_LONG).show()
                    askTitleAndSave(t)
                }
            }
        }
    }

    private fun progress(msg: String): AlertDialog {
        val box = Ui.hbox(this).apply { setPadding(dp(24), dp(24), dp(24), dp(24)) }
        box.addView(ProgressBar(this))
        val label = Ui.text(this, msg)
        box.addView(label, Ui.lp(0, -2, 1f))
        label.margins(this, l = 16)
        return AlertDialog.Builder(this).setView(box).setCancelable(false).show().also { it.window?.decorView?.tag = label }
    }

    private fun AlertDialog.setMsg(s: String) {
        (window?.decorView?.tag as? android.widget.TextView)?.text = s
    }

    private fun pasteDialog() {
        val et = EditText(this).apply {
            hint = "Wklej albo wpisz tekst do nauki"
            minLines = 6; gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val box = Ui.vbox(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) }
        AlertDialog.Builder(this).setTitle("Nowy tekst").setView(box)
            .setPositiveButton("Dalej") { _, _ -> if (et.text.isNotBlank()) askTitleAndSave(et.text.toString()) }
            .setNegativeButton("Anuluj", null).show()
    }

    private fun askTitleAndSave(text: String) {
        val guess = text.trim().lineSequence().firstOrNull { it.isNotBlank() }?.take(40) ?: "Tekst"
        val et = EditText(this).apply { setText(guess); selectAll() }
        val box = Ui.vbox(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) }
        AlertDialog.Builder(this).setTitle("Nazwa materiału").setView(box)
            .setPositiveButton("Zapisz") { _, _ ->
                val m = Store.addText(this, et.text.toString().ifBlank { "Tekst" }, text)
                refresh(); open(m)
            }.setNegativeButton("Anuluj", null).show()
    }

    private fun refresh() {
        list.removeAllViews()
        val items = Store.all(this)
        if (items.isEmpty()) {
            val c = Ui.card(this)
            c.addView(Ui.text(this, "Brak materiałów", 18f, true))
            c.addView(Ui.text(this, "Dodaj PDF, plik TXT albo zrób zdjęcia stron z tekstem do nauki (np. piosenkę, wiersz, notatki z biologii) albo audiobook. " +
                    "Aplikacja przeczyta tekst na głos, pozwoli zakryć tekst do sprawdzenia się, a po kilku przesłuchaniach zada pytania.",
                15f, color = Ui.muted(this)).margins(this, t = 6))
            list.addView(c)
            return
        }
        for (m in items) {
            val c = Ui.card(this)
            val icon = if (m.type == "audio") "🎧" else "📖"
            c.addView(Ui.text(this, "$icon  ${m.title}", 18f, true))
            val info = if (m.type == "audio") "Audiobook"
            else buildString {
                append("Przesłuchano: ${m.listens} z ${listensForQuiz}")
                if (m.bestScore >= 0) append("   ·   Najlepszy test: ${m.bestScore}%")
            }
            c.addView(Ui.text(this, info, 14f, color = if (m.type == "text" && m.listens >= listensForQuiz) Ui.GREEN else Ui.muted(this)).margins(this, t = 4))
            c.setOnClickListener { open(m) }
            c.setOnLongClickListener { itemMenu(m); true }
            list.addView(c)
            c.margins(this, b = 10)
        }
        list.addView(Ui.text(this, "Przytrzymaj materiał, żeby zmienić nazwę albo usunąć.", 13f, color = Ui.muted(this)).margins(this, t = 4))
    }

    private fun open(m: Material) {
        val cls = if (m.type == "audio") AudioActivity::class.java else ReaderActivity::class.java
        startActivity(Intent(this, cls).putExtra("id", m.id))
    }

    private fun itemMenu(m: Material) {
        AlertDialog.Builder(this).setTitle(m.title)
            .setItems(arrayOf("✏ Zmień nazwę", "🔄 Wyzeruj licznik przesłuchań", "🗑 Usuń")) { _, w ->
                when (w) {
                    0 -> {
                        val et = EditText(this).apply { setText(m.title) }
                        val box = Ui.vbox(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) }
                        AlertDialog.Builder(this).setTitle("Nowa nazwa").setView(box)
                            .setPositiveButton("Zapisz") { _, _ -> m.title = et.text.toString().ifBlank { m.title }; Store.update(this, m); refresh() }
                            .setNegativeButton("Anuluj", null).show()
                    }
                    1 -> { m.listens = 0; Store.update(this, m); refresh() }
                    2 -> AlertDialog.Builder(this).setTitle("Usunąć „${m.title}”?")
                        .setPositiveButton("Usuń") { _, _ ->
                            if (m.audioUri.startsWith("file:")) Uri.parse(m.audioUri).path?.let { File(it).delete() }
                            Store.delete(this, m.id); refresh()
                        }.setNegativeButton("Anuluj", null).show()
                }
            }.show()
    }

    private fun settings() {
        val box = Ui.vbox(this).apply { setPadding(dp(22), dp(10), dp(22), 0) }
        fun slider(label: String, from: Float, to: Float, step: Float, value: Float, fmt: (Float) -> String): Slider {
            val t = Ui.text(this, "$label: ${fmt(value)}", 16f)
            val s = Slider(this).apply {
                valueFrom = from; valueTo = to; stepSize = step; this.value = value
                addOnChangeListener { _, v, _ -> t.text = "$label: ${fmt(v)}" }
            }
            box.addView(t); box.addView(s)
            return s
        }
        val sListens = slider("Przesłuchań przed testem", 1f, 10f, 1f, listensForQuiz.toFloat()) { it.toInt().toString() }
        val sQuiz = slider("Pytań w teście", 5f, 30f, 5f, quizSize.toFloat().coerceIn(5f, 30f)) { it.toInt().toString() }
        val sRate = slider("Tempo czytania", 0.5f, 1.5f, 0.1f, (Math.round(rate * 10) / 10f).coerceIn(0.5f, 1.5f)) { String.format("%.1f", it) }
        AlertDialog.Builder(this).setTitle("Ustawienia").setView(box)
            .setPositiveButton("Zapisz") { _, _ ->
                listensForQuiz = sListens.value.toInt(); quizSize = sQuiz.value.toInt(); rate = sRate.value
                refresh()
                Toast.makeText(this, "Zapisano", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Głos lektora") { _, _ ->
                try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                catch (e: Exception) { Toast.makeText(this, "Otwórz: Ustawienia → Ogólne zarządzanie → Zamiana tekstu na mowę", Toast.LENGTH_LONG).show() }
            }
            .setNegativeButton("Anuluj", null).show()
    }
}
