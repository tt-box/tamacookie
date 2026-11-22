package com.tamacookie.species.rabbit

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.tamacookie.Accessory
import com.tamacookie.AmbientAnimator
import com.tamacookie.Ears
import com.tamacookie.Expr
import com.tamacookie.Look
import com.tamacookie.Options
import com.tamacookie.Palette
import com.tamacookie.Pattern
import com.tamacookie.PetRenderState
import com.tamacookie.PetRenderer
import com.tamacookie.Pose
import com.tamacookie.feature.toy.Food
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dessin du lapin, entièrement en Canvas (aucune image) : renderer de l'espèce [Rabbit], appelé par la
 * vue générique `PetView` (qui garde l'animation ambiante et les particules). [d] = densité (px par dp).
 */
class RabbitRenderer(private val d: Float) : PetRenderer {
    /** État et animation de l'image en cours de dessin (posés par [draw]). */
    private var state = PetRenderState()
    private lateinit var ambient: AmbientAnimator
    private val pose get() = state.pose
    private val expr get() = state.expr
    private val facing get() = state.facing
    private val walkPhase get() = state.walkPhase
    private val airVy get() = state.airVy
    private val eatProgress get() = state.eatProgress
    private val dirty get() = state.dirty
    private val look get() = state.look
    private val t get() = ambient.t

    // --- Peintures ---
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Palette.OUTLINE
        strokeWidth = 5f; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val fur = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.FUR }
    private val furShade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.FUR_SHADE }
    private val pink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.PINK }
    private val cheek = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.CHEEK }
    private val sickCheek = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.SICK_CHEEK }
    private val nose = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.NOSE }
    private val eye = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.INK }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Palette.INK
        strokeWidth = 2.6f; strokeCap = Paint.Cap.ROUND
    }
    private val dirt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.DIRT }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.SHADOW }
    private val carrot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.CARROT }
    private val leaf = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.LEAF }
    private val rect = RectF()
    private val path = Path()
    private val clip = Path()

    // --- Personnalisation ---
    private val patch = Paint(Paint.ANTI_ALIAS_FLAG)
    private val acc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.8f; strokeJoin = Paint.Join.ROUND
    }
    private val hatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.HAT }
    private val flowerCenter = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.YELLOW }
    private val lens = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.LENS }
    private val spot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.FUR_SPOT }
    private val gold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.CROWN_GOLD }
    private val goldLight = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.CROWN_SHINE }
    // --- Saisons (décor ajouté au dessin : l'apparence sauvegardée n'est pas touchée) ---
    private val seasonPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var darkFur = false
    /** Nourriture dont la couleur est dans [carrot] (parseColor seulement quand elle change). */
    private var paintedFood = Food.CARROT

    private fun applyLook(look: Look) {
        val f = Options.color(look.fur)
        fur.color = f
        furShade.color = Options.shade(f, 0.93f)
        darkFur = Options.isDark(f)
        outline.color = if (darkFur) Palette.OUTLINE_DARK else Palette.OUTLINE
        line.color = if (darkFur) Palette.CREAM else Palette.INK
        eye.color = Options.color(look.eyes)
        val lum = 0.299f * Color.red(f) + 0.587f * Color.green(f) + 0.114f * Color.blue(f)
        patch.color = when {
            lum > 190f -> Palette.PATCH_LIGHT
            lum > 110f -> Options.shade(f, 0.55f)
            else -> Palette.CREAM
        }
        acc.color = Options.color(look.accColor)
        accStroke.color = outline.color
    }

    init { applyLook(state.look) }

    override fun onState(old: PetRenderState, s: PetRenderState) {
        if (s.look != old.look) applyLook(s.look)
        if (s.food != paintedFood) { paintedFood = s.food; carrot.color = Color.parseColor(s.food.color) }
    }

    private fun unit() = AmbientAnimator.UNIT

    override fun draw(c: Canvas, s: PetRenderState, ambient: AmbientAnimator, width: Int, height: Int) {
        state = s
        this.ambient = ambient
        val cx = width / 2f
        val fy = height - 6f * d
        val s = d * unit()

        if (pose != Pose.AIR && pose != Pose.DRAGGED) {
            val w = if (pose == Pose.WALK) 24f - 6f * sin(walkPhase * PI.toFloat()) else 28f
            rect.set(cx - w * s, fy - 5f * s, cx + w * s, fy + 4f * s)
            c.drawOval(rect, shadow)
        }

        c.save()
        c.translate(cx, fy)
        c.scale(facing * s, s)
        if (state.seasonFlowers) groundFlowers(c)
        if (state.seasonPumpkin) pumpkin(c, -50f, 0f)

        // --- squash & stretch ---
        var yOff = 0f
        var sx = 1f
        var sy = 1f
        when (pose) {
            Pose.IDLE, Pose.SIT -> {
                val b = 1f + 0.025f * sin(t * 2.6f); sy = b; sx = 1f / b
            }
            Pose.WALK -> {
                val h = sin(walkPhase * PI.toFloat())
                yOff = -h * 14f
                val squash = if (walkPhase < 0.12f || walkPhase > 0.88f) 0.1f else -0.05f * h
                sy = 1f - squash; sx = 1f + squash
            }
            Pose.AIR -> {
                val st = (-airVy / (2600f * d)).coerceIn(-0.15f, 0.18f)
                sy = 1f + st; sx = 1f - st * 0.6f
            }
            Pose.SLEEP -> {
                val b = 1f + 0.04f * sin(t * 1.6f); sy = b; sx = 1f
            }
            Pose.EAT -> {
                val b = 1f + 0.02f * sin(t * 14f); sy = b; sx = 1f / b
            }
            Pose.DRAGGED -> { sy = 1.06f; sx = 0.96f }
        }
        c.translate(0f, yOff)
        c.scale(sx, sy)

        if (pose == Pose.SLEEP) drawSleeping(c) else drawBunny(c)
        c.restore()
    }


    // ------------------------------------------------------------------
    //  Lapin debout
    // ------------------------------------------------------------------
    private fun drawBunny(c: Canvas) {
        val dragged = pose == Pose.DRAGGED
        // Oreilles : angle selon l'humeur
        val earTwitch = ambient.earTwitch
        val twitch = if (earTwitch > 0f) sin(earTwitch * 30f) * 8f else 0f
        var a1: Float
        var a2: Float
        when {
            dragged -> { a1 = -25f + sin(t * 7f) * 18f; a2 = 15f + sin(t * 7f + 1f) * 18f }
            expr == Expr.SAD || expr == Expr.SICK -> { a1 = -62f; a2 = -48f }
            expr == Expr.SLEEPY -> { a1 = -40f; a2 = -22f }
            expr == Expr.HAPPY -> { a1 = -14f + sin(t * 8f) * 6f; a2 = 10f - sin(t * 8f) * 6f }
            expr == Expr.SURPRISED -> { a1 = -6f; a2 = 12f }
            pose == Pose.WALK -> { a1 = -22f - sin(walkPhase * 6.28f) * 8f; a2 = -4f - sin(walkPhase * 6.28f) * 8f }
            else -> { a1 = -16f; a2 = 4f + twitch }
        }

        val bodyCx = if (dragged) 0f else -4f
        val bodyCy = if (dragged) -28f else -24f
        val bodyRx = if (dragged) 21f else 26f
        val bodyRy = if (dragged) 24f else 21f
        val headCx = if (dragged) 4f else 8f
        val headCy = if (dragged) -60f else -54f

        // Style d'oreilles
        var b1x = headCx - 7f; var b1y = headCy - 14f
        var b2x = headCx + 7f; var b2y = headCy - 15f
        var earLen = 1f
        when (look.ears) {
            Ears.LOP -> { // tombantes (bélier)
                val sway = when {
                    dragged -> sin(t * 7f) * 14f
                    pose == Pose.WALK -> sin(walkPhase * 6.28f) * 7f
                    else -> sin(t * 1.5f) * 2f
                }
                a1 = -158f + sway; a2 = 158f + sway
                b1x = headCx - 16f; b1y = headCy - 9f
                b2x = headCx + 19f; b2y = headCy - 9f
                earLen = 0.82f
            }
            Ears.ONE_FOLDED -> a2 = if (dragged) a2 + 60f else 80f + twitch   // une oreille pliée
            Ears.UP -> {}
        }

        for (pass in 0..1) {
            val o = pass == 0
            // oreille arrière
            ear(c, b1x, b1y, a1, o, back = true, len = earLen)
            // queue
            circle(c, bodyCx - bodyRx + 1f, bodyCy + 2f, 8.5f, if (o) outline else fur)
            // pattes arrière
            if (dragged) {
                val sw = sin(t * 7f) * 3f
                oval(c, -9f + sw, -4f, 6f, 10f, if (o) outline else fur)
                oval(c, 9f + sw, -4f, 6f, 10f, if (o) outline else fur)
            } else {
                oval(c, -14f, -5f, 13f, 5.5f, if (o) outline else fur)
            }
            // corps
            oval(c, bodyCx, bodyCy, bodyRx, bodyRy, if (o) outline else fur)
            if (!o) oval(c, bodyCx + 6f, bodyCy + 6f, bodyRx * 0.55f, bodyRy * 0.6f, furShade)
            if (!o && look.pattern == Pattern.DUTCH) {
                // Hollandais : arrière du corps coloré
                c.save(); clipOval(c, bodyCx, bodyCy, bodyRx, bodyRy)
                c.drawRect(bodyCx - bodyRx - 2f, bodyCy - bodyRy - 2f, bodyCx - bodyRx * 0.1f, bodyCy + bodyRy + 2f, patch)
                c.restore()
            }
            if (!o && look.fur.spotted) {
                // Tacheté : quelques taches sur le dos et le flanc
                c.save(); clipOval(c, bodyCx, bodyCy, bodyRx, bodyRy)
                oval(c, bodyCx - 12f, bodyCy - 9f, 8f, 6.5f, spot)
                oval(c, bodyCx + 4f, bodyCy - 14f, 5f, 4f, spot)
                oval(c, bodyCx - 18f, bodyCy + 7f, 5.5f, 5f, spot)
                c.restore()
            }
            // patte avant
            if (!dragged) oval(c, 12f, -5f, 10f, 5f, if (o) outline else fur)
            else {
                oval(c, -12f, -30f, 5f, 8f, if (o) outline else fur)
                oval(c, 16f, -30f, 5f, 8f, if (o) outline else fur)
            }
            // tête
            oval(c, headCx, headCy, 22f, 20f, if (o) outline else fur)
            if (!o && look.pattern != Pattern.PLAIN) {
                c.save(); clipOval(c, headCx, headCy, 22f, 20f)
                if (look.pattern == Pattern.EYE_PATCH) oval(c, headCx - 7f, headCy - 1f, 8.5f, 7.5f, patch)
                else {
                    oval(c, headCx - 15f, headCy - 2f, 12f, 15f, patch)
                    oval(c, headCx + 18f, headCy - 2f, 10f, 15f, patch)
                }
                c.restore()
            }
            if (!o && look.fur.spotted) {
                c.save(); clipOval(c, headCx, headCy, 22f, 20f)
                oval(c, headCx - 14f, headCy - 12f, 6f, 5f, spot)
                c.restore()
            }
            // oreille avant
            ear(c, b2x, b2y, a2, o, back = false, len = earLen)
        }

        // taches de saleté
        if (dirty) {
            circle(c, bodyCx - 10f, bodyCy - 6f, 3.5f, dirt)
            circle(c, bodyCx + 8f, bodyCy + 8f, 2.6f, dirt)
            circle(c, headCx - 12f, headCy + 6f, 2.4f, dirt)
        }

        drawFace(c, headCx, headCy)
        drawAccessory(c, headCx, headCy, 20f, standing = true)

        if (pose == Pose.EAT) drawCarrotInMouth(c, headCx + 16f, headCy + 12f)
    }

    private fun clipOval(c: Canvas, x: Float, y: Float, rx: Float, ry: Float) {
        clip.reset()
        rect.set(x - rx, y - ry, x + rx, y + ry)
        clip.addOval(rect, Path.Direction.CW)
        c.clipPath(clip)
    }

    private fun ear(c: Canvas, x: Float, y: Float, angle: Float, outlinePass: Boolean, back: Boolean, len: Float = 1f) {
        c.save()
        c.translate(x, y)
        c.rotate(angle)
        rect.set(-6.5f, -38f * len, 6.5f, 4f)
        val furP = if (look.pattern == Pattern.DUTCH) patch else if (back) furShade else fur
        c.drawOval(rect, if (outlinePass) outline else furP)
        if (!outlinePass) {
            rect.set(-3.2f, -33f * len, 3.2f, -2f)
            c.drawOval(rect, pink)
        }
        c.restore()
    }

    // ------------------------------------------------------------------
    //  Accessoires
    // ------------------------------------------------------------------
    private fun drawAccessory(c: Canvas, hx: Float, hy: Float, hr: Float, standing: Boolean) {
        if (state.seasonHat) { santaHat(c, hx + 1f, hy - hr * 0.86f); return }
        when (look.accessory) {
            Accessory.BOW -> bow(c, hx + hr * 0.6f, hy - hr * 0.85f)
            Accessory.HAT -> hat(c, hx + 1f, hy - hr * 0.88f)
            Accessory.GLASSES -> if (standing) glasses(c, hx, hy)
            Accessory.SCARF -> if (standing) scarf(c, hx, hy)
            Accessory.FLOWERS -> flowers(c, hx, hy, hr)
            Accessory.CROWN -> crown(c, hx + 1f, hy - hr * 0.86f)
            Accessory.BANDANA -> if (standing) bandana(c, hx, hy)
            Accessory.BOW_TIE -> if (standing) bowTie(c, hx + 1f, hy + 20f)
            Accessory.NONE -> {}
        }
    }

    private fun bow(c: Canvas, x: Float, y: Float) {
        c.save(); c.translate(x, y); c.rotate(15f)
        path.reset()
        path.moveTo(0f, 0f); path.lineTo(-10f, -6.5f); path.lineTo(-10f, 6.5f); path.close()
        path.moveTo(0f, 0f); path.lineTo(10f, -6.5f); path.lineTo(10f, 6.5f); path.close()
        c.drawPath(path, acc); c.drawPath(path, accStroke)
        c.drawCircle(0f, 0f, 3f, acc); c.drawCircle(0f, 0f, 3f, accStroke)
        c.restore()
    }

    private fun hat(c: Canvas, x: Float, y: Float) {
        c.save(); c.translate(x, y); c.rotate(-10f)
        rect.set(-15f, -3.5f, 15f, 3.5f); c.drawOval(rect, hatPaint); c.drawOval(rect, accStroke)
        rect.set(-9f, -18f, 9f, 0f); c.drawRoundRect(rect, 2f, 2f, hatPaint); c.drawRoundRect(rect, 2f, 2f, accStroke)
        rect.set(-9f, -6f, 9f, -2f); c.drawRect(rect, acc)
        c.restore()
    }

    private fun glasses(c: Canvas, hx: Float, hy: Float) {
        val sw = accStroke.strokeWidth
        accStroke.color = acc.color; accStroke.strokeWidth = 2.2f
        c.drawCircle(hx - 7f, hy - 1f, 6.5f, lens); c.drawCircle(hx - 7f, hy - 1f, 6.5f, accStroke)
        c.drawCircle(hx + 9f, hy - 1f, 6.5f, lens); c.drawCircle(hx + 9f, hy - 1f, 6.5f, accStroke)
        c.drawLine(hx - 0.5f, hy - 2f, hx + 2.5f, hy - 2f, accStroke)
        accStroke.color = outline.color; accStroke.strokeWidth = sw
    }

    private fun scarf(c: Canvas, hx: Float, hy: Float) {
        rect.set(hx - 18f, hy + 14f, hx + 15f, hy + 22f)
        c.drawRoundRect(rect, 4f, 4f, acc); c.drawRoundRect(rect, 4f, 4f, accStroke)
        c.save(); c.translate(hx - 12f, hy + 19f); c.rotate(12f + sin(t * 3f) * 4f)
        rect.set(-4f, 0f, 4f, 17f)
        c.drawRoundRect(rect, 3f, 3f, acc); c.drawRoundRect(rect, 3f, 3f, accStroke)
        c.restore()
    }

    // ------------------------------------------------------------------
    //  Saisons
    // ------------------------------------------------------------------
    /** Noël : bonnet rouge à pompon (seulement sans accessoire, voir [PetRenderState.seasonHat]). */
    private fun santaHat(c: Canvas, x: Float, y: Float) {
        c.save(); c.translate(x, y); c.rotate(-12f)
        seasonPaint.color = Palette.SANTA_RED
        path.reset()
        path.moveTo(-13f, 0f); path.quadTo(-4f, -22f, 14f, -16f); path.quadTo(6f, -10f, 13f, 0f); path.close()
        c.drawPath(path, seasonPaint); c.drawPath(path, accStroke)
        seasonPaint.color = Palette.SANTA_WHITE
        rect.set(-15f, -3.5f, 15f, 4f)
        c.drawRoundRect(rect, 4f, 4f, seasonPaint); c.drawRoundRect(rect, 4f, 4f, accStroke)
        c.drawCircle(15f, -16f, 4f, seasonPaint); c.drawCircle(15f, -16f, 4f, accStroke)
        c.restore()
    }

    /** Halloween : petite citrouille posée au sol à ([x], [y]), avec son visage. */
    private fun pumpkin(c: Canvas, x: Float, y: Float) {
        seasonPaint.color = Palette.PUMPKIN_STEM
        rect.set(x - 1.8f, y - 22f, x + 1.8f, y - 15f); c.drawRect(rect, seasonPaint)
        seasonPaint.color = Palette.PUMPKIN_DARK
        oval(c, x - 6f, y - 8f, 7f, 8f, seasonPaint); oval(c, x + 6f, y - 8f, 7f, 8f, seasonPaint)
        seasonPaint.color = Palette.PUMPKIN
        oval(c, x, y - 8.5f, 8f, 8.5f, seasonPaint)
        rect.set(x - 13f, y - 17f, x + 13f, y); c.drawOval(rect, accStroke)
        seasonPaint.color = Palette.PUMPKIN_FACE
        path.reset()
        path.moveTo(x - 7f, y - 11f); path.lineTo(x - 3f, y - 11f); path.lineTo(x - 5f, y - 14f); path.close()
        path.moveTo(x + 3f, y - 11f); path.lineTo(x + 7f, y - 11f); path.lineTo(x + 5f, y - 14f); path.close()
        c.drawPath(path, seasonPaint)
        rect.set(x - 6f, y - 8f, x + 6f, y - 3f); c.drawArc(rect, 0f, 180f, true, seasonPaint)
    }

    /** Printemps : trois fleurs dans l'herbe, à ses pieds. */
    private fun groundFlowers(c: Canvas) {
        for (i in 0 until 3) {
            val x = FLOWER_SPOTS[i * 2]; val y = FLOWER_SPOTS[i * 2 + 1]
            seasonPaint.color = Palette.LEAF
            rect.set(x - 0.9f, y - 9f, x + 0.9f, y); c.drawRect(rect, seasonPaint)
            seasonPaint.color = if (i == 1) Palette.SPRING_FLOWER_2 else Palette.SPRING_FLOWER
            for (k in 0..4) {
                val a = k * 1.2566f
                c.drawCircle(x + cos(a) * 2.6f, y - 10f + sin(a) * 2.6f, 2.3f, seasonPaint)
            }
            c.drawCircle(x, y - 10f, 1.6f, flowerCenter)
        }
    }

    /** Couronne dorée à trois pointes, une pierre de la couleur de l'accessoire. */
    private fun crown(c: Canvas, x: Float, y: Float) {
        c.save(); c.translate(x, y); c.rotate(-8f)
        path.reset()
        path.moveTo(-11f, 2f); path.lineTo(-12f, -10f); path.lineTo(-6f, -4f); path.lineTo(0f, -13f)
        path.lineTo(6f, -4f); path.lineTo(12f, -10f); path.lineTo(11f, 2f); path.close()
        c.drawPath(path, gold); c.drawPath(path, accStroke)
        rect.set(-10f, -2f, 10f, 2f); c.drawRect(rect, goldLight)
        c.drawCircle(0f, -1f, 2.4f, acc); c.drawCircle(0f, -1f, 2.4f, accStroke)
        c.drawCircle(-12f, -10f, 1.6f, goldLight); c.drawCircle(12f, -10f, 1.6f, goldLight); c.drawCircle(0f, -13f, 1.6f, goldLight)
        c.restore()
    }

    /** Bandana noué au cou : un triangle qui pointe vers le bas, avec des pois. */
    private fun bandana(c: Canvas, hx: Float, hy: Float) {
        path.reset()
        path.moveTo(hx - 17f, hy + 14f); path.lineTo(hx + 15f, hy + 14f); path.lineTo(hx - 2f, hy + 30f); path.close()
        c.drawPath(path, acc); c.drawPath(path, accStroke)
        c.drawCircle(hx - 8f, hy + 18f, 1.4f, white); c.drawCircle(hx + 5f, hy + 18f, 1.4f, white)
        c.drawCircle(hx - 2f, hy + 24f, 1.4f, white)
    }

    /** Nœud papillon sous le menton. */
    private fun bowTie(c: Canvas, x: Float, y: Float) {
        path.reset()
        path.moveTo(0f + x, y); path.lineTo(x - 8f, y - 5f); path.lineTo(x - 8f, y + 5f); path.close()
        path.moveTo(0f + x, y); path.lineTo(x + 8f, y - 5f); path.lineTo(x + 8f, y + 5f); path.close()
        c.drawPath(path, acc); c.drawPath(path, accStroke)
        c.drawCircle(x, y, 2.4f, acc); c.drawCircle(x, y, 2.4f, accStroke)
    }

    private fun flowers(c: Canvas, hx: Float, hy: Float, hr: Float) {
        for (i in 0..4) {
            val a = Math.toRadians((205.0 + i * 32.5)).toFloat()
            val px = hx + cos(a) * hr * 1.08f
            val py = hy + sin(a) * hr * 1.0f
            for (k in 0..3) {
                val pa = k * 1.5708f + i
                c.drawCircle(px + cos(pa) * 2.4f, py + sin(pa) * 2.4f, 2.5f, acc)
            }
            c.drawCircle(px, py, 1.6f, flowerCenter)
        }
    }

    private fun drawFace(c: Canvas, hx: Float, hy: Float) {
        val e1x = hx - 7f; val e2x = hx + 9f; val ey = hy - 1f
        val closed = ambient.blinking
        when {
            expr == Expr.HAPPY -> { arcEye(c, e1x, ey, up = true); arcEye(c, e2x, ey, up = true) }
            expr == Expr.TONGUE -> { arcEye(c, e1x, ey, up = true); dotEye(c, e2x, ey) }   // clin d'œil
            expr == Expr.SURPRISED || pose == Pose.DRAGGED -> { bigEye(c, e1x, ey); bigEye(c, e2x, ey) }
            expr == Expr.DIZZY -> { spiralEye(c, e1x, ey); spiralEye(c, e2x, ey) }
            expr == Expr.SLEEPY || expr == Expr.SICK -> { lidEye(c, e1x, ey); lidEye(c, e2x, ey) }
            closed -> { c.drawLine(e1x - 3.5f, ey, e1x + 3.5f, ey, line); c.drawLine(e2x - 3.5f, ey, e2x + 3.5f, ey, line) }
            else -> { dotEye(c, e1x, ey); dotEye(c, e2x, ey) }
        }
        // sourcils
        if (expr == Expr.SAD) {
            c.drawLine(e1x - 4f, ey - 9f, e1x + 3f, ey - 11f, line)
            c.drawLine(e2x - 3f, ey - 11f, e2x + 4f, ey - 9f, line)
        } else if (expr == Expr.ANGRY) {
            c.drawLine(e1x - 4f, ey - 11f, e1x + 4f, ey - 7f, line)
            c.drawLine(e2x - 4f, ey - 7f, e2x + 4f, ey - 11f, line)
        }
        // joues
        val ck = if (expr == Expr.SICK) sickCheek else cheek
        oval(c, e1x - 4f, hy + 7f, 4.5f, 2.8f, ck)
        oval(c, e2x + 5f, hy + 7f, 4.5f, 2.8f, ck)
        // nez
        val nx = hx + 2f; val ny = hy + 5f
        path.reset(); path.moveTo(nx - 3f, ny); path.lineTo(nx + 3f, ny); path.lineTo(nx, ny + 2.8f); path.close()
        c.drawPath(path, nose)
        // bouche
        if (pose == Pose.EAT) {
            val open = (sin(t * 14f) + 1f) * 1.5f
            oval(c, nx, ny + 6f, 2.5f, 1f + open, eye)
        } else if (expr == Expr.TONGUE) {
            // il tire la langue (qui frétille un peu)
            c.drawLine(nx - 4f, ny + 5f, nx + 4f, ny + 5f, line)
            oval(c, nx, ny + 9f + sin(t * 10f) * 0.6f, 3f, 4f, pink)
            c.drawLine(nx, ny + 6f, nx, ny + 10f, line)
        } else if (expr == Expr.HAPPY || expr == Expr.SURPRISED) {
            oval(c, nx, ny + 6.5f, 3f, 3f, eye)
            oval(c, nx, ny + 7.5f, 2f, 1.6f, pink)
        } else if (expr == Expr.SAD || expr == Expr.SICK || expr == Expr.ANGRY) {
            rect.set(nx - 4f, ny + 5f, nx + 4f, ny + 11f)
            c.drawArc(rect, 200f, 140f, false, line)
        } else {
            rect.set(nx - 4f, ny + 1f, nx, ny + 6f); c.drawArc(rect, 20f, 140f, false, line)
            rect.set(nx, ny + 1f, nx + 4f, ny + 6f); c.drawArc(rect, 20f, 140f, false, line)
        }
    }

    private fun dotEye(c: Canvas, x: Float, y: Float) {
        if (darkFur || look.pattern != Pattern.PLAIN) oval(c, x, y, 4.6f, 5.4f, white)
        oval(c, x, y, 3.4f, 4.2f, eye)
        circle(c, x + 1.2f, y - 1.5f, 1.3f, white)
    }

    private fun bigEye(c: Canvas, x: Float, y: Float) {
        circle(c, x, y, 5.5f, white)
        val sw = line.strokeWidth; line.strokeWidth = 1.6f
        rect.set(x - 5.5f, y - 5.5f, x + 5.5f, y + 5.5f); c.drawOval(rect, line)
        line.strokeWidth = sw
        circle(c, x, y, 2.2f, eye)
    }

    private fun arcEye(c: Canvas, x: Float, y: Float, up: Boolean) {
        rect.set(x - 4f, y - 3f, x + 4f, y + 4f)
        c.drawArc(rect, if (up) 200f else 20f, 140f, false, line)
    }

    private fun lidEye(c: Canvas, x: Float, y: Float) {
        rect.set(x - 3.4f, y - 2f, x + 3.4f, y + 4f)
        c.drawArc(rect, 0f, 180f, true, eye)
        c.drawLine(x - 4f, y + 0.8f, x + 4f, y + 0.8f, line)
    }

    private fun spiralEye(c: Canvas, x: Float, y: Float) {
        path.reset()
        var first = true
        var a = 0f
        while (a < 12f) {
            val r = a * 0.42f
            val px = x + cos(a + t * 8f) * r; val py = y + sin(a + t * 8f) * r
            if (first) { path.moveTo(px, py); first = false } else path.lineTo(px, py)
            a += 0.4f
        }
        val sw = line.strokeWidth; line.strokeWidth = 1.5f
        c.drawPath(path, line)
        line.strokeWidth = sw
    }

    private fun drawCarrotInMouth(c: Canvas, x: Float, y: Float) {
        val len = 24f * (1f - eatProgress.coerceIn(0f, 0.9f))
        c.save()
        c.translate(x, y)
        c.rotate(30f)
        path.reset()
        path.moveTo(-1f, -5f); path.lineTo(len, -1.2f); path.lineTo(len, 1.2f); path.lineTo(-1f, 5f); path.close()
        c.drawPath(path, carrot)
        if (eatProgress < 0.4f) {
            oval(c, len + 3f, -3f, 4f, 1.6f, leaf)
            oval(c, len + 3f, 3f, 4f, 1.6f, leaf)
        }
        c.restore()
    }

    // ------------------------------------------------------------------
    //  Lapin endormi (roulé en boule)
    // ------------------------------------------------------------------
    private fun drawSleeping(c: Canvas) {
        for (pass in 0..1) {
            val o = pass == 0
            circle(c, -27f, -16f, 8f, if (o) outline else fur)
            oval(c, -4f, -17f, 29f, 17f, if (o) outline else fur)
            // oreilles couchées vers l'arrière
            c.save(); c.translate(6f, -32f); c.rotate(-100f)
            rect.set(-6f, -36f, 6f, 3f); c.drawOval(rect, if (o) outline else furShade)
            c.restore()
            oval(c, 14f, -24f, 19f, 16f, if (o) outline else fur)
            c.save(); c.translate(12f, -36f); c.rotate(-82f)
            rect.set(-6f, -36f, 6f, 3f); c.drawOval(rect, if (o) outline else fur)
            if (!o) { rect.set(-3f, -31f, 3f, -2f); c.drawOval(rect, pink) }
            c.restore()
        }
        if (look.fur.spotted) {
            c.save(); clipOval(c, -4f, -17f, 29f, 17f)
            oval(c, -16f, -24f, 7f, 5.5f, spot); oval(c, -2f, -8f, 5f, 4f, spot)
            c.restore()
        }
        if (dirty) { circle(c, -12f, -20f, 3f, dirt); circle(c, 2f, -10f, 2.4f, dirt) }
        // yeux fermés
        arcEye(c, 9f, -24f, up = false)
        arcEye(c, 22f, -24f, up = false)
        oval(c, 6f, -17f, 4f, 2.4f, cheek)
        oval(c, 26f, -17f, 4f, 2.4f, cheek)
        path.reset(); path.moveTo(15f, -19f); path.lineTo(20f, -19f); path.lineTo(17.5f, -16.6f); path.close()
        c.drawPath(path, nose)
        drawAccessory(c, 14f, -24f, 16f, standing = false)
    }

    private fun oval(c: Canvas, x: Float, y: Float, rx: Float, ry: Float, p: Paint) {
        rect.set(x - rx, y - ry, x + rx, y + ry); c.drawOval(rect, p)
    }

    private fun circle(c: Canvas, x: Float, y: Float, r: Float, p: Paint) = c.drawCircle(x, y, r, p)

    /** Vrai si le point (coordonnées de la vue) touche approximativement le lapin. */
    override fun hitTest(x: Float, y: Float, width: Int, height: Int): Boolean {
        val cx = width / 2f
        val fy = height - 6f * d
        val s = d * unit()
        return abs(x - cx) < 38f * s && y > fy - 100f * s && y < fy + 6f * s
    }
}

/** Fleurs au sol (printemps) : (x, y) en dp autour des pattes — constant, pas alloué à chaque dessin. */
private val FLOWER_SPOTS = floatArrayOf(-44f, 0f, 36f, 2f, -30f, 3f)
