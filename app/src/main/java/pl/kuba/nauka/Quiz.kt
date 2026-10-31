package pl.kuba.nauka

import kotlin.random.Random

/** Pytanie testowe ułożone automatycznie z tekstu (bez internetu). */
data class Question(
    val kind: Kind,
    val shown: String,       // co widać na ekranie
    val spoken: String,      // co czyta lektor
    val answer: String,      // poprawna odpowiedź (do pokazania i przeczytania)
    val truth: Boolean = true // dla prawda/fałsz
) {
    enum class Kind(val label: String) {
        NEXT("Co jest dalej?"),
        GAP("Jakiego słowa brakuje?"),
        TRUE_FALSE("Prawda czy fałsz?")
    }
}

object QuizMaker {

    private val stop = setOf(
        "i", "a", "w", "we", "z", "ze", "na", "do", "to", "o", "u", "od", "po", "za", "przez", "przy", "dla", "nad", "pod",
        "jest", "są", "był", "była", "było", "być", "się", "nie", "tak", "że", "czy", "jak", "ale", "lub", "oraz", "albo",
        "ten", "ta", "te", "tego", "tej", "tym", "tych", "jego", "jej", "ich", "on", "ona", "ono", "oni", "one", "my", "wy",
        "co", "kto", "który", "która", "które", "których", "gdy", "kiedy", "też", "także", "już", "jeszcze", "tylko",
        "bo", "aby", "żeby", "lecz", "ni", "ile", "ileż", "nasz", "nasza", "nasze", "naszą", "mnie", "mi", "go", "jemu"
    )

    private fun key(w: String) = w.trim { !it.isLetterOrDigit() }

    /** Słowa dobre do zakrycia: nie "się/nie/w", najlepiej dłuższe. */
    private fun candidates(line: String): List<String> =
        line.split(Regex("\\s+")).map(::key)
            .filter { it.length >= 4 && it.lowercase() !in stop && !it.all { c -> c.isDigit() } || (it.length >= 2 && it.all { c -> c.isDigit() }) }

    fun make(segs: List<Seg>, count: Int, rnd: Random = Random(System.nanoTime())): List<Question> {
        val out = mutableListOf<Question>()
        val usedLines = HashSet<String>()
        val n = segs.size
        val pool = (0 until n).filter { TextUtil.words(segs[it].text).size >= 2 }
        if (pool.isEmpty()) return out
        val allWords = segs.flatMap { candidates(it.text) }.distinctBy { it.lowercase() }

        var guard = 0
        while (out.size < count && guard++ < count * 25) {
            val i = pool[rnd.nextInt(pool.size)]
            val line = segs[i].text
            val kindRoll = rnd.nextInt(10)
            val q: Question? = when {
                kindRoll < 4 -> next(segs, i)
                kindRoll < 8 -> gap(line, rnd)
                else -> trueFalse(line, allWords, rnd)
            }
            if (q == null) continue
            val k = q.kind.name + TextUtil.norm(q.shown)
            if (k in usedLines) continue
            usedLines.add(k)
            out.add(q)
        }
        return out
    }

    private fun next(segs: List<Seg>, i: Int): Question? {
        if (i + 1 >= segs.size) return null
        val a = segs[i].text; val b = segs[i + 1].text
        if (TextUtil.norm(a) == TextUtil.norm(b) || TextUtil.words(b).size < 2) return null
        return Question(Question.Kind.NEXT, a, "Co jest dalej? $a ...", b)
    }

    private fun gap(line: String, rnd: Random): Question? {
        val c = candidates(line)
        if (c.isEmpty()) return null
        // dłuższe słowa częściej (zwykle ważniejsze)
        val word = c.sortedByDescending { it.length }.take(3).let { it[rnd.nextInt(it.size)] }
        val shown = line.replaceFirst(word, "_____")
        val spoken = "Jakiego słowa brakuje? " + line.replaceFirst(word, "... pik ...")
        return Question(Question.Kind.GAP, shown, spoken, word)
    }

    private fun trueFalse(line: String, allWords: List<String>, rnd: Random): Question? {
        val c = candidates(line)
        if (c.isEmpty()) return null
        val truth = rnd.nextBoolean()
        if (truth) return Question(Question.Kind.TRUE_FALSE, line, "Prawda czy fałsz? $line", "Prawda", true)
        val word = c[rnd.nextInt(c.size)]
        val others = allWords.filter { !it.equals(word, true) && TextUtil.wordSim(TextUtil.norm(it), TextUtil.norm(word)) < 0.6 }
        if (others.isEmpty()) return null
        var fake = others[rnd.nextInt(others.size)]
        if (word.first().isUpperCase()) fake = fake.replaceFirstChar { it.uppercase() } else fake = fake.lowercase()
        val changed = line.replaceFirst(word, fake)
        return Question(Question.Kind.TRUE_FALSE, changed, "Prawda czy fałsz? $changed",
            "Fałsz. Poprawnie: $line", false)
    }

    /** Sprawdza odpowiedź. Zwraca 1.0 = dobrze, 0.5 = prawie, 0 = źle. */
    fun check(q: Question, heard: List<String>): Double {
        var best = 0.0
        for (h in heard) {
            val score = when (q.kind) {
                Question.Kind.GAP -> if (TextUtil.saidWord(q.answer, h)) 1.0 else 0.0
                Question.Kind.NEXT -> TextUtil.coverage(q.answer, h).let { if (it >= 0.6) 1.0 else if (it >= 0.35) 0.5 else 0.0 }
                Question.Kind.TRUE_FALSE -> {
                    val w = TextUtil.words(h)
                    val yes = w.any { it in setOf("prawda", "prawde", "tak", "prawdziwe", "dobrze", "zgadza") }
                    val no = w.any { it in setOf("falsz", "fals", "nie", "nieprawda", "klamstwo", "zle", "bzdura") } || TextUtil.norm(h).contains("nieprawda")
                    when {
                        yes && !no -> if (q.truth) 1.0 else 0.0
                        no && !yes -> if (!q.truth) 1.0 else 0.0
                        else -> 0.0
                    }
                }
            }
            if (score > best) best = score
        }
        return best
    }
}
