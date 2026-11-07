package com.tamacookie.tile

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.tamacookie.BunnyService
import com.tamacookie.MainActivity
import com.tamacookie.R
import com.tamacookie.persist.Pets

/**
 * Tuile Réglages rapides (évolution 4c) : « Cookie » (le nom du résident), active = l'animal est affiché.
 * Un toucher bascule montrer / cacher par [BunnyService.ACTION_TOGGLE] (le chemin du « Cacher » / « Montrer » de
 * la notification) ; si le service ne tourne pas, il est démarré et l'animal montré ([BunnyService.ACTION_SHOW]).
 * Sans la permission de surimpression (ou si le système refuse le démarrage), l'app s'ouvre.
 * L'état est relu à chaque [onStartListening] et le service le redemande quand il change ([refresh]).
 */
class PetTileService : TileService() {

    companion object {
        private const val TAG = "TamaCookie"

        /** Demande au système de relire l'état de la tuile (sans effet si elle n'est pas posée). */
        fun refresh(ctx: Context) {
            try {
                requestListeningState(ctx, ComponentName(ctx, PetTileService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "tuile", e)
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        paint(BunnyService.running, BunnyService.visible)
    }

    override fun onClick() {
        super.onClick()
        if (!Settings.canDrawOverlays(this)) { openApp(); return }
        if (BunnyService.running) {
            startService(Intent(this, BunnyService::class.java).setAction(BunnyService.ACTION_TOGGLE))
            paint(running = true, visible = !BunnyService.visible)   // en attendant la confirmation du service
        } else {
            try {
                startForegroundService(BunnyService.userCommand(this, BunnyService.ACTION_SHOW))
                paint(running = true, visible = true)
            } catch (e: Exception) {
                Log.w(TAG, "tuile : démarrage refusé", e)
                openApp()
            }
        }
    }

    private fun paint(running: Boolean, visible: Boolean) {
        val tile = qsTile ?: return
        val st = TileTexts.state(running, visible)
        val name = try { Pets.repository(this).loadResident()?.identity?.name } catch (_: Exception) { null }
        tile.label = TileTexts.label(name, getString(R.string.tile_label))
        tile.subtitle = st.subtitle
        tile.contentDescription = getString(R.string.tile_description, tile.label, st.action)
        // Lu après le nom par le lecteur d'écran : « Sur l'écran », « Caché », « Rangé » (étape 8d)
        tile.stateDescription = st.subtitle
        tile.state = if (st.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivityAndCollapse(PendingIntent.getActivity(this, 30, i, PendingIntent.FLAG_IMMUTABLE))
    }
}
