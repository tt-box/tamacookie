package com.tamacookie

import com.tamacookie.feature.toy.Toy
import com.tamacookie.persist.PetRecord
import com.tamacookie.persist.PetSnapshot
import com.tamacookie.persist.PetStore
import com.tamacookie.travel.Arrival
import com.tamacookie.travel.Departure
import com.tamacookie.travel.Failure
import com.tamacookie.travel.Ndef
import com.tamacookie.travel.NdefTag
import com.tamacookie.travel.Refusal
import com.tamacookie.travel.TravelChannel
import com.tamacookie.travel.TravelInvite
import com.tamacookie.travel.TravelProtocol
import com.tamacookie.travel.TravelRules
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Voyage d'un animal vers un autre téléphone (`travel/`) : invitation NFC, étiquette émulée, protocole, magasin. */
class TravelTest {

    private val invite = INVITE

    private companion object {
        val INVITE = TravelInvite(0x0081, ByteArray(8) { it.toByte() }, ByteArray(16) { (it * 7).toByte() })
    }

    private val snap = PetSnapshot(
        id = PetId("3f2a9c1e-0000-4000-8000-123456789abc"),
        species = "rabbit",
        name = "Noisette",
        bornAt = 1_700_000_000_000L,
        traits = Traits(Personality.GLUTTON, Toy.FRISBEE),
        look = Look(Fur.CHOCOLATE, Ears.LOP),
        stats = PetStats(satiety = 42.5f, happiness = 61.25f),
    )

    // ---------------------------------------------------------------- invitation

    @Test
    fun `invitation - aller-retour et entrees invalides`() {
        val b = invite.toBytes()
        assertEquals(TravelInvite.SIZE, b.size)
        assertEquals(invite, TravelInvite.fromBytes(b))
        assertNull(TravelInvite.fromBytes(null))
        assertNull(TravelInvite.fromBytes(b.copyOf(b.size - 1)))
        assertNull(TravelInvite.fromBytes(b.copyOf().also { it[0] = 'X'.code.toByte() }))
        assertNull(TravelInvite.fromBytes(b.copyOf().also { it[3] = 2 }))                 // autre version
        assertNull(TravelInvite.fromBytes(b.copyOf().also { it[4] = 0; it[5] = 0 }))     // psm nul
        assertFalse("la clé n'est jamais affichée", invite.toString().contains("key"))
        // Les tableaux sont copiés : modifier l'original ne change pas l'invitation
        val key = ByteArray(16)
        val inv = TravelInvite(1, ByteArray(8), key)
        key[0] = 9
        assertEquals(0.toByte(), inv.key[0])
    }

    @Test
    fun `invitation creee avec l'alea fourni`() {
        var calls = 0
        val inv = TravelInvite.create(200) { n -> calls++; ByteArray(n) { 1 } }
        assertEquals(2, calls)
        assertEquals(200, inv.psm)
        assertArrayEquals(ByteArray(16) { 1 }, inv.key)
    }

    // ---------------------------------------------------------------- étiquette NFC émulée

