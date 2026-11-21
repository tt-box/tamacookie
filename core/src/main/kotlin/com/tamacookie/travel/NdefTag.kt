package com.tamacookie.travel

/**
 * Un message NDEF minimal (enregistrements courts) : ce que l'émetteur présente au contact NFC.
 * Deux enregistrements : l'invitation (type MIME [TravelInvite.MIME]) puis l'**Android Application Record**
 * (`android.com:pkg`) qui ouvre TamaCookie chez le receveur (ou sa fiche si l'app n'est pas installée).
 */
object Ndef {
    private const val MB = 0x80
    private const val ME = 0x40
    private const val SR = 0x10
    private const val TNF_MIME = 0x02
    private const val TNF_EXTERNAL = 0x04
    private const val AAR_TYPE = "android.com:pkg"
    /** Enregistrement court : charge utile < 256 octets. */
    private const val SHORT_MAX = 255

    /** Le message NDEF de l'invitation, avec l'AAR de [pkg]. */
    fun inviteMessage(invite: TravelInvite, pkg: String): ByteArray {
        val records = listOf(
            Triple(TNF_MIME, TravelInvite.MIME.toByteArray(Charsets.US_ASCII), invite.toBytes()),
            Triple(TNF_EXTERNAL, AAR_TYPE.toByteArray(Charsets.US_ASCII), pkg.toByteArray(Charsets.US_ASCII)),
        )
        val out = java.io.ByteArrayOutputStream()
        records.forEachIndexed { i, (tnf, type, payload) ->
            require(payload.size <= SHORT_MAX && type.size <= SHORT_MAX)
            var header = SR or tnf
            if (i == 0) header = header or MB
            if (i == records.lastIndex) header = header or ME
            out.write(header)
            out.write(type.size)
            out.write(payload.size)
            out.write(type)
            out.write(payload)
        }
        return out.toByteArray()
    }
}

/**
 * Étiquette NFC Forum **Type 4** émulée (HCE) : répond aux APDU d'un lecteur qui lit un message NDEF
 * (sélection de l'application NDEF, du fichier CC puis du fichier NDEF, lectures binaires). Lecture seule.
 * Pur : le service HCE Android ne fait que lui passer les APDU. [onRead] : le message NDEF a été lu en entier
 * (un pair a reçu l'invitation).
 */
class NdefTag(ndefMessage: ByteArray, private val onRead: () -> Unit = {}) {
    private val ndefFile: ByteArray = ByteArray(2 + ndefMessage.size).also {
        require(ndefMessage.size <= MAX_NDEF) { "message NDEF trop long" }
        it[0] = (ndefMessage.size ushr 8).toByte()
        it[1] = ndefMessage.size.toByte()
        ndefMessage.copyInto(it, 2)
    }

    private enum class Sel { NONE, APP, CC, NDEF }
    private var selected = Sel.NONE

    /** Réponse à une APDU (données éventuelles + mot d'état). */
    fun process(apdu: ByteArray): ByteArray {
        if (apdu.size < HEADER) return SW_WRONG_LENGTH
        val ins = apdu[1].toInt() and 0xFF
        val p1 = apdu[2].toInt() and 0xFF
        val p2 = apdu[3].toInt() and 0xFF
        return when (ins) {
            INS_SELECT -> select(apdu, p1, p2)
            INS_READ_BINARY -> read(apdu, (p1 shl 8) or p2)
            else -> SW_INS_NOT_SUPPORTED
        }
    }

    /** Le lecteur s'est éloigné (désactivation HCE) : on repart de zéro. */
    fun reset() { selected = Sel.NONE }

    private fun select(apdu: ByteArray, p1: Int, p2: Int): ByteArray {
        val data = lc(apdu) ?: return SW_WRONG_LENGTH
        val found = when {
            p1 == BY_NAME -> Sel.APP.takeIf { data.contentEquals(AID) }
            p1 == BY_ID && (p2 == NO_FCI || p2 == FIRST_OR_ONLY) && selected != Sel.NONE -> when {
                data.contentEquals(CC_ID) -> Sel.CC
                data.contentEquals(NDEF_ID) -> Sel.NDEF
                else -> null
            }
            else -> null
        }
        // Une autre application sélectionnée (paiement…) : on oublie la nôtre
        if (found != null) selected = found else if (p1 == BY_NAME) selected = Sel.NONE
        return if (found != null) SW_OK else SW_NOT_FOUND
    }

    private fun read(apdu: ByteArray, offset: Int): ByteArray {
        val file = when (selected) {
            Sel.CC -> CC
            Sel.NDEF -> ndefFile
            else -> return SW_NOT_FOUND
        }
        // Le = dernier octet (0 = 256) ; APDU sans Le : tout ce qui reste
        val le = if (apdu.size > HEADER) (apdu[HEADER].toInt() and 0xFF).let { if (it == 0) 256 else it } else 256
        if (offset > file.size) return SW_WRONG_P1P2
        val end = minOf(file.size, offset + le)
        if (selected == Sel.NDEF && end == file.size && offset < end) onRead()
        return file.copyOfRange(offset, end) + SW_OK
    }

    /** Les données d'une APDU de cas 3/4 (Lc puis données), null si mal formée. */
    private fun lc(apdu: ByteArray): ByteArray? {
        if (apdu.size <= HEADER) return null
        val n = apdu[HEADER].toInt() and 0xFF
        if (apdu.size < HEADER + 1 + n) return null
        return apdu.copyOfRange(HEADER + 1, HEADER + 1 + n)
    }

    companion object {
        /** Application NDEF Type 4 (NFC Forum), à déclarer dans `apduservice.xml`. */
        const val AID_HEX = "D2760000850101"
        val AID = hex(AID_HEX)
        private val CC_ID = byteArrayOf(0xE1.toByte(), 0x03)
        private val NDEF_ID = byteArrayOf(0xE1.toByte(), 0x04)
        private const val HEADER = 4
        private const val INS_SELECT = 0xA4
        /** SELECT : par nom d'application (P1), par identifiant de fichier (P1), sans réponse FCI (P2). */
        private const val BY_NAME = 0x04
        private const val BY_ID = 0x00
        private const val NO_FCI = 0x0C
        /** Ancienne forme (mapping Type 4 v1), encore envoyée par certains lecteurs. */
        private const val FIRST_OR_ONLY = 0x00
        private const val INS_READ_BINARY = 0xB0
        private const val MAX_NDEF = 0x7FFF - 2
        /** Taille maximale lue / écrite par APDU annoncée au lecteur. */
        private const val MLE = 0x00FF
        private const val MLC = 0x00FF

        /** Fichier CC (Capability Container) : version 2.0, fichier NDEF E104 lisible, non inscriptible. */
        val CC = byteArrayOf(
            0x00, 0x0F, 0x20,
            (MLE ushr 8).toByte(), MLE.toByte(), (MLC ushr 8).toByte(), MLC.toByte(),
            0x04, 0x06, 0xE1.toByte(), 0x04, 0x7F, 0xFF.toByte(), 0x00, 0xFF.toByte(),
        )

        val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
        val SW_NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
        val SW_WRONG_LENGTH = byteArrayOf(0x67, 0x00)
        val SW_WRONG_P1P2 = byteArrayOf(0x6B, 0x00)
        val SW_INS_NOT_SUPPORTED = byteArrayOf(0x6D, 0x00)

        fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
