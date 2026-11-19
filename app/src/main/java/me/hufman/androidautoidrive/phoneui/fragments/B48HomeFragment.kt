package me.hufman.androidautoidrive.phoneui.fragments

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.asLiveData
import androidx.navigation.fragment.findNavController
import me.hufman.androidautoidrive.AppSettings
import me.hufman.androidautoidrive.R
import me.hufman.androidautoidrive.carapp.carinfo.MotorData
import me.hufman.androidautoidrive.phoneui.viewmodels.ConnectionStatusModel
import me.hufman.androidautoidrive.phoneui.viewmodels.activityViewModels

/**
 * Pantalla de inicio de B48 Live: estado de la conexión, válvula de escape y ajustes del modo Auto.
 */
class B48HomeFragment: Fragment() {
	companion object {
		const val BG = 0xFF0B0B0D.toInt()
		const val CARD = 0xFF16161A.toInt()
		const val CARD_BORDER = 0xFF26262C.toInt()
		const val BUTTON = 0xFF202026.toInt()
		const val BUTTON_BORDER = 0xFF2E2E35.toInt()
		const val TEXT = 0xFFEEEEEE.toInt()
		const val MUTED = 0xFF8A8A93.toInt()
		const val RED = 0xFFE3262F.toInt()
		const val GREEN = 0xFF2ECC71.toInt()
		val ALL_SPORT_MODES = listOf("Sport", "Sport+", "Race")
	}

	private val connection by activityViewModels<ConnectionStatusModel> { ConnectionStatusModel.Factory(requireContext().applicationContext) }

	private lateinit var dotCar: View
	private lateinit var dotBmw: View
	private lateinit var dotBoard: View
	private lateinit var connHint: TextView
	private lateinit var valveState: TextView
	private lateinit var valvePill: TextView
	private val modeButtons = HashMap<MotorData.ValveMode, LinearLayout>()
	private lateinit var openRpmText: TextView
	private lateinit var closeRpmText: TextView
	private lateinit var chipsRow: LinearLayout

