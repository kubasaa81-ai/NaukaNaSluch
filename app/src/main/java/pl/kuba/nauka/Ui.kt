package pl.kuba.nauka

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton

/** Wspólne kolory i "klocki" interfejsu – nowoczesne, kolorowe przyciski. */
object Ui {
    const val GREEN = 0xFF2F6B4F.toInt()
    const val RED = 0xFFB3262D.toInt()
    const val GOLD = 0xFFA8780E.toInt()
    const val BLUE = 0xFF2B5C9E.toInt()
    const val PURPLE = 0xFF6A4C93.toInt()
    const val TEAL = 0xFF1F7A80.toInt()
    const val GREY = 0xFF5E5A52.toInt()

    fun dark(c: Context) =
        (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun bg(c: Context) = if (dark(c)) 0xFF1A1917.toInt() else 0xFFF3F1EC.toInt()
    fun paper(c: Context) = if (dark(c)) 0xFF24221F.toInt() else 0xFFFFFDF8.toInt()
    fun fg(c: Context) = if (dark(c)) 0xFFEFEBE3.toInt() else 0xFF23211D.toInt()
    fun muted(c: Context) = if (dark(c)) 0xFFA59F93.toInt() else 0xFF6B665C.toInt()
    fun line(c: Context) = if (dark(c)) 0xFF3A3732.toInt() else 0xFFDDD7CB.toInt()
    fun highlight(c: Context) = if (dark(c)) 0xFF4A3F1C.toInt() else 0xFFF8E6AE.toInt()
    fun hiddenBox(c: Context) = if (dark(c)) 0xFF3A3732.toInt() else 0xFFDDD7CB.toInt()

    fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    fun button(c: Context, text: String, color: Int, onClick: () -> Unit): MaterialButton =
        MaterialButton(c).apply {
            this.text = text
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(color)
            cornerRadius = c.dp(14)
            minHeight = c.dp(52)
            insetTop = 0; insetBottom = 0
            setPadding(c.dp(14), c.dp(8), c.dp(14), c.dp(8))
            setOnClickListener { onClick() }
        }

    fun text(c: Context, s: String, size: Float = 17f, bold: Boolean = false, color: Int = fg(c)) =
        TextView(c).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(0f, 1.15f)
        }

    fun card(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(paper(c)); cornerRadius = c.dp(16).toFloat()
            setStroke(c.dp(1), line(c))
        }
        setPadding(c.dp(16), c.dp(14), c.dp(16), c.dp(14))
    }

    fun vbox(c: Context) = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    fun hbox(c: Context) = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight)

    fun View.margins(c: Context, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): View {
        val p = (layoutParams as? ViewGroup.MarginLayoutParams) ?: lp()
        p.setMargins(c.dp(l), c.dp(t), c.dp(r), c.dp(b)); layoutParams = p
        return this
    }

    /** Pasek tytułu na górze ekranu */
    fun header(c: Context, title: String, subtitle: String? = null): LinearLayout = vbox(c).apply {
        setBackgroundColor(GREEN)
        setPadding(c.dp(18), c.dp(16), c.dp(18), c.dp(14))
        addView(text(c, title, 22f, true, Color.WHITE))
        if (subtitle != null) addView(text(c, subtitle, 14f, false, 0xFFDDEDE4.toInt()))
    }
}
