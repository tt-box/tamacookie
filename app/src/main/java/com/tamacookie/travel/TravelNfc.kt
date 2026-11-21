package com.tamacookie.travel

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * L'invitation présentée au contact NFC, le temps où l'écran d'envoi l'a armée ([TravelActivity]). Sinon,
 * le service HCE répond « introuvable » : un lecteur NFC qui passe ne lit rien.
 */
object TravelBeacon {
    private var tag: NdefTag? = null

    val armed: Boolean @Synchronized get() = tag != null

    @Synchronized fun arm(t: NdefTag) { tag = t }

    @Synchronized fun disarm() { tag = null }

    @Synchronized fun process(apdu: ByteArray): ByteArray = tag?.process(apdu) ?: NdefTag.SW_NOT_FOUND

    @Synchronized fun reset() { tag?.reset() }
}

/**
 * Émulation de carte (HCE) : le téléphone se présente comme une **étiquette NFC Type 4** portant l'invitation
 * ([NdefTag]). Le téléphone receveur la lit comme n'importe quelle étiquette : le système ouvre [TravelActivity]
 * (filtre `NDEF_DISCOVERED` sur [TravelInvite.MIME]). AID déclaré dans `res/xml/travel_apdu_service.xml`.
 */
class TravelHceService : HostApduService() {
    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray =
        TravelBeacon.process(commandApdu)

    override fun onDeactivated(reason: Int) = TravelBeacon.reset()
}
