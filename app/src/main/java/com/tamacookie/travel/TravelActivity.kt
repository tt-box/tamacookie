package com.tamacookie.travel

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Bundle
import android.provider.Settings
import android.view.View
import com.tamacookie.Palette
import com.tamacookie.PetId
import com.tamacookie.R
import com.tamacookie.persist.Pets

/**
 * Le voyage d'un animal vers un autre téléphone, des deux côtés :
 *
 * - **Envoi** (menu de l'animal → « Voyager », [send]) : [TravelSender] arme l'invitation NFC, ouvre le canal
 *   Bluetooth et envoie l'animal au téléphone qui l'a lue.
 * - **Réception** : le système ouvre cet écran quand le téléphone lit une invitation (filtre `NDEF_DISCOVERED`) ;
 *   [TravelReceiver] se connecte, demande à la personne si elle accueille l'animal et l'enregistre.
 *
 * L'écran vérifie d'abord ce qu'il faut (NFC, Bluetooth, autorisation « Appareils à proximité ») et propose le
 * réglage qui manque ; quitter l'écran annule le voyage.
 */
class TravelActivity : Activity() {

    companion object {
        private const val EXTRA_PET = "pet"
        private const val REQ_PERMS = 1
        private const val REQ_BT = 2
        private val APP_SETTINGS = R.string.travel_btn_app_settings

        /** Ouvre l'écran d'envoi de [id] (depuis le menu de l'animal, donc depuis le service). */
        fun send(ctx: Context, id: PetId) {
            val i = Intent(ctx, TravelActivity::class.java).putExtra(EXTRA_PET, id.value)
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private lateinit var screen: TravelScreen
    private var flow: TravelFlow? = null
    /** On attend un réglage (NFC, Bluetooth, autorisation) : revérifié au retour sur l'écran. */
    private var checking = false
    private val hceService by lazy { ComponentName(this, TravelHceService::class.java) }
    private val appSettings by lazy {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    }
    private val emulation: CardEmulation? by lazy {
        NfcAdapter.getDefaultAdapter(this)?.let(CardEmulation::getInstance)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setHideOverlayWindows(true)
        window.statusBarColor = Palette.UI_BG
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        screen = TravelScreen(this)
        setContentView(screen.root)
        flow = if (intent?.action == NfcAdapter.ACTION_NDEF_DISCOVERED) receiveFlow() else sendFlow()
        if (flow != null) check()
    }

    /** L'envoi de l'animal demandé par le menu (écran fermé s'il n'existe plus). */
    private fun sendFlow(): TravelFlow? {
        val id = intent?.getStringExtra(EXTRA_PET)?.let(::PetId)
        val pet = id?.let(Pets.repository(this)::load)
        val title = pet?.let { getString(R.string.travel_title_send, it.identity.name) }.orEmpty()
        screen.header(title, getString(R.string.travel_emoji_send))
        if (id == null || pet == null) finish()
        return if (id != null && pet != null) TravelSender(this, screen, id) else null
    }

    /** La réception de l'invitation lue au contact NFC ; null (expliqué) si impossible. */
    private fun receiveFlow(): TravelFlow? {
        screen.header(getString(R.string.travel_title_receive), getString(R.string.travel_emoji_receive))
        val invite = TravelReceiver.inviteFrom(intent)
        val problem = when {
            // Ce téléphone envoie déjà un animal : il ne peut pas en recevoir un en même temps
            TravelBeacon.armed -> R.string.travel_busy_sending
            invite == null -> R.string.travel_bad_invite
            else -> null
        }
        problem?.let { stop(getString(it)) }
        return if (problem == null && invite != null) TravelReceiver(this, screen, invite) else null
    }

    override fun onResume() {
        super.onResume()
        // Envoi : notre service répond en priorité au lecteur NFC tant que l'écran est affiché
        if (flow is TravelSender) emulation?.setPreferredService(this, hceService)
        // Retour des réglages (NFC, Bluetooth, autorisations)
        if (checking) check()
    }

    override fun onPause() {
        super.onPause()
        if (flow is TravelSender) emulation?.unsetPreferredService(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        flow?.close()
    }

    @Deprecated("API Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_BT && checking) check()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS && checking) check(askPermissions = false)
    }

    /** Vérifie ce qu'il faut pour voyager ; explique et propose le réglage qui manque, sinon commence. */
    private fun check(askPermissions: Boolean = true) {
        val f = flow ?: return
        val sending = f is TravelSender
        val nfc = NfcAdapter.getDefaultAdapter(this)
        val bt: BluetoothAdapter? = getSystemService(BluetoothManager::class.java)?.adapter
        val granted = f.permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        val hce = packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)
        val ble = packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        checking = true
        when {
            sending && (nfc == null || !hce) -> stop(getString(R.string.travel_no_nfc))
            bt == null || !ble -> stop(getString(R.string.travel_no_bt))
            sending && nfc?.isEnabled != true -> missing(R.string.travel_nfc_off, R.string.travel_btn_settings) {
                startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
            }
            !granted && askPermissions -> requestPermissions(f.permissions, REQ_PERMS)
            !granted -> missing(
                R.string.travel_perm_needed, R.string.travel_btn_allow, APP_SETTINGS, { startActivity(appSettings) },
            ) { requestPermissions(f.permissions, REQ_PERMS) }
            !bt.isEnabled -> missing(R.string.travel_bt_off, R.string.travel_btn_bt_on) {
                @Suppress("DEPRECATION", "MissingPermission") // BLUETOOTH_CONNECT accordée (vérifié juste avant)
                startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_BT)
            }
            else -> { checking = false; f.begin(bt) }
        }
    }

    /** Il manque un réglage : explication, bouton pour y remédier ([label2] / [run2] : un second, sinon Annuler). */
    private fun missing(message: Int, label: Int, label2: Int? = null, run2: (() -> Unit)? = null, run: () -> Unit) {
        screen.message(getString(message))
        if (label2 != null && run2 != null) {
            screen.buttons(label, run, label2, run2)
        } else {
            screen.buttons(label, run, R.string.travel_btn_cancel, ::finish)
        }
    }

    /** Voyage impossible : explication et « Fermer ». */
    private fun stop(message: String) {
        checking = false
        screen.message(message)
        screen.buttons(R.string.travel_btn_close, ::finish)
    }
}
