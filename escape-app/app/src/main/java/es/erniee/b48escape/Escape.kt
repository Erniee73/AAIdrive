package es.erniee.b48escape

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

/** Estado compartido entre el móvil, la notificación y la pantalla de Android Auto */
object Escape {
	enum class Mode(val label: String) { OPEN("Abrir"), CLOSED("Cerrar"), AUTO("Auto") }

	val mode = MutableStateFlow(Mode.AUTO)
	val valveOpen = MutableStateFlow<Boolean?>(null)
	val rpm = MutableStateFlow<Int?>(null)
	/** Modo de conducción (Eco Pro, Comfort, Sport…). De momento no se lee: null */
	val drivingMode = MutableStateFlow<String?>(null)

	val boardConnected = MutableStateFlow(false)
	val boardStatus = MutableStateFlow("Sin conectar")
	val obdConnected = MutableStateFlow(false)
	val obdStatus = MutableStateFlow("Sin conectar")
	val carConnected = MutableStateFlow(false)

	private var prefs: SharedPreferences? = null

	fun init(context: Context) {
		if (prefs != null) return
		prefs = context.applicationContext.getSharedPreferences("escape", Context.MODE_PRIVATE)
		mode.value = try { Mode.valueOf(prefs!!.getString("mode", "AUTO")!!) } catch (e: Exception) { Mode.AUTO }
	}

	var openRpm: Int
		get() = prefs?.getInt("open_rpm", 2500) ?: 2500
		set(v) { prefs?.edit()?.putInt("open_rpm", v)?.apply() }

	var closeRpm: Int
		get() = prefs?.getInt("close_rpm", 2200) ?: 2200
		set(v) { prefs?.edit()?.putInt("close_rpm", v)?.apply() }

	/** Dirección Bluetooth del vLinker elegido (null = buscar por nombre) */
	var obdAddress: String?
		get() = prefs?.getString("obd_address", null)
		set(v) { prefs?.edit()?.putString("obd_address", v)?.apply() }

	fun setMode(context: Context, m: Mode) {
		init(context)
		mode.value = m
		prefs?.edit()?.putString("mode", m.name)?.apply()
		EscapeService.start(context)
	}

	fun valveText(): String = when (valveOpen.value) {
		true -> "ABIERTA"
		false -> "CERRADA"
		null -> "--"
	}

	fun rpmText(): String = rpm.value?.let { "%,d".format(it).replace(',', '.') } ?: "--"
}
