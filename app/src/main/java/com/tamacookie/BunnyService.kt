package com.tamacookie

import android.app.KeyguardManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import com.tamacookie.feature.ActRegistry
import com.tamacookie.feature.adskip.AdSkipHunter
import com.tamacookie.feature.station.StationKind
import com.tamacookie.feature.wellbeing.WellbeingCoach
import com.tamacookie.feature.wellbeing.WellbeingTracker
import com.tamacookie.persist.Adoption
import com.tamacookie.persist.PetRecord
import com.tamacookie.persist.PetRepository
import com.tamacookie.persist.Pets
import com.tamacookie.social.SocialSystem
import com.tamacookie.tile.PetTileService
import com.tamacookie.widget.PetWidget
import com.tamacookie.widget.UpdateThrottle
import com.tamacookie.species.Species
import java.util.Calendar
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Service de premier plan qui fait vivre les animaux en surimpression sur l'écran.
 *
 * Orchestrateur : cycle de vie, notification, boucle d'animation et câblage des domaines. Possède le
 * [World] (le résident, ses compagnons et les objets) et implémente [PetCtx], la façade que reçoivent
 * les contrôleurs. À chaque image, chaque animal **affiché** ([World.shown], au plus
 * [Tuning.MAX_PETS_SHOWN]) passe dans [PetBehavior] (corps, sommeil, voyage, décisions) ou la
 * fonctionnalité qui le tient ([ActRegistry]), avec lui pour animal courant ([World.asPet]) ;
 * les autres « dorment hors écran » (leurs jauges seulement).
 *
 * [BunnyPhysics] (vol), [BunnyBrain] (décisions), [BunnyActor] (gestes), [PageTracker] (vues / pages du bureau),
 * [OverlayManager] (toutes les fenêtres, ids par entité), [OverlayWindows] (bulles, dock, panneaux),
 * [StationController] (bain, véto, cage, coffre),
 * [ToyController] (jouets, nourriture, crottes), [MischiefController] (bêtises, cache-cache).
 */
class BunnyService : Service(), Choreographer.FrameCallback, PetCtx {

    companion object {
        /** Chasseur de pubs : durée (ms) pendant laquelle nos fenêtres laissent passer le toucher simulé. */
        private const val AD_TAP_CLEAR_MS = 400L
        const val ACTION_FEED = "com.tamacookie.FEED"
        const val ACTION_TOGGLE = "com.tamacookie.TOGGLE"
        const val ACTION_STOP = "com.tamacookie.STOP"
        const val ACTION_RELOAD = "com.tamacookie.RELOAD"
        /** Le faire venir (widget « Appeler ») : montré s'il était caché, sinon il répond. */
        const val ACTION_SHOW = "com.tamacookie.SHOW"
        /** Commande qui peut **démarrer** le service au premier plan (widget, tuile : voir [userCommand]). */
        private const val EXTRA_FOREGROUND = "foreground"
        private const val TAG = "TamaCookie"
        /** Réponse à « Appeler » quand il est déjà là. */
        private const val CALLED = "Je suis là ! 👋"

        @Volatile var running = false
            private set
        /** Le service tourne **et** l'animal est affiché (pas « Caché ») : l'état de la tuile. */
        @Volatile var visible = false
            private set

        /**
         * Commande d'un toucher de l'utilisateur hors de l'app (widget, tuile), envoyée par
         * `startForegroundService` : le service la traite, démarré au besoin.
         */
        fun userCommand(ctx: Context, action: String): Intent =
            Intent(ctx, BunnyService::class.java).setAction(action).putExtra(EXTRA_FOREGROUND, true)

        fun start(ctx: Context) {
            Prefs.of(ctx).edit().putBoolean(Prefs.ENABLED, true).apply()
            ctx.startForegroundService(Intent(ctx, BunnyService::class.java))
        }

        fun send(ctx: Context, action: String) {
            if (running) ctx.startService(Intent(ctx, BunnyService::class.java).setAction(action))
        }

        fun stop(ctx: Context) {
            Prefs.of(ctx).edit().putBoolean(Prefs.ENABLED, false).apply()
            ctx.stopService(Intent(ctx, BunnyService::class.java))
        }
    }

    // --- Monde ---
    override val world = World()
    override val arena = Arena()
    override val actor = BunnyActor(arena)
    override val clock: Clock = AndroidClock
    /** Thème saisonnier (réglage `Prefs.SEASONAL`, relu à `ACTION_RELOAD`). */
    private val seasonal = SeasonalTheme(clock)
    override val season: Season get() = seasonal.current()
    /** Lanceurs vus au premier plan (le bien-être ne les compte pas). */
    private val homePkgs = HashSet<String>()
    /** Bien-être numérique (réglage `Prefs.WELLBEING`, coupé par défaut ; évolution 4c). */
    private val wellbeing = WellbeingTracker(
        clock,
        exempt = { it == packageName || it in homePkgs || it == screen.snapshot.launcherPkg },
        transient = { it == "com.android.systemui" },
    )
    /** Aléatoire du résident (et, avant l'étape 7, de tout le service) ; chaque compagnon a le sien. */
    private val residentRnd = Random(System.nanoTime())
    /** Aléatoire de l'animal courant. */
    override val rnd: Random get() = pet.mind.rnd
    /** L'animal résident (celui de ce téléphone). */
    private val resident: Pet get() = world.resident
    /** Seule classe qui lit / écrit les animaux ; le service en est l'autorité tant qu'il tourne. */
    private lateinit var repo: PetRepository

