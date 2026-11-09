package me.hufman.androidautoidrive.carapp.carinfo

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.hufman.androidautoidrive.CarInformation
import me.hufman.androidautoidrive.CarThreadExceptionHandler
import me.hufman.androidautoidrive.cds.CDSMetrics
import java.util.UUID

/**
 * Habla por Bluetooth (BLE) con la placa "B48-Escape", que pulsa el mando de la válvula.
 * La placa tiene que estar emparejada antes desde Ajustes > Bluetooth del móvil.
 */
@SuppressLint("MissingPermission")
class ValveBoardController(val context: Context, val handler: Handler) {
	companion object {
		const val TAG = "B48Escape"
		const val DEVICE_NAME = "B48-Escape"
		val SERVICE_UUID: UUID = UUID.fromString("6b7a0001-b48a-4c1e-9d3e-0e5c0a1f4800")
		val CMD_UUID: UUID = UUID.fromString("6b7a0002-b48a-4c1e-9d3e-0e5c0a1f4800")
		const val RETRY_MS = 10_000L
		const val TICK_MS = 500L
	}

	private val coroutineScope = CoroutineScope(handler.asCoroutineDispatcher() + SupervisorJob() + CarThreadExceptionHandler)
	private val logic = ValveLogic()
	private var running = false

	private var lastDrivingMode: String? = null
	private var lastRpm: Int? = null

	private var gatt: BluetoothGatt? = null
	private var commandChar: BluetoothGattCharacteristic? = null
	private var pendingOpen: Boolean? = null     // la orden que falta por mandar
	private var inFlight: Boolean? = null        // la orden que se está mandando ahora

	fun start() {
		if (running) return
		running = true
		val metrics = CDSMetrics(CarInformation())
		coroutineScope.launch { metrics.drivingMode.collectLatest { lastDrivingMode = it; evaluate() } }
		coroutineScope.launch { metrics.engineRpm.collectLatest { lastRpm = it; evaluate() } }
		coroutineScope.launch { MotorData.valveMode.collectLatest { evaluate() } }
		handler.post(tick)
		connect()
	}

	fun stop() {
		running = false
		handler.removeCallbacks(tick)
		handler.removeCallbacks(reconnect)
		coroutineScope.cancel()
		try {
			gatt?.disconnect()
			gatt?.close()
		} catch (e: Exception) {}
		gatt = null
		commandChar = null
		MotorData.valveBoardStatus.value = "Placa: sin conectar"
	}

	/** Se revisa cada medio segundo, para los cambios que esperaban el tiempo mínimo */
	private val tick = object : Runnable {
		override fun run() {
			evaluate()
			if (running) handler.postDelayed(this, TICK_MS)
		}
	}

	private fun evaluate() {
		val change = logic.update(MotorData.valveMode.value, lastDrivingMode, lastRpm, SystemClock.elapsedRealtime())
		if (change != null) {
			Log.i(TAG, "Válvula -> ${if (change) "abrir" else "cerrar"} (modo ${MotorData.valveMode.value}, $lastDrivingMode, $lastRpm rpm)")
			MotorData.valveOpen.value = change
			pendingOpen = change
			writePending()
		}
	}

	// ---- Bluetooth ----

	private val reconnect = Runnable { connect() }

	private fun hasPermission(): Boolean {
		return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
			ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
	}

	private fun connect() {
		if (!running || gatt != null) return
		if (!hasPermission()) {
			MotorData.valveBoardStatus.value = "Placa: falta permiso Bluetooth"
			handler.postDelayed(reconnect, RETRY_MS)
			return
		}
		val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
		val device = try {
			adapter?.bondedDevices?.firstOrNull { it.name == DEVICE_NAME }
		} catch (e: SecurityException) { null }
		if (device == null) {
			MotorData.valveBoardStatus.value = "Placa: no emparejada"
			handler.postDelayed(reconnect, RETRY_MS)
			return
		}
		MotorData.valveBoardStatus.value = "Placa: conectando…"
		gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
	}

	private fun disconnected() {
		try { gatt?.close() } catch (e: Exception) {}
		gatt = null
		commandChar = null
		if (running) {
			MotorData.valveBoardStatus.value = "Placa: desconectada"
			handler.removeCallbacks(reconnect)
			handler.postDelayed(reconnect, 3000)
		}
	}

	@Suppress("DEPRECATION")
	private fun writePending() {
		val open = pendingOpen ?: return
		val gatt = gatt ?: return
		val characteristic = commandChar ?: return
		characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
		characteristic.value = byteArrayOf((if (open) 'O' else 'C').code.toByte())
		val started = try { gatt.writeCharacteristic(characteristic) } catch (e: SecurityException) { false }
		if (started) {
			inFlight = open
		} else {
			// el Bluetooth está ocupado: reintentar en un momento
			handler.postDelayed({ writePending() }, 500)
		}
	}

	private val gattCallback = object : BluetoothGattCallback() {
		override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
			handler.post {
				if (newState == BluetoothProfile.STATE_CONNECTED) {
					MotorData.valveBoardStatus.value = "Placa: preparando…"
					g.discoverServices()
				} else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
					disconnected()
				}
			}
		}

		override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
			handler.post {
				val characteristic = g.getService(SERVICE_UUID)?.getCharacteristic(CMD_UUID)
				if (characteristic == null) {
					MotorData.valveBoardStatus.value = "Placa: no responde bien"
					g.disconnect()
					return@post
				}
				commandChar = characteristic
				MotorData.valveBoardStatus.value = "Placa: conectada"
				// la placa cierra al perder la conexión: volver a mandar el estado que toca
				pendingOpen = logic.currentOpen ?: false
				writePending()
			}
		}

		override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
			handler.post {
				if (status == BluetoothGatt.GATT_SUCCESS) {
					if (pendingOpen == inFlight) {
						pendingOpen = null
					} else {
						writePending()   // mientras se mandaba, llegó otra orden
					}
				} else {
					Log.w(TAG, "La placa rechazó la orden: $status")
					MotorData.valveBoardStatus.value = "Placa: orden rechazada ($status)"
					handler.postDelayed({ writePending() }, 1000)
				}
			}
		}
	}
}
