package com.tamacookie

import com.tamacookie.persist.Adoption
import com.tamacookie.persist.Pets
import com.tamacookie.species.Species
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity(), Choreographer.FrameCallback {

    private val d by lazy { resources.displayMetrics.density }
    private lateinit var preview: PetView
    private lateinit var overlayBtn: TextView
    private lateinit var notifBtn: TextView
    private lateinit var usageBtn: TextView
    private lateinit var a11yBtn: TextView
    private lateinit var a11yHelp: TextView
    private lateinit var mainBtn: TextView
    private lateinit var nameEdit: EditText
    /** Liste des animaux (résident + compagnons), reconstruite à chaque retour sur l'écran. */
    private lateinit var petList: LinearLayout
    private lateinit var companionBtn: TextView
    private var lastNs = 0L
    private var previewT = 0f
    /** Ce qu'affiche l'aperçu (il n'a pas de [Pet] : son état est fabriqué ici). */
    private var previewLook = Look()
    private var previewExpr = Expr.NORMAL
    private var previewFacing = 1f
    /** Emoji de l'espèce du résident (bouton principal). */
    private var residentEmoji = Species.DEFAULT.emoji
    /** Le lapin vient d'être lancé : on passera en arrière-plan après la réponse à la demande de permission. */
    private var backgroundAfterPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Anti-tapjacking : aucune surimpression d'une autre app par-dessus nos réglages
        window.setHideOverlayWindows(true)
        window.statusBarColor = Palette.UI_BG
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val p = (22 * d).toInt(); setPadding(p, p, p, p)
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Palette.UI_BG)
            isFillViewport = true
            addView(root)
        }

        root.addView(TextView(this).apply {
            setText(R.string.app_name)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            setTextColor(Palette.INK); paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            isAccessibilityHeading = true
        })
        root.addView(TextView(this).apply {
            setText(R.string.main_tagline)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Palette.UI_MUTED); gravity = Gravity.CENTER
        })

        preview = PetView(this)
        preview.setOnClickListener {
            preview.emit(PType.HEART, 3)
            previewExpr = Expr.HAPPY
            preview.postDelayed({ previewExpr = Expr.NORMAL }, 1200)
        }
        // Lecteur d'écran : « Cookie, lapin, content » (onResume), double toucher = caresser
        preview.clickLabel(getString(R.string.access_action_pet))
        root.addView(preview, LinearLayout.LayoutParams((170 * d).toInt(), (170 * d).toInt()).apply {
            topMargin = (8 * d).toInt()
        })

        // Nom
        val nameRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nameEdit = EditText(this).apply {
            setText(residentName())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
            setHint(R.string.main_name_hint)
        }
        nameRow.addView(nameEdit, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        nameRow.addView(smallButton(getString(R.string.main_rename)) { rename() })
        nameRow.addView(smallButton(getString(R.string.main_edit_look)) {
            startActivity(Intent(this, AdoptActivity::class.java).putExtra(AdoptActivity.EXTRA_NEW, false))
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = (8 * d).toInt() })
        root.addView(nameRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = (12 * d).toInt() })

        // Animaux : le résident et ses compagnons (lecture seule par le repository, actions par commandes)
        section(root, R.string.main_pets_title)
        petList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(petList, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        companionBtn = smallButton(getString(R.string.main_adopt_companion)) {
            startActivity(Intent(this, AdoptActivity::class.java).putExtra(AdoptActivity.EXTRA_COMPANION, true))
        }
        root.addView(companionBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = (4 * d).toInt(); bottomMargin = (12 * d).toInt()
        })

        // Niveau de bêtises
        section(root, R.string.main_mischief_title)
        val prefs = Prefs.of(this)
        val levels = listOf(
            R.string.main_mischief_0 to R.string.main_mischief_0_desc,
            R.string.main_mischief_1 to R.string.main_mischief_1_desc,
            R.string.main_mischief_2 to R.string.main_mischief_2_desc,
        )
        val levelRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val levelDesc = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setTextColor(Palette.UI_MUTED)
            setPadding((4 * d).toInt(), (4 * d).toInt(), 0, 0)
        }
        val levelChips = ArrayList<TextView>()
        fun paintLevels() {
            val cur = prefs.getInt(Prefs.MISCHIEF, 1)
            levelChips.forEachIndexed { i, c -> c.background = chipBg(i == cur); c.isSelected = i == cur }
            val desc = getString(levels[cur].second)
            levelDesc.text = if (cur > 0) getString(R.string.main_mischief_needs_a11y, desc) else desc
        }
        levels.forEachIndexed { i, (label, _) ->
            val c = TextView(this).apply {
                setText(label); gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTextColor(Palette.INK)
                val p = (10 * d).toInt(); setPadding(p, p, p, p)
                touchTarget(d); describePlain(text)
                setOnClickListener {
                    prefs.edit().putInt(Prefs.MISCHIEF, i).apply()
                    BunnyService.send(this@MainActivity, BunnyService.ACTION_RELOAD)
                    paintLevels()
                }
            }
            levelChips += c
            levelRow.addView(c, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = (6 * d).toInt() })
        }
        root.addView(levelRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(levelDesc, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        paintLevels()

        // Thèmes de saison (décor et répliques ajoutés : l'apparence choisie n'est jamais modifiée)
        section(root, R.string.main_seasons_title)
        val seasonChip = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTextColor(Palette.INK)
            val p = (10 * d).toInt(); setPadding(p, p, p, p)
            touchTarget(d)
        }
        fun paintSeason() {
            val on = prefs.getBoolean(Prefs.SEASONAL, Prefs.SEASONAL_DEFAULT)
            seasonChip.setText(if (on) R.string.main_seasons_on else R.string.main_seasons_off)
            seasonChip.describePlain(seasonChip.text)
            seasonChip.background = chipBg(on)
        }
        seasonChip.setOnClickListener {
            val on = prefs.getBoolean(Prefs.SEASONAL, Prefs.SEASONAL_DEFAULT)
            prefs.edit().putBoolean(Prefs.SEASONAL, !on).apply()
            BunnyService.send(this@MainActivity, BunnyService.ACTION_RELOAD)
            paintSeason()
        }
        root.addView(seasonChip, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(TextView(this).apply {
            setText(R.string.main_seasons_desc)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setTextColor(Palette.UI_MUTED)
            setPadding((4 * d).toInt(), (4 * d).toInt(), 0, 0)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        paintSeason()

        // Bien-être numérique (évolution 4c) : il propose une pause, ne bloque jamais rien
        section(root, R.string.main_wellbeing_title)
        val wellChip = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTextColor(Palette.INK)
            val p = (10 * d).toInt(); setPadding(p, p, p, p)
            touchTarget(d)
        }
        fun paintWellbeing() {
            val on = prefs.getBoolean(Prefs.WELLBEING, Prefs.WELLBEING_DEFAULT)
            wellChip.setText(if (on) R.string.main_wellbeing_on else R.string.main_wellbeing_off)
            wellChip.describePlain(wellChip.text)
            wellChip.background = chipBg(on)
        }
        wellChip.setOnClickListener {
            val on = prefs.getBoolean(Prefs.WELLBEING, Prefs.WELLBEING_DEFAULT)
            prefs.edit().putBoolean(Prefs.WELLBEING, !on).apply()
            BunnyService.send(this@MainActivity, BunnyService.ACTION_RELOAD)
            paintWellbeing()
        }
        root.addView(wellChip, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(TextView(this).apply {
            val rules = GameRules.DEFAULT
            text = getString(R.string.main_wellbeing_desc, rules.breakSuggestAfterMin, rules.breakSulkAfterMin)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setTextColor(Palette.UI_MUTED)
            setPadding((4 * d).toInt(), (4 * d).toInt(), 0, 0)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        paintWellbeing()

        // Chasseur de pubs : il saute sur le bouton « Ignorer l'annonce » d'une publicité (et rien d'autre)
        section(root, R.string.main_adskip_title)
        val adChip = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTextColor(Palette.INK)
            val p = (10 * d).toInt(); setPadding(p, p, p, p)
            touchTarget(d)
        }
        fun paintAdSkip() {
            val on = prefs.getBoolean(Prefs.AD_SKIP, Prefs.AD_SKIP_DEFAULT)
            adChip.setText(if (on) R.string.main_adskip_on else R.string.main_adskip_off)
            adChip.describePlain(adChip.text)
            adChip.background = chipBg(on)
        }
        adChip.setOnClickListener {
            val on = prefs.getBoolean(Prefs.AD_SKIP, Prefs.AD_SKIP_DEFAULT)
            prefs.edit().putBoolean(Prefs.AD_SKIP, !on).apply()
            BunnyService.send(this@MainActivity, BunnyService.ACTION_RELOAD)
            paintAdSkip()
        }
        root.addView(adChip, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(TextView(this).apply {
            setText(R.string.main_adskip_desc)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setTextColor(Palette.UI_MUTED)
            setPadding((4 * d).toInt(), (4 * d).toInt(), 0, 0)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        paintAdSkip()


        section(root, R.string.main_perms_title)
        overlayBtn = permButton(root) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        notifBtn = permButton(root) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        usageBtn = permButton(root) {
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }

        a11yBtn = permButton(root) { confirmAccessibility() }
        a11yHelp = TextView(this).apply {
            setText(R.string.main_a11y_help)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Palette.UI_MUTED)
            setPadding((12 * d).toInt(), 0, (12 * d).toInt(), (6 * d).toInt())
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        root.addView(a11yHelp, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        mainBtn = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            paint.isFakeBoldText = true
            val p = (16 * d).toInt(); setPadding(p, p, p, p)
            touchTarget(d)
            setOnClickListener { toggleBunny() }
        }
        root.addView(mainBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = (18 * d).toInt(); bottomMargin = (18 * d).toInt()
        })

        section(root, R.string.main_howto_title)
        root.addView(TextView(this).apply {
            setText(R.string.main_howto)
            contentDescription = SpokenText.plainLines(text.toString())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Palette.INK)
            setLineSpacing(0f, 1.35f)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(smallButton(getString(R.string.main_reset)) { confirmReset() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = (24 * d).toInt()
        })

        scroll.filterTouchesWhenObscured = true
        setContentView(scroll)
        EdgeToEdge.apply(window, scroll, root)

        // Premier lancement : écran d'adoption
        if (Pets.repository(this).loadResident()?.adopted != true) {
            startActivity(Intent(this, AdoptActivity::class.java).putExtra(AdoptActivity.EXTRA_NEW, true))
        }
    }

    private fun chipBg(selected: Boolean) = GradientDrawable().apply {
        cornerRadius = 14 * d
        setColor(if (selected) Palette.UI_SELECTED else Palette.WHITE)
        val stroke = if (selected) Palette.UI_SELECTED_STROKE else Palette.UI_STROKE
        setStroke((if (selected) 3 * d else 1 * d).toInt(), stroke)
    }

    override fun onResume() {
        super.onResume()
        // Lecture seule : si le service tourne, l'état en mémoire peut être un peu plus récent (jauges)
        val pet = Pets.repository(this).loadResident()
        previewLook = pet?.look ?: Look()
        residentEmoji = Species.of(pet?.identity?.species).emoji
        nameEdit.setText(pet?.identity?.name ?: Identity.DEFAULT_NAME)
        preview.contentDescription = A11yTexts.preview(
            nameEdit.text.toString(), Species.of(pet?.identity?.species).label,
            pet?.stats?.mood ?: Mood.OK, pet?.stats?.sleeping == true,
        )
        preview.reducedMotion = MotionSettings.reduced(contentResolver)
        refreshPets()
        refresh()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onPause() {
        super.onPause()
        Choreographer.getInstance().removeFrameCallback(this)
        lastNs = 0L
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        refresh()
        // La boîte de dialogue a reçu sa réponse : on peut laisser le lapin vivre sur l'écran
        if (requestCode == REQ_NOTIF_THEN_BACKGROUND && backgroundAfterPermission) {
            backgroundAfterPermission = false
            if (BunnyService.running) moveTaskToBack(true)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        val dt = if (lastNs == 0L) 0.016f else ((frameTimeNanos - lastNs) / 1e9f).coerceIn(0f, 0.05f)
        lastNs = frameTimeNanos
        previewT += dt
        // petite marche sur place dans l'aperçu
        val cycle = previewT % 8f
        if (cycle < 0.02f) previewFacing = -previewFacing
        preview.render(PetRenderState(
            pose = if (cycle < 3f) Pose.WALK else Pose.IDLE,
            expr = previewExpr,
            facing = previewFacing,
            walkPhase = (previewT * 2.2f) % 1f,
            look = previewLook,
        ), dt)
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun refresh() {
        val overlay = Settings.canDrawOverlays(this)
        val notif = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val usage = UsageStatsForegroundSource.hasPermission(this)
        setPerm(overlayBtn, overlay, R.string.main_perm_overlay, R.string.main_perm_overlay_why)
        setPerm(notifBtn, notif, R.string.main_perm_notif, R.string.main_perm_notif_why)
        setPerm(usageBtn, usage, R.string.main_perm_usage, R.string.main_perm_usage_why)
        val a11y = ScreenSensorService.isEnabled(this)
        setPerm(a11yBtn, a11y, R.string.main_perm_a11y, R.string.main_perm_a11y_why)
        a11yHelp.visibility = if (a11y) View.GONE else View.VISIBLE

        val running = BunnyService.running
        val name = nameEdit.text.toString()
        mainBtn.text = if (running) getString(R.string.main_stop_pet, name)
        else getString(R.string.main_start_pet, name, residentEmoji)
        mainBtn.describePlain(mainBtn.text)
        // Contrastes ≥ 4,5:1 (étape 8d) : framboise au lieu du rose ; sans la permission, fond clair + texte encre
        mainBtn.setTextColor(if (!overlay) Palette.INK else Palette.WHITE)
        mainBtn.background = GradientDrawable().apply {
            cornerRadius = 18 * d
            setColor(
                if (!overlay) Palette.UI_ACTION_OFF else if (running) Palette.UI_ACTION_STOP else Palette.UI_ACTION,
            )
        }
    }

    private fun toggleBunny() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (BunnyService.running) BunnyService.stop(this) else {
            BunnyService.start(this)
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                // Ne pas reculer maintenant : la boîte de dialogue partirait en arrière-plan.
                backgroundAfterPermission = true
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF_THEN_BACKGROUND)
            } else {
                moveTaskToBack(true)   // on laisse le lapin vivre sur l'écran
            }
        }
        mainBtn.postDelayed({ refresh() }, 300)
    }

    /** Divulgation et consentement explicites AVANT d'ouvrir les réglages d'accessibilité. */
    private fun confirmAccessibility() {
        AlertDialog.Builder(this)
            .setTitle(R.string.main_a11y_dialog_title)
            .setMessage(R.string.main_a11y_dialog_message)
            .setPositiveButton(R.string.main_a11y_dialog_accept) { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(R.string.main_a11y_dialog_decline, null)
            .show()
    }

    /** Une ligne par animal : nom, rôle, jauges ; « Retirer » pour un compagnon. */
    private fun refreshPets() {
        val repo = Pets.repository(this)
        val resident = repo.loadResident()
        val companions = repo.companions()
        petList.removeAllViews()
        val all = listOfNotNull(resident) + companions
        all.forEachIndexed { i, r ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                val p = (10 * d).toInt(); setPadding(p, p / 2, p / 2, p / 2)
                background = GradientDrawable().apply { cornerRadius = 12 * d; setColor(Color.WHITE) }
            }
            val st = r.stats
            val sleeping = if (i >= Tuning.MAX_PETS_SHOWN) getString(R.string.main_pet_offscreen) else ""
            val role = getString(if (r === resident) R.string.main_pet_resident else R.string.main_pet_companion)
            row.addView(TextView(this).apply {
                text = getString(R.string.main_pet_row, r.identity.name, role, sleeping,
                    NotifierTexts.statusLine(st.satiety, st.happiness, st.energy, st.cleanliness), st.health.toInt())
                // Lu « Cookie, résident. Satiété 80 %, … » au lieu des noms des emojis
                contentDescription = getString(R.string.access_pet_row, r.identity.name,
                    getString(if (r === resident) R.string.access_role_resident else R.string.access_role_companion),
                    if (i >= Tuning.MAX_PETS_SHOWN) getString(R.string.access_offscreen) else "",
                    A11yTexts.gauges(st))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTextColor(Palette.INK)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (r !== resident) {
                val id = r.identity.id; val name = r.identity.name
                row.addView(smallButton(getString(R.string.main_pet_remove)) { confirmRemove(id, name) }.apply {
                    contentDescription = getString(R.string.access_remove_pet, name)
                })
            }
            petList.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = (6 * d).toInt() })
        }
        val full = all.size >= Tuning.MAX_PETS
        companionBtn.isEnabled = resident?.adopted == true && !full
        companionBtn.alpha = if (companionBtn.isEnabled) 1f else 0.5f
        val max = Tuning.MAX_PETS
        companionBtn.text = if (full) resources.getQuantityString(R.plurals.main_house_full, max, max)
        else getString(R.string.main_adopt_companion)
        companionBtn.describePlain(companionBtn.text)
    }

    private fun confirmRemove(id: PetId, name: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.main_remove_title, name))
            .setMessage(getString(R.string.main_remove_message, name))
            .setPositiveButton(R.string.main_remove_confirm) { _, _ ->
                PetCommands.removeCompanion(this, id)
                // la commande est asynchrone si le service tourne : la liste suit au prochain affichage
                petList.postDelayed({ refreshPets() }, 300)
            }
            .setNegativeButton(R.string.common_no, null)
            .show()
    }

    private fun residentName(): String = Pets.repository(this).loadResident()?.identity?.name ?: Identity.DEFAULT_NAME

    private fun rename() {
        val n = Adoption.cleanName(nameEdit.text.toString()) ?: return
        // Commande : le service applique et sauvegarde (plus de course avec sa sauvegarde périodique)
        PetCommands.rename(this, n)
        refresh()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.main_reset_title)
            .setMessage(getString(R.string.main_reset_message, nameEdit.text.toString()))
            .setPositiveButton(R.string.common_yes) { _, _ ->
                startActivity(Intent(this, AdoptActivity::class.java).putExtra(AdoptActivity.EXTRA_NEW, true))
            }
            .setNegativeButton(R.string.common_no, null)
            .show()
    }

    // --- petits helpers UI ---
    private fun section(root: LinearLayout, title: Int) {
        root.addView(TextView(this).apply {
            setText(title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Palette.INK); paint.isFakeBoldText = true
            isAccessibilityHeading = true
            setPadding(0, (8 * d).toInt(), 0, (6 * d).toInt())
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun permButton(root: LinearLayout, onClick: () -> Unit): TextView {
        val tv = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Palette.INK)
            val p = (12 * d).toInt(); setPadding(p, p, p, p)
            touchTarget(d)
            setOnClickListener { onClick() }
        }
        root.addView(tv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = (6 * d).toInt() })
        return tv
    }

    private fun setPerm(tv: TextView, ok: Boolean, label: Int, why: Int) {
        tv.text = if (ok) getString(R.string.main_perm_granted, getString(label))
        else getString(R.string.main_perm_missing, getString(label), getString(why))
        tv.contentDescription = if (ok) getString(R.string.access_perm_granted, getString(label))
        else getString(R.string.access_perm_missing, getString(label), getString(why))
        tv.isEnabled = !ok
        tv.background = GradientDrawable().apply {
            cornerRadius = 12 * d
            setColor(if (ok) Palette.UI_OK else Palette.UI_WARN)
        }
    }

    private companion object {
        const val REQ_NOTIF = 1
        const val REQ_NOTIF_THEN_BACKGROUND = 2
    }

    private fun smallButton(label: String, click: () -> Unit) = TextView(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(Palette.INK)
        val p = (10 * d).toInt(); setPadding(p + p / 2, p, p + p / 2, p)
        gravity = Gravity.CENTER
        touchTarget(d)
        describePlain(label)
        background = GradientDrawable().apply { cornerRadius = 12 * d; setColor(Palette.UI_CHIP) }
        setOnClickListener { click() }
    }
}
