package app.bureung.english.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A soft picture-book look for a children's app, in Hyundai's own colours (the CI palette on hyundai.com): a Light
 * Sand page, white cards with very round corners, a Sky Blue sky with clouds behind 부릉이, tiles in tints of the
 * brand colours, chunky "pudding" buttons in Hyundai Blue, and a round typeface for everything bold. The words
 * the parent has to read stay dark on light, so it is still easy to read from the driver's seat.
 */
object Palette {
    /** Hyundai Light Sand */
    const val PAGE = 0xFFF6F3F2.toInt()
    const val CARD = 0xFFFFFFFF.toInt()
    /** the soft edge of a white card on the sand page */
    const val CARD_LINE = 0xFFEAE4DD.toInt()
    /** Hyundai Blue: titles and the main buttons */
    const val INK = 0xFF002C5F.toInt()
    const val INK2 = 0xFF3D5778.toInt()
    const val SUB = 0xFF8794A6.toInt()
    /** Hyundai Sand */
    const val SAND = 0xFFE4DCD3.toInt()
    const val LINE = SAND
    const val FILL = 0xFFEEE9E4.toInt()
    /** Active Blue, for bars, switches and outlines */
    const val ACTIVE = 0xFF00AAD2.toInt()
    /** Active Blue a step darker, for small coloured words on white (the pure colour is too light to read) */
    const val BLUE = 0xFF0087AD.toInt()
    const val BLUE_SOFT = 0xFFDDF3F9.toInt()
    const val BLUE_MID = 0xFF8ED6E9.toInt()
    /** Hyundai Gold */
    const val GOLD = 0xFFA36B4F.toInt()
    /** a good state ("준비됨", "다 외웠어요") */
    const val GOOD = BLUE
    /** Active Red */
    const val RED = 0xFFE63312.toInt()
    const val RED_SOFT = 0xFFFBE5E0.toInt()
    /** the day sky behind 부릉이: Sky Blue fading out */
    const val SKY_TOP = 0xFFAACAE6.toInt()
    const val SKY_BOTTOM = 0xFFE3EEF8.toInt()
    /** the evening sky of the driving screen: Hyundai Blue, dark so it does not glare at night */
    const val NIGHT = 0xFF002C5F.toInt()
    const val NIGHT_BOTTOM = 0xFF0A4380.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()

    /** tiles in tints of Active Blue, Sky Blue, Gold, Sand, Active Red and Hyundai Blue, one after another */
    val TILES = intArrayOf(BLUE_SOFT, 0xFFE3EEF8.toInt(), 0xFFF3E7E0.toInt(), 0xFFECE5DD.toInt(), RED_SOFT, 0xFFDFE6EF.toInt())

    /** the lower edge of a pudding button: the same colour a little darker (a dark colour gets a deeper shade) */
    fun deeper(color: Int): Int {
        val r = ((color shr 16 and 0xFF) * 0.72f).toInt()
        val g = ((color shr 8 and 0xFF) * 0.72f).toInt()
        val b = ((color and 0xFF) * 0.72f).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}

/**
 * The round typeface for titles, buttons and everything bold: Fredoka for English (the sentences the child says)
 * and Jua for Korean, both under the SIL Open Font License (assets/fonts).
 */
object Fonts {
    private var round: Typeface? = null

    fun round(context: Context): Typeface = round ?: load(context).also { round = it }

    private fun load(context: Context): Typeface {
        val korean = runCatching { Typeface.createFromAsset(context.assets, "fonts/Jua-Regular.ttf") }.getOrNull()
            ?: return Typeface.DEFAULT_BOLD
        if (android.os.Build.VERSION.SDK_INT < 29) return korean
        // English letters from Fredoka, everything it does not have (Korean) from Jua
        return runCatching {
            val latin = android.graphics.fonts.Font.Builder(context.assets, "fonts/Fredoka.ttf")
                .setFontVariationSettings("'wght' 600")
                .build()
            val hangul = android.graphics.fonts.Font.Builder(context.assets, "fonts/Jua-Regular.ttf").build()
            Typeface.CustomFallbackBuilder(android.graphics.fonts.FontFamily.Builder(latin).build())
                .addCustomFallback(android.graphics.fonts.FontFamily.Builder(hangul).build())
                .build()
        }.getOrDefault(korean)
    }
}

/**
 * The car screen is large and seen from arm's length, so everything is scaled with the app's window: about 1.3x on
 * the Pleos Connect display, 1x on a small screen.
 */
object Ui {
    var scale = 1f
        private set

    fun update(context: Context) {
        val width = context.resources.configuration.screenWidthDp
        scale = (width / 1280f).coerceIn(1f, 1.5f)
    }
}

fun Context.dp(v: Number): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat() * Ui.scale, resources.displayMetrics).toInt()

