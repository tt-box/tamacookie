package com.tamacookie.travel

/**
 * L'invitation au voyage, lue par contact NFC : le receveur y trouve de quoi rejoindre l'émetteur en Bluetooth.
 *
 * - [psm] : canal L2CAP (BLE) ouvert par l'émetteur ;
 * - [tag] : 8 octets publiés dans l'annonce BLE de l'émetteur (le receveur le reconnaît parmi les autres) ;
 * - [key] : clé AES-128 de la session (chiffre et authentifie tout l'échange) — elle ne passe **que** par le
 *   contact NFC : un appareil Bluetooth à portée qui n'a pas touché le téléphone ne peut ni lire ni répondre.
 *
 * Format binaire ([SIZE] = 30 octets) : `TCV` (3) + version (1) + psm (2, gros-boutiste) + tag (8) + clé (16).
 */
class TravelInvite(val psm: Int, tag: ByteArray, key: ByteArray) {
    val tag: ByteArray = tag.copyOf()
    val key: ByteArray = key.copyOf()

    init {
        require(psm in 1..MAX_PSM) { "psm invalide" }
        require(tag.size == TAG_BYTES) { "tag : $TAG_BYTES octets" }
        require(key.size == KEY_BYTES) { "clé : $KEY_BYTES octets" }
    }

    fun toBytes(): ByteArray {
        val out = ByteArray(SIZE)
        MAGIC.copyInto(out, 0)
        out[MAGIC.size] = VERSION.toByte()
        out[MAGIC.size + 1] = (psm ushr 8).toByte()
        out[MAGIC.size + 2] = psm.toByte()
        tag.copyInto(out, MAGIC.size + 3)
        key.copyInto(out, MAGIC.size + 3 + TAG_BYTES)
        return out
    }

    override fun equals(other: Any?) =
        other is TravelInvite && psm == other.psm && tag.contentEquals(other.tag) && key.contentEquals(other.key)

    override fun hashCode() = psm * 31 + tag.contentHashCode()

    /** Jamais la clé dans un journal. */
    override fun toString() = "TravelInvite(psm=$psm)"

    companion object {
        /** Type MIME de l'enregistrement NDEF (en minuscules : filtre d'intent Android). */
        const val MIME = "application/vnd.com.tamacookie.travel"
        const val VERSION = 1
        const val TAG_BYTES = 8
        const val KEY_BYTES = 16
        /** Les PSM L2CAP LE dynamiques tiennent sur 16 bits. */
        const val MAX_PSM = 0xFFFF
        private val MAGIC = byteArrayOf('T'.code.toByte(), 'C'.code.toByte(), 'V'.code.toByte())
        val SIZE = MAGIC.size + 1 + 2 + TAG_BYTES + KEY_BYTES

        /** Nouvelle invitation : [random] fournit le tag et la clé (aléa cryptographique dans l'app). */
        fun create(psm: Int, random: (Int) -> ByteArray) = TravelInvite(psm, random(TAG_BYTES), random(KEY_BYTES))

        /** Invitation reçue d'un pair : null si ce n'en est pas une (autre version, taille, psm nul…). */
        fun fromBytes(b: ByteArray?): TravelInvite? {
            if (b == null || b.size != SIZE || !b.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
            val psm = ((b[MAGIC.size + 1].toInt() and 0xFF) shl 8) or (b[MAGIC.size + 2].toInt() and 0xFF)
            val tagAt = MAGIC.size + 3
            return if (b[MAGIC.size].toInt() != VERSION || psm == 0) null
            else TravelInvite(psm, b.copyOfRange(tagAt, tagAt + TAG_BYTES), b.copyOfRange(tagAt + TAG_BYTES, SIZE))
        }
    }
}
