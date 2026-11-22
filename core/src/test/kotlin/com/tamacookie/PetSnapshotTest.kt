package com.tamacookie

import com.tamacookie.feature.toy.Toy
import com.tamacookie.persist.PetRecord
import com.tamacookie.persist.PetSnapshot
import com.tamacookie.persist.PetSnapshot.Decoded
import com.tamacookie.persist.PetSnapshot.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Représentation portable d'un animal (futur échange Bluetooth) : aller-retour et entrées malveillantes. */
class PetSnapshotTest {

    private val snap = PetSnapshot(
        id = PetId("3f2a9c1e-0000-4000-8000-123456789abc"),
        species = "rabbit",
        name = "Noisette 🐰",
        bornAt = 1_700_000_000_000L,
        traits = Traits(Personality.GLUTTON, Toy.FRISBEE),
        look = Look(Fur.CHOCOLATE, Ears.LOP, EyeColor.GREEN, Pattern.DUTCH, Accessory.SCARF, AccColor.GREEN),
        stats = PetStats(satiety = 42.5f, happiness = 61.25f, energy = 33.3f, cleanliness = 0.1f, health = 100f),
    )

    private fun ok(text: String): PetSnapshot = (PetSnapshot.decode(text) as Decoded.Ok).snapshot
    private fun reason(text: String): Reason = (PetSnapshot.decode(text) as Decoded.Rejected).reason

    /** [snap] encodé, avec la ligne [key] remplacée par [line] (null = supprimée). */
    private fun tampered(key: String, line: String?): String =
        snap.encode().lines().mapNotNull { if (it.startsWith("$key=")) line else it }.joinToString("\n")

    @Test
    fun `aller-retour exact`() {
        assertEquals(snap, ok(snap.encode()))
        assertEquals(Decoded.Ok(snap), PetSnapshot.fromBytes(snap.toBytes()))
        assertTrue(snap.toBytes().size < 600)
    }

    @Test
    fun `depuis et vers un PetRecord - seules les donnees portables passent`() {
        val rec = PetRecord(
            Identity(snap.id, snap.name, snap.bornAt), snap.traits, snap.look,
            snap.stats.copy(sleeping = true, poops = 3, caged = true, visitedApps = 9), adopted = true, lastUpdate = 5L,
        )
        val s = PetSnapshot.of(rec)
        assertEquals(snap, s)
        val now = snap.bornAt + 99L
        val back = s.toRecord(now)
        assertEquals(snap.stats, back.stats)                      // pas de cage, crottes, sommeil
        assertEquals(now, back.lastUpdate)
        assertEquals(rec.identity, back.identity)
        // Né « dans le futur » (horloge d'un pair, valeur forgée) : ramené à maintenant
        assertEquals(50L, s.copy(bornAt = Long.MAX_VALUE).toRecord(now = 50L).identity.bornAt)
    }

    @Test
    fun `en-tete, version et taille`() {
        assertEquals(Reason.NOT_A_PET, reason(""))
        assertEquals(Reason.NOT_A_PET, reason("bonjour"))
        assertEquals(Reason.NOT_A_PET, reason(snap.encode().replace("tamacookie-pet 1", "autre-chose 1")))
        assertEquals(Reason.NOT_A_PET, reason(snap.encode().replace("tamacookie-pet 1", "tamacookie-pet un")))
        assertEquals(Reason.UNSUPPORTED_VERSION, reason(snap.encode().replace("tamacookie-pet 1", "tamacookie-pet 2")))
        assertEquals(Reason.UNSUPPORTED_VERSION, reason(snap.encode().replace("tamacookie-pet 1", "tamacookie-pet 0")))
        assertEquals(Reason.TOO_LARGE, reason(snap.encode() + "x=" + "a".repeat(PetSnapshot.MAX_BYTES)))
        assertEquals(Decoded.Rejected(Reason.TOO_LARGE), PetSnapshot.fromBytes(ByteArray(PetSnapshot.MAX_BYTES + 1)))
        // Emojis : la limite est en octets, pas en caractères
        assertEquals(Reason.TOO_LARGE, reason(snap.encode() + "x=" + "🐰".repeat(PetSnapshot.MAX_BYTES / 3)))
    }