	private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
	private fun rounded(color: Int, radius: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
		setColor(color); cornerRadius = dp(radius).toFloat()
		if (stroke != null) setStroke(dp(1), stroke)
	}
	private fun text(ctx: Context, s: String, size: Float, color: Int = TEXT, bold: Boolean = false) = TextView(ctx).apply {
		text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color)
		if (bold) typeface = Typeface.DEFAULT_BOLD
	}
	private fun label(ctx: Context, s: String) = text(ctx, s.uppercase(), 11f, MUTED).apply {
		letterSpacing = 0.12f; setPadding(0, 0, 0, dp(10))
	}
	private fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
		orientation = LinearLayout.VERTICAL
		background = rounded(CARD, 16, CARD_BORDER)
		setPadding(dp(14), dp(14), dp(14), dp(14))
		layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
			setMargins(dp(16), dp(6), dp(16), dp(6))
		}
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
		val ctx = requireContext()
		val column = LinearLayout(ctx).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(0, dp(8), 0, dp(24))
		}

		// cabecera
		val header = LinearLayout(ctx).apply {
			orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
			setPadding(dp(18), dp(10), dp(18), dp(8))
		}
		val logo = FrameLayout(ctx).apply {
			background = rounded(CARD, 10, CARD_BORDER)
			layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) }
			addView(android.widget.ImageView(ctx).apply {
				setImageResource(R.drawable.ic_b48_foreground)
				scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
			}, FrameLayout.LayoutParams(dp(44), dp(44)))
		}
		header.addView(logo)
		header.addView(LinearLayout(ctx).apply {
			orientation = LinearLayout.VERTICAL
			addView(TextView(ctx).apply {
				val t = android.text.SpannableString("B48 Live")
				t.setSpan(android.text.style.ForegroundColorSpan(RED), 4, 8, 0)
				text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); setTextColor(TEXT); typeface = Typeface.DEFAULT_BOLD
			})
			addView(text(ctx, "BMW 430i · F32", 12f, MUTED))
		})
		column.addView(header)

		// conexión
		val connCard = card(ctx)
		connCard.addView(label(ctx, "Conexión"))
		val connRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
		fun connItem(name: String): View {
			val item = LinearLayout(ctx).apply {
				orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
				layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
			}
			val dot = View(ctx).apply {
				background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(RED) }
				layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply { bottomMargin = dp(6) }
			}
			item.addView(dot)
			item.addView(text(ctx, name, 12f))
			connRow.addView(item)
			return dot
		}
		dotCar = connItem("Coche")
		dotBmw = connItem("MyBMW")
		dotBoard = connItem("Placa válvula")
		connCard.addView(connRow)
		connHint = text(ctx, "", 12f, MUTED).apply { setPadding(0, dp(10), 0, 0); visibility = View.GONE }
		connCard.addView(connHint)
		connCard.setOnClickListener { findNavController().navigate(R.id.nav_overview) }
		column.addView(connCard)

		// válvula
		val valveCard = card(ctx)
		valveCard.addView(label(ctx, "Válvula de escape"))
		val stateRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
		valveState = text(ctx, "--", 30f, TEXT, true).apply {
			layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
		}
		valvePill = text(ctx, "", 12f, 0xFFCCCCCC.toInt()).apply {
			background = rounded(CARD_BORDER, 20); setPadding(dp(10), dp(5), dp(10), dp(5))
		}
		stateRow.addView(valveState); stateRow.addView(valvePill)
		valveCard.addView(stateRow)
		val buttons = LinearLayout(ctx).apply {
			orientation = LinearLayout.HORIZONTAL
			setPadding(0, dp(14), 0, 0)
		}
		fun modeButton(mode: MotorData.ValveMode, title: String, sub: String, last: Boolean) {
			val b = LinearLayout(ctx).apply {
				orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
				layoutParams = LinearLayout.LayoutParams(0, dp(74), 1f).apply { if (!last) marginEnd = dp(10) }
				addView(text(ctx, title, 15f, TEXT, true))
				addView(text(ctx, sub, 10f, MUTED))
				isClickable = true
				setOnClickListener { MotorData.valveMode.value = mode }
			}
			modeButtons[mode] = b
			buttons.addView(b)
		}
		modeButton(MotorData.ValveMode.OPEN, "Abrir", "siempre", false)
		modeButton(MotorData.ValveMode.CLOSED, "Cerrar", "siempre", false)
		modeButton(MotorData.ValveMode.AUTO, "Auto", "por modo y rpm", true)
		valveCard.addView(buttons)
		column.addView(valveCard)

		// modo auto
		val autoCard = card(ctx)
		autoCard.addView(label(ctx, "Modo Auto"))
		fun row(name: String, value: View, onClick: (() -> Unit)?, divider: Boolean = true): LinearLayout {
			val r = LinearLayout(ctx).apply {
				orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
				setPadding(0, dp(10), 0, dp(10))
				addView(text(ctx, name, 14f).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
				addView(value)
				if (onClick != null) setOnClickListener { onClick() }
			}
			autoCard.addView(r)
			if (divider) autoCard.addView(View(ctx).apply {
				setBackgroundColor(0xFF222228.toInt())
				layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
			})
			return r
		}
		openRpmText = text(ctx, "", 14f, RED, true)
		closeRpmText = text(ctx, "", 14f, TEXT, true)
		row("Abre por encima de", openRpmText, { askRpm(AppSettings.KEYS.VALVE_OPEN_RPM, "Abre por encima de") })
		row("Cierra por debajo de", closeRpmText, { askRpm(AppSettings.KEYS.VALVE_CLOSE_RPM, "Cierra por debajo de") })
		chipsRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
		row("Siempre abierta en", chipsRow, null)
		row("Al desconectar", text(ctx, "Cerrar a los 5 s", 14f, TEXT, true), null, false)
		column.addView(autoCard)
		column.addView(text(ctx, "Toca las rpm o los modos para cambiarlos.", 11f, MUTED).apply {
			setPadding(dp(20), dp(4), dp(20), 0)
		})

		return ScrollView(ctx).apply {
			setBackgroundColor(BG)
			isFillViewport = true
			addView(column)
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		val owner = viewLifecycleOwner
		connection.isBtConnected.observe(owner) { updateConnection() }
		connection.isBclConnected.observe(owner) { updateConnection() }
		MotorData.valveBoardStatus.asLiveData().observe(owner) { updateConnection() }
		MotorData.valveMode.asLiveData().observe(owner) { updateValve() }
		MotorData.valveOpen.asLiveData().observe(owner) { updateValve() }
		updateSettings()
	}

	override fun onResume() {
		super.onResume()
		connection.update()
		updateSettings()
	}

	private fun setDot(dot: View, ok: Boolean) {
		(dot.background as? GradientDrawable)?.setColor(if (ok) GREEN else RED)
	}

	private fun updateConnection() {
		val car = connection.isBtConnected.value == true
		val bmw = connection.isBclConnected.value == true
		val boardStatus = MotorData.valveBoardStatus.value
		val board = boardStatus == "Placa: conectada"
		setDot(dotCar, car); setDot(dotBmw, bmw); setDot(dotBoard, board)
		val hint = when {
			!car -> "El móvil no está conectado al coche por Bluetooth."
			!bmw -> "MyBMW aún no ha abierto la conexión de apps. Toca aquí para ver qué falla."
			!board -> boardStatus
			else -> ""
		}
		connHint.text = hint
		connHint.visibility = if (hint.isEmpty()) View.GONE else View.VISIBLE
	}

	private fun updateValve() {
		val mode = MotorData.valveMode.value
		when (MotorData.valveOpen.value) {
			true -> { valveState.text = "ABIERTA"; valveState.setTextColor(RED) }
			false -> { valveState.text = "CERRADA"; valveState.setTextColor(TEXT) }
			null -> { valveState.text = "--"; valveState.setTextColor(MUTED) }
		}
		valvePill.text = "Modo: ${mode.label}"
		modeButtons.forEach { (m, b) ->
			val selected = m == mode
			b.background = if (selected) rounded(RED, 14) else rounded(BUTTON, 14, BUTTON_BORDER)
			(b.getChildAt(1) as? TextView)?.setTextColor(if (selected) 0xFFFFD7D9.toInt() else MUTED)
		}
	}

	private fun updateSettings() {
		openRpmText.text = "${AppSettings[AppSettings.KEYS.VALVE_OPEN_RPM]} rpm"
		closeRpmText.text = "${AppSettings[AppSettings.KEYS.VALVE_CLOSE_RPM]} rpm"
		val selected = AppSettings[AppSettings.KEYS.VALVE_SPORT_MODES].split(',').map { it.trim() }.toSet()
		chipsRow.removeAllViews()
		ALL_SPORT_MODES.forEach { m ->
			val on = m in selected
			chipsRow.addView(text(requireContext(), m, 11f, if (on) 0xFFFF6B72.toInt() else MUTED).apply {
				background = rounded(if (on) 0x33E3262F else 0x00000000, 8, if (on) 0x88E3262F.toInt() else CARD_BORDER)
				setPadding(dp(8), dp(4), dp(8), dp(4))
				layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) }
				setOnClickListener {
					val now = if (on) selected - m else selected + m
					val ordered = ALL_SPORT_MODES.filter { it in now }
					AppSettings.saveSetting(requireContext(), AppSettings.KEYS.VALVE_SPORT_MODES, ordered.joinToString(","))
					updateSettings()
				}
			})
		}
	}

	private fun askRpm(key: AppSettings.KEYS, title: String) {
		val ctx = requireContext()
		val input = EditText(ctx).apply {
			inputType = InputType.TYPE_CLASS_NUMBER
			setText(AppSettings[key])
			setSelection(text.length)
		}
		val box = FrameLayout(ctx).apply {
			setPadding(dp(20), dp(8), dp(20), 0)
			addView(input)
		}
		AlertDialog.Builder(ctx)
			.setTitle("$title (rpm)")
			.setView(box)
			.setPositiveButton("Guardar") { _, _ ->
				val v = input.text.toString().toIntOrNull()
				if (v != null && v in 1000..6500) {
					AppSettings.saveSetting(ctx, key, v.toString())
					// la de cierre siempre por debajo de la de apertura
					val open = AppSettings[AppSettings.KEYS.VALVE_OPEN_RPM].toInt()
					val close = AppSettings[AppSettings.KEYS.VALVE_CLOSE_RPM].toInt()
					if (close >= open) {
						if (key == AppSettings.KEYS.VALVE_OPEN_RPM) AppSettings.saveSetting(ctx, AppSettings.KEYS.VALVE_CLOSE_RPM, (open - 300).toString())
						else AppSettings.saveSetting(ctx, AppSettings.KEYS.VALVE_OPEN_RPM, (close + 300).toString())
					}
					updateSettings()
				}
			}
			.setNegativeButton("Cancelar", null)
			.show()
	}
}
