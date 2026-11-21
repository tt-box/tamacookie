package com.tamacookie

import android.content.Context
import android.content.Intent
import com.tamacookie.feature.toy.Toy
import com.tamacookie.persist.Adoption
import com.tamacookie.persist.PetFields
import com.tamacookie.persist.Pets
import com.tamacookie.widget.PetWidget

/**
 * Ce que les activités demandent pour l'animal. **Une seule autorité** : si le service tourne, c'est lui
 * qui possède l'état (commande par intent, il applique et sauvegarde) ; sinon le [com.tamacookie.persist.PetRepository]
 * est écrit directement. Les activités ne lisent l'animal que par le repository (lecture sans effet de bord).
 */
object PetCommands {
    const val ACTION_ADOPT = "com.tamacookie.ADOPT"
    const val ACTION_SET_NAME = "com.tamacookie.SET_NAME"
    /** Un nouvel animal rejoint le résident (étape 7). */
    const val ACTION_ADOPT_COMPANION = "com.tamacookie.ADOPT_COMPANION"
    /** Un compagnon s'en va (jamais le résident). */
    const val ACTION_REMOVE_PET = "com.tamacookie.REMOVE_PET"

    /** Voyage : un animal est arrivé d'un autre téléphone (déjà enregistré dans le repository). */
    const val ACTION_PET_ARRIVED = "com.tamacookie.PET_ARRIVED"
    /** Voyage : un animal est parti sur un autre téléphone (déjà retiré du repository). */
    const val ACTION_PET_LEFT = "com.tamacookie.PET_LEFT"

    const val EXTRA_NAME = "name"
    const val EXTRA_ID = "id"
    private const val EXTRA_NEW = "new"
    private const val EXTRA_PERSONALITY = "personality"
    private const val EXTRA_TOY = "favoriteToy"
    private const val EXTRA_FUR = "look.fur"
    private const val EXTRA_EARS = "look.ears"
    private const val EXTRA_EYES = "look.eyes"
    private const val EXTRA_PATTERN = "look.pattern"
    private const val EXTRA_ACC = "look.accessory"
    private const val EXTRA_ACC_COLOR = "look.accColor"

    fun adopt(ctx: Context, a: Adoption) {
        if (BunnyService.running) {
            ctx.startService(adoptionIntent(ctx, ACTION_ADOPT, a))
        } else {
            Pets.repository(ctx).adopt(a)
            PetWidget.updateAll(ctx)
        }
    }

    /** Adopte un compagnon (nouvel animal, le résident reste) ; refusé au-delà de [Tuning.MAX_PETS]. */
    fun adoptCompanion(ctx: Context, a: Adoption) {
        if (BunnyService.running) {
            ctx.startService(adoptionIntent(ctx, ACTION_ADOPT_COMPANION, a.copy(isNew = true)))
        } else {
            Pets.repository(ctx).adoptCompanion(a)
        }
    }

    /** Le compagnon [id] s'en va (ses clés sont effacées) ; le résident ne peut pas être retiré. */
    fun removeCompanion(ctx: Context, id: PetId) {
        if (BunnyService.running) {
            ctx.startService(Intent(ctx, BunnyService::class.java).setAction(ACTION_REMOVE_PET).putExtra(EXTRA_ID, id.value))
        } else {
            Pets.repository(ctx).removeCompanion(id)
        }
    }

    /**
     * Voyage : le repository a déjà été écrit par le voyage ([com.tamacookie.travel.TravelRules]) — l'échange
     * Bluetooth exige que ce soit fait **avant** de répondre au pair ; le service, s'il tourne, met son monde à jour.
     */
    fun petArrived(ctx: Context, id: PetId) {
        if (BunnyService.running) ctx.startService(travelIntent(ctx, ACTION_PET_ARRIVED, id))
        PetWidget.updateAll(ctx)
    }

    /** Voyage : [id] est parti (voir [petArrived]). */
    fun petLeft(ctx: Context, id: PetId) {
        if (BunnyService.running) ctx.startService(travelIntent(ctx, ACTION_PET_LEFT, id))
        PetWidget.updateAll(ctx)
    }

    private fun travelIntent(ctx: Context, action: String, id: PetId) =
        Intent(ctx, BunnyService::class.java).setAction(action).putExtra(EXTRA_ID, id.value)

    private fun adoptionIntent(ctx: Context, action: String, a: Adoption): Intent =
        Intent(ctx, BunnyService::class.java).setAction(action)
            .putExtra(EXTRA_NAME, a.name).putExtra(EXTRA_NEW, a.isNew)
            .putExtra(EXTRA_PERSONALITY, a.personality.name).putExtra(EXTRA_TOY, a.favoriteToy.name)
            .putExtra(EXTRA_FUR, a.look.fur.id).putExtra(EXTRA_EARS, a.look.ears.id)
            .putExtra(EXTRA_EYES, a.look.eyes.id).putExtra(EXTRA_PATTERN, a.look.pattern.id)
            .putExtra(EXTRA_ACC, a.look.accessory.id).putExtra(EXTRA_ACC_COLOR, a.look.accColor.id)

    fun rename(ctx: Context, name: String) {
        val n = Adoption.cleanName(name) ?: return
        if (BunnyService.running) {
            ctx.startService(Intent(ctx, BunnyService::class.java).setAction(ACTION_SET_NAME).putExtra(EXTRA_NAME, n))
        } else {
            Pets.repository(ctx).rename(n)
            PetWidget.updateAll(ctx)
        }
    }

    /** Relit une commande d'adoption (null si incomplète). Les ids inconnus retombent sur les défauts. */
    fun adoptionFrom(i: Intent): Adoption? {
        val name = Adoption.cleanName(i.getStringExtra(EXTRA_NAME)) ?: return null
        val d = Look()
        return Adoption(
            name = name,
            look = Look(
                fur = Look.byId(Fur.entries, i.getStringExtra(EXTRA_FUR), d.fur),
                ears = Look.byId(Ears.entries, i.getStringExtra(EXTRA_EARS), d.ears),
                eyes = Look.byId(EyeColor.entries, i.getStringExtra(EXTRA_EYES), d.eyes),
                pattern = Look.byId(Pattern.entries, i.getStringExtra(EXTRA_PATTERN), d.pattern),
                accessory = Look.byId(Accessory.entries, i.getStringExtra(EXTRA_ACC), d.accessory),
                accColor = Look.byId(AccColor.entries, i.getStringExtra(EXTRA_ACC_COLOR), d.accColor),
            ),
            personality = PetFields.enumOr(i.getStringExtra(EXTRA_PERSONALITY), Personality.CUDDLY),
            favoriteToy = PetFields.enumOr(i.getStringExtra(EXTRA_TOY), Toy.BALL),
            isNew = i.getBooleanExtra(EXTRA_NEW, false),
        )
    }
}
