package com.tamacookie.travel

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Transport Bluetooth du voyage : **BLE + canal L2CAP orienté connexion** (sans appairage : `insecure`). La
 * confidentialité et l'authenticité viennent de la clé lue par NFC ([TravelChannel], AES-GCM), pas du lien.
 *
 * Les permissions (`BLUETOOTH_ADVERTISE` / `_SCAN` / `_CONNECT`) sont vérifiées par [TravelActivity] **avant** de
 * créer ces objets, d'où les `@SuppressLint("MissingPermission")`.
 */
object TravelBluetooth {
    /** Service annoncé par l'émetteur ; ses données = [TravelInvite.tag] (8 octets). */
    val SERVICE: ParcelUuid = ParcelUuid(UUID.fromString("6c1f0b2e-5a4d-4c6e-9f1a-7a6d2b3c4e5f"))
}

/** Émetteur : canal L2CAP en écoute + annonce BLE portant le tag de l'invitation. */
@SuppressLint("MissingPermission")
class TravelHost(private val adapter: BluetoothAdapter) : Closeable {
    private val server: BluetoothServerSocket = adapter.listenUsingInsecureL2capChannel()
    private var advertising: AdvertiseCallback? = null

    /** Canal L2CAP attribué par le système (dans l'invitation). */
    val psm: Int get() = server.psm

    /** Commence l'annonce ; [onFail] (fil principal) si le téléphone la refuse. */
    fun advertise(tag: ByteArray, onFail: (Int) -> Unit) {
        val advertiser = adapter.bluetoothLeAdvertiser ?: return onFail(-1)
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(TravelBluetooth.SERVICE, tag)
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) = onFail(errorCode)
        }
        advertising = cb
        advertiser.startAdvertising(settings, data, cb)
    }

    /** Attend un receveur (bloquant ; [close] l'interrompt). */
    @Throws(IOException::class)
    fun accept(): BluetoothSocket = server.accept()

    override fun close() {
        advertising?.let { cb -> runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertising(cb) } }
        advertising = null
        runCatching { server.close() }
    }
}

/**
 * Receveur : cherche l'annonce qui porte le tag de l'invitation, puis ouvre le canal L2CAP. Un appareil déjà
 * renvoyé par [find] ne l'est plus (un imitateur écarté, on trouve le suivant).
 */
@SuppressLint("MissingPermission")
class TravelGuest(private val adapter: BluetoothAdapter) : Closeable {
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var scan: ScanCallback? = null
    @Volatile private var waiting: CountDownLatch? = null
    @Volatile private var closed = false
    private val tried = HashSet<String>()

    /** Un émetteur de [invite] pas encore essayé, trouvé en moins de [timeoutMs] (bloquant), ou null. */
    fun find(invite: TravelInvite, timeoutMs: Long): BluetoothDevice? {
        val scanner = adapter.bluetoothLeScanner
        if (closed || scanner == null) return null
        val latch = CountDownLatch(1)
        val found = AtomicReference<BluetoothDevice?>()
        val filter = ScanFilter.Builder().setServiceData(TravelBluetooth.SERVICE, invite.tag).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val ours = result.scanRecord?.getServiceData(TravelBluetooth.SERVICE)?.contentEquals(invite.tag) == true
                val fresh = synchronized(tried) { result.device.address !in tried }
                if (ours && fresh && found.compareAndSet(null, result.device)) latch.countDown()
            }

            override fun onScanFailed(errorCode: Int) = latch.countDown()
        }
        scan = cb
        waiting = latch
        scanner.startScan(listOf(filter), settings, cb)
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            stopScan()
        }
        val d = found.get() ?: return null
        synchronized(tried) { tried += d.address }
        return if (closed) null else d
    }

    /** Ouvre le canal L2CAP vers [device] (bloquant ; [close] l'interrompt). */
    @Throws(IOException::class)
    fun connect(device: BluetoothDevice, psm: Int): BluetoothSocket {
        val s = device.createInsecureL2capChannel(psm)
        socket = s
        if (closed) s.close()
        s.connect()
        return s
    }

    private fun stopScan() {
        scan?.let { cb -> runCatching { adapter.bluetoothLeScanner?.stopScan(cb) } }
        scan = null
    }

    override fun close() {
        closed = true
        waiting?.countDown()
        stopScan()
        runCatching { socket?.close() }
    }
}
