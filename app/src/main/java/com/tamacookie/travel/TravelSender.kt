package com.tamacookie.travel

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import com.tamacookie.PetCommands
import com.tamacookie.PetId
import com.tamacookie.R
import com.tamacookie.persist.PetSnapshot
import java.io.IOException
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Côté **émetteur** : arme l'invitation NFC ([TravelBeacon]), ouvre le canal L2CAP et l'annonce en BLE pendant
 * [ARMED_MS], puis envoie l'animal ([TravelProtocol.send]). Sur `ACCEPT`, il est retiré d'ici
 * ([TravelRules.depart] puis [PetCommands.petLeft]) avant la confirmation.
 */
class TravelSender(activity: Activity, screen: TravelScreen, private val id: PetId) : TravelFlow(activity, screen) {
    override val permissions = arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)

    private var host: TravelHost? = null
    /** Nom de l'animal (gardé : après son départ il n'est plus dans le repository). */
    private var name: String = repo.load(id)?.identity?.name.orEmpty()

    override fun begin(bt: BluetoothAdapter) {
        val h = try {
            TravelHost(bt)
        } catch (_: IOException) {
            end(activity.getString(R.string.travel_bt_error))
            return
        }
        host = h
        val inv = TravelInvite.create(h.psm) { n -> ByteArray(n).also(SecureRandom()::nextBytes) }
        TravelBeacon.arm(NdefTag(Ndef.inviteMessage(inv, activity.packageName)) { main.post(::onInviteRead) })
        h.advertise(inv.tag) { code ->
            main.post { if (state == State.ARMED) end(activity.getString(R.string.travel_adv_failed, code)) }
        }
        state = State.ARMED
        armFor(ARMED_MS)
        screen.message(activity.getString(R.string.travel_send_hint, name))
        screen.buttons(R.string.travel_btn_cancel, activity::finish)
        thread(name = "voyage-envoi") { loop(h, inv) }
    }

    /** L'invitation a été lue : on laisse au receveur le temps de se connecter. */
    private fun onInviteRead() {
        if (state != State.ARMED) return
        armFor(ARMED_MS)
        screen.message(activity.getString(R.string.travel_read))
    }

    /** Un receveur s'est connecté : le délai devient celui de l'échange. */
    private fun onLinked() {
        if (state != State.ARMED) return
        state = State.LINKED
        armFor(LINKED_MS)
        screen.message(activity.getString(R.string.travel_linked))
    }

    /** Sur le fil du voyage : attend le receveur, envoie l'animal. */
    private fun loop(h: TravelHost, inv: TravelInvite) {
        while (true) {
            val s = try { h.accept() } catch (_: IOException) { return } // fermé : délai dépassé ou annulé
            socket = s
            main.post(::onLinked)
            val result = s.use {
                TravelProtocol.send(
                    TravelChannel(inv, isSender = true, it.inputStream, it.outputStream),
                    snapshot = { onMain { snapshot() } },
                    depart = { onMain { TravelRules.depart(repo, id); PetCommands.petLeft(activity, id) } },
                )
            }
            socket = null
            // Un appareil sans la clé (il n'est pas passé par le contact NFC) : on attend le vrai receveur
            if (result != Departure.Failed(Failure.AUTH)) { main.post { show(result) }; return }
            main.post { if (state == State.LINKED) { state = State.ARMED; armFor(ARMED_MS) } }
        }
    }

    /** L'animal tel qu'il est maintenant ; parti entre-temps (retiré par l'écran principal) : lien coupé. */
    private fun snapshot(): PetSnapshot = repo.load(id)?.let(PetSnapshot::of) ?: throw IOException("parti entre-temps")

    private fun show(result: Departure) {
        val text = when (result) {
            Departure.Gone -> R.string.travel_gone
            is Departure.Refused -> when (result.reason) {
                Refusal.HOUSE_FULL -> R.string.travel_refused_full
                Refusal.ALREADY_HERE -> R.string.travel_refused_here
                Refusal.INVALID -> R.string.travel_refused_invalid
                Refusal.DECLINED -> R.string.travel_refused_declined
            }
            is Departure.Failed -> if (timedOut) R.string.travel_timeout else R.string.travel_failed
        }
        val emoji = activity.getString(R.string.travel_emoji_gone).takeIf { result == Departure.Gone }
        end(activity.getString(text, name), emoji)
    }

    override fun onTimeout() {
        // Armé : personne n'est venu ; relié : le fil du voyage va voir le lien fermé et conclure
        if (state == State.ARMED) end(activity.getString(R.string.travel_timeout, name))
    }

    override fun close() {
        TravelBeacon.disarm()
        host?.close()
        host = null
        super.close()
    }

    private companion object {
        /** Invitation présentée au contact NFC (relancé quand elle est lue). */
        const val ARMED_MS = 60_000L
    }
}