/** how much rounder than the numbers in the layouts: everything is a little softer */
private const val ROUNDER = 1.35f

fun Context.rounded(color: Int, radiusDp: Number, stroke: Int? = null, strokeDp: Number = 1): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp.toFloat() * ROUNDER).toFloat()
        if (stroke != null) setStroke(dp(strokeDp).coerceAtLeast(1), stroke)
    }

fun Context.text(
    value: CharSequence,
    sp: Number = 17,
    color: Int = Palette.INK,
    bold: Boolean = false,
    lines: Int = 0,
): TextView = TextView(this).apply {
    text = value
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat() * Ui.scale)
    setTextColor(color)
    letterSpacing = if (bold) 0f else -0.01f
    typeface = if (bold) Fonts.round(context) else Typeface.DEFAULT
    if (lines > 0) {
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
    }
    includeFontPadding = false
    setLineSpacing(0f, 1.25f)
}

enum class ButtonStyle { PRIMARY, SECONDARY, GHOST }

/**
 * A pill with a darker lower edge, like a pudding you can press. [radiusDp] is the corner before [rounded] makes it
 * rounder; the default is a full pill for a 56dp button.
 */
fun Context.pudding(color: Int, radiusDp: Number = 22, edgeDp: Number = 5): android.graphics.drawable.Drawable =
    android.graphics.drawable.LayerDrawable(arrayOf(rounded(Palette.deeper(color), radiusDp), rounded(color, radiusDp))).apply {
        setLayerInset(1, 0, 0, 0, dp(edgeDp))
    }

/** Round button with a ripple, at least 56dp tall. PRIMARY is a pudding button in Hyundai Blue. */
fun Context.button(label: CharSequence, style: ButtonStyle = ButtonStyle.PRIMARY, sp: Number = 18, onClick: () -> Unit): TextView {
    val (bg, fg) = when (style) {
        ButtonStyle.PRIMARY -> Palette.INK to Palette.WHITE
        ButtonStyle.SECONDARY -> Palette.FILL to Palette.INK2
        ButtonStyle.GHOST -> 0 to Palette.SUB
    }
    val chunky = style == ButtonStyle.PRIMARY
    return text(label, sp, fg, bold = true).apply {
        gravity = Gravity.CENTER
        minHeight = dp(56)
        setPadding(dp(22), dp(12), dp(22), dp(if (chunky) 16 else 12))
        val shape = if (chunky) pudding(bg) else rounded(bg, 22)
        background = RippleDrawable(ColorStateList.valueOf(0x1F000000), shape, rounded(Palette.WHITE, 22))
        isClickable = true
        setOnClickListener { onClick() }
    }
}

/**
 * A sky with a few soft clouds, clipped to round corners: the backdrop for 부릉이 (day on the home screen, evening
 * on the driving screen, where [stars] come out).
 */
