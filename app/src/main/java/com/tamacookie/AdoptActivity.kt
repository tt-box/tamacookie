package com.tamacookie

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tamacookie.feature.toy.Toy
import com.tamacookie.persist.Adoption
import com.tamacookie.persist.Pets
import com.tamacookie.species.Species
import kotlin.random.Random

/**
 * Création / personnalisation du lapin : nom, pelage, oreilles, yeux, motif,
 * accessoire et caractère. Aperçu animé en direct.
 *
 * Trois modes : personnaliser le résident ([EXTRA_NEW] faux), adopter un nouveau lapin qui **remplace**
 * le résident ([EXTRA_NEW] vrai — avec, si un résident existe déjà, le choix de l'adopter plutôt comme
 * compagnon), ou adopter directement un **compagnon** ([EXTRA_COMPANION]).
 */
class AdoptActivity : Activity(), Choreographer.FrameCallback {

    companion object {
        const val EXTRA_NEW = "new"
        /** Adopter un compagnon (le résident reste). */
        const val EXTRA_COMPANION = "companion"
        /** Contour d'une pastille de couleur non choisie (non textuel). */
        private val SWATCH_STROKE = 0xFFB9ACA3.toInt()
    }

    /** Noms proposés à un compagnon (`adopt_companion_names`). */
    private val companionNames by lazy { resources.getStringArray(R.array.adopt_companion_names).toList() }

