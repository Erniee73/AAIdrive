package es.erniee.b48escape

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Pantalla del móvil: estado, botones y ajustes del modo Auto */
class MainActivity : AppCompatActivity() {
	companion object {
		const val BG = 0xFF0B0B0D.toInt()
		const val CARD = 0xFF16161A.toInt()
		const val BORDER = 0xFF26262C.toInt()
		const val BTN = 0xFF202026.toInt()
		const val RED = 0xFFE3262F.toInt()
		const val GREEN = 0xFF2ECC71.toInt()
		const val GREY = 0xFF8A8A93.toInt()
	}

	private val dp get() = resources.displayMetrics.density
	private fun px(v: Int) = (v * dp).toInt()

	private lateinit var dotObd: View
	private lateinit var dotBoard: View
	private lateinit var dotCar: View
	private lateinit var txtObd: TextView
	private lateinit var txtBoard: TextView
	private lateinit var txtRpm: TextView
	private lateinit var txtMode: TextView
	private lateinit var txtValve: TextView
	private val modeButtons = mutableMapOf<Escape.Mode, TextView>()
	private lateinit var rowOpen: TextView
	private lateinit var rowClose: TextView
	private lateinit var txtObdDevice: TextView

	private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
		if (hasBtPermission()) EscapeService.start(this)
		refreshObdDevice()
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		Escape.init(this)
		window.statusBarColor = BG
		window.navigationBarColor = BG
		setContentView(buildUi())

