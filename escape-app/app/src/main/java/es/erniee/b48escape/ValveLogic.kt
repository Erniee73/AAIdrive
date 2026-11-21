package es.erniee.b48escape

/**
 * Decide si la válvula de escape debe estar abierta.
 *
 * Auto:
 *  - Sport, Sport+: abierta siempre (cuando se pueda leer el modo).
 *  - Eco Pro y Comfort (o si no se sabe el modo): abre por encima de openRpm y
 *    cierra por debajo de closeRpm, con un mínimo de minChangeMs entre cambios.
 * Abrir / Cerrar a mano: se obedece al momento.
 */
class ValveLogic(
	var openRpm: Int = 2500,
	var closeRpm: Int = 2200,
	private val minChangeMs: Long = 3000,
) {
	var sportModes: Set<String> = setOf("Sport", "Sport+", "Race")

	var currentOpen: Boolean? = null
		private set
	private var lastChangeAt = 0L

	fun update(mode: Escape.Mode, drivingMode: String?, rpm: Int?, now: Long): Boolean? {
		val wanted: Boolean = when (mode) {
			Escape.Mode.OPEN -> true
			Escape.Mode.CLOSED -> false
			Escape.Mode.AUTO -> {
				val current = currentOpen ?: false
				when {
					drivingMode in sportModes -> true
					rpm == null -> current
					current && rpm < closeRpm -> false
					!current && rpm > openRpm -> true
					else -> current
				}
			}
		}
		if (wanted == currentOpen) return null
		val first = currentOpen == null
		if (mode == Escape.Mode.AUTO && !first && now - lastChangeAt < minChangeMs) return null
		currentOpen = wanted
		lastChangeAt = now
		return wanted
	}
}
