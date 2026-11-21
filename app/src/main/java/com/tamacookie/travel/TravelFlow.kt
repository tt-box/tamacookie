package com.tamacookie.travel

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.tamacookie.R
import com.tamacookie.persist.Pets
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Un voyage en cours, d'un côté ([TravelSender], [TravelReceiver]) : délai de l'étape en cours, fin (message +
 * « Fermer »), passage au fil principal. Le dialogue Bluetooth tourne sur un fil à lui ; tout ce qui touche
 * l'écran ou le repository passe par le fil principal ([onMain]), comme le service.
 */
abstract class TravelFlow(protected val activity: Activity, protected val screen: TravelScreen) {
    protected enum class State { READY, ARMED, LINKED, ASKING, DONE }

    protected var state = State.READY
    protected val main = Handler(Looper.getMainLooper())
    protected val repo by lazy { Pets.repository(activity) }
    /** La fin vient du délai (et non d'une vraie coupure) : message adapté. */
    protected var timedOut = false
    @Volatile protected var socket: BluetoothSocket? = null
    private var deadline = 0L

    /** Autorisations Bluetooth de ce côté. */
    abstract val permissions: Array<String>

    /** Tout est prêt (NFC, Bluetooth, autorisations) : commence. */
    abstract fun begin(bt: BluetoothAdapter)

    /** Le délai de l'étape est dépassé (les connexions viennent d'être fermées). */
    protected open fun onTimeout() = Unit

    /** Ferme tout ce qui est ouvert (quitter l'écran, délai, fin) ; le fil du voyage s'arrête de lui-même. */
    open fun close() {
        main.removeCallbacks(tick)
        runCatching { socket?.close() }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (state == State.DONE) return
            if (SystemClock.uptimeMillis() < deadline) { main.postDelayed(this, TICK_MS); return }
            timedOut = true
            close()
            onTimeout()
        }
    }

    /** L'étape en cours doit finir dans [ms]. */
    protected fun armFor(ms: Long) {
        deadline = SystemClock.uptimeMillis() + ms
        main.removeCallbacks(tick)
        main.postDelayed(tick, TICK_MS)
    }

    /** Fin du voyage : [message], bouton « Fermer » ; [emoji] : celui de la réussite. */
    protected fun end(message: String, emoji: String? = null) {
        if (state == State.DONE) return
        state = State.DONE
        close()
        emoji?.let(screen::emoji)
        screen.message(message)
        screen.buttons(R.string.travel_btn_close, activity::finish)
    }

    /** [block] sur le fil principal, depuis le fil du voyage ; une erreur y devient une coupure de lien. */
    protected fun <T> onMain(block: () -> T): T {
        val task = FutureTask(block)
        main.post(task)
        val failure: Throwable = try {
            return task.get(MAIN_MS, TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            e.cause ?: e
        } catch (e: TimeoutException) {
            e
        } catch (e: InterruptedException) {
            e
        }
        throw failure as? IOException ?: IOException(failure)
    }

    protected companion object {
        const val MAIN_MS = 5_000L
        const val TICK_MS = 1_000L
        /** Une fois les téléphones reliés : échange complet, réponse de la personne qui reçoit comprise. */
        const val LINKED_MS = 75_000L
    }
}
