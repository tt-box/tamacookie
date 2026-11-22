package com.tamacookie.travel

import com.tamacookie.PetId
import com.tamacookie.Tuning
import com.tamacookie.persist.PetSnapshot
import com.tamacookie.persist.PetStore
import java.io.IOException

/**
 * Ce que le voyage change dans le magasin des animaux (pur, sur le port [PetStore]).
 *
 * - **Arrivée** : refusée si l'animal vit déjà ici ou si la maison est pleine ; sinon il devient un compagnon — ou
 *   le résident si ce téléphone n'a encore adopté personne (il remplace l'animal provisoire du premier lancement).
 * - **Départ** : tout animal peut partir ; si c'est le résident, le plus ancien compagnon le devient. S'il n'en
 *   reste aucun, le téléphone est vide (l'écran d'adoption reviendra).
 */
object TravelRules {

    /** Pourquoi [snap] ne peut pas s'installer chez [store] (null : il peut). */
    fun refusal(store: PetStore, snap: PetSnapshot): Refusal? {
        val ids = store.ids()
        val provisional = store.loadResident()?.takeIf { !it.adopted }?.id
        return when {
            snap.id in ids && snap.id != provisional -> Refusal.ALREADY_HERE
            ids.count { it != provisional } >= Tuning.MAX_PETS -> Refusal.HOUSE_FULL
            else -> null
        }
    }

    /**
     * Installe [snap] chez [store] (horodaté [now]) ou dit pourquoi c'est impossible. L'écriture est rendue
     * **durable** ([PetStore.sync]) avant de rendre la main : l'appelant va confirmer au pair, qui le retirera de
     * chez lui. Écriture impossible : l'animal est retiré et [IOException] (le pair le garde).
     */
    @Throws(IOException::class)
    fun arrive(store: PetStore, snap: PetSnapshot, now: Long): Refusal? {
        refusal(store, snap)?.let { return it }
        val rec = snap.toRecord(now)
        val resident = store.loadResident()
        if (resident == null || !resident.adopted) {
            store.adopt(rec, replacing = resident?.id)
        } else {
            store.addCompanion(rec)
        }
        if (!store.sync()) {
            store.remove(rec.id)
            throw IOException("enregistrement impossible")
        }
        return null
    }

    /** Retire [id] de [store] (durablement) ; renvoie le résident d'après (null : plus aucun animal ici). */
    fun depart(store: PetStore, id: PetId): PetId? {
        val wasResident = store.residentId() == id
        store.remove(id)
        val heir = if (wasResident) store.ids().firstOrNull() else store.residentId()
        if (wasResident) heir?.let(store::makeResident)
        // Échec d'écriture : au pire il reviendra au prochain lancement (un double, jamais une perte)
        store.sync()
        return heir
    }
}
