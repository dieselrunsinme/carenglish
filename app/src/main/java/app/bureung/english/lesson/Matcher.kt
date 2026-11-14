package app.bureung.english.lesson

/**
 * Loose matching of what the child said against what we hoped to hear. Children's speech and on-device
 * recognition are both rough, so a near miss ("elefant", "lions") still counts: the reply is a recast either way,
 * never a "wrong".
 */
object Matcher {
    fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("[^\\p{L}\\p{Nd}' ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun matches(heard: String?, accept: List<String>): Boolean {
        if (heard.isNullOrBlank()) return false
        val said = normalize(heard)
        if (said.isEmpty()) return false
        val saidTokens = said.split(' ')
        return accept.any { raw ->
            val want = normalize(raw)
            when {
                want.isEmpty() -> false
                " $said ".contains(" $want ") -> true
                ' ' in want -> phraseClose(saidTokens, want.split(' '))
                else -> saidTokens.any { wordClose(it, want) }
            }
        }
    }

    /** every word of the phrase is (nearly) there, in any order */
    private fun phraseClose(said: List<String>, want: List<String>): Boolean =
        want.filter { it.length > 2 }.ifEmpty { want }.all { w -> said.any { wordClose(it, w) } }

    fun wordClose(said: String, want: String): Boolean {
        if (said == want) return true
        if (said.trimEnd('s') == want.trimEnd('s')) return true
        if (want.length <= 3) return false
        val distance = levenshtein(said, want)
        return distance <= if (want.length <= 5) 1 else 2
    }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }
}
