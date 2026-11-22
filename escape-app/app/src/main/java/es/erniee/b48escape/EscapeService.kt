package es.erniee.b48escape

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Hace de puente: vLinker (rpm) -> lógica Auto -> placa B48-Escape.
 * Sigue funcionando con la pantalla apagada y muestra una notificación con botones.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
class EscapeService : LifecycleService() {
	companion object {
		const val CHANNEL = "escape"
		const val NOTIF_ID = 48
		const val ACTION_MODE = "es.erniee.b48escape.MODE"
		const val ACTION_STOP = "es.erniee.b48escape.STOP"
		const val EXTRA_MODE = "mode"
		const val TICK_MS = 500L

		fun stop(context: Context) {
			try {
				context.startService(Intent(context, EscapeService::class.java).setAction(ACTION_STOP))
			} catch (e: Exception) {}
		}

		fun start(context: Context) {
			try {
				ContextCompat.startForegroundService(context, Intent(context, EscapeService::class.java))
			} catch (e: Exception) {
				Log.w("B48Escape", "No se pudo arrancar el servicio: ${e.message}")
			}
		}
	}

	private val handler = Handler(Looper.getMainLooper())
	private lateinit var board: BoardLink
	private lateinit var obd: ObdLink
	private val logic = ValveLogic()
	private var started = false

	override fun onCreate() {
		super.onCreate()
		Escape.init(this)
		createChannel()
		board = BoardLink(this, handler)
		obd = ObdLink(this)
		board.onReady = { logic.currentOpen?.let { board.send(it) } }
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		super.onStartCommand(intent, flags, startId)
		try {
			ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(),
				ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
		} catch (e: Exception) {
			Log.w("B48Escape", "Sin permiso para el servicio: ${e.message}")
			stopSelf()
			return START_NOT_STICKY
		}
		when (intent?.action) {
			ACTION_MODE -> intent.getStringExtra(EXTRA_MODE)?.let { name ->
				runCatching { Escape.Mode.valueOf(name) }.getOrNull()?.let { Escape.setMode(this, it) }
			}
			ACTION_STOP -> { stopEverything(); return START_NOT_STICKY }
		}
		if (!started) {
			started = true
			board.start()
			obd.start()
			lifecycleScope.launch {
				combine(Escape.mode, Escape.rpm, Escape.drivingMode) { _, _, _ -> Unit }.collect { evaluate() }
			}
			lifecycleScope.launch {
				combine(Escape.mode, Escape.valveOpen, Escape.boardConnected, Escape.obdConnected, Escape.drivingMode) { _, _, _, _, _ -> Unit }
					.debounce(300).collect { updateNotification() }
			}
			lifecycleScope.launch {
				// la notificación muestra las rpm redondeadas, sin refrescarla a cada lectura
				Escape.rpm.debounce(1000).distinctUntilChanged().collect { updateNotification() }
			}
			handler.post(tick)
		}
		return START_STICKY
	}

	private val tick = object : Runnable {
		override fun run() {
			evaluate()
			if (started) handler.postDelayed(this, TICK_MS)
		}
	}

	private fun evaluate() {
		logic.openRpm = Escape.openRpm
		logic.closeRpm = minOf(Escape.closeRpm, Escape.openRpm - 100)
		val change = logic.update(Escape.mode.value, Escape.drivingMode.value, Escape.rpm.value, SystemClock.elapsedRealtime())
		if (change != null) {
			Escape.valveOpen.value = change
			board.send(change)
		}
	}

	private fun stopEverything() {
		started = false
		handler.removeCallbacks(tick)
		board.stop()
		obd.stop()
		ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
		stopSelf()
	}

	override fun onDestroy() {
		if (started) {
			started = false
			handler.removeCallbacks(tick)
			board.stop()
			obd.stop()
		}
		super.onDestroy()
	}

	// ---- Notificación ----

	private fun createChannel() {
		val nm = getSystemService(NotificationManager::class.java)
		if (nm.getNotificationChannel(CHANNEL) == null) {
			nm.createNotificationChannel(NotificationChannel(CHANNEL, "Válvula de escape", NotificationManager.IMPORTANCE_LOW).apply {
				setShowBadge(false)
				setSound(null, null)
			})
		}
	}

	private fun modeIntent(mode: Escape.Mode): PendingIntent {
		val i = Intent(this, EscapeService::class.java).setAction(ACTION_MODE).putExtra(EXTRA_MODE, mode.name)
		return PendingIntent.getService(this, mode.ordinal + 1, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
	}

	private fun buildNotification(): Notification {
		val mode = Escape.mode.value
		val title = "Flap · ${Escape.valveText().lowercase().replaceFirstChar { it.uppercase() }}"
		val parts = mutableListOf("Modo ${mode.label}")
		Escape.drivingMode.value?.let { parts += it }
		if (!Escape.boardConnected.value) parts += "placa: ${Escape.boardStatus.value.lowercase()}"
		if (!Escape.obdConnected.value) parts += "vLinker: ${Escape.obdStatus.value.lowercase()}"
		val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
		fun label(m: Escape.Mode) = if (m == mode) "● ${m.label}" else m.label
		return NotificationCompat.Builder(this, CHANNEL)
			.setSmallIcon(R.drawable.ic_notif)
			.setContentTitle(title)
			.setContentText(parts.joinToString(" · "))
			.setContentIntent(open)
			.setOngoing(true)
			.setOnlyAlertOnce(true)
			.setSilent(true)
			.setColor(0xFFE3262F.toInt())
			.setCategory(NotificationCompat.CATEGORY_SERVICE)
			.addAction(0, label(Escape.Mode.OPEN), modeIntent(Escape.Mode.OPEN))
			.addAction(0, label(Escape.Mode.CLOSED), modeIntent(Escape.Mode.CLOSED))
			.addAction(0, label(Escape.Mode.AUTO), modeIntent(Escape.Mode.AUTO))
			.build()
	}

	private fun updateNotification() {
		if (!started) return
		try {
			getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
		} catch (e: SecurityException) {}
	}
}
