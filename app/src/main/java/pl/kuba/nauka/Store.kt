package pl.kuba.nauka

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Jeden materiał do nauki: tekst (z TXT/PDF/wklejony) albo audiobook. */
data class Material(
    val id: String,
    var title: String,
    val type: String,            // "text" albo "audio"
    var audioUri: String = "",
    var listens: Int = 0,        // ile razy przesłuchano całość
    var lastLine: Int = 0,
    var audioPos: Int = 0,
    var bestScore: Int = -1,     // najlepszy wynik testu w %
    var created: Long = System.currentTimeMillis()
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("title", title); put("type", type); put("audioUri", audioUri)
        put("listens", listens); put("lastLine", lastLine); put("audioPos", audioPos)
        put("bestScore", bestScore); put("created", created)
    }

    companion object {
        fun fromJson(o: JSONObject) = Material(
            o.getString("id"), o.getString("title"), o.getString("type"),
            o.optString("audioUri"), o.optInt("listens"), o.optInt("lastLine"),
            o.optInt("audioPos"), o.optInt("bestScore", -1), o.optLong("created")
        )
    }
}

object Store {
    private fun listFile(c: Context) = File(c.filesDir, "materialy.json")
    private fun textFile(c: Context, id: String) = File(c.filesDir, "tekst_$id.txt")
    private fun prefs(c: Context) = c.getSharedPreferences("ustawienia", Context.MODE_PRIVATE)

    fun all(c: Context): MutableList<Material> {
        val f = listFile(c)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            MutableList(arr.length()) { Material.fromJson(arr.getJSONObject(it)) }
                .sortedByDescending { it.created }.toMutableList()
        } catch (e: Exception) { mutableListOf() }
    }

    private fun saveAll(c: Context, list: List<Material>) {
        val arr = JSONArray(); list.forEach { arr.put(it.toJson()) }
        val tmp = File(c.filesDir, "materialy.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(listFile(c))
    }

    fun get(c: Context, id: String) = all(c).firstOrNull { it.id == id }

    fun update(c: Context, m: Material) {
        val list = all(c)
        val i = list.indexOfFirst { it.id == m.id }
        if (i >= 0) list[i] = m else list.add(m)
        saveAll(c, list)
    }

    fun addText(c: Context, title: String, text: String): Material {
        val m = Material(UUID.randomUUID().toString(), title, "text")
        textFile(c, m.id).writeText(text)
        update(c, m)
        return m
    }

    fun addAudio(c: Context, title: String, uri: String): Material {
        val m = Material(UUID.randomUUID().toString(), title, "audio", audioUri = uri)
        update(c, m)
        return m
    }

    fun text(c: Context, id: String): String =
        textFile(c, id).let { if (it.exists()) it.readText() else "" }

    fun saveText(c: Context, id: String, text: String) = textFile(c, id).writeText(text)

    fun delete(c: Context, id: String) {
        textFile(c, id).delete()
        saveAll(c, all(c).filter { it.id != id })
    }

    // --- ustawienia ---
    var Context.rate: Float
        get() = prefs(this).getFloat("rate", 0.9f)
        set(v) { prefs(this).edit().putFloat("rate", v).apply() }

    var Context.listensForQuiz: Int
        get() = prefs(this).getInt("listensForQuiz", 3)
        set(v) { prefs(this).edit().putInt("listensForQuiz", v).apply() }

    var Context.quizSize: Int
        get() = prefs(this).getInt("quizSize", 10)
        set(v) { prefs(this).edit().putInt("quizSize", v).apply() }
}
