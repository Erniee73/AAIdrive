package me.hufman.androidautoidrive.carapp.b48live

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import de.bmw.idrive.BMWRemoting
import de.bmw.idrive.BMWRemotingServer
import de.bmw.idrive.BaseBMWRemotingClient
import io.bimmergestalt.idriveconnectkit.IDriveConnection
import io.bimmergestalt.idriveconnectkit.RHMIDimensions
import io.bimmergestalt.idriveconnectkit.RHMIUtils.rhmi_setResourceCached
import io.bimmergestalt.idriveconnectkit.android.CarAppResources
import io.bimmergestalt.idriveconnectkit.android.IDriveConnectionStatus
import io.bimmergestalt.idriveconnectkit.android.security.SecurityAccess
import io.bimmergestalt.idriveconnectkit.rhmi.*
import io.bimmergestalt.idriveconnectkit.rhmi.deserialization.loadFromXML
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.hufman.androidautoidrive.CarInformation
import me.hufman.androidautoidrive.CarThreadExceptionHandler
import me.hufman.androidautoidrive.carapp.AMCategory
import me.hufman.androidautoidrive.carapp.FocusTriggerController
import me.hufman.androidautoidrive.carapp.RHMIActionAbort
import me.hufman.androidautoidrive.carapp.carinfo.MotorData
import me.hufman.androidautoidrive.cds.CDSMetrics
import me.hufman.androidautoidrive.utils.GraphicsHelpers
import java.io.ByteArrayOutputStream

/**
 * B48 Live: pantalla de relojes en el iDrive.
 * El móvil dibuja los relojes como imagen y la manda al coche, igual que hace el mapa.
 * Girar el mando cambia de página; pulsarlo abre el menú de la válvula de escape.
 */
