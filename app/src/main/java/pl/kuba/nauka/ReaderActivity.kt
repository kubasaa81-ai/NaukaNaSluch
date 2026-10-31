package pl.kuba.nauka

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import pl.kuba.nauka.Store.listensForQuiz
import pl.kuba.nauka.Store.rate
import pl.kuba.nauka.Ui.dp
import pl.kuba.nauka.Ui.margins

class ReaderActivity : AppCompatActivity() {

    private lateinit var m: Material
    private var segs: List<Seg> = emptyList()
    private lateinit var speaker: Speaker
    private lateinit var rv: RecyclerView
    private lateinit var status: TextView
    private lateinit var modeBtn: MaterialButton
    private val main = Handler(Looper.getMainLooper())

    private var mode = 0                 // 0 widoczny, 1 pierwsze słowa, 2 zakryty
    private val revealed = HashSet<Int>()
    private var current = -1
    private var runId = 0
    private var heard = HashSet<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        m = Store.get(this, intent.getStringExtra("id") ?: "") ?: run { finish(); return }
        segs = TextUtil.segments(Store.text(this, m.id))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = Ui.vbox(this).apply { setBackgroundColor(Ui.bg(this@ReaderActivity)) }
        root.addView(Ui.header(this, m.title, "${segs.size} wersów · przesłuchano ${m.listens} z $listensForQuiz"))

        // tryb tekstu + edycja
        val top = Ui.hbox(this).apply { setPadding(dp(12), dp(10), dp(12), dp(4)) }
        modeBtn = Ui.button(this, "", Ui.TEAL) { mode = (mode + 1) % 3; revealed.clear(); updateMode() }
        top.addView(modeBtn, Ui.lp(0, -2, 1f))
        top.addView(Ui.button(this, "✏ Popraw tekst", Ui.GREY) { editText() }.also { it.layoutParams = Ui.lp(-2, -2) }.margins(this, l = 8))
        root.addView(top)

        rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ReaderActivity)
            adapter = LinesAdapter()
            setPadding(dp(12), dp(4), dp(12), dp(12)); clipToPadding = false
        }
        root.addView(rv, Ui.lp(-1, 0, 1f))

        // dolny panel
        val bar = Ui.vbox(this).apply {
            setBackgroundColor(Ui.paper(this@ReaderActivity))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val r1 = Ui.hbox(this)
        r1.addView(Ui.button(this, "▶ Czytaj", Ui.GREEN) { play(startIndex(), false) }, Ui.lp(0, -2, 1f))
        r1.addView(Ui.button(this, "🗣 Powtarzaj za mną", Ui.GOLD) { play(startIndex(), true) }.also { it.layoutParams = Ui.lp(0, -2, 1.3f) }.margins(this, l = 8))
        bar.addView(r1)
        val r2 = Ui.hbox(this)
        r2.addView(Ui.button(this, "■ Stop", Ui.RED) { stop("Zatrzymano.") }, Ui.lp(0, -2, 1f))
        r2.addView(Ui.button(this, "⏮ Od początku", Ui.BLUE) { play(0, false) }.also { it.layoutParams = Ui.lp(0, -2, 1.1f) }.margins(this, l = 8))
        r2.addView(Ui.button(this, "❓ Test", Ui.PURPLE) { openQuiz() }.also { it.layoutParams = Ui.lp(0, -2, 1f) }.margins(this, l = 8))
        bar.addView(r2.margins(this, t = 8))

        val speedRow = Ui.hbox(this)
        val speedTxt = Ui.text(this, "Tempo ${fmt(rate)}", 14f, color = Ui.muted(this))
        val slider = Slider(this).apply {
            valueFrom = 0.5f; valueTo = 1.5f; stepSize = 0.1f
            value = (Math.round(rate * 10) / 10f).coerceIn(0.5f, 1.5f)
            addOnChangeListener { _, v, _ -> rate = v; speaker.rate = v; speedTxt.text = "Tempo ${fmt(v)}" }
        }
        speedRow.addView(speedTxt, Ui.lp(-2, -2))
        speedRow.addView(slider, Ui.lp(0, -2, 1f))
        bar.addView(speedRow.margins(this, t = 4))
        status = Ui.text(this, "Uruchamiam lektora…", 14f, color = Ui.muted(this))
        bar.addView(status)
        root.addView(bar)
        setContentView(root)

        updateMode()
        speaker = Speaker(this) { ok, msg ->
            runOnUiThread {
                if (ok) status.text = if (m.lastLine > 0) "Gotowe. „Czytaj” zacznie od miejsca, gdzie skończyłeś." else "Gotowe. Dotknij wersu, żeby czytać od niego."
                else AlertDialog.Builder(this).setTitle("Lektor").setMessage(msg).setPositiveButton("OK", null).show().also { status.text = msg }
            }
        }
        speaker.rate = rate
        if (segs.isEmpty()) status.text = "Ten materiał nie ma tekstu."
    }

    private fun fmt(v: Float) = String.format("%.1f", v)

    private fun startIndex() = if (m.lastLine in 1 until segs.size) m.lastLine else 0

    private fun updateMode() {
        modeBtn.text = when (mode) { 0 -> "👁 Tekst widoczny"; 1 -> "🔤 Pierwsze słowa"; else -> "🙈 Tekst zakryty" }
        rv.adapter?.notifyDataSetChanged()
        if (mode > 0 && ::status.isInitialized) status.text = "Dotknij zakrytego wersu, żeby sprawdzić odpowiedź."
    }

    // ---------- czytanie ----------

    private fun play(from: Int, repeat: Boolean) {
        if (segs.isEmpty()) return
        stop(null)
        val id = ++runId
        if (from == 0) heard = HashSet()
        step(from, repeat, id)
    }

    private fun step(i: Int, repeat: Boolean, id: Int) {
        if (id != runId) return
        if (i >= segs.size) { finished(); return }
        setCurrent(i)
        status.text = if (repeat) "Słuchaj…" else "Czytam ${i + 1} z ${segs.size}"
        speaker.say(segs[i].text) {
            if (id != runId) return@say
            heard.add(i)
            m.lastLine = i + 1; Store.update(this, m)
            val nextBlockGap = i + 1 < segs.size && segs[i + 1].block != segs[i].block
            if (repeat) {
                status.text = "✋ Teraz Ty – powtórz na głos"
                val pause = (1500 + segs[i].text.length * 75 / speaker.rate).toLong()
                main.postDelayed({ step(i + 1, true, id) }, pause)
            } else {
                main.postDelayed({ step(i + 1, false, id) }, if (nextBlockGap) 700 else 150)
            }
        }
    }

    private fun finished() {
        setCurrent(-1)
        m.lastLine = 0
        val full = heard.size >= (segs.size * 0.9).toInt().coerceAtLeast(1)
        if (full) {
            m.listens++
            heard = HashSet()
        }
        Store.update(this, m)
        status.text = if (full) "Koniec. Przesłuchano ${m.listens} z $listensForQuiz." else "Koniec."
        speaker.say(if (full) "Koniec. Brawo!" else "Koniec.")
        if (full && m.listens == listensForQuiz) {
            AlertDialog.Builder(this).setTitle("Przesłuchane ${m.listens} razy! 🎉")
                .setMessage("Czas sprawdzić, ile pamiętasz. Zrobić test?")
                .setPositiveButton("Tak, test") { _, _ -> openQuiz() }
                .setNegativeButton("Jeszcze posłucham", null).show()
        }
    }

    private fun stop(msg: String?) {
        runId++
        main.removeCallbacksAndMessages(null)
        if (::speaker.isInitialized) speaker.stop()
        setCurrent(-1)
        if (msg != null) status.text = msg
    }

    private fun setCurrent(i: Int) {
        val old = current; current = i
        if (old >= 0) rv.adapter?.notifyItemChanged(old)
        if (i >= 0) {
            rv.adapter?.notifyItemChanged(i)
            (rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(i, rv.height / 3)
        }
    }

    private fun openQuiz() {
        stop(null)
        if (segs.size < 4) {
            AlertDialog.Builder(this).setMessage("Za mało tekstu na test (potrzeba co najmniej 4 wersów).").setPositiveButton("OK", null).show(); return
        }
        if (m.listens < listensForQuiz) {
            AlertDialog.Builder(this).setTitle("Najpierw posłuchaj")
                .setMessage("Przesłuchano ${m.listens} z $listensForQuiz razy. Test najlepiej robić po przesłuchaniu. Zrobić mimo to?")
                .setPositiveButton("Zrób test") { _, _ -> startActivity(Intent(this, QuizActivity::class.java).putExtra("id", m.id)) }
                .setNegativeButton("Posłucham", null).show()
            return
        }
        startActivity(Intent(this, QuizActivity::class.java).putExtra("id", m.id))
    }

    private fun editText() {
        stop(null)
        val et = EditText(this).apply {
            setText(Store.text(this@ReaderActivity, m.id)); gravity = Gravity.TOP; textSize = 15f
        }
        val box = Ui.vbox(this).apply { setPadding(dp(16), dp(8), dp(16), 0); addView(et) }
        AlertDialog.Builder(this).setTitle("Popraw tekst")
            .setMessage("Każdy wers w osobnej linii, pusta linia oddziela zwrotki/akapity.")
            .setView(box)
            .setPositiveButton("Zapisz") { _, _ ->
                Store.saveText(this, m.id, et.text.toString())
                segs = TextUtil.segments(et.text.toString())
                m.lastLine = 0; Store.update(this, m)
                revealed.clear(); rv.adapter?.notifyDataSetChanged()
            }.setNegativeButton("Anuluj", null).show()
    }

    override fun onPause() {
        super.onPause()
        if (isFinishing) stop(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::speaker.isInitialized) speaker.shutdown()
    }

    // ---------- lista wersów ----------

    private inner class LinesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = segs.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
            val tv = TextView(p.context).apply {
                textSize = 19f; setLineSpacing(0f, 1.2f)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = RecyclerView.LayoutParams(-1, -2)
            }
            return object : RecyclerView.ViewHolder(tv) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            val tv = h.itemView as TextView
            val s = segs[pos]
            val newBlock = pos > 0 && segs[pos - 1].block != s.block
            (tv.layoutParams as RecyclerView.LayoutParams).topMargin = if (newBlock) dp(18) else 0
            tv.setTextColor(Ui.fg(this@ReaderActivity))
            tv.setTypeface(null, if (s.text.length < 60 && isChorus(s)) Typeface.BOLD else Typeface.NORMAL)
            tv.text = masked(s.text, pos)
            tv.setBackgroundColor(if (pos == current) Ui.highlight(this@ReaderActivity) else 0)
            tv.setOnClickListener {
                if (mode > 0 && pos !in revealed) { revealed.add(pos); notifyItemChanged(pos) }
                else if (mode > 0 && pos in revealed) { revealed.remove(pos); notifyItemChanged(pos) }
                else play(pos, false)
            }
            tv.setOnLongClickListener { play(pos, false); true }
        }
    }

    /** Wers powtarzający się w tekście (np. refren) pogrubiamy. */
    private var repeatsFor: List<Seg>? = null
    private var repeats = HashMap<String, Int>()
    private fun isChorus(s: Seg): Boolean {
        if (repeatsFor !== segs) {
            repeats = HashMap(); segs.forEach { val k = TextUtil.norm(it.text); repeats[k] = (repeats[k] ?: 0) + 1 }
            repeatsFor = segs
        }
        return (repeats[TextUtil.norm(s.text)] ?: 0) >= 2
    }

    private fun masked(t: String, pos: Int): CharSequence {
        if (mode == 0 || pos in revealed) return t
        val sp = SpannableString(t)
        val box = Ui.hiddenBox(this)
        var firstDone = false
        Regex("\\S+").findAll(t).forEach { w ->
            if (mode == 1 && !firstDone) { firstDone = true; return@forEach }
            sp.setSpan(BackgroundColorSpan(box), w.range.first, w.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(ForegroundColorSpan(box), w.range.first, w.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sp
    }
}
