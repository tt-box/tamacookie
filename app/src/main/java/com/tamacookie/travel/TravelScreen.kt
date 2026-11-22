package com.tamacookie.travel

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tamacookie.Palette
import com.tamacookie.describePlain
import com.tamacookie.touchTarget

/**
 * L'écran du voyage : un grand emoji (décoratif), un titre, un message (annoncé par le lecteur d'écran à chaque
 * changement) et jusqu'à deux boutons.
 */
class TravelScreen(private val activity: Activity) {
    private val d = activity.resources.displayMetrics.density

    private val emoji = TextView(activity).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 56f)
        gravity = Gravity.CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val title = TextView(activity).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        setTextColor(Palette.INK); paint.isFakeBoldText = true
        gravity = Gravity.CENTER
        isAccessibilityHeading = true
    }
    private val message = TextView(activity).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setTextColor(Palette.INK)
        gravity = Gravity.CENTER
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        setPadding(0, (16 * d).toInt(), 0, (24 * d).toInt())
    }
    private val primary = button()
    private val secondary = button()

    /** Contenu (reçoit les marges des barres système, [com.tamacookie.EdgeToEdge]). */
    val content: View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val p = (22 * d).toInt(); setPadding(p, p, p, p)
        for (v in listOf(emoji, title, message)) addView(v, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        for (b in listOf(primary, secondary)) {
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = (10 * d).toInt() }
            addView(b, lp)
        }
    }

    val root: View = ScrollView(activity).apply {
        setBackgroundColor(Palette.UI_BG)
        isFillViewport = true
        addView(content)
    }

    /** Titre et emoji (l'emoji n'est pas lu). */
    fun header(titleText: String, emojiText: String) {
        title.text = titleText
        emoji.text = emojiText
    }

    fun emoji(text: String) { emoji.text = text }

    fun message(text: String) {
        message.text = text
        message.describePlain(text)
    }

    /** Un seul bouton ; [label2] / [run2] : un second. */
    fun buttons(label: Int, run: () -> Unit, label2: Int? = null, run2: (() -> Unit)? = null) {
        show(primary, label, run)
        if (label2 != null && run2 != null) show(secondary, label2, run2) else secondary.visibility = View.GONE
    }

    private fun show(b: TextView, label: Int, run: () -> Unit) {
        b.setText(label)
        b.visibility = View.VISIBLE
        b.setOnClickListener { run() }
    }

    private fun button() = TextView(activity).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(Palette.INK)
        gravity = Gravity.CENTER
        val p = (12 * d).toInt(); setPadding(p, p, p, p)
        touchTarget(d)
        background = GradientDrawable().apply { cornerRadius = 12 * d; setColor(Palette.UI_CHIP) }
        visibility = View.GONE
    }
}
