package me.hufman.androidautoidrive.carapp.carinfo

/**
 * Decide si la válvula de escape debe estar abierta.
 *
 * Auto:
 *  - Sport, Sport+ y Race: abierta siempre.
 *  - Comfort, Comfort+, EcoPro y EcoPro+: abre por encima de OPEN_RPM y
 *    cierra por debajo de CLOSE_RPM, con un mínimo de MIN_CHANGE_MS entre cambios
 *    para que no esté abriendo y cerrando sin parar.
 * Abrir / Cerrar a mano: se obedece al momento.
 */
class ValveLogic(
	val openRpm: Int = 2500,
	val closeRpm: Int = 2200,
	val minChangeMs: Long = 3000,
) {
	companion object {
		val SPORT_MODES = setOf("Sport", "Sport+", "Race")
	}

	/** Lo último que se decidió, null si aún no se ha decidido nada */
	var currentOpen: Boolean? = null
		private set
	private var lastChangeAt = 0L

	/**
	 * Calcula el estado que toca ahora.
	 * Devuelve el nuevo estado si hay que cambiar, o null si se queda como está.
	 */
	fun update(mode: MotorData.ValveMode, drivingMode: String?, rpm: Int?, now: Long): Boolean? {
		val wanted: Boolean = when (mode) {
			MotorData.ValveMode.OPEN -> true
			MotorData.ValveMode.CLOSED -> false
			MotorData.ValveMode.AUTO -> {
				val current = currentOpen ?: false
				when {
					drivingMode in SPORT_MODES -> true
					rpm == null -> current
					current && rpm < closeRpm -> false
					!current && rpm > openRpm -> true
					else -> current
				}
			}
		}
		if (wanted == currentOpen) return null

		// en Auto se respeta un tiempo mínimo entre cambios; a mano, cambio inmediato
		val first = currentOpen == null
		if (mode == MotorData.ValveMode.AUTO && !first && now - lastChangeAt < minChangeMs) return null

		currentOpen = wanted
		lastChangeAt = now
		return wanted
	}

	/** Olvida el estado, para que se vuelva a mandar la orden (p. ej. al reconectar con la placa) */
	fun reset() {
		currentOpen = null
	}
}
