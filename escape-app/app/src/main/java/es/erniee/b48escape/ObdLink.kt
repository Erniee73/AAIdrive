package es.erniee.b48escape

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Lee las rpm del motor con el vLinker (Bluetooth clásico, comandos tipo ELM327).
 * Usa la petición OBD estándar 01 0C, que la DME del B48 contesta por el gateway.
 */
@SuppressLint("MissingPermission")
class ObdLink(private val context: Context) {
	companion object {
		const val TAG = "B48EscapeObd"
		val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
		const val POLL_MS = 250L

		/** Busca el vLinker entre los dispositivos emparejados */
		fun findDevice(context: Context): BluetoothDevice? {
			val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
			val bonded = try { adapter.bondedDevices ?: emptySet() } catch (e: SecurityException) { emptySet() }
			Escape.obdAddress?.let { addr -> bonded.firstOrNull { it.address == addr }?.let { return it } }
			return bonded.firstOrNull {
				val n = (try { it.name } catch (e: SecurityException) { null } ?: "").lowercase()
				n.contains("vlinker") || n.contains("obd") || n.contains("elm")
			}
		}

		/** "41 0C 0F A0" -> 1000 rpm */
		fun parseRpm(reply: String): Int? {
			val clean = reply.uppercase().replace(" ", "").replace("\r", "").replace("\n", "")
			val i = clean.indexOf("410C")
			if (i < 0 || clean.length < i + 8) return null
			val a = clean.substring(i + 4, i + 6).toIntOrNull(16) ?: return null
			val b = clean.substring(i + 6, i + 8).toIntOrNull(16) ?: return null
			return (a * 256 + b) / 4
		}
	}

	@Volatile private var running = false
	private var worker: Thread? = null
	@Volatile private var socket: BluetoothSocket? = null

	fun start() {
		if (running) return
		running = true
		worker = thread(name = "obd") { loop() }
	}

	fun stop() {
		running = false
		try { socket?.close() } catch (e: Exception) {}
		worker?.interrupt()
		worker = null
		status(false, "Sin conectar")
		Escape.rpm.value = null
	}

	private fun status(connected: Boolean, text: String) {
		Escape.obdConnected.value = connected
		Escape.obdStatus.value = text
	}

	private fun loop() {
		while (running) {
			val device = findDevice(context)
			if (device == null) {
				status(false, "No emparejado")
				sleep(8000); continue
			}
			try {
				status(false, "Conectando…")
				val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
				socket = s
				try { context.getSystemService(BluetoothManager::class.java)?.adapter?.cancelDiscovery() } catch (e: SecurityException) {}
				s.connect()
				val input = s.inputStream
				val output = s.outputStream
				command(input, output, "ATZ", 3000)
				for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP6")) command(input, output, c, 1500)
				status(true, "Conectado")
				var misses = 0
				while (running) {
					val reply = command(input, output, "010C", 1500)
					val rpm = parseRpm(reply)
					if (rpm != null) {
						misses = 0
						Escape.rpm.value = rpm
						if (Escape.obdStatus.value != "Conectado") status(true, "Conectado")
					} else {
						misses++
						if (misses >= 4) {
							Escape.rpm.value = null
							status(true, "Sin datos del motor")
						}
						if (misses >= 4) sleep(1000)
					}
					sleep(POLL_MS)
				}
			} catch (e: Exception) {
				Log.w(TAG, "vLinker: ${e.message}")
				Escape.rpm.value = null
				status(false, "Buscando…")
				try { socket?.close() } catch (x: Exception) {}
				socket = null
				sleep(5000)
			}
		}
	}

	/** Manda un comando y lee hasta el ">" del ELM */
	private fun command(input: InputStream, output: OutputStream, cmd: String, timeoutMs: Long): String {
		while (input.available() > 0) input.read()
		output.write("$cmd\r".toByteArray())
		output.flush()
		val sb = StringBuilder()
		val end = SystemClock.elapsedRealtime() + timeoutMs
		while (SystemClock.elapsedRealtime() < end) {
			if (input.available() > 0) {
				val ch = input.read()
				if (ch < 0) throw java.io.IOException("cerrado")
				if (ch.toChar() == '>') break
				sb.append(ch.toChar())
			} else {
				sleep(10)
			}
		}
		return sb.toString()
	}

	private fun sleep(ms: Long) {
		try { Thread.sleep(ms) } catch (e: InterruptedException) {}
	}
}
