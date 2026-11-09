package me.hufman.androidautoidrive.carapp.b48live

import android.graphics.*
import me.hufman.androidautoidrive.carapp.carinfo.MotorData
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Una foto de todos los valores que se dibujan en pantalla */
data class GaugeValues(
	val water: Double? = null,
	val oil: Double? = null,
	val boost: Double? = null,
	val torque: Double? = null,
	val rpm: Int? = null,
	val gear: String? = null,
	val valve: MotorData.ValveMode = MotorData.ValveMode.AUTO,
	val timing: Double? = null,
	val egt: Double? = null,
	val lambdaPre: Double? = null,
	val lambdaPost: Double? = null,
	val corrections: List<Double?> = listOf(null, null, null, null),
	val adapter: String = "",
)

/** Definición de un reloj: rango, marcas y cómo se escribe su valor */
data class GaugeSpec(
	val label: String,
	val unit: String,
	val min: Double,
	val max: Double,
	val steps: Int,
	val decimals: Int = 0,
	val redFrom: Double? = null,
	val value: (GaugeValues) -> Double?,
)

/**
 * Dibuja las pantallas de relojes de B48 Live.
 * La parte fija (fondo, esferas, marcas, números) se dibuja una sola vez y se guarda;
 * en cada fotograma solo se pintan encima las agujas y los valores.
 */
class GaugeRenderer {
	companion object {
		const val PAGE_COUNT = 2
		private const val START_ANGLE = 135f     // grados, 0 = derecha, sentido horario
		private const val SWEEP_ANGLE = 270f

		val PAGES = listOf(
			listOf(
				GaugeSpec("Agua", "°C", 40.0, 140.0, 5, redFrom = 120.0) { it.water },
				GaugeSpec("Aceite", "°C", 40.0, 160.0, 6, redFrom = 140.0) { it.oil },
				GaugeSpec("Turbo", "bar", -1.0, 2.0, 6, decimals = 1, redFrom = 1.5) { it.boost },
				GaugeSpec("Par", "Nm", 0.0, 500.0, 5) { it.torque },
			),
			listOf(
				GaugeSpec("Avance", "°", -10.0, 50.0, 6, decimals = 1) { it.timing },
				GaugeSpec("Gases esc.", "°C", 200.0, 1000.0, 4, redFrom = 900.0) { it.egt },
				GaugeSpec("Lambda pre-cat", "λ", 0.7, 1.3, 6, decimals = 2) { it.lambdaPre },
				GaugeSpec("Lambda post-cat", "V", 0.0, 1.0, 5, decimals = 2) { it.lambdaPost },
			),
		)
	}

	// colores estilo indicadores deportivos
	private val colorBackgroundTop = Color.rgb(32, 40, 56)
	private val colorBackgroundBottom = Color.rgb(18, 22, 32)
	private val colorBandDark = Color.rgb(70, 10, 16)
	private val colorBandBright = Color.rgb(214, 28, 40)
	private val colorNeedle = Color.rgb(255, 70, 55)
	private val colorText = Color.rgb(236, 240, 246)
	private val colorTextDim = Color.rgb(170, 178, 192)
	private val colorBar = Color.rgb(12, 15, 22)