		lifecycleScope.launch {
			repeatOnLifecycle(Lifecycle.State.STARTED) {
				launch { combine(Escape.obdConnected, Escape.obdStatus) { c, s -> c to s }.collect { (c, s) -> setDot(dotObd, c); txtObd.text = s } }
				launch { combine(Escape.boardConnected, Escape.boardStatus) { c, s -> c to s }.collect { (c, s) -> setDot(dotBoard, c); txtBoard.text = s } }
				launch { Escape.carConnected.collect { setDot(dotCar, it) } }
				launch { Escape.rpm.collect { txtRpm.text = Escape.rpmText() } }
				launch { Escape.drivingMode.collect { txtMode.text = it ?: "--" } }
				launch { Escape.valveOpen.collect {
					txtValve.text = Escape.valveText()
					txtValve.setTextColor(if (it == true) RED else Color.WHITE)
				} }
				launch { Escape.mode.collect { m -> modeButtons.forEach { (k, v) -> styleButton(v, k == m) } } }
			}
		}
		requestOrStart()
	}

	override fun onResume() {
		super.onResume()
		refreshRpmRows()
		refreshObdDevice()
	}

	private fun hasBtPermission() = Build.VERSION.SDK_INT < 31 ||
		ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

	private fun requestOrStart() {
		val needed = mutableListOf<String>()
		if (!hasBtPermission()) needed += Manifest.permission.BLUETOOTH_CONNECT
		if (Build.VERSION.SDK_INT >= 33 &&
			ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
			needed += Manifest.permission.POST_NOTIFICATIONS
		}
		if (needed.isEmpty()) EscapeService.start(this) else askPermissions.launch(needed.toTypedArray())
	}

	// ---- Construcción de la pantalla ----

	private fun buildUi(): View {
		val root = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(px(18), px(18), px(18), px(24))
		}

		root.addView(TextView(this).apply {
			text = android.text.SpannableString("B48 Escape").also {
				it.setSpan(android.text.style.ForegroundColorSpan(RED), 4, 10, 0)
			}
			textSize = 24f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
		})
		root.addView(TextView(this).apply {
			text = "Válvula de escape · BMW 430i F32"; textSize = 13f; setTextColor(GREY)
		})

		// Conexión
		val conn = card(root, "Conexión")
		val connRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
		fun connCell(name: String): Triple<View, TextView, LinearLayout> {
			val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
			val dot = View(this)
			cell.addView(dot, LinearLayout.LayoutParams(px(12), px(12)).apply { bottomMargin = px(6) })
			cell.addView(TextView(this).apply { text = name; textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER })
			val sub = TextView(this).apply { textSize = 11f; setTextColor(GREY); gravity = Gravity.CENTER }
			cell.addView(sub)
			connRow.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
			return Triple(dot, sub, cell)
		}
		connCell("vLinker").let { dotObd = it.first; txtObd = it.second }
		connCell("Placa").let { dotBoard = it.first; txtBoard = it.second }
		connCell("Android Auto").let { dotCar = it.first; it.second.text = "pantalla Escape" }
		conn.addView(connRow)

		// En vivo
		val live = card(root, "En vivo")
		val liveRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
		fun liveCell(sub: String): TextView {
			val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
			val big = TextView(this).apply { textSize = 21f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; text = "--" }
			cell.addView(big)
			cell.addView(TextView(this).apply { text = sub; textSize = 12f; setTextColor(GREY) })
			liveRow.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
			return big
		}
		txtRpm = liveCell("rpm")
		txtMode = liveCell("modo")
		txtValve = liveCell("válvula")
		live.addView(liveRow)

		val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
		val subs = mapOf(Escape.Mode.OPEN to "siempre", Escape.Mode.CLOSED to "siempre", Escape.Mode.AUTO to "por rpm")
		Escape.Mode.values().forEachIndexed { i, m ->
			val b = TextView(this).apply {
				text = "${m.label}\n${subs[m]}"; gravity = Gravity.CENTER; textSize = 15f
				setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
				setOnClickListener { Escape.setMode(this@MainActivity, m) }
			}
			modeButtons[m] = b
			btnRow.addView(b, LinearLayout.LayoutParams(0, px(62), 1f).apply { if (i > 0) leftMargin = px(8) })
		}
		live.addView(btnRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12) })

		// Modo Auto
		val auto = card(root, "Modo Auto")
		auto.addView(infoRow("Eco Pro y Comfort", "por rpm"))
		rowOpen = infoRow("Abre por encima de", "").also { r ->
			r.setOnClickListener { editRpm(true) }
		}.valueView
		auto.addView(rowOpen.parent as View)
		rowClose = infoRow("Cierra por debajo de", "").also { r ->
			r.setOnClickListener { editRpm(false) }
		}.valueView
		auto.addView(rowClose.parent as View)
		auto.addView(infoRow("Sport / Sport+", "abierta (próximamente)"))

		// vLinker
		val obdCard = card(root, "vLinker")
		val obdRow = infoRow("Dispositivo", "")
		txtObdDevice = obdRow.valueView
		obdRow.setOnClickListener { chooseObd() }
		obdCard.addView(obdRow)

		root.addView(TextView(this).apply {
			text = "La placa y el vLinker tienen que estar emparejados en Ajustes > Bluetooth. " +
				"Si apagas el coche o se pierde la conexión, la placa cierra la válvula a los 5 segundos."
			textSize = 12f; setTextColor(GREY)
		}, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14) })

		root.addView(TextView(this).apply {
			text = "Apagar B48 Escape"; textSize = 14f; setTextColor(GREY); gravity = Gravity.CENTER
			setPadding(0, px(14), 0, px(14))
			setOnClickListener { EscapeService.stop(this@MainActivity) }
		})

		return ScrollView(this).apply {
			setBackgroundColor(BG)
			isFillViewport = true
			addView(root)
		}
	}

	private fun card(parent: LinearLayout, label: String): LinearLayout {
		val c = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(px(14), px(14), px(14), px(14))
			background = GradientDrawable().apply {
				cornerRadius = px(16).toFloat(); setColor(CARD); setStroke(px(1), BORDER)
			}
		}
		c.addView(TextView(this).apply {
			text = label.uppercase(); textSize = 11f; setTextColor(GREY); letterSpacing = 0.1f
			setPadding(0, 0, 0, px(10))
		})
		parent.addView(c, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12) })
		return c
	}

	private class InfoRow(ctx: android.content.Context) : LinearLayout(ctx) {
		lateinit var valueView: TextView
	}

	private fun infoRow(label: String, value: String): InfoRow {
		val r = InfoRow(this).apply {
			orientation = LinearLayout.HORIZONTAL
			setPadding(0, px(9), 0, px(9))
		}
		r.addView(TextView(this).apply { text = label; textSize = 14f; setTextColor(Color.WHITE) },
			LinearLayout.LayoutParams(0, -2, 1f))
		r.valueView = TextView(this).apply { text = value; textSize = 14f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD }
		r.addView(r.valueView)
		return r
	}

	private fun setDot(v: View, on: Boolean) {
		v.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (on) GREEN else 0xFF55555E.toInt()) }
	}

	private fun styleButton(b: TextView, selected: Boolean) {
		b.background = GradientDrawable().apply {
			cornerRadius = px(12).toFloat()
			setColor(if (selected) RED else BTN)
			setStroke(px(1), if (selected) RED else 0xFF2E2E35.toInt())
		}
	}

	private fun fmt(v: Int) = "%,d rpm".format(v).replace(',', '.')

	private fun refreshRpmRows() {
		rowOpen.text = fmt(Escape.openRpm); rowOpen.setTextColor(RED)
		rowClose.text = fmt(Escape.closeRpm)
	}

	private fun editRpm(open: Boolean) {
		val input = EditText(this).apply {
			inputType = InputType.TYPE_CLASS_NUMBER
			setText((if (open) Escape.openRpm else Escape.closeRpm).toString())
		}
		AlertDialog.Builder(this)
			.setTitle(if (open) "Abrir por encima de (rpm)" else "Cerrar por debajo de (rpm)")
			.setView(input)
			.setPositiveButton("Guardar") { _, _ ->
				val v = input.text.toString().toIntOrNull()?.coerceIn(1000, 6500) ?: return@setPositiveButton
				if (open) {
					Escape.openRpm = v
					if (Escape.closeRpm > v - 300) Escape.closeRpm = v - 300
				} else {
					Escape.closeRpm = minOf(v, Escape.openRpm - 300)
				}
				refreshRpmRows()
			}
			.setNegativeButton("Cancelar", null)
			.show()
	}

	@SuppressLint("MissingPermission")
	private fun refreshObdDevice() {
		if (!::txtObdDevice.isInitialized) return
		val d = if (hasBtPermission()) ObdLink.findDevice(this) else null
		txtObdDevice.text = d?.let { try { it.name } catch (e: SecurityException) { null } } ?: "Elegir…"
	}

	@SuppressLint("MissingPermission")
	private fun chooseObd() {
		if (!hasBtPermission()) { requestOrStart(); return }
		val bonded = try {
			getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices?.toList() ?: emptyList()
		} catch (e: SecurityException) { emptyList() }
		if (bonded.isEmpty()) {
			Toast.makeText(this, "Empareja antes el vLinker en Ajustes > Bluetooth", Toast.LENGTH_LONG).show()
			return
		}
		val names = bonded.map { (try { it.name } catch (e: SecurityException) { null }) ?: it.address }
		AlertDialog.Builder(this)
			.setTitle("¿Cuál es el vLinker?")
			.setItems(names.toTypedArray()) { _, i ->
				Escape.obdAddress = bonded[i].address
				refreshObdDevice()
				// reiniciar la conexión con el nuevo
				EscapeService.stop(this)
				window.decorView.postDelayed({ EscapeService.start(this) }, 800)
			}
			.show()
	}
}
