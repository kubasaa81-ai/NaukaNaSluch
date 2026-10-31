package pl.kuba.nauka

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import pl.kuba.nauka.Store.quizSize
import pl.kuba.nauka.Store.rate
import pl.kuba.nauka.Ui.dp
import pl.kuba.nauka.Ui.margins

class QuizActivity : AppCompatActivity() {

    private lateinit var m: Material
    private lateinit var segs: List<Seg>
    private lateinit var speaker: Speaker
    private var recognizer: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())

    private var questions: List<Question> = emptyList()
    private var idx = 0
    private var points = 0.0
    private var retries = 0
    private var answered = false
    private val mistakes = mutableListOf<Question>()

    private lateinit var body: android.widget.LinearLayout
    private lateinit var progress: TextView
    private lateinit var kind: TextView
    private lateinit var prompt: TextView
    private lateinit var mic: TextView
    private lateinit var heardTv: TextView
    private lateinit var result: TextView
    private lateinit var controls: android.widget.LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        m = Store.get(this, intent.getStringExtra("id") ?: "") ?: run { finish(); return }
        segs = TextUtil.segments(Store.text(this, m.id))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = Ui.vbox(this).apply { setBackgroundColor(Ui.bg(this@QuizActivity)) }
        root.addView(Ui.header(this, "Test: ${m.title}", "Odpowiadaj na głos"))
        val scroll = ScrollView(this)
        body = Ui.vbox(this).apply { setPadding(dp(16), dp(16), dp(16), dp(24)) }
        scroll.addView(body)
        root.addView(scroll, Ui.lp(-1, 0, 1f))
        setContentView(root)

        buildQuestionView()
        speaker = Speaker(this) { ok, msg ->
            runOnUiThread {
                if (!ok) AlertDialog.Builder(this).setTitle("Lektor").setMessage(msg).setPositiveButton("OK", null).show()
                else if (hasMic()) start() else askMic()
            }
        }
        speaker.rate = rate
    }

    // ---------- uprawnienia i rozpoznawanie mowy ----------

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun askMic() = ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 7)

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (hasMic()) start()
        else AlertDialog.Builder(this).setTitle("Potrzebny mikrofon")
            .setMessage("Test polega na odpowiadaniu na głos, więc aplikacja potrzebuje mikrofonu. Zezwól w ustawieniach aplikacji.")
            .setPositiveButton("Spróbuj jeszcze raz") { _, _ -> askMic() }
            .setNegativeButton("Wróć") { _, _ -> finish() }.show()
    }

    private fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            AlertDialog.Builder(this).setTitle("Brak rozpoznawania mowy")
                .setMessage("Na telefonie potrzebna jest aplikacja Google (rozpoznawanie mowy). Zainstaluj lub zaktualizuj „Google” ze Sklepu Play. Bez internetu działa, gdy w Google pobrany jest język polski offline.")
                .setPositiveButton("OK") { _, _ -> finish() }.show()
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply { setRecognitionListener(listener) }
        questions = QuizMaker.make(segs, quizSize)
        if (questions.isEmpty()) {
            AlertDialog.Builder(this).setMessage("Nie udało się ułożyć pytań z tego tekstu.").setPositiveButton("OK") { _, _ -> finish() }.show()
            return
        }
        idx = 0; points = 0.0; mistakes.clear()
        speaker.say("Zaczynamy test. ${questions.size} pytań. Odpowiadaj na głos, po sygnale.") { ask() }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) { setMic("🎤 Słucham… mów teraz", Ui.GREEN) }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() { setMic("⏳ Sprawdzam…", Ui.GOLD) }
        override fun onPartialResults(b: Bundle?) {
            b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { heardTv.text = "Słyszę: $it" }
        }
        override fun onEvent(t: Int, b: Bundle?) {}
        override fun onResults(b: Bundle?) {
            val list = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
            onHeard(list)
        }
        override fun onError(error: Int) {
            if (answered) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> notHeard()
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> {
                    setMic("📵 Brak internetu do rozpoznawania mowy", Ui.RED)
                    heardTv.text = "Włącz internet albo pobierz język polski offline w aplikacji Google (Ustawienia → Głos → Rozpoznawanie mowy offline)."
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> main.postDelayed({ listen() }, 600)
                else -> notHeard()
            }
        }
    }

    private fun listen() {
        if (answered) return
        heardTv.text = ""
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pl-PL")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pl-PL")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }
        try { recognizer?.cancel(); recognizer?.startListening(i) }
        catch (e: Exception) { setMic("Nie mogę włączyć mikrofonu", Ui.RED) }
    }

    // ---------- przebieg testu ----------

    private fun ask() {
        if (idx >= questions.size) { finishQuiz(); return }
        val q = questions[idx]
        answered = false; retries = 0
        progress.text = "Pytanie ${idx + 1} z ${questions.size}"
        kind.text = q.kind.label
        prompt.text = q.shown
        result.text = ""; heardTv.text = ""
        setMic("🔊 Słuchaj pytania…", Ui.BLUE)
        showControls(answering = true)
        speaker.say(q.spoken) { listen() }
    }

    private fun onHeard(list: List<String>) {
        if (answered) return
        val all = list.joinToString(" ")
        val n = TextUtil.norm(all)
        heardTv.text = "Usłyszałem: ${list.firstOrNull() ?: ""}"
        // komendy głosowe
        if (n.contains("powtorz") || n.contains("jeszcze raz pytanie")) {
            setMic("🔊 Powtarzam pytanie…", Ui.BLUE); speaker.say(questions[idx].spoken) { listen() }; return
        }
        val q = questions[idx]
        if (n.contains("nie wiem") || n == "pas") { grade(q, 0.0); return }
        grade(q, QuizMaker.check(q, list))
    }

    private fun notHeard() {
        if (answered) return
        retries++
        if (retries <= 2) {
            setMic("🤔 Nie usłyszałem", Ui.GOLD)
            speaker.say("Nie usłyszałem. Powiedz jeszcze raz.") { listen() }
        } else {
            setMic("🎤 Dotknij „Odpowiadam”, gdy będziesz gotowy", Ui.GREY)
        }
    }

    private fun grade(q: Question, score: Double) {
        answered = true
        recognizer?.cancel()
        points += score
        val say: String
        if (score >= 1.0) {
            setMic("✅ Dobrze!", Ui.GREEN)
            result.setTextColor(Ui.GREEN)
            result.text = if (q.kind == Question.Kind.TRUE_FALSE) q.answer else "Odpowiedź: ${q.answer}"
            say = listOf("Dobrze!", "Brawo!", "Świetnie!", "Tak jest!").random()
        } else {
            mistakes.add(q)
            setMic(if (score > 0) "🟡 Prawie!" else "❌ Niestety", if (score > 0) Ui.GOLD else Ui.RED)
            result.setTextColor(if (score > 0) Ui.GOLD else Ui.RED)
            result.text = "Prawidłowo: ${q.answer}"
            say = (if (score > 0) "Prawie. " else "Niestety. ") + "Prawidłowa odpowiedź: ${q.answer}"
        }
        showControls(answering = false)
        speaker.say(say) { main.postDelayed({ if (answered && !isFinishing) { idx++; ask() } }, 1800) }
    }

    private fun finishQuiz() {
        main.removeCallbacksAndMessages(null)
        val pct = (points * 100 / questions.size).toInt()
        if (pct > m.bestScore) m.bestScore = pct
        Store.update(this, m)

        body.removeAllViews()
        val c = Ui.card(this)
        c.addView(Ui.text(this, "Wynik", 16f, color = Ui.muted(this)))
        c.addView(Ui.text(this, "$pct%", 48f, true, if (pct >= 80) Ui.GREEN else if (pct >= 50) Ui.GOLD else Ui.RED))
        val msg = when {
            pct >= 90 -> "Znakomicie! Umiesz ten materiał. 🏆"
            pct >= 70 -> "Bardzo dobrze! Jeszcze trochę i będzie perfekcyjnie."
            pct >= 50 -> "Nieźle. Posłuchaj jeszcze raz i spróbuj ponownie."
            else -> "Trzeba jeszcze poćwiczyć. Posłuchaj materiału kilka razy."
        }
        c.addView(Ui.text(this, msg, 17f))
        c.addView(Ui.text(this, "Najlepszy wynik: ${m.bestScore}%", 14f, color = Ui.muted(this)).margins(this, t = 6))
        body.addView(c)

        if (mistakes.isNotEmpty()) {
            body.addView(Ui.text(this, "Do powtórki:", 17f, true).margins(this, t = 16, b = 6))
            mistakes.forEach { q ->
                val mc = Ui.card(this)
                mc.addView(Ui.text(this, q.kind.label, 13f, color = Ui.muted(this)))
                mc.addView(Ui.text(this, q.shown, 16f))
                mc.addView(Ui.text(this, "→ ${q.answer}", 16f, true, Ui.GREEN).margins(this, t = 4))
                body.addView(mc); mc.margins(this, b = 8)
            }
            body.addView(Ui.button(this, "🔊 Przeczytaj poprawne odpowiedzi", Ui.GOLD) { readMistakes(0) }.margins(this, t = 8))
        }
        body.addView(Ui.button(this, "🔄 Nowy test", Ui.PURPLE) { recreate() }.margins(this, t = 8))
        body.addView(Ui.button(this, "📖 Wróć do nauki", Ui.GREEN) { finish() }.margins(this, t = 8))
        speaker.say("Koniec testu. Twój wynik to $pct procent. $msg".replace("🏆", ""))
    }

    private fun readMistakes(i: Int) {
        if (i >= mistakes.size) return
        val q = mistakes[i]
        val t = when (q.kind) {
            Question.Kind.TRUE_FALSE -> q.answer.removePrefix("Fałsz. Poprawnie: ").removePrefix("Prawda")
                .ifBlank { q.shown }
            Question.Kind.GAP -> q.shown.replace("_____", q.answer)
            Question.Kind.NEXT -> q.shown + " " + q.answer
        }
        speaker.say(t) { main.postDelayed({ readMistakes(i + 1) }, 700) }
    }

    // ---------- wygląd ----------

    private fun buildQuestionView() {
        body.removeAllViews()
        progress = Ui.text(this, "", 14f, color = Ui.muted(this))
        body.addView(progress)
        val c = Ui.card(this)
        kind = Ui.text(this, "", 15f, true, Ui.PURPLE)
        prompt = Ui.text(this, "Przygotowuję test…", 21f)
        c.addView(kind); c.addView(prompt.margins(this, t = 8))
        body.addView(c.margins(this, t = 6))

        mic = TextView(this).apply {
            textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            setPadding(dp(12), dp(18), dp(12), dp(18))
        }
        body.addView(mic.margins(this, t = 16))
        setMic("…", Ui.GREY)
        heardTv = Ui.text(this, "", 15f, color = Ui.muted(this))
        body.addView(heardTv.margins(this, t = 10))
        result = Ui.text(this, "", 18f, true)
        body.addView(result.margins(this, t = 6))
        controls = Ui.vbox(this)
        body.addView(controls.margins(this, t = 14))
        body.addView(Ui.text(this, "Możesz też powiedzieć: „powtórz” – przeczytam pytanie jeszcze raz, „nie wiem” – pokażę odpowiedź.",
            13f, color = Ui.muted(this)).margins(this, t = 14))
    }

    private fun setMic(t: String, color: Int) {
        mic.text = t
        mic.background = GradientDrawable().apply { setColor(color); cornerRadius = dp(18).toFloat() }
    }

    private fun showControls(answering: Boolean) {
        controls.removeAllViews()
        val row = Ui.hbox(this)
        if (answering) {
            row.addView(Ui.button(this, "🎤 Odpowiadam", Ui.GREEN) { retries = 0; listen() }, Ui.lp(0, -2, 1f))
            row.addView(Ui.button(this, "🔁 Powtórz", Ui.BLUE) {
                recognizer?.cancel(); setMic("🔊 Powtarzam pytanie…", Ui.BLUE)
                speaker.say(questions[idx].spoken) { listen() }
            }.also { it.layoutParams = Ui.lp(0, -2, 1f) }.margins(this, l = 8))
            controls.addView(row)
            controls.addView(Ui.button(this, "⏭ Nie wiem – pokaż odpowiedź", Ui.GREY) { grade(questions[idx], 0.0) }.margins(this, t = 8))
        } else {
            row.addView(Ui.button(this, "➡ Dalej", Ui.PURPLE) {
                main.removeCallbacksAndMessages(null); speaker.stop(); idx++; ask()
            }, Ui.lp(0, -2, 1f))
            controls.addView(row)
        }
    }

    override fun onPause() {
        super.onPause()
        recognizer?.cancel()
        if (::speaker.isInitialized) speaker.stop()
        main.removeCallbacksAndMessages(null)
    }

    override fun onResume() {
        super.onResume()
        // po powrocie do aplikacji powtórz bieżące pytanie
        if (questions.isNotEmpty() && idx < questions.size && !answered && ::speaker.isInitialized && speaker.ready) {
            main.postDelayed({ ask() }, 400)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer?.destroy()
        if (::speaker.isInitialized) speaker.shutdown()
    }
}
