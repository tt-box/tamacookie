package com.tamacookie.travel

import com.tamacookie.persist.PetSnapshot
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Pourquoi le receveur refuse l'animal (code échangé : ne jamais renuméroter, ajouts à la fin). */
enum class Refusal(val code: Int) {
    /** Déjà [com.tamacookie.Tuning.MAX_PETS] animaux. */
    HOUSE_FULL(1),
    /** Cet animal (même identifiant) vit déjà ici. */
    ALREADY_HERE(2),
    /** Animal illisible (autre version, espèce inconnue…). */
    INVALID(3),
    /** La personne qui tient le téléphone receveur a dit non. */
    DECLINED(4),
    ;

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: INVALID
    }
}

/** Fin d'un voyage, vue de l'émetteur. */
sealed interface Departure {
    /** Le receveur l'a gardé ; il a quitté ce téléphone. */
    data object Gone : Departure
    data class Refused(val reason: Refusal) : Departure
    data class Failed(val reason: Failure) : Departure
}

/** Fin d'un voyage, vue du receveur. */
sealed interface Arrival {
    /** Il est enregistré ici ; [confirmed] : l'émetteur a confirmé son départ (sinon, lien coupé juste après). */
    data class Arrived(val snapshot: PetSnapshot, val confirmed: Boolean) : Arrival
    data class Refused(val reason: Refusal) : Arrival
    data class Failed(val reason: Failure) : Arrival
}

enum class Failure {
    /** Lien Bluetooth coupé ou délai dépassé (le socket a été fermé). */
    LINK,
    /** Message non authentifié : le pair n'a pas la clé de l'invitation, ou message altéré / rejoué. */
    AUTH,
    /** Message inattendu ou mal formé. */
    PROTOCOL,
}

/**
 * Protocole du voyage sur le canal L2CAP (Kotlin pur, testé sur des flux) — **deux phases** :
 *
 * 1. Le receveur se présente (`HELLO`, preuve qu'il a la clé lue par NFC) ; l'émetteur envoie l'animal (`OFFER`,
 *    un [PetSnapshot]) ; le receveur le valide et l'**enregistre** avant de répondre `ACCEPT` (ou `REFUSE` + raison).
 * 2. Sur `ACCEPT`, l'émetteur **retire** l'animal de chez lui puis confirme (`GONE`) ; le receveur le fait alors
 *    apparaître.
 *
 * L'animal n'est donc jamais perdu : si le lien tombe avant `ACCEPT`, il reste chez l'émetteur ; s'il tombe entre
 * l'enregistrement chez le receveur et la réception de `ACCEPT`, il existe (rare) sur les deux téléphones.
 *
 * Chaque message : type (1 octet) + longueur (2) + corps chiffré AES-128-GCM avec la clé de l'invitation.
 * Le nonce n'est pas transmis : sens + compteur (clé neuve à chaque invitation → jamais réutilisé ; un message
 * rejoué, réordonné ou renvoyé dans l'autre sens ne se déchiffre pas). Le type est authentifié (AAD).
 */
class TravelChannel(invite: TravelInvite, private val isSender: Boolean, input: InputStream, output: OutputStream) {
    private val key = SecretKeySpec(invite.key, "AES")
    private val inp = DataInputStream(input)
    private val out = DataOutputStream(output)
    private var sent = 0L
    private var received = 0L

    class AuthException : IOException("message non authentifié")
    class ProtocolException(msg: String) : IOException(msg)

    fun send(type: Int, payload: ByteArray = EMPTY) {
        val body = cipher(Cipher.ENCRYPT_MODE, if (isSender) TO_RECEIVER else TO_SENDER, sent++, type).doFinal(payload)
        expect(body.size <= MAX_FRAME) { "message trop long" }
        out.writeByte(type)
        out.writeShort(body.size)
        out.write(body)
        out.flush()
    }

    /** Le prochain message, qui doit être de l'un des [expected] types ; renvoie (type, contenu en clair). */
    fun receive(vararg expected: Int): Pair<Int, ByteArray> {
        val type = inp.readUnsignedByte()
        val len = inp.readUnsignedShort()
        expect(type in expected) { "type $type inattendu" }
        expect(len <= MAX_FRAME) { "message trop long" }
        val body = ByteArray(len).also(inp::readFully)
        val clear = try {
            cipher(Cipher.DECRYPT_MODE, if (isSender) TO_SENDER else TO_RECEIVER, received++, type).doFinal(body)
        } catch (_: GeneralSecurityException) {
            throw AuthException()
        }
        return type to clear
    }

