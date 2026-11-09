package me.hufman.androidautoidrive.carapp.carinfo.views

import io.bimmergestalt.idriveconnectkit.rhmi.*
import me.hufman.androidautoidrive.AppSettings
import me.hufman.androidautoidrive.carapp.carinfo.CarDetailedInfo
import me.hufman.androidautoidrive.carapp.carinfo.MotorData

class CategoryView(val state: RHMIState, val carInfo: CarDetailedInfo, val appSettings: AppSettings) {
	val listComponent = state.componentsList.filterIsInstance<RHMIComponent.List>().first()

	private val categories
		get() = if (!appSettings[AppSettings.KEYS.SHOW_ADVANCED_SETTINGS].toBoolean()) {
			carInfo.basicCategories
		} else {
			carInfo.advancedCategories
		}

	// Opciones de la válvula de escape, que aparecen en la lista justo después de las pantallas del motor
	private val valveOptions = listOf(
		"Escape: Abrir" to MotorData.ValveMode.OPEN,
		"Escape: Cerrar" to MotorData.ValveMode.CLOSED,
		"Escape: Auto" to MotorData.ValveMode.AUTO,
	)

	/** Las entradas de la lista: pantallas del motor, opciones de válvula y el resto de pantallas */
	private fun entries(): List<Pair<String, MotorData.ValveMode?>> {
		val titles = categories.keys.toList()
		val motorCount = titles.count { it.startsWith(CarDetailedInfo.MOTOR_TITLE) }
		return titles.take(motorCount).map { it to null } +
				valveOptions +
				titles.drop(motorCount).map { it to null }
	}

	fun initWidgets() {
		// destination state is hardcoded, and it's not synced, so we just have to react
		listComponent.getAction()?.asRAAction()?.rhmiActionCallback = RHMIActionListCallback { index ->
			val list = entries()
			val (title, valveMode) = list[Math.floorMod(index, list.size)]
			if (valveMode != null) {
				// elegir una opción de válvula cambia el modo y vuelve a la pantalla principal del motor
				MotorData.valveMode.value = valveMode
				carInfo.category.tryEmit(CarDetailedInfo.MOTOR_TITLE)
			} else {
				carInfo.category.tryEmit(title)
			}
		}

		state.focusCallback = FocusCallback { visible ->
			if (visible) {
				listComponent.getModel()?.value = RHMIModel.RaListModel.RHMIListAdapter(1, entries().map { it.first })
			}
		}
	}
}
