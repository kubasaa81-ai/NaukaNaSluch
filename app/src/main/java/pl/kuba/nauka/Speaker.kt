package pl.kuba.nauka

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lektor telefonu (TextToSpeech) po polsku.
 * say(tekst) { ... } – wywołuje blok po skończeniu czytania (w wątku głównym).
 */
class Speaker(ctx: Context, private val onReady: (ok: Boolean, msg: String) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private val counter = AtomicInteger()
    private val callbacks = HashMap<String, () -> Unit>()
    var ready = false; private set
    var rate = 0.9f

    private val tts: TextToSpeech = TextToSpeech(ctx.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) setup()
        else onReady(false, "Telefon nie ma lektora. Zainstaluj „Usługi mowy Google” ze Sklepu Play.")
    }

    private fun setup() {
        val r = tts.setLanguage(Locale("pl", "PL"))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            // spróbuj silnika Google, jeśli domyślny (np. Samsung) nie ma polskiego
            onReady(false, "Lektor nie ma języka polskiego. W ustawieniach telefonu: Zamiana tekstu na mowę → wybierz „Usługi mowy Google” i pobierz język polski.")
            return
        }
        // wybierz najlepszy polski głos (offline, wysoka jakość)
        try {
            tts.voices?.filter { it.locale.language == "pl" && !it.isNetworkConnectionRequired }
                ?.maxByOrNull { it.quality }?.let { tts.voice = it }
        } catch (_: Exception) { }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) = finish(id)
            @Deprecated("") override fun onError(id: String?) = finish(id)
            override fun onError(id: String?, code: Int) = finish(id)
            override fun onStop(id: String?, interrupted: Boolean) { synchronized(callbacks) { callbacks.remove(id) } }
        })
        ready = true
        main.post { onReady(true, "") }
    }

    private fun finish(id: String?) {
        val cb = synchronized(callbacks) { callbacks.remove(id) } ?: return
        main.post(cb)
    }

    /** Czyta tekst (przerywa poprzednie czytanie). */
    fun say(text: String, then: (() -> Unit)? = null) {
        if (!ready) { then?.let { main.post(it) }; return }
        synchronized(callbacks) { callbacks.clear() }
        val id = "u" + counter.incrementAndGet()
        if (then != null) synchronized(callbacks) { callbacks[id] = then }
        tts.setSpeechRate(rate)
        // kropka na końcu = naturalne zakończenie zdania
        val t = text.trim().let { if (it.lastOrNull()?.let { c -> c.isLetterOrDigit() || c == ',' } == true) it.trimEnd(',') + "." else it }
        tts.speak(t, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
    }

    fun stop() {
        synchronized(callbacks) { callbacks.clear() }
        if (ready) tts.stop()
    }

    fun shutdown() {
        stop(); tts.shutdown()
    }
}