    // --- Fenêtres ---
    private lateinit var wm: WindowManager
    /** La seule autorité sur les fenêtres overlay. */
    private lateinit var overlayManager: OverlayManager
    override val overlay: OverlayHost get() = overlayManager
    private lateinit var windows: OverlayWindows
    /** Fenêtre de chaque animal affiché (vue + paramètres). */
    private class PetWindow(val id: WindowId, val view: PetView, val lp: WindowManager.LayoutParams)
    private val petWindows = HashMap<PetId, PetWindow>()
    /** À qui appartient chaque vue (relu à chaque geste : la vue du résident est réutilisée à l'adoption). */
    private val viewOwner = HashMap<View, PetId>()
    /** Animaux dont la fenêtre est retirée (en cage sur une autre page, caché). */
    private val awayPets = HashSet<PetId>()
    private val shownBuf = ArrayList<Pet>()
    private val shown: List<Pet> get() = world.shown(shownBuf)
    /** Animaux de l'image en cours (copie : un animal peut partir pendant l'image). */
    private val framePets = ArrayList<Pet>()

    // --- Domaines ---
    override lateinit var pages: PageTracker
        private set
    /** Activités prises en charge par une fonctionnalité (stations : [Act.IN_STATION]). */
    override val acts = ActRegistry()
    override val director = Director(world)
    private lateinit var stations: StationController
    private lateinit var toys: ToyController
    private lateinit var mischief: MischiefController
    private lateinit var behavior: PetBehavior
    private lateinit var social: SocialSystem
    /** Chasseur de pubs (réglage `Prefs.AD_SKIP`, activé par défaut) : appuie par le port du capteur d'écran. */
    private lateinit var adSkip: AdSkipHunter
    private val handler = Handler(Looper.getMainLooper())
    /**
     * Paroles : bulle de l'animal courant (si les fenêtres existent) + reprise de la boucle. Une seule bulle
     * à la fois quand plusieurs animaux parlent : l'arbitre ([Director.bubbles]) fait attendre les autres.
     */
    override val speech: Speech = Speech({ pet.mind.rnd }) { text, seconds ->
        if (::windows.isInitialized) {
            val p = pet
            if (!hidden && !isAway(p) && world.isShown(p) &&
                director.bubbles.request(Director.BubbleQueue.Line(p.id, text, seconds, speech.priority))
            ) showBubble(p.id, text, seconds, speech.priority)
            requestFrames()
        }
    }

    /** Affiche la bulle de [id] ; celle d'un autre animal, interrompue, disparaît. */
    private fun showBubble(id: PetId, text: String, seconds: Float, priority: SpeechPriority) {
        for (q in world.pets.keys) if (q != id) windows.hideBubble(q)
        windows.say(id, text, seconds, announce = A11yTexts.announces(priority))
    }
    private lateinit var notifier: PetNotifier
    private lateinit var touch: TouchHandler
    private lateinit var watcher: ForegroundAppWatcher
    /** Fenêtres créées et domaines câblés : les intents peuvent être traités. */
    private var ready = false

    // --- Adaptateurs du processus ([AppGraph]) ---
    private val graph: AppGraph by lazy { AppGraph.of(this) }
    /** Ce que voit le service d'accessibilité (plateformes, pages, signaux). */
    override val screen: ScreenSensor get() = graph.screen
    /** Désabonnement de [AppGraph.ownApp] (nos écrans visibles : stations vides retirées). */
    private var ownAppUnsub: (() -> Unit)? = null

    // --- Visibilité ---
    override var hidden = false
        private set

    // --- Boucle ---
    /** Écran allumé ET déverrouillé (voir [ScreenWake] : le déverrouillage n'est pas toujours signalé). */
    private val screenWake = ScreenWake()
    private val awake get() = screenWake.awake
    private var lastTickWall = 0L
    private var lastSaveWall = 0L
    private var notifTimer = 0f
    /** Démarrage du service (uptime) : « Appeler » juste après le lancement laisse la place à l'accueil. */
    private var createdAt = 0L
    /** Widget d'écran d'accueil : au plus une mise à jour par minute après les sauvegardes. */
    private val widgetThrottle = UpdateThrottle()
    private val widgetUpdate = Runnable {
        widgetThrottle.fired(clock.uptimeMs())
        PetWidget.updateAll(this)
    }
    private var menuTimer = 0f

    // =====================================================================
    //  Cycle de vie
    // =====================================================================
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createdAt = clock.uptimeMs()
        notifier = PetNotifier(
            this,
            pet = { if (world.pets.isEmpty()) null else resident },
            director = { director },
            hidden = { hidden },
            hiding = { ::mischief.isInitialized && mischief.hidingView != null },
        )
        notifier.createChannels()
        if (!Settings.canDrawOverlays(this)) {
            // Lancé par startForegroundService : il faut tout de même passer au premier plan avant de s'arrêter
            tryStartForeground()
            stopSelf(); return
        }
        val d = resources.displayMetrics.density
        arena.d = d
        // Fenêtre commune à tous les animaux : gabarit de l'espèce par défaut (la seule aujourd'hui)
        val spec = Species.DEFAULT.spec
        arena.bw = (spec.windowWDp * d).toInt()
        arena.bh = (spec.windowHDp * d).toInt()
        arena.feetPad = spec.feetPadDp * d
        repo = Pets.repository(this)
        world.add(loadResident(BunnyPhysics(d)), resident = true)
        for (id in repo.ids()) if (id != resident.id) loadPet(id, BunnyPhysics(d))?.let { world.add(it) }
        watcher = graph.foregroundWatcher()
        wm = getSystemService(WindowManager::class.java)
        overlayManager = OverlayManager(wm)
        graph.clearOverlayArea = { area -> overlayManager.clearArea(area, AD_TAP_CLEAR_MS) }
        windows = OverlayWindows(this, arena, { sharedPrefs }, overlayManager, bubbleAnchor)
        pages = PageTracker(this)
        toys = ToyController(this)
        toys.registerActs(acts)
        stations = StationController(this, toys, StationPanelWindows(this, windows, arena))
        stations.registerActs(acts)
        mischief = MischiefController(this)
        mischief.registerActs(acts)
        social = SocialSystem(this)
        social.registerActs(acts)
        adSkip = AdSkipHunter(this, graph.screenActions)
        adSkip.registerActs(acts)
        behavior = PetBehavior(this, behaviorHooks)
        touch = TouchHandler(this, windows, this, stations, mischief)
        val entities = EntityWindows(this, windows, world, toys, stations,
            occupantTouch = { s ->
                val view = stations.occupantOf(s)?.let { petWindows[it.id]?.view }
                view?.let { v -> { ev -> touch.forward(v, ev) } }
            },
            // Lecteur d'écran : la station occupée « touchée » caresse son occupant
            caressOccupant = { s -> stations.occupantOf(s)?.let { touch.accessibility.caress(it); true } ?: false },
            asUser = { block -> speech.at(SpeechPriority.HIGH, block) },
        )
        overlayManager.factory = entities::create

