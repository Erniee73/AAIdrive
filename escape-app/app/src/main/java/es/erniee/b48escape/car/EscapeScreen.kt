package es.erniee.b48escape.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import es.erniee.b48escape.Escape
import es.erniee.b48escape.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Pantalla con Abrir / Cerrar / Auto y el estado de la válvula */
class EscapeScreen(carContext: CarContext) : Screen(carContext) {
	private var lastTitle = ""
	private var lastMode: Escape.Mode? = null
	private var watcher: Job? = null

	init {
		lifecycle.addObserver(object : DefaultLifecycleObserver {
			override fun onStart(owner: LifecycleOwner) {
				watcher = lifecycleScope.launch {
					combine(Escape.mode, Escape.valveOpen, Escape.rpm, Escape.drivingMode, Escape.boardConnected) { _, _, _, _, _ -> Unit }
						.collect {
							if (title() != lastTitle || Escape.mode.value != lastMode) {
								invalidate()
								// Android Auto limita los refrescos: como mucho uno por segundo
								delay(1000)
							}
						}
				}
			}
			override fun onStop(owner: LifecycleOwner) {
				watcher?.cancel()
			}
		})
	}

	private fun title(): String {
		val parts = mutableListOf("Escape", Escape.valveText())
		if (!Escape.boardConnected.value) parts[1] = "placa sin conectar"
		Escape.drivingMode.value?.let { parts += it }
		Escape.rpm.value?.let { parts += "${(it / 50) * 50} rpm" }
		return parts.joinToString(" · ")
	}

	private fun item(mode: Escape.Mode, icon: Int, sub: String): GridItem {
		val selected = Escape.mode.value == mode
		return GridItem.Builder()
			.setTitle(if (selected) "✓ ${mode.label}" else mode.label)
			.setText(sub)
			.setImage(CarIcon.Builder(IconCompat.createWithResource(carContext, icon))
				.apply { if (selected) setTint(CarColor.RED) }
				.build(), GridItem.IMAGE_TYPE_LARGE)
			.setOnClickListener { Escape.setMode(carContext, mode) }
			.build()
	}

	override fun onGetTemplate(): Template {
		lastTitle = title()
		lastMode = Escape.mode.value
		val list = ItemList.Builder()
			.addItem(item(Escape.Mode.OPEN, R.drawable.ic_open, "siempre abierta"))
			.addItem(item(Escape.Mode.CLOSED, R.drawable.ic_close, "siempre cerrada"))
			.addItem(item(Escape.Mode.AUTO, R.drawable.ic_auto, "por rpm"))
			.build()
		@Suppress("DEPRECATION")
		return GridTemplate.Builder()
			.setTitle(lastTitle)
			.setHeaderAction(Action.APP_ICON)
			.setSingleList(list)
			.build()
	}
}
