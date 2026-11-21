package com.tamacookie

import com.tamacookie.feature.station.StationKind
import com.tamacookie.feature.toy.Toy
import com.tamacookie.persist.Adoption
import com.tamacookie.persist.PetMigrations
import com.tamacookie.persist.PetRecord
import com.tamacookie.persist.PetRepository
import com.tamacookie.species.Species
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Persistance des animaux : schéma 1, migration depuis le schéma 0 (version installée), lectures sans effet de bord. */
class PetRepositoryTest {

    private val clock = RepoClock(wall = 1_800_000_000_000L)
    private var nextId = 0
    private val ids = { "uuid-${++nextId}" }

    private fun open(pets: FakePrefs, settings: FakePrefs) = PetRepository.open(pets, settings, clock, ids)

    /**
     * Fichier « pet » tel que l'écrit la version actuellement installée : réglages + toutes les clés de
     * l'ancien `PetState.save()` et `saveLook()` (liste exacte).
     */
    private fun legacyFile() = FakePrefs().apply {
        edit()
            // Réglages
            .putBoolean(Prefs.ENABLED, true)
            .putInt(Prefs.MISCHIEF, 2)
            .putFloat(Prefs.DOCK_FX, 0.3f).putFloat(Prefs.DOCK_FY, 0.7f)
            .putFloat(Prefs.pageWidth(1080), 1234f)
            .putString(Prefs.stationAnchor(StationKind.CAGE), "home#p1")
            .putFloat(Prefs.stationFx(StationKind.CAGE), 0.4f).putFloat(Prefs.stationFy(StationKind.CAGE), 0.9f)
            // PetState.save()
            .putString("name", "Noisette")
            .putFloat("satiety", 42.5f).putFloat("happiness", 61.25f)
            .putFloat("energy", 33.3f).putFloat("cleanliness", 12.75f)
            .putFloat("health", 77.7f).putBoolean("sleeping", true)
            .putInt("poops", 2).putFloat("poopAcc", 0.625f)
            .putLong("bornAt", 1_700_000_000_000L).putLong("lastUpdate", 1_799_999_000_000L)
            .putInt("visitedApps", 17)
            .putBoolean("caged", true)
            // PetState.saveLook()
            .putBoolean("adopted", true)
            .putInt("look_fur", 3).putInt("look_ears", 1).putInt("look_eyes", 2)
            .putInt("look_pattern", 2).putInt("look_acc", 4)
            .putInt("look_accColor", 5)
            .putString("personality", "GLUTTON").putString("favoriteToy", "FRISBEE")
            .apply()
    }

    @Test
    fun `la liste des cles du schema 0 est celle de l'ancien PetState`() {
        val legacyPetKeys = legacyFile().map.keys.filter {
            it !in setOf(Prefs.ENABLED, Prefs.MISCHIEF, Prefs.DOCK_FX, Prefs.DOCK_FY) && !it.startsWith("pageW2_") && !it.startsWith("st_")
        }
        assertEquals(legacyPetKeys.toSet(), PetMigrations.LEGACY_PET_KEYS.toSet())
    }

    @Test
    fun `migration 0 vers 1 - tout est conserve`() {
        val settings = legacyFile()
        val pets = FakePrefs()
        val repo = open(pets, settings)

        assertEquals(1, pets.map[PetRepository.KEY_SCHEMA])
        val r = repo.loadResident()!!
        assertEquals(PetId("uuid-1"), r.id)
        assertEquals(listOf(PetId("uuid-1")), repo.ids())
        assertEquals("Noisette", r.identity.name)
        assertEquals("rabbit", r.identity.species)
        assertEquals(1_700_000_000_000L, r.identity.bornAt)
        assertEquals(1_799_999_000_000L, r.lastUpdate)          // pas de rattrapage pendant la migration
        assertTrue(r.adopted)
        assertEquals(Traits(Personality.GLUTTON, Toy.FRISBEE), r.traits)
        assertEquals(
            Look(Fur.CHOCOLATE, Ears.LOP, EyeColor.GREEN, Pattern.DUTCH, Accessory.SCARF, AccColor.GREEN),
            r.look,
        )
        assertEquals(
            PetStats(
                satiety = 42.5f, happiness = 61.25f, energy = 33.3f, cleanliness = 12.75f, health = 77.7f,
                sleeping = true, poops = 2, poopAccumulator = 0.625f, visitedApps = 17, caged = true,
            ),
            r.stats,
        )
        // Nouveau schéma : apparence par identifiants stables
        assertEquals("chocolate", pets.map["pet.uuid-1.look.fur"])
        assertEquals("scarf", pets.map["pet.uuid-1.look.accessory"])

        // Réglages conservés dans le fichier « pet », clés de l'ancien animal effacées
        assertEquals(
            mapOf<String, Any?>(
                Prefs.ENABLED to true, Prefs.MISCHIEF to 2, Prefs.DOCK_FX to 0.3f, Prefs.DOCK_FY to 0.7f,
                Prefs.pageWidth(1080) to 1234f, Prefs.stationAnchor(StationKind.CAGE) to "home#p1",
                Prefs.stationFx(StationKind.CAGE) to 0.4f, Prefs.stationFy(StationKind.CAGE) to 0.9f,
            ),
            settings.map,
        )
    }

