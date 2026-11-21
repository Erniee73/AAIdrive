package es.erniee.b48escape.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import es.erniee.b48escape.Escape
import es.erniee.b48escape.EscapeService

/** La app "Escape" que sale en la pantalla del iDrive a través de Android Auto */
class EscapeCarAppService : CarAppService() {
	override fun createHostValidator(): HostValidator {
		return if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
			HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
		} else {
			HostValidator.Builder(applicationContext)
				.addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
				.build()
		}
	}

	override fun onCreateSession(): Session = object : Session() {
		init {
			lifecycle.addObserver(object : DefaultLifecycleObserver {
				override fun onCreate(owner: LifecycleOwner) {
					Escape.init(carContext)
					Escape.carConnected.value = true
					EscapeService.start(carContext)
				}
				override fun onDestroy(owner: LifecycleOwner) {
					Escape.carConnected.value = false
				}
			})
		}

		override fun onCreateScreen(intent: Intent): Screen = EscapeScreen(carContext)
	}
}
