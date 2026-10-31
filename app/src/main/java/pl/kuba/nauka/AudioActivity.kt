package pl.kuba.nauka

import android.app.AlertDialog
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import pl.kuba.nauka.Ui.dp
import pl.kuba.nauka.Ui.margins

/** Odtwarzacz audiobooka – pamięta miejsce, ma przewijanie i tempo. */
class AudioActivity : AppCompatActivity() {

    private lateinit var m: Material
    private var player: MediaPlayer? = null
    private lateinit var seek: SeekBar
    private lateinit var time: TextView
    private lateinit var playBtn: MaterialButton
    private val main = Handler(Looper.getMainLooper())
    private var speed = 1.0f

    private val tick = object : Runnable {
        override fun run() {
            player?.let {
                if (!seekDragging) seek.progress = it.currentPosition
                time.text = "${fmt(it.currentPosition)} / ${fmt(it.duration)}"
                m.audioPos = it.currentPosition
            }
            main.postDelayed(this, 500)
        }
    }
    private var seekDragging = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        m = Store.get(this, intent.getStringExtra("id") ?: "") ?: run { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = Ui.vbox(this).apply { setBackgroundColor(Ui.bg(this@AudioActivity)) }
        root.addView(Ui.header(this, m.title, "Audiobook"))
        val body = Ui.vbox(this).apply { setPadding(dp(16), dp(24), dp(16), dp(16)) }

        val c = Ui.card(this)
        c.addView(Ui.text(this, "🎧", 64f).apply { gravity = Gravity.CENTER })
        time = Ui.text(this, "0:00 / 0:00", 18f, true).apply { gravity = Gravity.CENTER }
        c.addView(time)
        seek = SeekBar(this)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) time.text = "${fmt(p)} / ${fmt(player?.duration ?: 0)}" }
            override fun onStartTrackingTouch(s: SeekBar?) { seekDragging = true }
            override fun onStopTrackingTouch(s: SeekBar?) { seekDragging = false; player?.seekTo(seek.progress) }
        })
        c.addView(seek.margins(this, t = 12))
        body.addView(c)

        val row = Ui.hbox(this)
        row.addView(Ui.button(this, "⏪ 30 s", Ui.BLUE) { jump(-30_000) }, Ui.lp(0, -2, 1f))
        playBtn = Ui.button(this, "▶ Graj", Ui.GREEN) { toggle() }
        row.addView(playBtn.also { it.layoutParams = Ui.lp(0, -2, 1.3f) }.margins(this, l = 8))
        row.addView(Ui.button(this, "30 s ⏩", Ui.BLUE) { jump(30_000) }.also { it.layoutParams = Ui.lp(0, -2, 1f) }.margins(this, l = 8))
        body.addView(row.margins(this, t = 18))

        val row2 = Ui.hbox(this)
        row2.addView(Ui.button(this, "⏪ 5 s", Ui.TEAL) { jump(-5_000) }, Ui.lp(0, -2, 1f))
        row2.addView(Ui.button(this, "⏮ Od początku", Ui.GOLD) { player?.seekTo(0) }.also { it.layoutParams = Ui.lp(0, -2, 1.4f) }.margins(this, l = 8))
        body.addView(row2.margins(this, t = 8))

        val sp = Ui.text(this, "Tempo 1.0", 15f, color = Ui.muted(this))
        body.addView(sp.margins(this, t = 18))
        body.addView(Slider(this).apply {
            valueFrom = 0.5f; valueTo = 1.5f; stepSize = 0.1f; value = 1.0f
            addOnChangeListener { _, v, _ -> speed = v; sp.text = "Tempo ${String.format("%.1f", v)}"; applySpeed() }
        })
        body.addView(Ui.text(this,
            "Zakrywanie tekstu i test działają dla materiałów z tekstem (PDF, TXT, wklejony tekst). Jeśli masz tekst tego audiobooka, dodaj go osobno przyciskiem „Wklej tekst” albo jako plik.",
            13f, color = Ui.muted(this)).margins(this, t = 16))
        root.addView(body)
        setContentView(root)

        prepare()
    }

    private fun prepare() {
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(this@AudioActivity, Uri.parse(m.audioUri))
                setOnPreparedListener {
                    seek.max = it.duration
                    if (m.audioPos in 1 until it.duration - 2000) it.seekTo(m.audioPos)
                    time.text = "${fmt(m.audioPos)} / ${fmt(it.duration)}"
                    main.post(tick)
                }
                setOnCompletionListener { playBtn.text = "▶ Graj"; m.audioPos = 0; Store.update(this@AudioActivity, m) }
                prepareAsync()
            }
        } catch (e: Exception) {
            AlertDialog.Builder(this).setTitle("Nie mogę otworzyć pliku")
                .setMessage("Plik mógł zostać przeniesiony lub usunięty. Usuń ten materiał i dodaj plik jeszcze raz.")
                .setPositiveButton("OK") { _, _ -> finish() }.show()
        }
    }

    private fun toggle() {
        val p = player ?: return
        if (p.isPlaying) { p.pause(); playBtn.text = "▶ Graj"; Store.update(this, m) }
        else { p.start(); applySpeed(); playBtn.text = "⏸ Pauza" }
    }

    private fun applySpeed() {
        val p = player ?: return
        try { if (p.isPlaying) p.playbackParams = p.playbackParams.setSpeed(speed) } catch (_: Exception) { }
    }

    private fun jump(ms: Int) {
        val p = player ?: return
        p.seekTo((p.currentPosition + ms).coerceIn(0, p.duration))
    }

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format("%d:%02d", s / 60, s % 60)
    }

    override fun onPause() {
        super.onPause()
        Store.update(this, m)
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        player?.release(); player = null
    }
}