    @Test
    fun `migration idempotente - rouvrir ne change rien`() {
        val settings = legacyFile()
        val pets = FakePrefs()
        open(pets, settings)
        val snapshot = HashMap(pets.map)
        val repo = open(pets, settings)
        assertEquals(snapshot, pets.map)
        assertEquals(PetId("uuid-1"), repo.residentId())
    }

    @Test
    fun `migration interrompue apres le schema 1 - les anciennes cles sont effacees, rien n'est perdu`() {
        val pets = FakePrefs()
        open(pets, legacyFile())
        val migrated = HashMap(pets.map)
        // Le processus est tué entre l'écriture du schéma 1 et l'effacement : les deux coexistent
        val settings = legacyFile()
        open(pets, settings)
        assertEquals(migrated, pets.map)
        assertTrue(PetMigrations.LEGACY_PET_KEYS.none { it in settings.map })
        assertEquals(2, settings.map[Prefs.MISCHIEF])
    }

    @Test
    fun `premier lancement - aucun animal, puis un resident non adopte est cree`() {
        val settings = FakePrefs().apply { edit().putBoolean(Prefs.ENABLED, false).apply() }
        val pets = FakePrefs()
        val repo = open(pets, settings)
        assertEquals(1, pets.map[PetRepository.KEY_SCHEMA])
        assertNull(repo.loadResident())
        val r = repo.residentOrCreate()
        assertFalse(r.adopted)
        assertEquals(PetStats(), r.stats)
        assertEquals(clock.wall, r.identity.bornAt)
        assertEquals(r, repo.loadResident())
        assertEquals(r, repo.residentOrCreate())                // pas de second animal
        assertEquals(1, repo.ids().size)
    }

    @Test
    fun `ancien fichier aux index hors limites - bornes comme l'ancien affichage`() {
        val settings = FakePrefs().apply {
            edit().putLong("bornAt", 5L).putInt("look_fur", 99).putInt("look_ears", 7).putInt("look_eyes", -2)
                .putInt("look_acc", 42).putInt("look_accColor", 12).putString("personality", "INCONNU").apply()
        }
        val r = open(FakePrefs(), settings).loadResident()!!
        assertEquals(Look(Fur.PINK, Ears.UP, EyeColor.HAZEL, Pattern.PLAIN, Accessory.NONE, AccColor.GREEN), r.look)
        assertEquals(Personality.CUDDLY, r.traits.personality)
        assertEquals(Identity.DEFAULT_NAME, r.identity.name)
    }

    @Test
    fun `lire n'a aucun effet de bord`() {
        val pets = FakePrefs()
        val repo = open(pets, legacyFile())
        val snapshot = HashMap(pets.map)
        clock.advance(10 * 3_600_000L)
        repo.loadResident(); repo.ids(); repo.residentId(); repo.load(PetId("uuid-1")); repo.load(PetId("absent"))
        assertEquals(snapshot, pets.map)
    }

    @Test
    fun `sauvegarde puis rechargement, horodatee par l'horloge`() {
        val repo = open(FakePrefs(), FakePrefs())
        val r0 = repo.residentOrCreate()
        val r = r0.copy(
            identity = r0.identity.copy(name = "Caramel"),
            traits = Traits(Personality.CURIOUS, Toy.PLUSH),
            look = Look(Fur.GREY, Ears.ONE_FOLDED, EyeColor.RUBY, Pattern.EYE_PATCH, Accessory.HAT, AccColor.VIOLET),
            stats = PetStats(1f, 2f, 3f, 4f, 5f, sleeping = true, poops = 4, poopAccumulator = 0.5f, visitedApps = 9, caged = true),
            adopted = true,
        )
        clock.advance(60_000L)
        assertTrue(repo.save(r))
        assertEquals(r.copy(lastUpdate = clock.wall), repo.loadResident())
    }