    private fun apdu(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun sw(r: ByteArray) = r.copyOfRange(r.size - 2, r.size)
    private fun data(r: ByteArray) = r.copyOfRange(0, r.size - 2)

    private val selectApp = apdu(0x00, 0xA4, 0x04, 0x00, 0x07, 0xD2, 0x76, 0x00, 0x00, 0x85, 0x01, 0x01, 0x00)
    private val selectCc = apdu(0x00, 0xA4, 0x00, 0x0C, 0x02, 0xE1, 0x03)
    private val selectNdef = apdu(0x00, 0xA4, 0x00, 0x0C, 0x02, 0xE1, 0x04)
    private fun readBinary(offset: Int, le: Int) = apdu(0x00, 0xB0, offset ushr 8, offset and 0xFF, le)

    @Test
    fun `etiquette - un lecteur NDEF lit l'invitation comme sur une vraie etiquette`() {
        val message = Ndef.inviteMessage(invite, "com.tamacookie")
        var reads = 0
        val tag = NdefTag(message) { reads++ }

        assertArrayEquals(NdefTag.SW_OK, tag.process(selectApp))
        assertArrayEquals(NdefTag.SW_OK, tag.process(selectCc))
        val cc = tag.process(readBinary(0, 15))
        assertArrayEquals(NdefTag.SW_OK, sw(cc))
        assertArrayEquals(NdefTag.CC, data(cc))
        assertArrayEquals(NdefTag.SW_OK, tag.process(selectNdef))
        val nlen = data(tag.process(readBinary(0, 2)))
        val len = ((nlen[0].toInt() and 0xFF) shl 8) or (nlen[1].toInt() and 0xFF)
        assertEquals(message.size, len)
        assertEquals(0, reads)
        // Lu par morceaux de 16 octets, comme un lecteur prudent
        val got = ByteArrayOutputStream()
        var off = 2
        while (off < len + 2) {
            val r = tag.process(readBinary(off, minOf(16, len + 2 - off)))
            assertArrayEquals(NdefTag.SW_OK, sw(r))
            got.write(data(r)); off += data(r).size
        }
        assertArrayEquals(message, got.toByteArray())
        assertEquals("lecture complète signalée une fois", 1, reads)
    }

    @Test
    fun `etiquette - refuse ce qui n'est pas pour elle`() {
        val tag = NdefTag(Ndef.inviteMessage(invite, "com.tamacookie"))
        // Fichiers inaccessibles tant que l'application NDEF n'est pas sélectionnée
        assertArrayEquals(NdefTag.SW_NOT_FOUND, tag.process(selectNdef))
        assertArrayEquals(NdefTag.SW_NOT_FOUND, tag.process(readBinary(0, 2)))
        // Autre application (paiement…) : introuvable, et la sélection précédente est oubliée
        tag.process(selectApp)
        assertArrayEquals(NdefTag.SW_NOT_FOUND, tag.process(apdu(0x00, 0xA4, 0x04, 0x00, 0x02, 0xA0, 0x00)))
        assertArrayEquals(NdefTag.SW_NOT_FOUND, tag.process(selectNdef))
        // Écriture, APDU tronquées, lecture hors du fichier
        assertArrayEquals(NdefTag.SW_INS_NOT_SUPPORTED, tag.process(apdu(0x00, 0xD6, 0x00, 0x00, 0x01, 0x00)))
        assertArrayEquals(NdefTag.SW_WRONG_LENGTH, tag.process(apdu(0x00, 0xA4)))
        assertArrayEquals(NdefTag.SW_WRONG_LENGTH, tag.process(apdu(0x00, 0xA4, 0x04, 0x00, 0x07, 0xD2)))
        tag.process(selectApp); tag.process(selectCc)
        assertArrayEquals(NdefTag.SW_WRONG_P1P2, tag.process(readBinary(200, 2)))
        tag.reset()
        assertArrayEquals(NdefTag.SW_NOT_FOUND, tag.process(readBinary(0, 2)))
    }

    @Test
    fun `message NDEF - invitation puis enregistrement d'application`() {
        val m = Ndef.inviteMessage(invite, "com.tamacookie")
        val mime = TravelInvite.MIME.toByteArray()
        // 1er : MB + SR + MIME (0x92) ; type, puis l'invitation
        assertEquals(0x92, m[0].toInt() and 0xFF)
        assertEquals(mime.size, m[1].toInt())
        assertEquals(TravelInvite.SIZE, m[2].toInt())
        assertArrayEquals(mime, m.copyOfRange(3, 3 + mime.size))
        val inv = m.copyOfRange(3 + mime.size, 3 + mime.size + TravelInvite.SIZE)
        assertEquals(invite, TravelInvite.fromBytes(inv))
        // 2e : ME + SR + externe (0x54) « android.com:pkg »
        val second = 3 + mime.size + TravelInvite.SIZE
        assertEquals(0x54, m[second].toInt() and 0xFF)
        assertEquals("com.tamacookie", String(m.copyOfRange(m.size - "com.tamacookie".length, m.size)))
    }

    // ---------------------------------------------------------------- protocole

    /** Deux bouts d'un lien relié (comme un socket L2CAP). */
    private class Link {
        val aIn = PipedInputStream(8192); val bOut = PipedOutputStream(aIn)
        val bIn = PipedInputStream(8192); val aOut = PipedOutputStream(bIn)
        fun closeA() { listOf(aIn, aOut).forEach { runCatching { it.close() } } }
        fun closeB() { listOf(bIn, bOut).forEach { runCatching { it.close() } } }
        fun close() { closeA(); closeB() }
        fun sender(inv: TravelInvite = INVITE) = TravelChannel(inv, true, aIn, aOut)
        fun receiver(inv: TravelInvite = INVITE) = TravelChannel(inv, false, bIn, bOut)
    }

    private val pool = Executors.newFixedThreadPool(2)

    @After
    fun stopPool() { pool.shutdownNow() }

    private fun <A, B> both(a: () -> A, b: () -> B): Pair<A, B> {
        val fa = pool.submit(Callable(a)); val fb = pool.submit(Callable(b))
        return fa.get(5, TimeUnit.SECONDS) to fb.get(5, TimeUnit.SECONDS)
    }

    private fun travel(
        senderInvite: TravelInvite = invite,
        receiverInvite: TravelInvite = invite,
        arrive: (PetSnapshot) -> Refusal? = { null },
    ): Triple<Departure, Arrival, Int> {
        val link = Link()
        var departed = 0
        val (d, a) = both(
            {
                TravelProtocol.send(link.sender(senderInvite), { snap }) { departed++ }
                    .also { link.closeA() }
            },
            { TravelProtocol.receive(link.receiver(receiverInvite), arrive).also { link.closeB() } },
        )
        return Triple(d, a, departed)
    }

    @Test
    fun `voyage reussi - l'animal arrive tel quel et quitte l'emetteur une fois`() {
        val (d, a, departed) = travel()
        assertEquals(Departure.Gone, d)
        assertEquals(Arrival.Arrived(snap, confirmed = true), a)
        assertEquals(1, departed)
    }

    @Test
    fun `refus du receveur - l'animal reste chez l'emetteur`() {
        for (r in Refusal.entries) {
            val (d, a, departed) = travel(arrive = { r })
            assertEquals(Departure.Refused(r), d)
            assertEquals(Arrival.Refused(r), a)
            assertEquals(0, departed)
        }
    }

    @Test
    fun `sans la cle de l'invitation - rien ne passe`() {
        val other = TravelInvite(invite.psm, invite.tag, ByteArray(16) { 3 })
        var arrived = 0
        val (d, a, departed) = travel(receiverInvite = other, arrive = { arrived++; null })
        assertEquals(Departure.Failed(Failure.AUTH), d)
        assertTrue(a is Arrival.Failed)
        assertEquals(0, departed)
        assertEquals(0, arrived)
    }

    @Test
    fun `lien coupe avant l'acceptation - l'animal ne part pas`() {
        // Le receveur se présente puis disparaît
        val link = Link()
        val rx = link.receiver()
        rx.send(TravelChannel.HELLO, byteArrayOf(TravelChannel.PROTOCOL_VERSION.toByte()))
        var departed = 0
        val f = pool.submit(Callable {
            TravelProtocol.send(link.sender(), { snap }) { departed++ }
        })
        rx.receive(TravelChannel.OFFER)
        link.close()
        assertEquals(Departure.Failed(Failure.LINK), f.get(5, TimeUnit.SECONDS))
        assertEquals(0, departed)
    }

    @Test
    fun `confirmation perdue - l'animal est quand meme arrive`() {
        // L'émetteur reçoit ACCEPT puis le lien tombe avant GONE : le receveur le garde (non confirmé)
        val link = Link()
        val tx = link.sender()
        val f = pool.submit(Callable { TravelProtocol.receive(link.receiver()) { null } })
        tx.receive(TravelChannel.HELLO)
        tx.send(TravelChannel.OFFER, snap.toBytes())
        tx.receive(TravelChannel.ACCEPT)
        link.close()
        assertEquals(Arrival.Arrived(snap, confirmed = false), f.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `animal illisible - refuse sans appeler le magasin`() {
        val link = Link()
        val tx = link.sender()
        var asked = 0
        val f = pool.submit(Callable { TravelProtocol.receive(link.receiver()) { asked++; null } })
        tx.receive(TravelChannel.HELLO)
        tx.send(TravelChannel.OFFER, "pas un animal".toByteArray())
        val (type, body) = tx.receive(TravelChannel.ACCEPT, TravelChannel.REFUSE)
        assertEquals(TravelChannel.REFUSE, type)
        assertEquals(Refusal.INVALID, Refusal.of(body[0].toInt()))
        assertEquals(Arrival.Refused(Refusal.INVALID), f.get(5, TimeUnit.SECONDS))
        assertEquals(0, asked)
        link.close()
    }

    /** Capture ce qu'un canal écrit, pour le rejouer / l'altérer. */
    private fun frames(vararg msgs: Pair<Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        val ch = TravelChannel(invite, false, ByteArrayInputStream(ByteArray(0)), out)
        for ((t, p) in msgs) ch.send(t, p)
        return out.toByteArray()
    }

    private fun senderReading(bytes: ByteArray) = TravelChannel(invite, true, ByteArrayInputStream(bytes), NullOut)

    private object NullOut : OutputStream() { override fun write(b: Int) {} }

    @Test
    fun `canal - message rejoue, altere, retype ou dans le mauvais sens rejete`() {
        val hello = frames(TravelChannel.HELLO to byteArrayOf(1))
        assertEquals(1, senderReading(hello).receive(TravelChannel.HELLO).second[0].toInt())
        // Rejoué : le compteur a avancé, le même message ne se déchiffre plus
        val twice = senderReading(hello + hello)
        twice.receive(TravelChannel.HELLO)
        assertThrows<TravelChannel.AuthException> { twice.receive(TravelChannel.HELLO) }
        // Un octet chiffré altéré
        val bad = hello.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertThrows<TravelChannel.AuthException> { senderReading(bad).receive(TravelChannel.HELLO) }
        // Type changé (authentifié)
        val retyped = hello.copyOf().also { it[0] = TravelChannel.ACCEPT.toByte() }
        assertThrows<TravelChannel.AuthException> { senderReading(retyped).receive(TravelChannel.ACCEPT) }
        // Type inattendu
        assertThrows<TravelChannel.ProtocolException> { senderReading(hello).receive(TravelChannel.ACCEPT) }
        // Renvoyé à son auteur (mauvais sens)
        val back = TravelChannel(invite, false, ByteArrayInputStream(hello), NullOut)
        assertThrows<TravelChannel.AuthException> { back.receive(TravelChannel.HELLO) }
        // Longueur annoncée démesurée : refusée avant toute allocation
        val huge = byteArrayOf(TravelChannel.HELLO.toByte(), 0x7F, 0xFF.toByte())
        assertThrows<TravelChannel.ProtocolException> { senderReading(huge).receive(TravelChannel.HELLO) }
        // Flux tronqué
        assertThrows<IOException> { senderReading(hello.copyOf(hello.size - 3)).receive(TravelChannel.HELLO) }
    }

    @Test
    fun `version de protocole inconnue - l'emetteur abandonne`() {
        val link = Link()
        val rx = link.receiver()
        rx.send(TravelChannel.HELLO, byteArrayOf(99))
        val d = TravelProtocol.send(link.sender(), { snap }) { }
        assertEquals(Departure.Failed(Failure.PROTOCOL), d)
        link.close()
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try { block() } catch (e: Throwable) {
            assertTrue("attendu ${T::class.simpleName}, reçu $e", e is T); return
        }
        throw AssertionError("attendu ${T::class.simpleName}")
    }

    // ---------------------------------------------------------------- magasin

    /** Magasin en mémoire (même contrat que `PetRepository`). */
    private class MemStore : PetStore {
        val recs = LinkedHashMap<PetId, PetRecord>()
        var resident: PetId? = null
        override fun ids() = recs.keys.toList()
        override fun load(id: PetId) = recs[id]
        override fun residentId() = resident?.takeIf { it in recs }
        override fun save(record: PetRecord) = (record.id in recs).also { if (it) recs[record.id] = record }
        override fun adopt(record: PetRecord, replacing: PetId?) {
            replacing?.let(recs::remove); recs.remove(record.id); recs[record.id] = record; resident = record.id
        }
        override fun addCompanion(record: PetRecord) { recs.remove(record.id); recs[record.id] = record }
        override fun remove(id: PetId) { recs.remove(id); if (resident == id) resident = null }
        override fun makeResident(id: PetId) { require(id in recs); resident = id }
    }

    private fun rec(id: String, adopted: Boolean = true) = PetRecord(Identity(PetId(id), name = id), adopted = adopted)

    @Test
    fun `arrivee - compagnon chez un resident adopte`() {
        val s = MemStore().apply { adopt(rec("r"), null) }
        assertNull(TravelRules.arrive(s, snap, now = 5L))
        assertEquals(PetId("r"), s.residentId())
        assertEquals(listOf(PetId("r"), snap.id), s.ids())
        val got = s.load(snap.id)!!
        assertEquals(snap.name, got.identity.name)
        assertEquals(snap.stats, got.stats)
        assertTrue(got.adopted)
        assertEquals(5L, got.lastUpdate)
    }

    @Test
    fun `arrivee - remplace l'animal provisoire d'un telephone sans adoption`() {
        val s = MemStore().apply { adopt(rec("provisoire", adopted = false), null) }
        assertNull(TravelRules.arrive(s, snap, now = 5L))
        assertEquals(snap.id, s.residentId())
        assertEquals(listOf(snap.id), s.ids())
        // Téléphone tout neuf (aucun animal)
        val empty = MemStore()
        assertNull(TravelRules.arrive(empty, snap, now = 5L))
        assertEquals(snap.id, empty.residentId())
    }

    @Test
    fun `arrivee refusee - deja la, ou maison pleine`() {
        val s = MemStore().apply { adopt(rec("r"), null) }
        TravelRules.arrive(s, snap, 0L)
        assertEquals(Refusal.ALREADY_HERE, TravelRules.arrive(s, snap, 0L))
        val full = MemStore().apply {
            adopt(rec("r"), null)
            for (i in 1 until Tuning.MAX_PETS) addCompanion(rec("c$i"))
        }
        val before = full.ids()
        assertEquals(Refusal.HOUSE_FULL, TravelRules.arrive(full, snap, 0L))
        assertEquals(before, full.ids())
    }

    @Test
    fun `depart d'un compagnon - le resident reste`() {
        val s = MemStore().apply { adopt(rec("r"), null); addCompanion(rec("c1")) }
        assertEquals(PetId("r"), TravelRules.depart(s, PetId("c1")))
        assertEquals(listOf(PetId("r")), s.ids())
    }

    @Test
    fun `depart du resident - le plus ancien compagnon lui succede, sinon plus personne`() {
        val s = MemStore().apply { adopt(rec("r"), null); addCompanion(rec("c1")); addCompanion(rec("c2")) }
        assertEquals(PetId("c1"), TravelRules.depart(s, PetId("r")))
        assertEquals(PetId("c1"), s.residentId())
        assertEquals(listOf(PetId("c1"), PetId("c2")), s.ids())
        val alone = MemStore().apply { adopt(rec("r"), null) }
        assertNull(TravelRules.depart(alone, PetId("r")))
        assertTrue(alone.ids().isEmpty())
    }
}