    @Test
    fun `lignes malformees ou en double rejetees`() {
        assertEquals(Reason.MALFORMED, reason(snap.encode() + "sans-egal\n"))
        assertEquals(Reason.MALFORMED, reason(snap.encode() + "=valeur\n"))
        assertEquals(Reason.MALFORMED, reason(snap.encode() + "x=%zz\n"))
        assertEquals(Reason.DUPLICATE_FIELD, reason(snap.encode() + "satiety=1\n"))
    }

    @Test
    fun `identifiant et espece obligatoires et valides`() {
        assertEquals(Reason.BAD_ID, reason(tampered("id", null)))
        assertEquals(Reason.BAD_ID, reason(tampered("id", "id=")))
        assertEquals(Reason.BAD_ID, reason(tampered("id", "id=a%2Cb")))            // séparateur de liste
        assertEquals(Reason.BAD_ID, reason(tampered("id", "id=..%2F..%2Fx")))     // chemin
        assertEquals(Reason.BAD_ID, reason(tampered("id", "id=" + "a".repeat(65))))
        assertEquals(Reason.UNKNOWN_SPECIES, reason(tampered("species", "species=dragon")))
        assertEquals(Reason.UNKNOWN_SPECIES, reason(tampered("species", null)))
    }

    @Test
    fun `ids d'apparence, caractere et jouet inconnus - valeurs par defaut`() {
        val s = ok(
            tampered("look.fur", "look.fur=licorne").let { it.replace("personality=GLUTTON", "personality=DRAGON") }
                .replace("favoriteToy=FRISBEE", "favoriteToy=BAZOOKA"),
        )
        assertEquals(Fur.SNOW, s.look.fur)
        assertEquals(Ears.LOP, s.look.ears)                       // le reste est conservé
        assertEquals(Personality.CUDDLY, s.traits.personality)
        assertEquals(Toy.BALL, s.traits.favoriteToy)
    }

    @Test
    fun `jauges bornees, nombres invalides remplaces`() {
        val s = ok(
            snap.encode().replace("satiety=42.5", "satiety=1e9").replace("happiness=61.25", "happiness=-50")
                .replace("energy=33.3", "energy=NaN").replace("health=100.0", "health=Infinity")
                .replace("cleanliness=0.1", "cleanliness=abc"),
        )
        assertEquals(PetStats(satiety = 100f, happiness = 0f, energy = 90f, cleanliness = 90f, health = 100f), s.stats)
        assertEquals(0L, ok(tampered("bornAt", "bornAt=-5")).bornAt)
        assertEquals(0L, ok(tampered("bornAt", "bornAt=demain")).bornAt)
    }

    @Test
    fun `nom nettoye et tronque`() {
        assertEquals("A b cd", ok(tampered("name", "name=%00A%07b%0A%0Dcd%E2%80%AE")).name)
        assertEquals(Identity.MAX_NAME, ok(tampered("name", "name=" + "x".repeat(500))).name.length)
        assertEquals(Identity.DEFAULT_NAME, ok(tampered("name", "name=%20%20")).name)
        assertEquals(Identity.DEFAULT_NAME, ok(tampered("name", null)).name)
        // Pas d'emoji coupé en deux à la troncature
        val cut = PetSnapshot.cleanName("x".repeat(Identity.MAX_NAME - 1) + "🐰")
        assertTrue(cut.none { it.isSurrogate() })
    }

    @Test
    fun `champs inconnus ignores - compatibilite avec une version future`() {
        assertEquals(snap, ok(snap.encode() + "wings=2\nsparkles=on\n"))
    }

    @Test
    fun `nom saisi a l'adoption - meme nettoyage qu'a l'echange`() {
        val cut = com.tamacookie.persist.Adoption.cleanName("x".repeat(Identity.MAX_NAME - 1) + "🐰")!!
        assertTrue(cut.isNotEmpty() && !cut.last().isHighSurrogate())   // emoji jamais coupé en deux
        assertEquals("Noisette", com.tamacookie.persist.Adoption.cleanName(" Noi‮sette\n "))
        assertEquals(null, com.tamacookie.persist.Adoption.cleanName("   "))
    }
}
