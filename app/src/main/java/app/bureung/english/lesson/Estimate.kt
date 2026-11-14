package app.bureung.english.lesson

/** Rough spoken length of steps, slow enough for a five-year-old. Used to fit a lesson to the trip. */
object Estimate {
    private const val EN_SEC_PER_WORD = 0.45
    private const val KO_SEC_PER_SYLLABLE = 0.19
    private const val LINE_OVERHEAD = 0.6
    /** time for the reply after an Ask (praise or recast) */
    const val ASK_REPLY_SEC = 3.0
    const val ECHO_GAP_SEC = 0.8

    fun speech(text: String, lang: Lang): Double = when (lang) {
        Lang.EN -> LINE_OVERHEAD + text.split(' ', '\n').count { it.isNotBlank() } * EN_SEC_PER_WORD
        Lang.KO -> LINE_OVERHEAD + text.count { it.isLetterOrDigit() } * KO_SEC_PER_SYLLABLE
    }

    /** room the child gets to repeat a line: about as long as the line itself */
    fun echoGap(text: String): Double = speech(text, Lang.EN) + ECHO_GAP_SEC

    fun step(step: Step): Double = when (step) {
        is Step.Say -> speech(step.text, step.lang)
        is Step.Echo -> speech(step.text, Lang.EN) + echoGap(step.text)
        is Step.Ask -> step.waitSec + ASK_REPLY_SEC
        is Step.Pause -> step.sec
        is Step.Sfx -> step.sec
        is Step.Card -> 0.0
        // the open conversation has no fixed length: the plan's chat budget counts it
        is Step.Chat -> 0.0
    }

    fun block(block: Block): Double = block.steps.sumOf { step(it) }

    /** a conversation plan: the intro and goodbye plus the time set aside for talking */
    fun chat(plan: Plan): Int = (plan.blocks.sumOf { block(it) } + (plan.chat?.budgetSec ?: 0)).toInt()
}
