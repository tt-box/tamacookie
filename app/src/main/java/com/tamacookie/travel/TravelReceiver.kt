package com.tamacookie.travel

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.provider.Settings
import com.tamacookie.BunnyService
import com.tamacookie.PetCommands
import com.tamacookie.R
import com.tamacookie.persist.PetSnapshot
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Côté **receveur** (ouvert par la lecture NFC de l'invitation) : trouve l'émetteur en BLE, ouvre le canal L2CAP,
 * **demande** à la personne si elle accueille l'animal, l'enregistre ([TravelRules.arrive]) puis le montre
 * ([PetCommands.petArrived] ; le service est lancé s'il ne tournait pas et que l'affichage est autorisé).
 */
class TravelReceiver(activity: Activity, screen: TravelScreen, private val invite: TravelInvite) :
    TravelFlow(activity, screen) {
    override val permissions = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

    private var guest: TravelGuest? = null
    @Volatile private var answer = false
    @Volatile private var answered: CountDownLatch? = null
    @Volatile private var visitor = ""

    override fun begin(bt: BluetoothAdapter) {
        val g = TravelGuest(bt)
        guest = g
        state = State.LINKED
        armFor(FIND_MS + LINKED_MS)
        screen.message(activity.getString(R.string.travel_searching))
        screen.buttons(R.string.travel_btn_cancel, activity::finish)
        thread(name = "voyage-reception") { run(g) }
    }

    /** Sur le fil du voyage : trouver, se connecter, recevoir. */
    private fun run(g: TravelGuest) {
        val device = g.find(invite, FIND_MS)
        if (device == null) {
            main.post { end(activity.getString(R.string.travel_not_found)) }
            return
        }
        main.post { if (state == State.LINKED) screen.message(activity.getString(R.string.travel_connecting)) }
        val result = try {
            g.connect(device, invite.psm).use {
                socket = it
                val channel = TravelChannel(invite, isSender = false, it.inputStream, it.outputStream)
                TravelProtocol.receive(channel, ::decide)
            }
        } catch (_: IOException) {
            Arrival.Failed(Failure.LINK)
        }
        socket = null
        main.post { show(result) }
    }

    /** Sur le fil du voyage : peut-il venir, la personne l'accueille-t-elle, puis enregistrement durable. */
    private fun decide(snap: PetSnapshot): Refusal? {
        visitor = snap.name
        val blocked = onMain { TravelRules.refusal(repo, snap) }
        val welcome = blocked == null && ask(snap.name)
        return when {
            blocked != null -> blocked
            !welcome -> Refusal.DECLINED
            else -> onMain { TravelRules.arrive(repo, snap, System.currentTimeMillis()) }
        }
    }

    /** Pose la question et attend la réponse (au plus [ASK_MS] ; sans réponse : non). */
    private fun ask(name: String): Boolean {
        val latch = CountDownLatch(1)
        answer = false
        answered = latch
        onMain { question(name) }
        latch.await(ASK_MS, TimeUnit.MILLISECONDS)
        answered = null
        return answer
    }

    private fun question(name: String) {
        if (state != State.LINKED) return
        state = State.ASKING
        armFor(ASK_MS + LINKED_MS)
        screen.message(activity.getString(R.string.travel_ask, name))
        screen.buttons(R.string.travel_btn_welcome, { reply(true) }, R.string.travel_btn_decline) { reply(false) }
    }

    private fun reply(yes: Boolean) {
        if (state != State.ASKING) return
        answer = yes
        state = State.LINKED
        screen.message(activity.getString(R.string.travel_connecting))
        screen.buttons(R.string.travel_btn_cancel, activity::finish)
        answered?.countDown()
    }

    private fun show(result: Arrival) {
        if (result !is Arrival.Arrived) { end(refusalText(result)); return }
        PetCommands.petArrived(activity, result.snapshot.id)
        val shown = BunnyService.running ||
            Settings.canDrawOverlays(activity).also { if (it) BunnyService.start(activity) }
        val text = when {
            !result.confirmed -> R.string.travel_arrived_unconfirmed
            shown -> R.string.travel_arrived
            else -> R.string.travel_arrived_offline
        }
        end(activity.getString(text, result.snapshot.name), activity.getString(R.string.travel_emoji_arrived))
    }

    private fun refusalText(result: Arrival): String = when (result) {
        is Arrival.Refused -> when (result.reason) {
            Refusal.HOUSE_FULL -> activity.getString(R.string.travel_recv_full, visitor)
            Refusal.ALREADY_HERE -> activity.getString(R.string.travel_recv_here, visitor)
            Refusal.INVALID -> activity.getString(R.string.travel_recv_invalid)
            Refusal.DECLINED -> activity.getString(R.string.travel_recv_declined, visitor)
        }
        else -> activity.getString(if (timedOut) R.string.travel_recv_timeout else R.string.travel_recv_failed)
    }

    override fun close() {
        // Quitter l'écran pendant la question : c'est non
        answered?.countDown()
        guest?.close()
        guest = null
        super.close()
    }

    companion object {
        /** Le receveur cherche l'annonce BLE de l'émetteur. */
        private const val FIND_MS = 20_000L
        /** La personne répond « accueillir ? ». */
        private const val ASK_MS = 45_000L

        /** L'invitation lue au contact NFC (le système joint l'étiquette lue), ou null. */
        fun inviteFrom(i: Intent?): TravelInvite? {
            if (i?.action != NfcAdapter.ACTION_NDEF_DISCOVERED) return null
            if (i.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java) == null) return null
            val record = i.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java).orEmpty()
                .flatMap { it.records.asList() }
                .firstOrNull { String(it.type, Charsets.US_ASCII) == TravelInvite.MIME }
            return TravelInvite.fromBytes(record?.payload)
        }
    }
}