	private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		textAlign = Paint.Align.CENTER
		typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
	}
	private val boldText = Typeface.create("sans-serif-condensed", Typeface.BOLD)
	private val normalText = Typeface.create("sans-serif-condensed", Typeface.NORMAL)

	// la parte fija ya dibujada, por página y tamaño
	private val staticLayers = HashMap<String, Bitmap>()

	/** Valores que muestran las agujas ahora mismo, para moverlas suavemente hacia el valor real */
	private val shownNeedles = HashMap<String, Double>()

	private class Layout(val width: Int, val height: Int) {
		val barHeight = height * 0.16f
		val gaugeAreaTop = height * 0.04f
		val gaugeAreaBottom = height - barHeight - height * 0.03f
		val columnWidth = width / 4f
		val radius = minOf(columnWidth * 0.40f, (gaugeAreaBottom - gaugeAreaTop) * 0.40f)
		fun centerX(index: Int) = columnWidth * index + columnWidth / 2
		val centerY = gaugeAreaTop + (gaugeAreaBottom - gaugeAreaTop) * 0.47f
	}

	private fun angleFor(spec: GaugeSpec, value: Double): Float {
		val t = ((value - spec.min) / (spec.max - spec.min)).coerceIn(0.0, 1.0)
		return START_ANGLE + SWEEP_ANGLE * t.toFloat()
	}

	private fun format(value: Double?, decimals: Int): String {
		if (value == null) return "--"
		return if (decimals == 0) "%.0f".format(value) else "%.${decimals}f".format(value)
	}

	/** Dibuja una página completa en el bitmap dado */
	fun draw(target: Bitmap, page: Int, values: GaugeValues, fps: Int, frameMs: Long, transport: String): Boolean {
		val canvas = Canvas(target)
		val layout = Layout(target.width, target.height)
		val specs = PAGES[page]

		// 1. la parte fija
		canvas.drawBitmap(staticLayer(page, layout), 0f, 0f, null)

		// 2. agujas y valores
		var stillMoving = false
		specs.forEachIndexed { index, spec ->
			val key = "$page/$index"
			val realValue = spec.value(values)
			val shown = if (realValue == null) null else {
				// la aguja se acerca al valor real poco a poco, para que no vaya a saltos
				val previous = shownNeedles[key] ?: realValue
				val step = (realValue - previous) * 0.5
				val next = if (abs(realValue - previous) < (spec.max - spec.min) * 0.004) realValue else previous + step
				if (next != realValue) stillMoving = true
				shownNeedles[key] = next
				next
			}
			if (realValue == null) shownNeedles.remove(key)
			drawNeedleAndValue(canvas, layout, index, spec, shown, realValue)
		}

		// 3. barra inferior
		drawBottomBar(canvas, layout, page, values)

		// 4. contador de fotogramas (temporal, para medir la fluidez)
		textPaint.typeface = normalText
		textPaint.textSize = layout.height * 0.042f
		textPaint.color = colorTextDim
		textPaint.textAlign = Paint.Align.RIGHT
		canvas.drawText("$transport · $fps fps · ${frameMs} ms", layout.width - layout.width * 0.015f, layout.height * 0.06f, textPaint)
		textPaint.textAlign = Paint.Align.CENTER

		return stillMoving
	}

	private fun staticLayer(page: Int, layout: Layout): Bitmap {
		val key = "$page/${layout.width}x${layout.height}"
		return staticLayers.getOrPut(key) {
			val bitmap = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
			val canvas = Canvas(bitmap)
			// fondo degradado
			paint.shader = LinearGradient(0f, 0f, 0f, layout.height.toFloat(), colorBackgroundTop, colorBackgroundBottom, Shader.TileMode.CLAMP)
			paint.style = Paint.Style.FILL
			canvas.drawRect(0f, 0f, layout.width.toFloat(), layout.height.toFloat(), paint)
			paint.shader = null
			PAGES[page].forEachIndexed { index, spec -> drawDial(canvas, layout, index, spec) }
			bitmap
		}
	}

	private fun drawDial(canvas: Canvas, layout: Layout, index: Int, spec: GaugeSpec) {
		val cx = layout.centerX(index)
		val cy = layout.centerY
		val r = layout.radius
		val band = r * 0.16f
		val bandRect = RectF(cx - r + band / 2, cy - r + band / 2, cx + r - band / 2, cy + r - band / 2)

		// banda roja con degradado, como los indicadores deportivos
		paint.style = Paint.Style.STROKE
		paint.strokeWidth = band
		paint.strokeCap = Paint.Cap.BUTT
		paint.shader = SweepGradient(cx, cy, intArrayOf(colorBandDark, colorBandBright, colorBandDark), floatArrayOf(0f, 0.5f, 1f)).apply {
			val matrix = Matrix(); matrix.setRotate(START_ANGLE - 45f, cx, cy); setLocalMatrix(matrix)
		}
		canvas.drawArc(bandRect, START_ANGLE, SWEEP_ANGLE, false, paint)
		paint.shader = null

		// zona roja: borde exterior más brillante
		if (spec.redFrom != null) {
			val from = angleFor(spec, spec.redFrom)
			paint.color = Color.rgb(255, 60, 60)
			paint.strokeWidth = r * 0.04f
			val outer = RectF(cx - r, cy - r, cx + r, cy + r)
			canvas.drawArc(outer, from, START_ANGLE + SWEEP_ANGLE - from, false, paint)
		}

		// marcas grandes y pequeñas
		paint.color = Color.rgb(255, 225, 225)
		val minorPerMajor = 5
		val totalMinor = spec.steps * minorPerMajor
		for (i in 0..totalMinor) {
			val major = i % minorPerMajor == 0
			val angle = Math.toRadians((START_ANGLE + SWEEP_ANGLE * i / totalMinor).toDouble())
			val outerR = r - band * 0.05f
			val innerR = if (major) r - band * 1.25f else r - band * 0.6f
			paint.strokeWidth = if (major) r * 0.025f else r * 0.012f
			canvas.drawLine(
				cx + (outerR * cos(angle)).toFloat(), cy + (outerR * sin(angle)).toFloat(),
				cx + (innerR * cos(angle)).toFloat(), cy + (innerR * sin(angle)).toFloat(), paint)
		}

		// números de la escala
		paint.style = Paint.Style.FILL
		textPaint.typeface = normalText
		textPaint.textSize = r * 0.15f
		textPaint.color = colorText
		for (i in 0..spec.steps) {
			val value = spec.min + (spec.max - spec.min) * i / spec.steps
			val angle = Math.toRadians((START_ANGLE + SWEEP_ANGLE * i / spec.steps).toDouble())
			val labelR = r - band * 2.2f
			val text = format(value, if (spec.decimals > 0 && (spec.max - spec.min) / spec.steps < 1) 1 else 0)
			canvas.drawText(text, cx + (labelR * cos(angle)).toFloat(), cy + (labelR * sin(angle)).toFloat() + textPaint.textSize * 0.35f, textPaint)
		}

		// nombre y unidad
		textPaint.textSize = r * 0.17f
		textPaint.color = colorTextDim
		canvas.drawText(spec.unit, cx, cy + r * 0.55f, textPaint)
		textPaint.textSize = r * 0.2f
		textPaint.color = colorText
		canvas.drawText(spec.label, cx, cy + r * 0.92f, textPaint)
	}

	private fun drawNeedleAndValue(canvas: Canvas, layout: Layout, index: Int, spec: GaugeSpec, shown: Double?, real: Double?) {
		val cx = layout.centerX(index)
		val cy = layout.centerY
		val r = layout.radius

		if (shown != null) {
			val angle = Math.toRadians(angleFor(spec, shown).toDouble())
			val tipR = r * 0.86f
			val tailR = r * 0.12f
			paint.style = Paint.Style.STROKE
			paint.strokeCap = Paint.Cap.ROUND
			paint.color = colorNeedle
			paint.strokeWidth = r * 0.05f
			canvas.drawLine(
				cx - (tailR * cos(angle)).toFloat(), cy - (tailR * sin(angle)).toFloat(),
				cx + (tipR * cos(angle)).toFloat(), cy + (tipR * sin(angle)).toFloat(), paint)
			paint.style = Paint.Style.FILL
			canvas.drawCircle(cx, cy, r * 0.07f, paint)
		}

		// valor en grande
		textPaint.typeface = boldText
		textPaint.textSize = r * 0.3f
		textPaint.color = if (real == null) colorTextDim else colorText
		canvas.drawText(format(real, spec.decimals), cx, cy + r * 0.38f, textPaint)
	}

	private fun drawBottomBar(canvas: Canvas, layout: Layout, page: Int, values: GaugeValues) {
		val top = layout.height - layout.barHeight
		paint.style = Paint.Style.FILL
		paint.color = colorBar
		canvas.drawRect(0f, top, layout.width.toFloat(), layout.height.toFloat(), paint)
		paint.color = Color.rgb(60, 66, 80)
		canvas.drawRect(0f, top, layout.width.toFloat(), top + 2f, paint)

		val baseline = top + layout.barHeight * 0.62f
		val size = layout.barHeight * 0.36f
		val margin = layout.width * 0.025f

		fun text(value: String, x: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT): Float {
			textPaint.typeface = if (bold) boldText else normalText
			textPaint.textSize = size
			textPaint.color = color
			textPaint.textAlign = align
			canvas.drawText(value, x, baseline, textPaint)
			return textPaint.measureText(value)
		}

		if (page == 0) {
			var x = margin
			x += text("RPM ", x, colorTextDim)
			x += text(values.rpm?.toString() ?: "--", x, colorText, bold = true)
			x += text("    Marcha ", x, colorTextDim)
			text(values.gear ?: "--", x, colorText, bold = true)

			val valveColor = when (values.valve) {
				MotorData.ValveMode.OPEN -> colorNeedle
				MotorData.ValveMode.CLOSED -> colorText
				MotorData.ValveMode.AUTO -> colorText
			}
			var vx = layout.width * 0.46f
			vx += text("Escape ", vx, colorTextDim)
			text(values.valve.label.uppercase(), vx, valveColor, bold = true)

			text("Avanzado  ›", layout.width - margin, colorTextDim, align = Paint.Align.RIGHT)
		} else {
			var x = margin
			values.corrections.forEachIndexed { i, value ->
				x += text("Cil ${i + 1} ", x, colorTextDim)
				x += text(format(value, 1) + "°", x, colorText, bold = true)
				x += text("    ", x, colorTextDim)
			}
			text("‹  Principal", layout.width - margin, colorTextDim, align = Paint.Align.RIGHT)
		}

		// estado del vLinker, pequeño encima de la barra
		textPaint.typeface = normalText
		textPaint.textSize = layout.height * 0.035f
		textPaint.color = colorTextDim
		textPaint.textAlign = Paint.Align.LEFT
		canvas.drawText(values.adapter, margin, top - layout.height * 0.015f, textPaint)
		textPaint.textAlign = Paint.Align.CENTER
	}
}