class B48LiveApp(iDriveConnectionStatus: IDriveConnectionStatus, securityAccess: SecurityAccess,
                 val carAppAssets: CarAppResources, val handler: Handler,
                 val fullDimensions: RHMIDimensions, val transport: String,
                 val graphicsHelpers: GraphicsHelpers, val menuIcon: Drawable) {
	companion object {
		const val TAG = "B48Live"
		const val APP_IDENT = "es.erniee73.b48live"
		const val AM_APP_ID = "b48live.motor"
		const val APP_NAME = "B48 Live"
		const val JPEG_QUALITY = 75
		const val IDLE_CHECK_MS = 40L
	}

	private val listener = Listener()
	val carConnection: BMWRemotingServer
	val carApp: RHMIApplication
	private val amHandle: Int
	private val focusTriggerController: FocusTriggerController
	private val coroutineScope = CoroutineScope(handler.asCoroutineDispatcher() + SupervisorJob() + CarThreadExceptionHandler)

	// pantallas
	private val gaugeState: RHMIState
	private val valveState: RHMIState
	private val imageComponent: RHMIComponent.Image
	private val imageModel: RHMIModel
	private val knobList: RHMIComponent.List
	private val valveList: RHMIComponent.List
	private val focusEvent: RHMIEvent.FocusEvent

	// medidas de la imagen: a pantalla completa en pantallas anchas
	private val dimensions = io.bimmergestalt.idriveconnectkit.SidebarRHMIDimensions(fullDimensions) { fullDimensions.rhmiWidth >= 1000 }

	// dibujo
	private val renderer = GaugeRenderer()
	private var frameBitmap: Bitmap? = null
	private val values = MutableStateFlow(GaugeValues())
	@Volatile private var visible = false
	private var page = 0
	private var lastSentValues: GaugeValues? = null
	private var lastSentPage = -1
	private var lastFrameTime = 0L
	private var needlesMoving = false
	private val frameTimes = ArrayDeque<Long>()
	private var lastFrameMs = 0L

	init {
		carConnection = IDriveConnection.getEtchConnection(iDriveConnectionStatus.host ?: "127.0.0.1", iDriveConnectionStatus.port ?: 8003, listener)
		val appCert = carAppAssets.getAppCertificate(iDriveConnectionStatus.brand ?: "")?.readBytes() as ByteArray
		val sasChallenge = carConnection.sas_certificate(appCert)
		val sasLogin = securityAccess.signChallenge(challenge = sasChallenge)
		carConnection.sas_login(sasLogin)
		listener.server = carConnection

		synchronized(carConnection) {
			val rhmiHandle = carConnection.rhmi_create(null, BMWRemoting.RHMIMetaData(APP_IDENT, BMWRemoting.VersionInfo(0, 1, 0), APP_IDENT, "erniee73"))
			carConnection.rhmi_setResourceCached(rhmiHandle, BMWRemoting.RHMIResourceType.DESCRIPTION, carAppAssets.getUiDescription())
			carConnection.rhmi_setResourceCached(rhmiHandle, BMWRemoting.RHMIResourceType.IMAGEDB, carAppAssets.getImagesDB("common"))
			carConnection.rhmi_initialize(rhmiHandle)

			carApp = RHMIApplicationSynchronized(RHMIApplicationIdempotent(RHMIApplicationEtch(carConnection, rhmiHandle)), carConnection)
			listener.app = carApp
			carApp.loadFromXML(carAppAssets.getUiDescription()?.readBytes() as ByteArray)

			focusEvent = carApp.events.values.filterIsInstance<RHMIEvent.FocusEvent>().first()
			focusTriggerController = FocusTriggerController(focusEvent) {}

			// la pantalla de relojes: un estado con una imagen y una lista (la misma que usa el mapa)
			gaugeState = carApp.states.values.first { state ->
				state is RHMIState.PlainState &&
					state.componentsList.filterIsInstance<RHMIComponent.Image>().isNotEmpty() &&
					state.componentsList.filterIsInstance<RHMIComponent.List>().isNotEmpty()
			}
			imageComponent = gaugeState.componentsList.filterIsInstance<RHMIComponent.Image>().first()
			imageModel = imageComponent.getModel()!!
			knobList = gaugeState.componentsList.filterIsInstance<RHMIComponent.List>().first()

			// el menú de la válvula: un estado sencillo con una sola lista
			valveState = carApp.states.values.first { state ->
				state != gaugeState && state is RHMIState.PlainState &&
					state.componentsList.size == state.componentsList.filterIsInstance<RHMIComponent.List>().size &&
					state.componentsList.filterIsInstance<RHMIComponent.List>().size == 1
			}
			valveList = valveState.componentsList.filterIsInstance<RHMIComponent.List>().first()

			initGaugeState()
			initValveState()
			initEntryButtons()

			carConnection.rhmi_addActionEventHandler(rhmiHandle, APP_IDENT, -1)
			carConnection.rhmi_addHmiEventHandler(rhmiHandle, APP_IDENT, -1, -1)
		}

		// entrada en el menú del coche, con nombre e icono propios
		amHandle = carConnection.am_create("0", "\u0000\u0000\u0000\u0000\u0000\u0002\u0000\u0000".toByteArray())
		carConnection.am_addAppEventHandler(amHandle, APP_IDENT)
		registerAmApp()
	}

	/** Conecta los datos del coche y de la DME a la foto de valores */
	fun onCreate() {
		val metrics = CDSMetrics(CarInformation())
		collect(metrics.engineTemp) { v -> values.value = values.value.copy(water = v) }
		collect(metrics.oilTemp) { v -> values.value = values.value.copy(oil = v) }
		collect(metrics.torque) { v -> values.value = values.value.copy(torque = v) }
		collect(metrics.engineRpm) { v -> values.value = values.value.copy(rpm = v) }
		collect(metrics.drivingGearName) { v -> values.value = values.value.copy(gear = v) }
		collect(MotorData.boostBar) { v -> values.value = values.value.copy(boost = v) }
		collect(MotorData.ignitionTiming) { v -> values.value = values.value.copy(timing = v) }
		collect(MotorData.exhaustTemp) { v -> values.value = values.value.copy(egt = v) }
		collect(MotorData.lambdaPre) { v -> values.value = values.value.copy(lambdaPre = v) }
		collect(MotorData.lambdaPostVolts) { v -> values.value = values.value.copy(lambdaPost = v) }
		collect(MotorData.valveMode) { v -> values.value = values.value.copy(valve = v) }
		collect(MotorData.adapterStatus) { v -> values.value = values.value.copy(adapter = v) }
		MotorData.ignitionCorrections.forEachIndexed { index, flow ->
			collect(flow) { v ->
				val list = values.value.corrections.toMutableList()
				list[index] = v
				values.value = values.value.copy(corrections = list)
			}
		}
	}

	private fun <T> collect(flow: Flow<T>, onValue: (T) -> Unit) {
		coroutineScope.launch { flow.collectLatest { onValue(it) } }
	}

	private fun registerAmApp() {
		val amInfo = mutableMapOf<Int, Any>(
			0 to 145,   // versión de basecore
			1 to APP_NAME,
			2 to graphicsHelpers.compress(menuIcon, 48, 48),
			3 to AMCategory.VEHICLE_INFORMATION.value,
			4 to true,
			5 to 800,   // orden en la lista
			8 to gaugeState.id
		)
		// el mismo nombre en todos los idiomas
		for (languageCode in 101..123) {
			amInfo[languageCode] = APP_NAME
		}
		synchronized(carConnection) {
			carConnection.am_registerApp(amHandle, AM_APP_ID, amInfo)
		}
	}

	private fun initEntryButtons() {
		carApp.components.values.filterIsInstance<RHMIComponent.EntryButton>().forEach { button ->
			button.getAction()?.asHMIAction()?.getTargetModel()?.asRaIntModel()?.value = gaugeState.id
			try {
				button.getModel()?.asRaDataModel()?.value = APP_NAME
				button.getImageModel()?.asRaImageModel()?.value = graphicsHelpers.compress(menuIcon, 48, 48)
			} catch (e: Exception) {
				Log.w(TAG, "No se pudo poner nombre al botón de entrada", e)
			}
		}
	}

	private fun initGaugeState() {
		gaugeState.getTextModel()?.asRaDataModel()?.value = APP_NAME
		gaugeState.setProperty(RHMIProperty.PropertyId.HMISTATE_TABLETYPE, 3)
		gaugeState.setProperty(RHMIProperty.PropertyId.HMISTATE_TABLELAYOUT, "1,0,7")
		gaugeState.componentsList.forEach { it.setVisible(false) }

		gaugeState.focusCallback = FocusCallback { focused ->
			visible = focused
			if (focused) {
				Log.i(TAG, "Mostrando relojes ${dimensions.visibleWidth}x${dimensions.visibleHeight}")
				imageComponent.setProperty(RHMIProperty.PropertyId.WIDTH.id, dimensions.visibleWidth)
				imageComponent.setProperty(RHMIProperty.PropertyId.HEIGHT.id, dimensions.visibleHeight)
				lastSentValues = null   // forzar un fotograma nuevo
				centerKnob()
				scheduleFrame(0)
			} else {
				handler.removeCallbacks(frameRunnable)
			}
		}

		// lista invisible para leer el giro del mando: arriba = página anterior, abajo = siguiente
		val scrollList = RHMIModel.RaListModel.RHMIListConcrete(3)
		(0..2).forEach { scrollList.addRow(arrayOf("‹", "", "")) }
		scrollList.addRow(arrayOf(APP_NAME, "", ""))
		(4..6).forEach { scrollList.addRow(arrayOf("›", "", "")) }
		knobList.getModel()?.asRaListModel()?.value = scrollList

		knobList.getSelectAction()?.asRAAction()?.rhmiActionCallback = RHMIActionListCallback { index ->
			val direction = when (index) {
				in 0..2 -> -1
				in 4..6 -> 1
				else -> 0
			}
			if (direction != 0) {
				page = Math.floorMod(page + direction, GaugeRenderer.PAGE_COUNT)
				scheduleFrame(0)
				centerKnob()
			}
		}
		// pulsar el mando: abrir el menú de la válvula
		knobList.getAction()?.asRAAction()?.rhmiActionCallback = object : RHMIActionButtonCallback {
			override fun onAction(invokedBy: Int?) {
				val target = if (invokedBy == 2) gaugeState.id else valveState.id   // 2 = favorito
				try {
					focusEvent.triggerEvent(mapOf(0 to target))
				} catch (e: BMWRemoting.IllegalArgumentException) {}
			}
		}
		knobList.setVisible(true)
		knobList.setProperty(RHMIProperty.PropertyId.POSITION_X.id, -50000)   // fuera de la vista, pero recibe el mando
		knobList.setProperty(RHMIProperty.PropertyId.POSITION_Y.id, 0)
		knobList.setProperty(RHMIProperty.PropertyId.BOOKMARKABLE, true)

		imageComponent.setVisible(true)
		imageComponent.setProperty(RHMIProperty.PropertyId.POSITION_X.id, -dimensions.paddingLeft)
		imageComponent.setProperty(RHMIProperty.PropertyId.POSITION_Y.id, -dimensions.paddingTop)
		imageComponent.setProperty(RHMIProperty.PropertyId.WIDTH.id, dimensions.visibleWidth)
		imageComponent.setProperty(RHMIProperty.PropertyId.HEIGHT.id, dimensions.visibleHeight)
	}

	private fun centerKnob() {
		try {
			focusEvent.triggerEvent(mapOf(0 to knobList.id, 41 to 3))
		} catch (e: Exception) {}
	}

	private val valveOptions = listOf(MotorData.ValveMode.AUTO, MotorData.ValveMode.OPEN, MotorData.ValveMode.CLOSED)

	private fun initValveState() {
		valveState.getTextModel()?.asRaDataModel()?.value = "Válvula de escape"
		valveState.componentsList.forEach { it.setVisible(false) }
		valveList.setVisible(true)
		valveList.setEnabled(true)
		valveList.setSelectable(true)

		valveState.focusCallback = FocusCallback { focused ->
			if (focused) redrawValveList()
		}
		valveList.getAction()?.asRAAction()?.rhmiActionCallback = RHMIActionListCallback { index ->
			val mode = valveOptions.getOrNull(index) ?: throw RHMIActionAbort()
			MotorData.valveMode.value = mode
			// volver a los relojes
			valveList.getAction()?.asHMIAction()?.getTargetModel()?.asRaIntModel()?.value = gaugeState.id
			handler.post {
				try { focusTriggerController.focusState(gaugeState, false) } catch (e: Exception) {}
			}
		}
	}

	private fun redrawValveList() {
		val current = MotorData.valveMode.value
		val list = RHMIModel.RaListModel.RHMIListConcrete(1)
		valveOptions.forEach { mode ->
			val mark = if (mode == current) "●  " else "     "
			list.addRow(arrayOf(mark + "Escape: " + mode.label))
		}
		valveList.getModel()?.asRaListModel()?.value = list
	}

	// ---- envío de fotogramas ----

	private val frameRunnable = Runnable { drawFrame() }

	private fun scheduleFrame(delayMs: Long) {
		handler.removeCallbacks(frameRunnable)
		handler.postDelayed(frameRunnable, delayMs)
	}

	private fun drawFrame() {
		if (!visible) return
		val now = SystemClock.elapsedRealtime()
		val current = values.value
		val needed = needlesMoving || current != lastSentValues || page != lastSentPage || now - lastFrameTime > 1000
		if (needed) {
			val width = dimensions.visibleWidth
			val height = dimensions.visibleHeight
			val bitmap = frameBitmap?.takeIf { it.width == width && it.height == height }
				?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { frameBitmap = it }

			// fotogramas por segundo del último segundo
			while (frameTimes.isNotEmpty() && now - frameTimes.first() > 1000) frameTimes.removeFirst()
			needlesMoving = renderer.draw(bitmap, page, current, frameTimes.size, lastFrameMs, transport)

			val output = ByteArrayOutputStream()
			bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
			try {
				val model = imageModel
				if (model is RHMIModel.RaImageModel) {
					model.value = output.toByteArray()
				}
			} catch (e: RuntimeException) {
			} catch (e: org.apache.etch.util.TimeoutException) {
				// el móvil se ha desconectado a mitad de envío
			}
			lastSentValues = current
			lastSentPage = page
			lastFrameTime = now
			frameTimes.addLast(now)
			lastFrameMs = SystemClock.elapsedRealtime() - now
		}
		scheduleFrame(if (needlesMoving) 0 else IDLE_CHECK_MS)
	}

	fun onDestroy() {
		visible = false
		handler.removeCallbacks(frameRunnable)
		coroutineScope.cancel()
	}

	fun disconnect() {
		try {
			IDriveConnection.disconnectEtchConnection(carConnection)
		} catch (e: java.io.IOError) {
		} catch (e: RuntimeException) {}
	}

	inner class Listener : BaseBMWRemotingClient() {
		var server: BMWRemotingServer? = null
		var app: RHMIApplication? = null

		override fun am_onAppEvent(handle: Int?, ident: String?, appId: String?, event: BMWRemoting.AMEvent?) {
			synchronized(server!!) {}
			focusTriggerController.focusState(gaugeState, false)
			registerAmApp()
		}

		override fun rhmi_onActionEvent(handle: Int?, ident: String?, actionId: Int?, args: MutableMap<*, *>?) {
			try {
				app?.actions?.get(actionId)?.asRAAction()?.rhmiActionCallback?.onActionEvent(args)
				synchronized(server!!) { server?.rhmi_ackActionEvent(handle, actionId, 1, true) }
			} catch (e: RHMIActionAbort) {
				synchronized(server!!) { server?.rhmi_ackActionEvent(handle, actionId, 1, false) }
			} catch (e: Exception) {
				Log.e(TAG, "Error en un evento del mando", e)
				synchronized(server!!) { server?.rhmi_ackActionEvent(handle, actionId, 1, true) }
			}
		}

		override fun rhmi_onHmiEvent(handle: Int?, ident: String?, componentId: Int?, eventId: Int?, args: MutableMap<*, *>?) {
			try {
				app?.states?.get(componentId)?.onHmiEvent(eventId, args)
				app?.components?.get(componentId)?.onHmiEvent(eventId, args)
			} catch (e: Exception) {
				Log.e(TAG, "Error en un evento de pantalla", e)
			}
		}
	}
}