class SkyDrawable(
    private val top: Int,
    private val bottom: Int,
    private val cloud: Int,
    private val radius: Float,
    private val stars: Boolean = false,
) : android.graphics.drawable.Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = android.graphics.Path()
    private val box = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val u = minOf(w, h)
        box.set(b)
        clip.reset()
        clip.addRoundRect(box, radius, radius, android.graphics.Path.Direction.CW)
        val saved = canvas.save()
        canvas.clipPath(clip)
        // full strength again: the clouds below leave their own see-through colour on the paint
        paint.color = 0xFF000000.toInt()
        paint.shader = android.graphics.LinearGradient(0f, box.top, 0f, box.bottom, top, bottom, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(box, paint)
        paint.shader = null

        fun puff(x: Float, y: Float, r: Float) = canvas.drawCircle(box.left + x, box.top + y, r, paint)
        if (stars) {
            paint.color = 0x66FFFFFF
            val dots = floatArrayOf(0.08f, 0.14f, 0.22f, 0.30f, 0.37f, 0.10f, 0.52f, 0.20f, 0.66f, 0.08f, 0.78f, 0.26f, 0.90f, 0.12f, 0.95f, 0.36f, 0.04f, 0.42f)
            for (i in dots.indices step 2) puff(w * dots[i], h * dots[i + 1], u * (if (i % 4 == 0) 0.006f else 0.004f))
        }
        paint.color = cloud
        // a small cloud up on the right
        puff(w - u * 0.34f, u * 0.17f, u * 0.060f)
        puff(w - u * 0.26f, u * 0.14f, u * 0.085f)
        puff(w - u * 0.17f, u * 0.17f, u * 0.065f)
        // a bank of clouds along the bottom
        puff(w * 0.04f, h + u * 0.03f, u * 0.17f)
        puff(w * 0.20f, h + u * 0.07f, u * 0.14f)
        puff(w * 0.62f, h + u * 0.08f, u * 0.15f)
        puff(w * 0.80f, h + u * 0.02f, u * 0.21f)
        puff(w * 0.96f, h + u * 0.01f, u * 0.16f)
        canvas.restoreToCount(saved)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(filter: android.graphics.ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

/** A pill for a small fact in the header ("하린 · Level 2"). */
fun Context.chip(value: CharSequence, bg: Int, fg: Int = Palette.INK2, sp: Number = 15): TextView =
    text(value, sp, fg, bold = true, lines = 1).apply {
        background = rounded(bg, 16)
        setPadding(dp(14), dp(8), dp(14), dp(8))
    }

/** An emoji on a white circle. */
fun Context.badge(emoji: String, sizeDp: Number = 56, sp: Number = 30, bg: Int = Palette.WHITE): TextView =
    text(emoji, sp).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(bg) }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

fun Context.vbox(gap: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    if (gap > 0) {
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerDrawable = GradientDrawable().apply { setSize(1, dp(gap)); setColor(0) }
    }
}

fun Context.hbox(gap: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    if (gap > 0) {
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerDrawable = GradientDrawable().apply { setSize(dp(gap), 1); setColor(0) }
    }
}

/** A white card with a soft edge on the cream page. */
fun Context.card(pad: Int = 28, gap: Int = 0): LinearLayout = vbox(gap).apply {
    background = rounded(Palette.CARD, 24, Palette.CARD_LINE, 1.5)
    setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
}

/** Toss-style list row: label on the left in grey, value on the right in ink. */
fun Context.row(label: String, value: CharSequence, valueColor: Int = Palette.INK): View = hbox().apply {
    setPadding(0, dp(8), 0, dp(8))
    addView(text(label, 17, Palette.SUB), weightWrap(1f))
    addView(text(value, 17, valueColor, bold = true).apply { gravity = Gravity.END }, wrap())
}

fun Context.divider(): View = View(this).apply {
    setBackgroundColor(Palette.LINE)
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1).coerceAtLeast(1))
}

/** Segmented control: a cream track, the chosen option on a white pill. */
fun <T> Context.segmented(options: List<Pair<T, String>>, current: T, sp: Number = 16, onPick: (T) -> Unit): LinearLayout =
    hbox().apply {
        background = rounded(Palette.FILL, 18)
        setPadding(dp(5), dp(5), dp(5), dp(5))
        options.forEach { (value, label) ->
            val on = value == current
            addView(text(label, sp, if (on) Palette.BLUE else Palette.SUB, bold = true, lines = 1).apply {
                gravity = Gravity.CENTER
                minHeight = dp(46)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = if (on) rounded(Palette.CARD, 14, Palette.CARD_LINE, 1.5) else null
                isClickable = true
                setOnClickListener { onPick(value) }
            }, weightWrap(1f))
        }
    }

/** A small status label (no pill): coloured text only. */
fun Context.tag(value: CharSequence, color: Int, sp: Number = 14): TextView = text(value, sp, color, bold = true)

/**
 * The lesson as one bar: each block a segment sized by its length. Done blocks are blue, the current one is half
 * blue, the rest grey.
 */
class SegmentBar(context: Context) : View(context) {
    private var weights: List<Float> = emptyList()
    private var current = -1
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun set(weights: List<Float>, current: Int) {
        this.weights = weights
        this.current = current
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (weights.isEmpty()) return
        val gap = context.dp(4).toFloat()
        val total = weights.sum().coerceAtLeast(0.001f)
        val usable = width - gap * (weights.size - 1)
        val radius = height / 2f
        var x = 0f
        weights.forEachIndexed { i, w ->
            val segment = (usable * w / total).coerceAtLeast(height.toFloat())
            rect.set(x, 0f, (x + segment).coerceAtMost(width.toFloat()), height.toFloat())
            paint.color = when {
                current < 0 -> Palette.LINE
                i < current -> Palette.ACTIVE
                i == current -> Palette.BLUE_MID
                else -> Palette.LINE
            }
            canvas.drawRoundRect(rect, radius, radius, paint)
            x += segment + gap
        }
    }
}

fun wrap(): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

fun matchWidth(): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

fun weight(w: Float): LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, w)

fun weightWrap(w: Float): LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)

/** Sets [params] with margins (dp) on this view, for a one-argument addView. */
fun <T : View> T.lp(
    params: LinearLayout.LayoutParams,
    top: Int = 0,
    bottom: Int = 0,
    start: Int = 0,
    end: Int = 0,
): T {
    params.setMargins(context.dp(start), context.dp(top), context.dp(end), context.dp(bottom))
    layoutParams = params
    return this
}
