package es.erniee.b48escape

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.util.Log
import java.util.UUID

/**
 * Habla por Bluetooth (BLE) con la placa "B48-Escape", que pulsa el mando de la válvula.
 * La placa tiene que estar emparejada antes desde Ajustes > Bluetooth del móvil.
 */
@SuppressLint("MissingPermission")
class BoardLink(private val context: Context, private val handler: Handler) {
	companion object {
		const val TAG = "B48Escape"
		const val DEVICE_NAME = "B48-Escape"
		val SERVICE_UUID: UUID = UUID.fromString("6b7a0001-b48a-4c1e-9d3e-0e5c0a1f4800")
		val CMD_UUID: UUID = UUID.fromString("6b7a0002-b48a-4c1e-9d3e-0e5c0a1f4800")
		const val RETRY_MS = 8_000L
		const val CONNECT_TIMEOUT_MS = 15_000L
	}

	private var running = false
	private var gatt: BluetoothGatt? = null
	private var commandChar: BluetoothGattCharacteristic? = null
	private var pendingOpen: Boolean? = null
	private var inFlight: Boolean? = null
	/** El último estado pedido, para volver a mandarlo al reconectar */
	private var lastWanted: Boolean = false

	/** Se llama al conectar, para que el servicio vuelva a mandar el estado que toca */
	var onReady: (() -> Unit)? = null

	fun start() {
		if (running) return
		running = true
		connect()
	}

	fun stop() {
		running = false
		handler.removeCallbacks(reconnect)
		handler.removeCallbacks(connectTimeout)
		try { gatt?.disconnect(); gatt?.close() } catch (e: Exception) {}
		gatt = null
		commandChar = null
		status(false, "Sin conectar")
	}

	fun send(open: Boolean) {
		lastWanted = open
		pendingOpen = open
		writePending()
	}

	private fun status(connected: Boolean, text: String) {
		Escape.boardConnected.value = connected
		Escape.boardStatus.value = text
	}

	private val reconnect = Runnable { connect() }

	private fun connect() {
		if (!running || gatt != null) return
		val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
		val device = try {
			adapter?.bondedDevices?.firstOrNull { it.name == DEVICE_NAME }
		} catch (e: SecurityException) { null }
		if (device == null) {
			status(false, "No emparejada")
			handler.postDelayed(reconnect, RETRY_MS)
			return
		}
		status(false, "Conectando…")
		gatt = try {
			device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
		} catch (e: SecurityException) { null }
		if (gatt == null) handler.postDelayed(reconnect, RETRY_MS)
		else handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
	}

	/** Si en un rato no ha conectado, cerrar y volver a intentar */
	private val connectTimeout = Runnable {
		if (running && commandChar == null) {
			try { gatt?.disconnect() } catch (e: SecurityException) {}
			disconnected()
		}
	}

	private fun disconnected() {
		handler.removeCallbacks(connectTimeout)
		try { gatt?.close() } catch (e: Exception) {}
		gatt = null
		commandChar = null
		if (running) {
			status(false, "Buscando…")
			handler.removeCallbacks(reconnect)
			handler.postDelayed(reconnect, 3000)
		}
	}

	@Suppress("DEPRECATION")
	private fun writePending() {
		val open = pendingOpen ?: return
		val g = gatt ?: return
		val c = commandChar ?: return
		c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
		c.value = byteArrayOf((if (open) 'O' else 'C').code.toByte())
		val started = try { g.writeCharacteristic(c) } catch (e: SecurityException) { false }
		if (started) inFlight = open
		else handler.postDelayed({ writePending() }, 500)
	}

	private val gattCallback = object : BluetoothGattCallback() {
		override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
			handler.post {
				if (newState == BluetoothProfile.STATE_CONNECTED) {
					status(false, "Preparando…")
					try { g.discoverServices() } catch (e: SecurityException) {}
				} else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
					disconnected()
				}
			}
		}

		override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
			handler.post {
				val c = g.getService(SERVICE_UUID)?.getCharacteristic(CMD_UUID)
				if (c == null) {
					status(false, "No responde bien")
					try { g.disconnect() } catch (e: SecurityException) {}
					return@post
				}
				commandChar = c
				handler.removeCallbacks(connectTimeout)
				status(true, "Conectada")
				// la placa cierra al perder la conexión: volver a mandar lo que toca
				pendingOpen = lastWanted
				writePending()
				onReady?.invoke()
			}
		}

		override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
			handler.post {
				if (st == BluetoothGatt.GATT_SUCCESS) {
					if (pendingOpen == inFlight) pendingOpen = null else writePending()
				} else {
					Log.w(TAG, "La placa rechazó la orden: $st")
					status(true, "Orden rechazada ($st)")
					handler.postDelayed({ writePending() }, 1000)
				}
			}
		}
	}
}