    @Test
    fun `identifiants d'apparence inconnus - valeurs par defaut`() {
        val pets = FakePrefs()
        val repo = open(pets, FakePrefs())
        val id = repo.residentOrCreate().id
        pets.edit().putString("pet.${id.value}.look.fur", "licorne").putString("pet.${id.value}.favoriteToy", "TOUPIE").apply()
        val r = repo.loadResident()!!
        assertEquals(Fur.SNOW, r.look.fur)
        assertEquals(Toy.BALL, r.traits.favoriteToy)
    }

    @Test
    fun `adoption - le nouveau remplace l'ancien, qui ne peut plus sauvegarder`() {
        val pets = FakePrefs()
        val repo = open(pets, legacyFile())
        val old = repo.loadResident()!!
        val adopted = repo.adopt(Adoption("Praline", Look(fur = Fur.CREAM), Personality.LAZY, Toy.BALLOON, isNew = true))
        assertNotEquals(old.id, adopted.id)
        assertEquals(adopted.id, repo.residentId())
        assertEquals(listOf(adopted.id), repo.ids())
        assertEquals(PetStats(), adopted.stats)
        assertEquals(Traits(Personality.LAZY, Toy.BALLOON), adopted.traits)
        assertTrue(adopted.adopted)
        assertTrue(pets.map.keys.none { it.startsWith("pet.${old.id.value}.") })
        // L'ancien lapin (une instance encore en mémoire) sauvegarde en partant : ignoré
        assertFalse(repo.save(old.copy(stats = PetStats(satiety = 1f))))
        assertTrue(pets.map.keys.none { it.startsWith("pet.${old.id.value}.") })
        assertEquals(adopted.copy(lastUpdate = clock.wall), repo.loadResident())
    }

    @Test
    fun `personnaliser - meme animal, nom et apparence seulement`() {
        val repo = open(FakePrefs(), legacyFile())
        val old = repo.loadResident()!!
        val r = repo.adopt(Adoption("Biscotte", Look(fur = Fur.PINK), Personality.LAZY, Toy.BALL, isNew = false))
        assertEquals(old.id, r.id)
        assertEquals("Biscotte", r.identity.name)
        assertEquals(Look(fur = Fur.PINK), r.look)
        assertEquals(old.traits, r.traits)                       // le caractère ne change pas
        assertEquals(old.stats, r.stats)
        assertEquals(old.identity.bornAt, r.identity.bornAt)
    }

    @Test
    fun `renommer sans service`() {
        val repo = open(FakePrefs(), legacyFile())
        assertTrue(repo.rename("Pistache"))
        assertEquals("Pistache", repo.loadResident()!!.identity.name)
        assertFalse(open(FakePrefs(), FakePrefs()).rename("Personne"))
    }

    @Test
    fun `nom saisi nettoye et tronque`() {
        assertEquals("Noisette", Adoption.cleanName("  Noisette  "))
        assertNull(Adoption.cleanName("   "))
        assertNull(Adoption.cleanName(null))
        assertEquals(Identity.MAX_NAME, Adoption.cleanName("x".repeat(40))!!.length)
    }

    @Test
    fun `un PetRecord se recharge en Pet sans rattrapage`() {
        val rec = PetRecord(Identity(PetId("a"), "A", bornAt = 1L), stats = PetStats(satiety = 10f), lastUpdate = 1L)
        val p = Pet.fromRecord(rec, BunnyPhysics(), kotlin.random.Random(1))
        assertEquals(10f, p.stats.satiety)
        p.on(PetEvent.Elapsed((clock.wall - rec.lastUpdate) / 3_600_000f), clock.wall)
        assertEquals(0f, p.stats.satiety)                         // rattrapage explicite, plafonné à 48 h
    }
    // ------------------------------------------------------------------ Compagnons (étape 7)

    private fun companion(name: String) = Adoption(name, Look(fur = Fur.CREAM), Personality.PLAYFUL, Toy.FRISBEE, isNew = false)

