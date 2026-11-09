package me.hufman.androidautoidrive.carapp.carinfo

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Datos del motor que no vienen del coche (CDS) sino de la centralita (DME)
 * a través del adaptador vLinker BM+.
 * Mientras no haya lectura, los valores son null y se muestran como "--".
 */
object MotorData {
	enum class ValveMode(val label: String) {
		AUTO("Auto"),
		OPEN("Abierta"),
		CLOSED("Cerrada"),
	}

	/** Presión de turbo relativa, en bar */
	val boostBar = MutableStateFlow<Double?>(null)

	/** Avance de encendido, en grados */
	val ignitionTiming = MutableStateFlow<Double?>(null)

	/** Corrección de encendido de cada cilindro (1-4), en grados */
	val ignitionCorrections = List(4) { MutableStateFlow<Double?>(null) }

	/** Temperatura de gases de escape (calculada por la DME), en °C */
	val exhaustTemp = MutableStateFlow<Double?>(null)

	/** Lambda real de la sonda antes del catalizador (banda ancha) */
	val lambdaPre = MutableStateFlow<Double?>(null)

	/** Tensión de la sonda después del catalizador/OPF, en voltios */
	val lambdaPostVolts = MutableStateFlow<Double?>(null)

	/** Modo de la válvula de escape pedido por el usuario */
	val valveMode = MutableStateFlow(ValveMode.AUTO)

	/** Si la válvula está abierta según la última orden mandada (null = aún nada) */
	val valveOpen = MutableStateFlow<Boolean?>(null)

	/** Estado de la conexión con la placa de la válvula */
	val valveBoardStatus = MutableStateFlow("Placa: sin conectar")

	/** Estado de la conexión con el adaptador, para mostrarlo en pantalla */
	val adapterStatus = MutableStateFlow("vLinker: sin conectar")
}
