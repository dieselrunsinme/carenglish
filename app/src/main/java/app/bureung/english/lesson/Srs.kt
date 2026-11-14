package app.bureung.english.lesson

/** Review state of one word. [due] is an epoch day. */
data class WordState(val stage: Int, val due: Long, val seen: Int = 0, val hits: Int = 0) {
    val mastered: Boolean get() = stage >= Srs.GAPS.size
}

/**
 * Spaced repetition kept deliberately simple for preschoolers: a new word comes back the same afternoon (하원),
 * then three days later, then a week later. A missed review comes back the next day.
 */
object Srs {
    val GAPS = intArrayOf(0, 3, 7)

    fun introduce(today: Long, previous: WordState?): WordState = previous ?: WordState(0, today + GAPS[0])

    fun reviewed(state: WordState, today: Long, success: Boolean): WordState {
        if (!success) return state.copy(due = today + 1, seen = state.seen + 1)
        val next = state.stage + 1
        val due = if (next < GAPS.size) today + GAPS[next] else Long.MAX_VALUE
        return WordState(next, due, state.seen + 1, state.hits + 1)
    }

    fun due(states: Map<String, WordState>, today: Long): List<String> =
        states.entries
            .filter { !it.value.mastered && it.value.due <= today }
            .sortedWith(compareBy({ it.value.due }, { it.value.stage }))
            .map { it.key }
}
