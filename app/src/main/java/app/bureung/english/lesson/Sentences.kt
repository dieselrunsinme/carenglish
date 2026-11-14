package app.bureung.english.lesson

/** Lines are spoken, and pre-recorded, one sentence at a time. */
object Sentences {
    private val ABBREVIATIONS = setOf("Mr.", "Mrs.", "Ms.", "Dr.")

    /** "Hi, Harin! Welcome back!" → ["Hi, Harin!", "Welcome back!"]; "Mr. Sun said, hi." stays whole */
    fun split(text: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { w ->
            if (current.isNotEmpty()) current.append(' ')
            current.append(w)
            if (w.last() in ".!?" && w !in ABBREVIATIONS) {
                out += current.toString()
                current.clear()
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out.ifEmpty { listOf(text) }
    }

    /**
     * The sentence without the child's name, for a recording made without it: "Good morning, Jiwoo!" →
     * "Good morning!". Null when the name is not in the sentence.
     */
    fun withoutName(sentence: String, name: String): String? {
        if (name.isBlank() || !sentence.contains(name)) return null
        return sentence
            .replace(", $name", "")
            .replace(" $name", "")
            .replace(name, "")
            .replace(Regex("\\s+([!?.,])"), "$1")
            .replace(Regex("\\s+"), " ")
            .trim()
            .takeIf { it.any(Char::isLetter) }
    }
}