    private inline fun expect(ok: Boolean, msg: () -> String) { if (!ok) throw ProtocolException(msg()) }

    private fun cipher(mode: Int, dir: Int, counter: Long, type: Int): Cipher {
        val nonce = ByteArray(NONCE_BYTES)
        nonce[0] = dir.toByte()
        for (i in 0 until Long.SIZE_BYTES) nonce[NONCE_BYTES - 1 - i] = (counter ushr (8 * i)).toByte()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(byteArrayOf(type.toByte()))
        }
    }

    companion object {
        const val HELLO = 1
        const val OFFER = 2
        const val ACCEPT = 3
        const val REFUSE = 4
        const val GONE = 5
        /** Version du protocole, dans `HELLO`. */
        const val PROTOCOL_VERSION = 1
        /** Un animal encodé + l'étiquette GCM, avec de la marge. */
        const val MAX_FRAME = PetSnapshot.MAX_BYTES + 64
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val TO_RECEIVER = 1
        private const val TO_SENDER = 2
        private val EMPTY = ByteArray(0)
    }
}

/** Les deux rôles, sur un lien déjà établi ; bloquants (à lancer hors du fil principal ; fermer le lien interrompt). */
object TravelProtocol {

    /**
     * Côté émetteur. [snapshot] : l'animal au moment de l'envoi ; [depart] : le retirer de chez nous (appelé
     * seulement sur `ACCEPT`, avant de confirmer).
     */
    fun send(channel: TravelChannel, snapshot: () -> PetSnapshot, depart: () -> Unit): Departure =
        guard(Departure::Failed) {
            val (_, hello) = channel.receive(TravelChannel.HELLO)
            if (hello.size != 1 || hello[0].toInt() != TravelChannel.PROTOCOL_VERSION) {
                return@guard Departure.Failed(Failure.PROTOCOL)
            }
            channel.send(TravelChannel.OFFER, snapshot().toBytes())
            val (type, body) = channel.receive(TravelChannel.ACCEPT, TravelChannel.REFUSE)
            if (type == TravelChannel.REFUSE) {
                return@guard Departure.Refused(Refusal.of(body.firstOrNull()?.toInt() ?: 0))
            }
            depart()
            // Le départ est fait : une confirmation perdue n'y change rien (le receveur l'a déjà enregistré)
            try { channel.send(TravelChannel.GONE) } catch (_: IOException) { }
            Departure.Gone
        }

    /**
     * Côté receveur. [arrive] : valide **et enregistre durablement** l'animal, ou dit pourquoi il est refusé (il
     * peut attendre la réponse de l'utilisateur : l'émetteur attend `ACCEPT` / `REFUSE`).
     */
    fun receive(channel: TravelChannel, arrive: (PetSnapshot) -> Refusal?): Arrival = guard(Arrival::Failed) {
        channel.send(TravelChannel.HELLO, byteArrayOf(TravelChannel.PROTOCOL_VERSION.toByte()))
        val (_, offer) = channel.receive(TravelChannel.OFFER)
        val snap = (PetSnapshot.fromBytes(offer) as? PetSnapshot.Decoded.Ok)?.snapshot
        val refusal = if (snap == null) Refusal.INVALID else arrive(snap)
        if (snap == null || refusal != null) {
            val r = refusal ?: Refusal.INVALID
            channel.send(TravelChannel.REFUSE, byteArrayOf(r.code.toByte()))
            return@guard Arrival.Refused(r)
        }
        channel.send(TravelChannel.ACCEPT)
        val confirmed = try { channel.receive(TravelChannel.GONE); true } catch (_: IOException) { false }
        Arrival.Arrived(snap, confirmed)
    }

    private inline fun <T> guard(fail: (Failure) -> T, block: () -> T): T = try {
        block()
    } catch (_: TravelChannel.AuthException) {
        fail(Failure.AUTH)
    } catch (_: TravelChannel.ProtocolException) {
        fail(Failure.PROTOCOL)
    } catch (_: EOFException) {
        fail(Failure.LINK)
    } catch (_: IOException) {
        fail(Failure.LINK)
    }
}