    private val d by lazy { resources.displayMetrics.density }
    private lateinit var preview: PetView
    private lateinit var nameEdit: EditText
    private var look = Look()
    private var personality = Personality.CUDDLY
    private var isNew = true
    private var asCompanion = false
    private var lastNs = 0L
    private var t = 0f
    private var previewFacing = 1f
    private val refreshers = ArrayList<() -> Unit>()
    /** Espèce dite par l'aperçu (lecteur d'écran). */
    private var previewSpecies = Species.DEFAULT.label

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Anti-tapjacking : aucune surimpression d'une autre app par-dessus cet écran
        window.setHideOverlayWindows(true)
        window.statusBarColor = Palette.UI_BG
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        // Lecture seule (pas de rattrapage ni d'écriture) : le service ou PetCommands appliqueront le choix
        val repo = Pets.repository(this)
        val pet = repo.loadResident()
        val adopted = pet?.adopted == true
        asCompanion = adopted && intent.getBooleanExtra(EXTRA_COMPANION, false)
        isNew = asCompanion || intent.getBooleanExtra(EXTRA_NEW, !adopted)
        val taken = (listOfNotNull(pet) + repo.companions()).map { it.identity.name }.toSet()
        if (asCompanion) {
            randomLook(Random(System.nanoTime()))
        } else if (pet != null && (!isNew || adopted)) {
            look = pet.look
            personality = pet.traits.personality
        }
        val canAddCompanion = adopted && repo.ids().size < Tuning.MAX_PETS

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val p = (20 * d).toInt(); setPadding(p, p, p, p)
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Palette.UI_BG)
            addView(root)
        }

        root.addView(TextView(this).apply {
            // Une adoption crée un animal de l'espèce par défaut ; la personnalisation montre celle du résident
            text = when {
                asCompanion -> getString(R.string.adopt_title_companion, Species.DEFAULT.emoji)
                isNew -> getString(R.string.adopt_title_new, Species.DEFAULT.emoji)
                else -> getString(R.string.adopt_title_edit, Species.of(pet?.identity?.species).emoji)
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTextColor(Palette.INK); paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            isAccessibilityHeading = true
        })

        preview = PetView(this)
        preview.setOnClickListener { preview.emit(PType.HEART, 3) }
        preview.clickLabel(getString(R.string.access_action_pet))
        previewSpecies = Species.of(if (isNew) null else pet?.identity?.species).label
        root.addView(preview, LinearLayout.LayoutParams((200 * d).toInt(), (200 * d).toInt()))

        nameEdit = EditText(this).apply {
            setText(
                if (asCompanion) companionNames.firstOrNull { it !in taken }.orEmpty()
                else pet?.identity?.name ?: Identity.DEFAULT_NAME,
            )
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
            setHint(R.string.adopt_name_hint)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            // L'aperçu porte le nom saisi (lecteur d'écran)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) { describePreview() }
            })
        }
        root.addView(nameEdit, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        section(root, R.string.adopt_section_fur)
        root.addView(swatches(Fur.entries, { look.fur }) { look = look.copy(fur = it) })
        section(root, R.string.adopt_section_ears)
        root.addView(chips(Ears.entries, { look.ears }) { look = look.copy(ears = it) })
        section(root, R.string.adopt_section_eyes)
        root.addView(swatches(EyeColor.entries, { look.eyes }) { look = look.copy(eyes = it) })
        section(root, R.string.adopt_section_pattern)
        root.addView(chips(Pattern.entries, { look.pattern }) { look = look.copy(pattern = it) })
        section(root, R.string.adopt_section_accessory)
        root.addView(chips(Accessory.entries, { look.accessory }) { look = look.copy(accessory = it) })
        section(root, R.string.adopt_section_acc_color)
        root.addView(swatches(AccColor.entries, { look.accColor }) { look = look.copy(accColor = it) })

        section(root, R.string.adopt_section_personality)
        if (isNew) {
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            for (p in Personality.values()) {
                val card = TextView(this).apply {
                    text = getString(R.string.adopt_personality, p.emoji, p.label, p.desc)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(Palette.INK)
                    val pd = (12 * d).toInt(); setPadding(pd, pd, pd, pd)
                    contentDescription = getString(R.string.adopt_personality, "", p.label, p.desc).trim()
                    setOnClickListener { personality = p; refreshAll() }
                }
                refreshers += { card.background = chipBg(personality == p); card.isSelected = personality == p }
                list.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = (6 * d).toInt() })
            }
            root.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        } else {
            root.addView(TextView(this).apply {
                text = getString(
                    R.string.adopt_personality_fixed, personality.emoji, personality.label, personality.desc,
                )
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(Palette.UI_MUTED)
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(bigButton(getString(R.string.adopt_random), Palette.UI_CHIP, Palette.INK) { randomize() },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = (8 * d).toInt() })
        val confirm = getString(if (isNew) R.string.adopt_confirm else R.string.adopt_save)
        // Framboise (et non plus rose) : texte blanc lisible à 4,8:1 (étape 8d)
        btnRow.addView(bigButton(confirm, Palette.UI_ACTION, Palette.WHITE) { validate() },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(btnRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (22 * d).toInt() })
        // Nouveau lapin alors qu'un résident existe : il peut aussi le rejoindre au lieu de le remplacer
        if (isNew && !asCompanion && canAddCompanion) {
            val asComp = bigButton(getString(R.string.adopt_as_companion), Palette.UI_OK, Palette.INK) {
                asCompanion = true; validate()
            }
            root.addView(asComp,
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (10 * d).toInt() })
            root.addView(TextView(this).apply {
                text = getString(R.string.adopt_replace_hint, pet.identity.name)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setTextColor(Palette.UI_MUTED)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (6 * d).toInt() })
        }

        scroll.filterTouchesWhenObscured = true
        setContentView(scroll)
        EdgeToEdge.apply(window, scroll, root)
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        preview.reducedMotion = MotionSettings.reduced(contentResolver)
        Choreographer.getInstance().postFrameCallback(this)
    }
    override fun onPause() { super.onPause(); Choreographer.getInstance().removeFrameCallback(this); lastNs = 0L }

    override fun doFrame(frameTimeNanos: Long) {
        val dt = if (lastNs == 0L) 0.016f else ((frameTimeNanos - lastNs) / 1e9f).coerceIn(0f, 0.05f)
        lastNs = frameTimeNanos
        t += dt
        val cycle = t % 7f
        if (cycle < 0.02f) previewFacing = -previewFacing
        // L'aperçu affiche l'apparence en cours de choix ([Look] est immuable)
        preview.render(PetRenderState(
            pose = if (cycle < 2.5f) Pose.WALK else Pose.IDLE,
            facing = previewFacing,
            walkPhase = (t * 2.2f) % 1f,
            look = look,
        ), dt)
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun refreshAll() {
        refreshers.forEach { it() }
        describePreview()
    }

    /** « Noisette, lapin, content » : le nom en cours de saisie (lecteur d'écran). */
    private fun describePreview() {
        val name = Adoption.cleanName(nameEdit.text.toString()) ?: Identity.DEFAULT_NAME
        preview.contentDescription = A11yTexts.preview(name, previewSpecies)
    }

    private fun randomize() {
        val r = Random(System.nanoTime())
        randomLook(r)
        preview.emit(PType.SPARKLE, 6)
        refreshAll()
    }

    private fun randomLook(r: Random) {
        look = Look(
            fur = Fur.entries[r.nextInt(Fur.entries.size)], ears = Ears.entries[r.nextInt(Ears.entries.size)],
            eyes = EyeColor.entries[r.nextInt(EyeColor.entries.size)], pattern = Pattern.entries[r.nextInt(Pattern.entries.size)],
            accessory = Accessory.entries[r.nextInt(Accessory.entries.size)], accColor = AccColor.entries[r.nextInt(AccColor.entries.size)],
        )
        if (isNew) personality = Personality.values()[r.nextInt(Personality.values().size)]
    }

    private fun validate() {
        val name = Adoption.cleanName(nameEdit.text.toString())
            ?: if (asCompanion) companionNames.random() else Identity.DEFAULT_NAME
        val a = Adoption(
            name = name,
            look = look,
            personality = personality,
            favoriteToy = Toy.entries[Random.nextInt(Toy.entries.size)],
            isNew = isNew,
        )
        // Une commande : le service (s'il tourne) applique sans redémarrer, sinon le repository est écrit
        if (asCompanion) PetCommands.adoptCompanion(applicationContext, a) else PetCommands.adopt(applicationContext, a)
        setResult(RESULT_OK)
        finish()
    }

    // --- UI helpers ---
    private fun section(root: LinearLayout, title: Int) {
        root.addView(TextView(this).apply {
            setText(title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Palette.INK); paint.isFakeBoldText = true
            isAccessibilityHeading = true
            setPadding(0, (14 * d).toInt(), 0, (6 * d).toInt())
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun chipBg(selected: Boolean) = GradientDrawable().apply {
        cornerRadius = 14 * d
        setColor(if (selected) Palette.UI_SELECTED else Palette.WHITE)
        val stroke = if (selected) Palette.UI_SELECTED_STROKE else Palette.UI_STROKE
        setStroke((if (selected) 3 * d else 1 * d).toInt(), stroke)
    }

    private fun <E : LookOption> chips(options: List<E>, get: () -> E, set: (E) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        options.forEach { o ->
            val c = TextView(this).apply {
                text = o.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(Palette.INK)
                val p = (10 * d).toInt(); setPadding(p + p / 2, p, p + p / 2, p)
                gravity = Gravity.CENTER
                touchTarget(d)
                describePlain(o.label)
                setOnClickListener { set(o); refreshAll() }
            }
            refreshers += { c.background = chipBg(get() == o); c.isSelected = get() == o }
            row.addView(c, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = (8 * d).toInt() })
        }
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    }

    private fun <E : ColorOption> swatches(options: List<E>, get: () -> E, set: (E) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        options.forEach { o ->
            val col = o.hex
            // Pastille de couleur : son nom pour le lecteur d'écran, « sélectionné » sur le choix en cours
            val v = View(this).apply {
                contentDescription = SpokenText.plain(o.label)
                setOnClickListener { set(o); refreshAll() }
            }
            refreshers += {
                v.isSelected = get() == o
                v.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(col))
                    val sel = get() == o
                    val stroke = if (sel) Palette.UI_SELECTED_STROKE else SWATCH_STROKE
                    setStroke((if (sel) 4 * d else 1.5f * d).toInt(), stroke)
                }
            }
            val s = (MIN_TOUCH_DP * d).toInt()   // 44 dp avant l'étape 8d
            row.addView(v, LinearLayout.LayoutParams(s, s).apply { rightMargin = (10 * d).toInt() })
        }
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    }

    private fun bigButton(label: String, bg: Int, fg: Int, click: () -> Unit) = TextView(this).apply {
        text = label
        describePlain(label)
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setTextColor(fg); paint.isFakeBoldText = true
        val p = (15 * d).toInt(); setPadding(p, p, p, p)
        touchTarget(d)
        background = GradientDrawable().apply { cornerRadius = 18 * d; setColor(bg) }
        setOnClickListener { click() }
    }
}