    @Test
    fun `compagnon - adopte a cote du resident, qui reste le resident`() {
        val pets = FakePrefs()
        val repo = open(pets, legacyFile())
        val resident = repo.loadResident()!!
        val c = repo.adoptCompanion(companion("Praline"))!!
        assertNotEquals(resident.id, c.id)
        assertEquals(resident.id, repo.residentId())
        assertEquals(listOf(resident.id, c.id), repo.ids())
        assertEquals(listOf(c.copy(lastUpdate = clock.wall)), repo.companions())
        assertEquals(PetStats(), c.stats)                         // toujours un nouvel animal (isNew forcé)
        assertEquals(Traits(Personality.PLAYFUL, Toy.FRISBEE), c.traits)
        assertEquals(Species.DEFAULT.id, c.identity.species)
        assertEquals("rabbit", pets.getString("pet.${c.id.value}.species", null))
        // le résident n'a pas bougé (aucune clé réécrite)
        assertEquals(resident, repo.loadResident())
    }

    @Test
    fun `compagnon - sauvegarde par PetId, independante du resident`() {
        val repo = open(FakePrefs(), legacyFile())
        val resident = repo.loadResident()!!
        val c = repo.adoptCompanion(companion("Praline"))!!
        assertTrue(repo.save(c.copy(stats = PetStats(satiety = 12f))))
        assertEquals(12f, repo.load(c.id)!!.stats.satiety)
        assertEquals(resident.stats, repo.loadResident()!!.stats)
    }

    @Test
    fun `compagnon - retirer, jamais le resident`() {
        val pets = FakePrefs()
        val repo = open(pets, legacyFile())
        val resident = repo.loadResident()!!
        val c = repo.adoptCompanion(companion("Praline"))!!
        assertFalse(repo.removeCompanion(resident.id))
        assertFalse(repo.removeCompanion(PetId("inconnu")))
        assertTrue(repo.removeCompanion(c.id))
        assertEquals(listOf(resident.id), repo.ids())
        assertTrue(pets.map.keys.none { it.startsWith("pet.${c.id.value}.") })
        assertFalse(repo.save(c))                                 // parti : ne revient pas
        assertEquals(resident.id, repo.residentId())
    }

    @Test
    fun `voyage - le resident part, un compagnon prend sa place`() {
        val pets = FakePrefs()
        val repo = open(pets, legacyFile())
        val resident = repo.loadResident()!!
        val c = repo.adoptCompanion(companion("Praline"))!!
        assertEquals(c.id, com.tamacookie.travel.TravelRules.depart(repo, resident.id))
        assertEquals(c.id, repo.residentId())
        assertEquals(listOf(c.id), repo.ids())
        assertEquals(c.id.value, pets.getString("resident", null))
        assertTrue(repo.companions().isEmpty())
        assertNull(com.tamacookie.travel.TravelRules.depart(repo, c.id))
        assertNull(repo.residentId())
    }

    @Test
    fun `compagnon - plafond, et pas sans resident`() {
        assertNull(open(FakePrefs(), FakePrefs()).adoptCompanion(companion("Seul")))
        val repo = open(FakePrefs(), legacyFile())
        repeat(Tuning.MAX_PETS - 1) { assertTrue(repo.adoptCompanion(companion("C$it")) != null) }
        assertEquals(Tuning.MAX_PETS, repo.ids().size)
        assertNull(repo.adoptCompanion(companion("De trop")))
    }

    @Test
    fun `nouveau lapin - remplace le resident, garde les compagnons`() {
        val repo = open(FakePrefs(), legacyFile())
        val old = repo.loadResident()!!
        val c = repo.adoptCompanion(companion("Praline"))!!
        val adopted = repo.adopt(Adoption("Caramel", Look(), Personality.LAZY, Toy.BALL, isNew = true))
        assertEquals(adopted.id, repo.residentId())
        assertEquals(listOf(c.id, adopted.id), repo.ids())
        assertEquals(listOf(c.id), repo.companions().map { it.id })
        assertNull(repo.load(old.id))
    }
}

/** Horloge de test avancée à la main (le `FakeClock` du cœur vit dans les tests de `:core`, invisibles ici). */
private class RepoClock(var wall: Long) : Clock {
    override fun uptimeMs() = wall
    override fun wallMs() = wall
    fun advance(ms: Long) { wall += ms }
}
