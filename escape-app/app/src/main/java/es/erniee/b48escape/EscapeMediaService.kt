package es.erniee.b48escape

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.content.ContextCompat
import androidx.media.MediaBrowserServiceCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

/**
 * "Escape" en Android Auto. Android Auto solo deja usar apps de fuera de Play Store si son
 * de audio, así que se presenta como una fuente de audio que no suena:
 *  - la lista tiene Abrir / Cerrar / Auto
 *  - la pantalla de "reproduciendo" muestra el estado de la válvula, con los tres botones
 * Nunca pide el audio, así que no corta la música de Spotify.
 */
class EscapeMediaService : MediaBrowserServiceCompat() {
	companion object {
		const val ROOT = "root"
		const val GEARHEAD = "com.google.android.projection.gearhead"
		const val ACTION_PREFIX = "escape."
	}

	private lateinit var session: MediaSessionCompat
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
	private val icons = mutableMapOf<Int, Bitmap>()

	override fun onCreate() {
		super.onCreate()
		Escape.init(this)
		session = MediaSessionCompat(this, "Flap").apply {
			setCallback(callback)
			isActive = true
		}
		sessionToken = session.sessionToken
		EscapeService.start(this)

		scope.launch {
			combine(Escape.mode, Escape.valveOpen, Escape.drivingMode, Escape.boardConnected) { _, _, _, _ -> Unit }
				.collect {
					updateSession()
					notifyChildrenChanged(ROOT)
				}
		}
		scope.launch {
			// las rpm cambian mucho: refrescar la pantalla como mucho una vez por segundo
			while (isActive) {
				updateSession()
				delay(1000)
			}
		}
	}

	override fun onDestroy() {
		scope.cancel()
		Escape.carConnected.value = false
		session.release()
		super.onDestroy()
	}

	override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
		if (clientPackageName == GEARHEAD) Escape.carConnected.value = true
		return BrowserRoot(ROOT, null)
	}

	override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
		if (parentId != ROOT) {
			result.sendResult(mutableListOf())
			return
		}
		val subs = mapOf(
			Escape.Mode.OPEN to "Siempre abierta",
			Escape.Mode.CLOSED to "Siempre cerrada",
			Escape.Mode.AUTO to "Abre y cierra por rpm",
		)
		val items = Escape.Mode.values().map { m ->
			val selected = Escape.mode.value == m
			val desc = MediaDescriptionCompat.Builder()
				.setMediaId(m.name)
				.setTitle(if (selected) "✓ ${m.label}" else m.label)
				.setSubtitle(subs[m])
				.setIconBitmap(icon(iconFor(m), if (selected) 0xFFE3262F.toInt() else Color.WHITE))
				.build()
			MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
		}
		result.sendResult(items.toMutableList())
	}

	private fun iconFor(m: Escape.Mode) = when (m) {
		Escape.Mode.OPEN -> R.drawable.ic_open
		Escape.Mode.CLOSED -> R.drawable.ic_close
		Escape.Mode.AUTO -> R.drawable.ic_auto
	}

	/** Los iconos son vectores: se pasan a imagen para Android Auto */
	private fun icon(res: Int, color: Int): Bitmap {
		val key = res * 31 + color
		return icons.getOrPut(key) {
			val d = ContextCompat.getDrawable(this, res)!!.mutate()
			d.setTint(color)
			val b = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
			d.setBounds(0, 0, 128, 128)
			d.draw(Canvas(b))
			b
		}
	}

	/** Imagen grande de la pantalla de "reproduciendo": el icono de la app */
	private val art: Bitmap by lazy {
		val d = ContextCompat.getDrawable(this, R.drawable.ic_launcher)!!
		val b = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
		d.setBounds(0, 0, 320, 320)
		d.draw(Canvas(b))
		b
	}

	private fun updateSession() {
		val valve = when {
			!Escape.boardConnected.value -> "placa sin conectar"
			else -> Escape.valveText()
		}
		val sub = mutableListOf("Modo ${Escape.mode.value.label}")
		Escape.drivingMode.value?.let { sub += it }
		Escape.rpm.value?.let { sub += "%,d rpm".format((it / 50) * 50).replace(',', '.') }
		session.setMetadata(MediaMetadataCompat.Builder()
			.putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, "estado")
			.putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Flap · $valve")
			.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, sub.joinToString(" · "))
			.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, "Flap · $valve")
			.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, sub.joinToString(" · "))
			.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
			.build())

		val state = PlaybackStateCompat.Builder()
			.setActions(PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID)
			.setState(PlaybackStateCompat.STATE_PAUSED, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 0f)
		for (m in Escape.Mode.values()) {
			val selected = Escape.mode.value == m
			state.addCustomAction(
				PlaybackStateCompat.CustomAction.Builder(ACTION_PREFIX + m.name,
					if (selected) "✓ ${m.label}" else m.label, iconFor(m)).build())
		}
		session.setPlaybackState(state.build())
	}

	private val callback = object : MediaSessionCompat.Callback() {
		override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
			val m = runCatching { Escape.Mode.valueOf(mediaId ?: "") }.getOrNull() ?: return
			Escape.setMode(this@EscapeMediaService, m)
		}

		override fun onCustomAction(action: String?, extras: Bundle?) {
			val name = action?.removePrefix(ACTION_PREFIX) ?: return
			val m = runCatching { Escape.Mode.valueOf(name) }.getOrNull() ?: return
			Escape.setMode(this@EscapeMediaService, m)
		}

		// sin sonido: reproducir / pausar no hacen nada
		override fun onPlay() {}
		override fun onPause() {}
		override fun onStop() {}
	}
}