        computeBounds()
        for (p in world.pets.values) dropIn(p)
        mischief.loadLevel()
        loadSeasonal()
        loadWellbeing()
        loadAdSkip()
        loadMotion()
        for (u in MotionSettings.URIS) contentResolver.registerContentObserver(u, false, motionObserver)

        // La fenêtre du lapin est ajoutée AVANT startForeground : sur Android 15+, une fenêtre
        // en surimpression visible autorise le passage au premier plan (redémarrage START_STICKY).
        try {
            createWindows()
        } catch (e: Exception) {
            Log.e(TAG, "createWindows", e)
            tryStartForeground()
            removeAllWindows(); stopSelf(); return
        }
        if (!tryStartForeground()) {
            removeAllWindows(); stopSelf(); return
        }
        running = true
        ready = true
        visible = !hidden
        // Un de nos écrans apparaît / disparaît : la prochaine image retire / remet les stations vides
        // (layoutAnchored lit l'état ; une rotation qui arrête puis redémarre l'écran dans le même message ne
        // fait donc rien clignoter).
        ownAppUnsub = graph.ownApp.visible.observe { requestFrames() }
        PetTileService.refresh(this)
        PetWidget.updateAll(this)

        for (p in shown) world.asPet(p) { toys.restorePoops(p) }
        handler.postDelayed({
            if (!ready) return@postDelayed
            pages.currentView = pages.computeView()
            for (p in shown) world.asPet(p) { stations.restore(p) }
            applyView()
        }, 300)
        lastTickWall = clock.wallMs()
        lastSaveWall = lastTickWall

        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_SCREEN_ON)
        }, Context.RECEIVER_NOT_EXPORTED)

        startLoop()
        handler.postDelayed(appPoll, 2000)
        handler.postDelayed({
            say(speech.greeting(resident.name, resident.stats.mood, hourNow(), resident.species.lines))
        }, 900)
    }

    /** Charge le résident (créé au premier lancement) et rattrape explicitement le temps passé hors-ligne. */
    private fun loadResident(phys: BunnyPhysics): Pet {
        val rec = repo.residentOrCreate()
        return catchUp(Pet.fromRecord(rec, phys, residentRnd), rec)
    }

    /** Charge le compagnon [id] (avec son propre aléatoire) et rattrape le temps passé hors-ligne. */
    private fun loadPet(id: PetId, phys: BunnyPhysics): Pet? {
        val rec = repo.load(id) ?: return null
        return catchUp(Pet.fromRecord(rec, phys, newPetRandom(id)), rec)
    }

    private fun newPetRandom(id: PetId) = Random(System.nanoTime() xor id.value.hashCode().toLong())

    private fun catchUp(p: Pet, rec: PetRecord): Pet {
        val now = clock.wallMs()
        p.on(PetEvent.Elapsed((now - rec.lastUpdate) / 3_600_000f), now)
        save(p)
        return p
    }

    /** Il tombe du haut de l'écran en arrivant. */
    private fun dropIn(p: Pet) {
        val b = p.body
        b.fx = arena.screenW * (0.3f + p.mind.rnd.nextFloat() * 0.4f)
        b.fy = arena.topY + Tuning.SPAWN_DROP_DP * arena.d
        b.phys.vy = 0f
        b.setAct(Act.AIR); b.afterAir = Act.IDLE
    }

    private fun hourNow() = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    /** Nom, apparence ou réglages changés : petite fête. */
    private fun showNewLook(p: Pet = resident) = world.asPet(p) {
        applyView()
        emit(p, PType.SPARKLE, 6)
        speech.sayOneOf(p.species.lines.newLook(p.name))
        requestFrames()
    }

    /** Écran d'adoption validé (commande [PetCommands.ACTION_ADOPT]) : le service est l'autorité. */
    private fun adopt(a: Adoption) {
        val old = resident
        val rec = a.apply(old.toRecord(), repo::newPetId, clock.wallMs())
        if (rec.id != old.id) { replaceResident(rec); return }
        old.rename(rec.identity.name)
        old.look = rec.look
        old.adopted = true
        save(old)
        showNewLook()
    }

    /**
     * Un nouveau lapin remplace le résident, sans redémarrer le service : l'ancien quitte proprement
     * cache-cache, station, menu, panneaux et objets ; ses clés sont effacées ; la fenêtre est réutilisée.
     * Les compagnons restent.
     */
    private fun replaceResident(rec: PetRecord) {
        val old = resident
        if (old.isHiding) mischief.endHiding(old)
        if (world.stationOf(old) != null) stations.leaveStation(old, hopOut = false)
        closeMenu()
        stations.closePanels()
        toys.clear()
        windows.forgetBubble(old.id)
        director.bubbles.forget(old.id)
        val w = petWindows.remove(old.id)
        if (w != null) overlayManager.release(w.id)
        awayPets.remove(old.id)
        world.remove(old.id)
        repo.adopt(rec, replacing = old.id)

        val p = Pet.fromRecord(rec, BunnyPhysics(arena.d), residentRnd)
        world.add(p, resident = true)
        computeBounds()
        dropIn(p)
        if (w != null) {
            petWindows[p.id] = PetWindow(WindowId.Pet(p.id), w.view, w.lp)
            viewOwner[w.view] = p.id
        }
        if (!hidden) attachPet(p)
        // les crottes des compagnons ont été retirées avec le reste : elles reviennent
        for (c in shown) if (c !== p) world.asPet(c) { toys.restorePoops(c) }
        applyView()
        lastTickWall = clock.wallMs()
        emit(p, PType.SPARKLE, 6)
        say(speech.greeting(p.name, p.stats.mood, hourNow(), p.species.lines))
        updateNotification(force = true)
        requestFrames()
    }

    /** Un compagnon rejoint le résident (commande [PetCommands.ACTION_ADOPT_COMPANION]) : il tombe du ciel. */
    private fun adoptCompanion(a: Adoption) {
        if (world.pets.size >= Tuning.MAX_PETS) { say(CommonLines.HOUSE_FULL); return }
        val rec = a.copy(isNew = true).apply(null, repo::newPetId, clock.wallMs())
        repo.addCompanion(rec)
        val p = Pet.fromRecord(rec, BunnyPhysics(arena.d), newPetRandom(rec.id))
        world.add(p)
        computeBounds()
        dropIn(p)
        if (world.isShown(p)) showPet(p)
        updateNotification(force = true)
        requestFrames()
    }

    /** [p] apparaît à l'écran (nouveau compagnon, ou un compagnon qui dormait hors écran prend une place). */
    private fun showPet(p: Pet) = world.asPet(p) {
        ensurePetWindow(p)
        if (!hidden) attachPet(p)
        toys.restorePoops(p)
        emit(p, PType.SPARKLE, 6)
        say(speech.greeting(p.name, p.stats.mood, hourNow(), p.species.lines))
    }

    /** Un compagnon s'en va (commande [PetCommands.ACTION_REMOVE_PET]) ; le résident ne peut pas être retiré. */
    private fun removeCompanion(id: PetId) {
        val p = world.pets[id] ?: return
        if (p === resident) return
        if (p.isHiding) mischief.endHiding(p)
        if (world.stationOf(p) != null) stations.leaveStation(p, hopOut = false)
        touch.closeMenuOf(id)
        windows.forgetBubble(id)
        director.bubbles.forget(id)
        petWindows.remove(id)?.let { w ->
            overlayManager.release(w.id)
            touch.detach(w.view)
            viewOwner.remove(w.view)
        }
        awayPets.remove(id)
        toys.removePoopsOf(id)
        world.remove(id)
        repo.remove(id)
        // Un compagnon qui dormait hors écran prend la place libérée
        for (q in shown.toList()) if (q.id !in petWindows) { dropIn(q); showPet(q) }
        say(CommonLines.goodbye(p.name))
        updateNotification(force = true)
        requestFrames()
    }

    /** Passe au premier plan ; false si le système le refuse (ex. redémarrage en arrière-plan sur Android 15+). */
    private fun tryStartForeground(): Boolean = try {
        val n = notifier.build()
        startForeground(PetNotifier.NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        true
    } catch (e: Exception) {
        // ForegroundServiceStartNotAllowedException (API 31+) ou autre refus
        Log.w(TAG, "startForeground refusé", e)
        false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_FOREGROUND, false) == true && ready) {
            // Envoyée par startForegroundService (widget, tuile) : le système attend startForeground à chaque fois
            tryStartForeground()
            // Démarré ainsi, le lapin doit revenir au redémarrage du téléphone, comme par l'écran principal
            if (!sharedPrefs.getBoolean(Prefs.ENABLED, false)) sharedPrefs.edit().putBoolean(Prefs.ENABLED, true).apply()
        }
        return speech.at(SpeechPriority.HIGH) { command(intent) }
    }

    /** Une commande (notification, activités) : réponse à l'utilisateur, ses répliques passent devant. */
    private fun command(intent: Intent?): Int {
        if (!ready) {
            // Vues non initialisées (permission manquante, démarrage refusé…) : on ignore
            if (intent?.action == ACTION_STOP) stop(this)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_FEED -> { if (hidden) show(); toys.feed(); requestFrames() }
            ACTION_TOGGLE -> if (hidden) show() else hide()
            ACTION_SHOW -> if (hidden) show() else if (clock.uptimeMs() - createdAt > 3_000) say(CALLED)
            ACTION_STOP -> { stop(this); return START_NOT_STICKY }
            // Réglages changés (niveau de bêtises)
            ACTION_RELOAD -> {
                mischief.loadLevel(); loadSeasonal(); loadWellbeing(); loadAdSkip(); loadMotion(); showNewLook()
            }
            PetCommands.ACTION_SET_NAME -> {
                val n = Adoption.cleanName(intent.getStringExtra(PetCommands.EXTRA_NAME)) ?: return START_STICKY
                resident.rename(n)
                save(resident)
                showNewLook()
            }
            PetCommands.ACTION_ADOPT -> PetCommands.adoptionFrom(intent)?.let(::adopt)
            PetCommands.ACTION_ADOPT_COMPANION -> PetCommands.adoptionFrom(intent)?.let(::adoptCompanion)
            PetCommands.ACTION_REMOVE_PET -> intent.getStringExtra(PetCommands.EXTRA_ID)?.let { removeCompanion(PetId(it)) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        visible = false
        graph.clearOverlayArea = null
        graph.screen.adSkipWanted = false   // plus personne pour sauter sur le bouton : on ne le cherche plus
        val wasReady = ready
        ready = false
        handler.removeCallbacksAndMessages(null)
        ownAppUnsub?.invoke(); ownAppUnsub = null
        widgetThrottle.cancel()
        stopLoop()
        handler.removeCallbacks(unlockPoll)
        // Sans effet s'il n'a pas été enregistré (démarrage interrompu)
        contentResolver.unregisterContentObserver(motionObserver)
        if (wasReady) {
            try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
            saveAll()
            removeAllWindows()
            PetWidget.updateAll(this)   // « Rangé »
        }
        PetTileService.refresh(this)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.post {
            if (!ready) return@post
            computeBounds()
            // nouvelle orientation : la largeur des pages du bureau change
            pages.onScreenResized()
            for (p in world.pets.values) {
                val b = p.body
                b.fx = b.fx.coerceIn(arena.minX, arena.maxX)
                b.platform = null
                if (b.act != Act.AIR && b.act != Act.DRAGGED) b.fy = arena.groundY
            }
            toys.onScreenResized()
            closeMenu()
            // stations et dock : recalculés depuis les fractions sauvegardées
            stations.reloadPositions()
            windows.reloadDockPosition()
            for (p in shown) world.asPet(p) { stations.repositionOccupant(p) }
            stations.repositionPanels()
            requestFrames()
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenWake.screenOff(); stopLoop(); handler.removeCallbacks(appPoll); saveAll()
                    handler.removeCallbacks(unlockPoll)
                    wellbeing.reset()   // écran éteint : la séance est finie (sans réplique)
                }
                // Écran allumé mais verrouillé : rien à animer derrière l'écran de verrouillage, on guette le
                // déverrouillage (USER_PRESENT n'arrive pas toujours)
                Intent.ACTION_SCREEN_ON -> {
                    if (screenWake.screenOn(isLocked())) onWoke(greet = false)
                    else if (screenWake.waitingUnlock) { handler.removeCallbacks(unlockPoll); handler.postDelayed(unlockPoll, ScreenWake.POLL_MS) }
                }
                Intent.ACTION_USER_PRESENT -> if (screenWake.userPresent()) onWoke(greet = true)
            }
        }
    }

    /** Écran allumé mais verrouillé : relit le verrou jusqu'au déverrouillage (ou à l'extinction). */
    private val unlockPoll = object : Runnable {
        override fun run() {
            if (!screenWake.waitingUnlock) return
            if (screenWake.poll(isLocked())) onWoke(greet = true)
            else handler.postDelayed(this, ScreenWake.POLL_MS)
        }
    }

    /** Un doigt sur une de nos fenêtres : l'écran est utilisable, même si le déverrouillage n'a pas été signalé. */
    override fun userTouched() {
        if (screenWake.touched()) onWoke(greet = false)
        requestFrames()
    }

    private fun isLocked(): Boolean =
        try { getSystemService(KeyguardManager::class.java).isKeyguardLocked } catch (_: Exception) { false }

    private fun onWoke(greet: Boolean) {
        handler.removeCallbacks(unlockPoll)
        // Écran rallumé (et déverrouillé) : on reprend le suivi de l'app au premier plan
        handler.removeCallbacks(appPoll)
        handler.postDelayed(appPoll, 1500)
        tickStats(force = true)
        if (!hidden) startLoop()
        if (greet && !resident.stats.sleeping) speech.sayOneOf(resident.species.lines.WELCOME_BACK)
    }

    private fun saveAll() { for (p in world.pets.values) save(p) }

    // =====================================================================
    //  Fenêtres
    // =====================================================================
    private fun createWindows() {
        for (p in shown) {
            ensurePetWindow(p)
            attachPet(p)
        }
        stations.create()
        windows.createDock { stations.toggleStationsPanel() }
    }

    /** Animations réduites (réglage d'Android, étape 8d) : relu au démarrage, à `ACTION_RELOAD`, à son changement. */
    private var reducedMotion = false

    private fun loadMotion() {
        reducedMotion = MotionSettings.reduced(contentResolver)
        for (w in petWindows.values) w.view.reducedMotion = reducedMotion
    }

    private val motionObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) { loadMotion() }
    }

    private fun loadSeasonal() { seasonal.enabled = prefs.getBoolean(Prefs.SEASONAL, Prefs.SEASONAL_DEFAULT) }

    private fun loadWellbeing() { wellbeing.enabled = prefs.getBoolean(Prefs.WELLBEING, Prefs.WELLBEING_DEFAULT) }

    /** Chasseur de pubs : coupé, le service d'accessibilité ne cherche même plus le bouton. */
    private fun loadAdSkip() {
        adSkip.enabled = prefs.getBoolean(Prefs.AD_SKIP, Prefs.AD_SKIP_DEFAULT)
        graph.screen.adSkipWanted = adSkip.enabled
    }

    /** La vue de [p] (créée à sa première apparition, avec l'écouteur des gestes). */
    private fun ensurePetWindow(p: Pet): PetWindow = petWindows.getOrPut(p.id) {
        val v = PetView(this, p.species)
        v.reducedMotion = reducedMotion
        v.render(PetRenderState.of(p, season = season), 0f)
        viewOwner[v] = p.id
        touch.attach(v) { viewOwner[v]?.let { world.pets[it] } }
        PetWindow(WindowId.Pet(p.id), v, windows.params(arena.bw, arena.bh, touchable = true))
    }

    /** Affiche la fenêtre de [p] à sa position. */
    private fun attachPet(p: Pet, fadeIn: Boolean = false) {
        val w = petWindows[p.id] ?: return
        w.lp.x = petX(p); w.lp.y = petY(p)
        overlayManager.attach(w.id, w.view, w.lp, fadeIn)
    }

    /** Retire toutes les fenêtres ; menu, panneaux et partie en cours sont fermés proprement. */
    private fun removeAllWindows() {
        if (!::overlayManager.isInitialized) return
        closeMenu()
        if (::stations.isInitialized) stations.closePanels()
        overlayManager.detachAll()
        if (::toys.isInitialized) toys.onDetached()
    }

    override fun hide() {
        if (hidden) return
        hidden = true
        visible = false
        wellbeing.reset()
        removeAllWindows()
        stopLoop()
        updateNotification(force = true)
        PetTileService.refresh(this)
    }

    private fun show() {
        if (!hidden) return
        hidden = false
        computeBounds()
        awayPets.clear()
        for (p in shown) attachPet(p)
        toys.reattach()
        windows.addDock()
        layoutAnchored()
        applyView()   // gère aussi le lapin en cage sur une autre vue
        stations.reopenVetPanelIfNeeded()
        startLoop()
        speech.sayOneOf(resident.species.lines.SHOWN)
        updateNotification(force = true)
        visible = true
        PetTileService.refresh(this)
    }

    override fun isAway(pet: Pet): Boolean = pet.id in awayPets

    /**
     * Rend [pet] invisible ici (fenêtre retirée) ou le réaffiche.
     * [fadeIn] : petite apparition en fondu.
     */
    override fun setAway(pet: Pet, away: Boolean, fadeIn: Boolean) {
        if (away == isAway(pet)) return
        if (away) awayPets.add(pet.id) else awayPets.remove(pet.id)
        val w = petWindows[pet.id] ?: return
        if (away) {
            touch.closeMenuOf(pet.id); windows.hideBubble(pet.id); director.bubbles.forget(pet.id)
            overlayManager.detach(w.id)
        } else {
            attachPet(pet, fadeIn)
            requestFrames()
        }
    }

    @Suppress("DEPRECATION")
    private fun computeBounds() {
        val m = wm.currentWindowMetrics
        val b = m.bounds
        val ins = m.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        )
        val d = arena.d
        arena.screenW = b.width().toFloat()
        arena.groundY = b.height() - ins.bottom - Tuning.GROUND_MARGIN_DP * d
        arena.topY = ins.top.toFloat()
        arena.minX = ins.left + Tuning.SIDE_MARGIN_DP * d
        arena.maxX = arena.screenW - ins.right - Tuning.SIDE_MARGIN_DP * d
        for (p in world.pets.values) p.body.phys.setBounds(arena.minX, arena.maxX, arena.topY, arena.groundY)
    }

    /** Coin haut gauche de la fenêtre de [p] (ses pieds en bas au centre, décalage de sa page compris). */
    private fun petX(p: Pet) = (p.body.fx - arena.bw / 2f + p.body.pageShift).roundToInt()
    private fun petY(p: Pet) = (p.body.fy - (arena.bh - arena.feetPad)).roundToInt()

    override fun place(pet: Pet) {
        // Boucle suspendue : il est hors de l'écran, inutile de déplacer la fenêtre (fait à la reprise)
        if (loop.suspended) return
        val w = petWindows[pet.id] ?: return
        overlay.move(w.id, petX(pet), petY(pet))
    }

    /** Position des bulles : au-dessus de la tête de chaque animal, à l'écran. */
    private val bubbleAnchor = object : OverlayWindows.BubbleAnchor {
        override fun headX(pet: PetId): Float {
            val p = world.pets[pet] ?: return 0f
            return p.body.fx + p.body.pageShift
        }
        override fun headTop(pet: PetId): Float {
            val p = world.pets[pet] ?: return 0f
            val spec = p.species.spec
            return p.body.fy - (if (p.act == Act.SLEEP) spec.headTopSleepingDp else spec.headTopDp) * arena.d
        }
    }

    // =====================================================================
    //  Vues : chaque objet reste sur l'app / la page du bureau où il est posé
    // =====================================================================
    private fun checkView() {
        if (pages.refresh()) applyView()
        layoutAnchored()
    }

    /** Place, montre ou cache chaque objet ancré selon la vue et le défilement. Appelé à chaque image. */
    override fun layoutAnchored() {
        if (hidden) return
        stations.ownAppVisible = graph.ownApp.visible.value
        stations.layout()
        toys.layoutPoops()
        for (p in shown) world.asPet(p) {
            val holder = stations.holdingStation(p)
            if (holder != null) stations.followHolder(holder, p) else pages.updateBunnyPage()
        }
    }

    /** Changement de vue : bain/véto interrompus, cache-cache. */
    override fun applyView() {
        if (hidden) return
        for (p in shown) world.asPet(p) {
            val st = world.stationOf(p)
            if (st != null && !st.behavior.holdsPet && !stations.anchoredHere(st)) stations.leaveStation(p, hopOut = false)
        }
        stations.refreshStationsPanel()
        for (p in shown) world.asPet(p) {
            mischief.onViewChanged()
            if (stations.holdingStation(p) == null) setAway(p, p.act == Act.HIDDEN, fadeIn = true)
        }
        layoutAnchored()
    }

    // =====================================================================
    //  Boucle d'animation (fréquence adaptée à l'activité, suspendue si rien n'est visible)
    // =====================================================================
    private fun startLoop() {
        if (hidden || !awake) return
        loop.start()
    }

    private fun stopLoop() = loop.stop()

    /** Quelque chose de visible va se passer : reprend la boucle si elle était suspendue. */
    override fun requestFrames() { loop.resume() }

    private fun targetFps(): Int {
        val pets = shown
        if (pets.any { it.act in Act.MOVING } || toys.anyMoving() || pages.scrollingRecently()) return Tuning.FPS_MOVING
        val allSlow = pets.all { it.act in Act.SLOW || acts.handlerFor(it)?.slowFrames == true }
        return if (allSlow) Tuning.FPS_SLEEP else Tuning.FPS_IDLE
    }

    /** Rien de visible à animer : chaque animal est caché / en cage ailleurs / hors de l'écran sur une autre page. */
    private fun canSuspend(): Boolean {
        for (p in shown) {
            val invisible = p.act == Act.HIDDEN || isAway(p) || world.asPet(p) { pages.bunnyOffscreen() }
            if (!invisible) return false
        }
        return !touch.menuOpen && !windows.anyBubbleShown && !pages.scrollingRecently() &&
            !world.hasToyOrFood() && !toys.anyMoving() && !stations.animating()
    }

    override fun doFrame(frameTimeNanos: Long) = loop.onFrame(frameTimeNanos)

    /** Boucle suspendue : la simulation avance par petits pas, et la boucle reprend dès qu'il redevient visible. */
    private val suspendedTick: Runnable = Runnable { loop.onTick() }

    /** Images (Choreographer) et vérifications de la boucle suspendue (Handler). */
    private val frameScheduler: FrameScheduler = object : FrameScheduler {
        override fun postFrame(delayMs: Long) {
            if (delayMs > 0) Choreographer.getInstance().postFrameCallbackDelayed(this@BunnyService, delayMs)
            else Choreographer.getInstance().postFrameCallback(this@BunnyService)
        }
        override fun cancelFrames() = Choreographer.getInstance().removeFrameCallback(this@BunnyService)
        override fun postTick(delayMs: Long) { handler.postDelayed(suspendedTick, delayMs) }
        override fun cancelTicks() = handler.removeCallbacks(suspendedTick)
    }

    private val loop: FrameLoop = FrameLoop(
        frameScheduler, uptimeMs = { clock.uptimeMs() }, targetFps = ::targetFps, canSuspend = ::canSuspend,
        onError = { what, e -> Log.e(TAG, what, e) }, onResume = { for (p in shown) place(p) },
    ) { dt -> update(dt) }

    /**
     * Une image : vue et pages, signaux de l'utilisateur, stations, jauges et objets, puis **chaque animal
     * affiché** (plateforme, comportement, coffre) avec lui pour animal courant, et le rendu.
     * Avec un seul animal, l'ordre est celui d'avant l'étape 7.
     */
    private fun update(dt: Float) {
        val pets = framePets
        pets.clear(); pets.addAll(shown)
        for (p in pets) { p.body.actTime += dt; p.mind.tick(dt) }
        checkView()
        for (p in pets) world.asPet(p) { pages.checkPageReturn() }
        adSkip.check()            // le bouton « Ignorer l'annonce » passe avant les bêtises et le bavardage
        mischief.checkUserEvents()
        stations.update(dt)

        tickStats(force = false)
        toys.update(dt)
        for (p in pets) if (world.pets[p.id] === p) world.asPet(p) {
            behavior.checkPlatform(p)
            behavior.update(p, dt)
        }
        social.update(dt)
        stations.tickChest(dt)
        for (p in pets) world.asPet(p) { stations.checkChestBump() }

        // Rendu : chaque animal affiché reçoit son état de l'image
        val sea = season
        for (p in pets) petWindows[p.id]?.view?.render(PetRenderState.of(p, season = sea), dt)
        for (p in pets) place(p)
        // Bulle suivante (plusieurs animaux) : celle qui attendait son tour
        director.bubbles.tick(dt)?.let { l ->
            val p = world.pets[l.pet]
            if (p != null && !hidden && !isAway(p) && world.isShown(p)) showBubble(l.pet, l.text, l.seconds, l.priority)
        }
        windows.updateBubbles(dt)
        // Au plus un updateViewLayout par fenêtre et par image
        overlayManager.flush()

        menuTimer += dt
        if (menuTimer > 1f) { menuTimer = 0f; touch.refreshMenu() }
        notifTimer += dt
        if (notifTimer > 20f) { notifTimer = 0f; updateNotification(force = false); notifier.checkAlerts() }
    }

    /**
     * Jauges de **tous** les animaux (y compris ceux qui dorment hors écran) ; les crottes n'apparaissent
     * que pour les animaux affichés.
     */
    private fun tickStats(force: Boolean) {
        val now = clock.wallMs()
        if (!force && now - lastTickWall < Tuning.STATS_EVERY_MS) return
        val hours = (now - lastTickWall) / 3_600_000f
        lastTickWall = now
        for (p in world.pets.values.toList()) world.asPet(p) {
            val newPoops = on(p, PetEvent.Elapsed(hours)).newPoops
            if (world.isShown(p)) {
                repeat(newPoops) {
                    if (toys.poopCount(p.id) < p.stats.poops) {
                        toys.spawnPoop((p.body.fx - p.body.facing * 40 * arena.d).coerceIn(arena.minX, arena.maxX), p)
                    }
                }
                if (newPoops > 0 && p.act != Act.SLEEP) speech.sayOneOf(p.species.lines.POOPED)
            }
        }
        if (now - lastSaveWall > Tuning.SAVE_EVERY_MS) { saveAll(); lastSaveWall = now }
    }

    /** Ce que le comportement demande aux autres domaines. */
    private val behaviorHooks = object : PetBehavior.Hooks {
        override fun chestVisible(p: Pet) = stations.visibleChest() != null && director.mayHeadTo(p, StationKind.CHEST)
        override val lastSelfServe: Long get() = stations.selfServeAt
        override fun goToChest(p: Pet, now: Long) { stations.selfServeAt = now }
        override fun seekAttention(p: Pet) = mischief.maybeSeekAttention()
        override fun startPageWander(p: Pet) { pages.startPageWander() }
        override fun hour() = hourNow()
        override fun nearest(p: Pet) = social.nearest(p)
        override fun socialize(p: Pet, other: Neighbor) = social.tryStart(p, other)
        override fun bowlAvailable(p: Pet) = stations.bowlAvailable(p)
        override fun goToBowl(p: Pet) = stations.goToBowl(p)
    }

    override fun startSleep(pet: Pet) = world.asPet(pet) {
        // Dodo pendant le cache-cache : la partie s'arrête proprement
        if (pet.isHiding) {
            mischief.endHiding(pet)
            pet.body.fx = pet.body.fx.coerceIn(arena.minX, arena.maxX)
        }
        on(pet, PetEvent.FellAsleep)
        speech.sayOneOf(pet.species.lines.GOING_TO_SLEEP)
        val k = world.stationOf(pet)
        if (k != null && k.behavior.sleepsInside) return@asPet   // il dort dans sa cage, dans le lit
        if (k != null) stations.leaveStation(pet, hopOut = false)
        pet.setActOrAfterAir(Act.SLEEP)
    }

    // =====================================================================
    //  Toucher, glisser / lancer, menu : voir [TouchHandler]
    // =====================================================================
    override fun closeMenu() { if (::touch.isInitialized) touch.closeMenu() }

    // =====================================================================
    //  Paroles (répliques de l'espèce, tirage et bulle dans [Speech])
    // =====================================================================
    override fun say(text: String, seconds: Float) = speech.say(text, seconds)

    override fun pick(vararg s: String) = speech.pick(*s)

    // =====================================================================
    //  Voyage d'application en application : voir [PetBehavior.onAppChanged]
    // =====================================================================
    override fun foregroundPkg(): String? = if (::watcher.isInitialized) watcher.currentPkg else null

    private val appPoll = object : Runnable {
        override fun run() {
            if (awake && !hidden) {
                try {
                    watcher.poll()?.let { app ->
                        if (app.isHome) homePkgs += app.pkg
                        for (p in shown.toList()) world.asPet(p) { behavior.onAppChanged(p, app) }
                    }
                    checkWellbeing()
                } catch (e: Exception) {
                    Log.w(TAG, "poll", e)
                }
            }
            handler.postDelayed(this, 1500)
        }
    }

    /** Bien-être (évolution 4c) : le résident propose une pause, boude, se réjouit d'un changement d'app. */
    private fun checkWellbeing() {
        if (!wellbeing.enabled) return
        val p = resident
        val cue = wellbeing.observe(watcher.lastSeen)
        world.asPet(p) {
            if (cue != null) WellbeingCoach.react(this, p, cue)
            WellbeingCoach.maintain(this, p, wellbeing.sulking)
        }
    }

    /** Une caresse du résident met fin à sa bouderie (bien-être) ; sinon, le chemin commun de [PetCtx.on]. */
    override fun on(pet: Pet, event: PetEvent): PetRules.Result {
        val r = super.on(pet, event)
        if (event == PetEvent.Petted && ready && pet === resident) {
            wellbeing.onPetted()?.let { cue -> world.asPet(pet) { WellbeingCoach.react(this, pet, cue) } }
        }
        return r
    }

    // =====================================================================
    //  Notifications : voir [PetNotifier]
    // =====================================================================
    override fun updateNotification(force: Boolean) = notifier.update(force)

    // =====================================================================
    //  PetCtx : le reste de la façade (délégations vers les domaines)
    // =====================================================================
    /** Réglages côté Android (dock, activation) ; le cœur les lit par [prefs]. */
    private val sharedPrefs: SharedPreferences get() = Prefs.of(this)
    override val prefs: SettingsStore by lazy { SharedPrefsSettings(sharedPrefs) }

    override fun save(pet: Pet) {
        repo.save(pet.toRecord())
        if (ready && pet === resident) requestWidgetUpdate()
    }

    /** Le widget relit le résident sauvegardé : tout de suite, ou reporté pour rester à une fois par minute. */
    private fun requestWidgetUpdate() {
        val delay = widgetThrottle.request(clock.uptimeMs())
        if (delay == 0L) PetWidget.updateAll(this)
        else if (delay > 0L) handler.postDelayed(widgetUpdate, delay)
    }

    override fun appLabel(pkg: String): String? = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { null }

    override fun emit(pet: Pet, type: PType, n: Int) { petWindows[pet.id]?.view?.emit(type, n, pet.body.facing) }

    override fun haptic(pet: Pet, feedback: Int) { petWindows[pet.id]?.view?.performHapticFeedback(feedback) }

    override fun vibrate(pattern: LongArray) {
        try {
            val vib = getSystemService(VibratorManager::class.java).defaultVibrator
            vib.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {}
    }

    override var lastInteraction: Long = clock.wallMs()

    override fun setDockHiding(hiding: Boolean) = windows.setDockHiding(hiding)
}
