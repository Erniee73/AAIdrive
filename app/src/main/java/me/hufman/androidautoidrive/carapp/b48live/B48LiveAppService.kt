package me.hufman.androidautoidrive.carapp.b48live

import android.util.Log
import androidx.core.content.res.ResourcesCompat
import io.bimmergestalt.idriveconnectkit.RHMIDimensions
import io.bimmergestalt.idriveconnectkit.android.CarAppAssetResources
import me.hufman.androidautoidrive.R
import me.hufman.androidautoidrive.carapp.CarAppService
import me.hufman.androidautoidrive.carapp.music.MusicAppMode
import me.hufman.androidautoidrive.utils.GraphicsHelpersAndroid

/** Arranca B48 Live cuando el móvil se conecta al coche */
class B48LiveAppService : CarAppService() {
	var carApp: B48LiveApp? = null

	override fun shouldStartApp(): Boolean = true

	override fun onCarStart() {
		Log.i(B48LiveApp.TAG, "Arrancando B48 Live")
		val handler = handler!!
		val transport = when (MusicAppMode.TRANSPORT_PORTS.fromPort(iDriveConnectionStatus.port)) {
			MusicAppMode.TRANSPORT_PORTS.USB -> "USB"
			MusicAppMode.TRANSPORT_PORTS.BT -> "BT"
			MusicAppMode.TRANSPORT_PORTS.ETH -> "ETH"
			null -> "?"
		}
		val menuIcon = ResourcesCompat.getDrawable(applicationContext.resources, R.drawable.ic_b48_menu, null)!!
		val carApp = B48LiveApp(iDriveConnectionStatus, securityAccess,
			CarAppAssetResources(applicationContext, "smartthings"),
			handler, RHMIDimensions.create(carInformation.capabilities), transport,
			GraphicsHelpersAndroid(), menuIcon)
		this.carApp = carApp
		carApp.onCreate()
	}

	override fun onCarStop() {
		carApp?.onDestroy()
		carApp?.disconnect()
		carApp = null
	}
}
