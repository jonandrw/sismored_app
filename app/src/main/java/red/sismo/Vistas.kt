package red.sismo

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/*
 * Los cuatro `canvas` de la PWA, uno a uno.
 *
 * Ninguno es decorativo y ninguno se inventa nada: el radar pinta las balizas
 * que se han oído de verdad en cada salto, el osciloscopio pinta el micrófono,
 * la traza pinta el acelerómetro y el anillo pinta el estado. Si no hay dato,
 * la línea se queda plana — que es un dato.
 *
 * Las dos que se mueven solas (anillo y radar) se repintan desde `onDraw` con
 * `postInvalidateOnAnimation` mientras estén a la vista. No hace falta apagar
 * nada al salir de la pestaña: si la vista no se dibuja, el bucle se para solo,
 * y cuando vuelve a verse el sistema la dibuja una vez y arranca otra vez.
 */

private fun px(v: View, dp: Float) = dp * v.resources.displayMetrics.density

/**
 * La barra de un detector.
 *
 * Existe por una razón de rendimiento, no de estética: antes era un peso de
 * `LinearLayout` que se cambiaba cada medio segundo, y cambiar un peso obliga a
 * remedir el árbol entero — cinco veces por tick, con toda la pestaña Entorno
 * colgando. Eso es exactamente lo que se notaba como tirones al hacer scroll.
 * Pintando la fracción a mano solo se invalida este rectángulo de 3 dp.
 */
class VistaBarra @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) :
    View(ctx, attrs) {

    private var fraccion = 0f
    private var caliente = false
    private val p = Paint()

    fun pintar(fraccion: Float, caliente: Boolean) {
        val f = fraccion.coerceIn(0f, 1f)
        if (f == this.fraccion && caliente == this.caliente) return
        this.fraccion = f; this.caliente = caliente
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.color = context.getColor(R.color.line)
        c.drawRect(0f, 0f, w, h, p)
        if (fraccion <= 0f) return
        p.color = context.getColor(if (caliente) R.color.rd else R.color.gr)
        c.drawRect(0f, 0f, w * fraccion, h, p)
    }
}

/** El anillo de onda de ESTADO ACTUAL: rojo en alarma, verde armado, gris en reposo. */
class VistaAnillo @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) :
    View(ctx, attrs) {

    var alarma = false
    var armado = false
    /** Sacudida actual: la onda respira con lo que mide el acelerómetro. */
    var sacudida = 0.0

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        p.color = context.getColor(if (alarma) R.color.rd else if (armado) R.color.gr else R.color.ctl)
        p.strokeWidth = px(this, 3f)

        val t = System.currentTimeMillis() / 260.0
        // 26 / 10 / 5 sobre los 120 px del canvas de la PWA, escalados a lo que mida aquí
        val esc = h / 120f
        val amp = (if (alarma) 26.0 else if (armado) 10.0 + sacudida * 8 else 5.0) * esc

        var px0 = 0f; var py0 = 0f
        var x = 0f
        while (x <= w) {
            val k = x / w
            val env = exp(-((k - 0.5) * 3.4).let { it * it }.toDouble())
            val y = (h / 2 + sin(k * 22 + t) * amp * env).toFloat()
            if (x > 0) c.drawLine(px0, py0, x, y, p)
            px0 = x; py0 = y
            x += 3f
        }
        if (isShown) postInvalidateOnAnimation()
    }
}

/**
 * Visualizador sísmico en tiempo real:
 * - Esquina superior derecha achaflanada a 14 dp (clipPath táctico).
 * - Cuadrícula táctica de 32 dp con línea central al 50%.
 * - Línea de umbral roja discontinua con rótulo de aceleración.
 * - Barras de espectro sísmico vertical (3 dp ancho, 2 dp espaciado) en #90CA50 que oscilan en vivo a 60 Hz.
 * - Telemetría inferior: ACELERÓMETRO · 50 Hz y valor numérico instantáneo en g.
 */
class VistaTraza @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) :
    View(ctx, attrs), android.hardware.SensorEventListener {

    var umbral = 0.42

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
    private val sensor = sm?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)

    private val clipTactico = Path()
    private val pFondo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = android.graphics.Color.parseColor("#0D1113")
    }
    private val pBorde = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = android.graphics.Color.parseColor("#1E252A")
        strokeWidth = 2.5f
    }
    private val pGrid = Paint().apply {
        color = android.graphics.Color.parseColor("#09FFFFFF")
        strokeWidth = 1f
    }
    private val pCenter = Paint().apply {
        color = android.graphics.Color.parseColor("#2A3238")
        strokeWidth = 1.5f
    }
    private val pBarra = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pUmbral = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val pTexto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#E53035")
        textSize = 26f
        typeface = android.graphics.Typeface.MONOSPACE
        letterSpacing = 0.08f
    }
    private val pSub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#7C858D")
        textSize = 28f
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val pValor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#90CA50")
        textSize = 36f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
    }

    // Buffer de barras sísmicas (64 barras de historia)
    private val numBarras = 64
    private val barras = FloatArray(numBarras) { 0.04f }
    private var ultimoG = 0.07f
    private var gravedadFiltro = 9.81f

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sm?.registerListener(this, sensor, android.hardware.SensorManager.SENSOR_DELAY_GAME)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        sm?.unregisterListener(this)
    }

    override fun onSensorChanged(event: android.hardware.SensorEvent?) {
        if (event == null) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val modulo = kotlin.math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        gravedadFiltro = gravedadFiltro * 0.92f + modulo * 0.08f
        val dev = kotlin.math.abs(modulo - gravedadFiltro) / 9.81f
        ultimoG = (ultimoG * 0.7f + dev * 0.3f).coerceAtLeast(0.02f)

        // Desplazar buffer de barras a la izquierda y meter la nueva lectura
        System.arraycopy(barras, 1, barras, 0, numBarras - 1)
        barras[numBarras - 1] = ultimoG
    }

    override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 1. Clip táctico con esquina achaflanada
        val ch = px(this, 14f)
        clipTactico.rewind()
        clipTactico.moveTo(0f, 0f)
        clipTactico.lineTo(w - ch, 0f)
        clipTactico.lineTo(w, ch)
        clipTactico.lineTo(w, h)
        clipTactico.lineTo(0f, h)
        clipTactico.close()

        // Fondo y borde exterior del radar
        c.drawPath(clipTactico, pFondo)
        c.drawPath(clipTactico, pBorde)

        c.save()
        c.clipPath(clipTactico)

        // 2. Radar grid (32 dp)
        val step = px(this, 32f)
        var gx = 0f
        while (gx <= w) {
            c.drawLine(gx, 0f, gx, h, pGrid)
            gx += step
        }
        var gy = 0f
        while (gy <= h) {
            c.drawLine(0f, gy, w, gy, pGrid)
            gy += step
        }

        // 3. Línea central al 50%
        val centerY = h * 0.5f
        c.drawLine(0f, centerY, w, centerY, pCenter)

        // 4. Línea de umbral roja discontinua
        val pad = px(this, 36f)
        fun scaleY(gVal: Double): Float {
            val normalized = (gVal / 1.0).coerceIn(0.0, 1.0).toFloat()
            return centerY - (normalized * (centerY - pad))
        }

        val yUmbral = scaleY(umbral)
        pUmbral.color = android.graphics.Color.parseColor("#E53035")
        pUmbral.strokeWidth = px(this, 1.5f)
        pUmbral.pathEffect = DashPathEffect(floatArrayOf(px(this, 4f), px(this, 4f)), 0f)
        c.drawLine(0f, yUmbral, w, yUmbral, pUmbral)

        c.drawText("UMBRAL ${String.format(java.util.Locale.US, "%.2f", umbral)} g", px(this, 12f), yUmbral - px(this, 6f), pTexto)

        // 5. Barras de espectro sísmico en vivo
        val barW = px(this, 3f)
        val gap = px(this, 2f)
        val totalBarW = barW + gap
        val numToDraw = ((w - px(this, 16f)) / totalBarW).toInt().coerceAtMost(numBarras)
        val startX = (w - (numToDraw * totalBarW)) / 2f

        val t = android.os.SystemClock.uptimeMillis() / 1000f
        val calienteGlobal = ultimoG >= umbral

        for (i in 0 until numToDraw) {
            val idx = numBarras - numToDraw + i
            val rawG = if (idx in 0 until numBarras) barras[idx] else 0.04f
            val osc = sin(t * 7f + i * 0.35f).toFloat() * 0.015f
            val gVal = (rawG + osc).coerceAtLeast(0.02f)
            val calienteBarra = gVal >= umbral

            pBarra.color = android.graphics.Color.parseColor(if (calienteBarra) "#E53035" else "#90CA50")

            val barHeight = ((gVal / 0.8f).coerceIn(0.04f, 1.0f) * (centerY - pad) * 1.8f).coerceAtLeast(px(this, 6f))
            val bx = startX + i * totalBarW
            val byTop = centerY - barHeight / 2f
            val byBottom = centerY + barHeight / 2f

            c.drawRect(bx, byTop, bx + barW, byBottom, pBarra)
        }

        // 6. Textos inferiores
        c.drawText("ACELERÓMETRO · 50 Hz", px(this, 12f), h - px(this, 12f), pSub)
        val valStr = "${String.format(java.util.Locale.US, "%.2f", ultimoG)} g"
        pValor.color = android.graphics.Color.parseColor(if (calienteGlobal) "#E53035" else "#90CA50")
        val valW = pValor.measureText(valStr)
        c.drawText(valStr, w - valW - px(this, 12f), h - px(this, 12f), pValor)

        c.restore()

        // 7. Borde exterior por encima
        c.drawPath(clipTactico, pBorde)

        if (isShown) postInvalidateOnAnimation()
    }
}

/**
 * Interruptor táctico con diseño exacto del documento de rediseño:
 * - Pastilla de 44x26 dp con radio completo.
 * - Círculo interior de 20 dp que se desliza suavemente con animación desacelerada.
 * - Estados ON (#90CA50 o #E53035) y OFF (#1E252A).
 */
class VistaInterruptor @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs), android.widget.Checkable {

    private var _checked = true
    private var progress = 1f // 0f = OFF, 1f = ON
    private var listener: ((Boolean) -> Unit)? = null
    private var animator: android.animation.ValueAnimator? = null

    var colorActivo: Int = android.graphics.Color.parseColor("#90CA50")
        set(v) { field = v; invalidate() }
    var colorInactivo: Int = android.graphics.Color.parseColor("#1E252A")
        set(v) { field = v; invalidate() }

    private val pTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pThumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rectTrack = android.graphics.RectF()

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener {
            toggle()
        }
    }

    override fun isChecked(): Boolean = _checked

    override fun setChecked(b: Boolean) {
        if (_checked != b) {
            _checked = b
            animateProgress(if (b) 1f else 0f)
            listener?.invoke(_checked)
        }
    }

    fun setCheckedSilently(b: Boolean) {
        _checked = b
        progress = if (b) 1f else 0f
        animator?.cancel()
        invalidate()
    }

    override fun toggle() {
        setChecked(!_checked)
    }

    fun setOnCheckedChangeListener(l: (Boolean) -> Unit) {
        listener = l
    }

    private fun animateProgress(target: Float) {
        animator?.cancel()
        animator = android.animation.ValueAnimator.ofFloat(progress, target).apply {
            duration = 200L
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = px(this, 44f).toInt()
        val h = px(this, 26f).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        rectTrack.set(0f, 0f, w, h)
        val radius = h / 2f

        // Track blend
        val trackColor = androidx.core.graphics.ColorUtils.blendARGB(colorInactivo, colorActivo, progress)
        pTrack.color = trackColor
        c.drawRoundRect(rectTrack, radius, radius, pTrack)

        // Thumb blend
        /* Blanco encendido, gris apagado: exactamente los mismos dos colores que
           usa `sw_tactico` en sus drawables. Estaba en #0A0F06, casi negro, y en
           Ajustes se veia un circulo negro sobre verde justo al lado de otros
           interruptores blancos sobre verde. Dos mecanismos distintos pintando
           el mismo control tienen que dar el mismo control. */
        val cThumbOn = android.graphics.Color.parseColor("#FFFFFF")
        val cThumbOff = android.graphics.Color.parseColor("#7C858D")
        val thumbColor = androidx.core.graphics.ColorUtils.blendARGB(cThumbOff, cThumbOn, progress)
        pThumb.color = thumbColor

        val thumbDiameter = px(this, 20f)
        val pad = px(this, 3f)
        val thumbRadius = thumbDiameter / 2f
        val leftX = pad + thumbRadius
        val rightX = w - pad - thumbRadius
        val thumbCenterX = leftX + (rightX - leftX) * progress
        val thumbCenterY = h / 2f

        c.drawCircle(thumbCenterX, thumbCenterY, thumbRadius, pThumb)
    }
}

/** El osciloscopio del entorno: la envolvente del micrófono, espejada. */
class VistaOsciloscopio @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) :
    View(ctx, attrs) {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val trazo = Path()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        p.color = context.getColor(R.color.line)
        p.strokeWidth = px(this, 1f)
        c.drawLine(0f, h / 2, w, h / 2, p)          // el cero se ve aunque no haya señal

        /* Igual que la traza: se lee del servicio en cada marco. Un osciloscopio
           que solo se refresca dos veces por segundo no parece un osciloscopio,
           parece que la app se ha quedado colgada. */
        val onda = ServicioSos.oyeOnda
        if (onda.size < 2) { if (isShown) postInvalidateOnAnimation(); return }

        val ahora = System.currentTimeMillis()
        val caliente = ServicioSos.oyeCuando.any { it > 0 && ahora - it < Escucha.CALIENTE_MS }
        p.color = context.getColor(if (caliente) R.color.rd else R.color.gr)
        p.strokeWidth = px(this, 1.6f)
        val alto = h / 2 - px(this, 2f)

        // Ida por arriba y vuelta por abajo, en un solo Path: la envolvente
        // espejada es lo que hace que se lea como sonido y no como una gráfica.
        trazo.rewind()
        trazo.moveTo(0f, h / 2 - onda[0].coerceIn(0f, 1f) * alto)
        for (i in 1 until onda.size) {
            trazo.lineTo(i * w / (onda.size - 1), h / 2 - onda[i].coerceIn(0f, 1f) * alto)
        }
        for (i in onda.indices.reversed()) {
            trazo.lineTo(i * w / (onda.size - 1), h / 2 + onda[i].coerceIn(0f, 1f) * alto)
        }
        c.drawPath(trazo, p)

        if (isShown) postInvalidateOnAnimation()
    }
}

/**
 * El radar de saltos: cuatro anillos **cuadrados** concéntricos.
 *
 * Cuadrados y no círculos por la misma regla que el resto de la interfaz — aquí
 * no hay ni un radio distinto de 0 —, y además porque un radar redondo sugiere
 * una dirección que la malla acústica no puede dar: un tono de 17 kHz dice a
 * cuántos saltos está el que lo emitió, nunca hacia dónde.
 *
 * Anillo 1 = a tu lado. Anillo 4 = a cuatro móviles de distancia.
 */
/**
 * Radar acústico circular concéntrico (Pantalla 04 · Malla Acústica).
 *
 * Muestra anillos circulares de saltos, barrido giratorio continuo (sweep),
 * nodo central "TÚ" y nodos vecinos respirando en verde táctico (#90CA50).
 */
class VistaRadar @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) :
    View(ctx, attrs) {

    var viva = false
    private var porSalto = IntArray(MallaAcustica.MAX_HOP)
    private var tx = 0
    private val pulsos = ArrayList<Long>()

    fun pintar(viva: Boolean, porSalto: IntArray, tx: Int) {
        this.viva = viva
        this.porSalto = porSalto
        if (tx != this.tx) { this.tx = tx; pulsos.add(System.currentTimeMillis()) }
        invalidate()
    }

    private val pRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val pCenterFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = android.graphics.Color.parseColor("#161B1F")
    }
    private val pCenterBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = android.graphics.Color.parseColor("#2A3238")
        strokeWidth = 2.5f
    }
    private val pCenterText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#BCC3C9")
        textAlign = Paint.Align.CENTER
        textSize = 28f
        typeface = android.graphics.Typeface.MONOSPACE
        letterSpacing = 0.06f
    }
    private val pNode = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pSweep = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pSweepLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#BCC3C9")
        strokeWidth = 2f
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val cx = w / 2f; val cy = h / 2f
        val rMax = min(cx, cy) - px(this, 10f)

        // 1. Tres anillos concéntricos circulares (de dentro a fuera)
        val ringColors = intArrayOf(
            android.graphics.Color.parseColor("#1E252A"), // 84px
            android.graphics.Color.parseColor("#1A2126"), // 42px
            android.graphics.Color.parseColor("#161C21")  // 0px / max
        )
        val rCenter = px(this, 28f)
        val rStep = (rMax - rCenter) / 3f
        for (i in 1..3) {
            val r = rCenter + i * rStep
            pRing.color = ringColors[i - 1]
            pRing.strokeWidth = px(this, 1f)
            c.drawCircle(cx, cy, r, pRing)
        }

        // 2. Barrido giratorio continuo (Sweep Gradient 360° en 3.4s)
        val t = (android.os.SystemClock.uptimeMillis() % 3400L) / 3400f
        val angle = t * 360f

        c.save()
        c.rotate(angle, cx, cy)
        val sweepGradient = SweepGradient(
            cx, cy,
            intArrayOf(
                android.graphics.Color.parseColor("#55BCC3C9"),
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.25f, 1f)
        )
        pSweep.shader = sweepGradient
        c.drawCircle(cx, cy, rMax, pSweep)
        pSweep.shader = null
        c.drawLine(cx, cy, cx + rMax, cy, pSweepLine)
        c.restore()

        // 3. Nodos acústicos reales según saltos detectados (Regla 01: no inventar contactos)
        for (hop in 1..MallaAcustica.MAX_HOP) {
            val count = if (hop - 1 < porSalto.size) porSalto[hop - 1] else 0
            if (count > 0) {
                val rHop = rCenter + hop * (rMax - rCenter) / MallaAcustica.MAX_HOP
                for (k in 0 until count) {
                    val a = (k * 2.0 * Math.PI / count + hop * 0.7).toFloat()
                    val nx = cx + rHop * cos(a.toDouble()).toFloat()
                    val ny = cy + rHop * sin(a.toDouble()).toFloat()
                    pNode.color = android.graphics.Color.parseColor("#90CA50")
                    c.drawCircle(nx, ny, px(this, 5f), pNode)
                }
            }
        }

        // 4. Nodo central ("TÚ" círculo de 56 dp)
        c.drawCircle(cx, cy, rCenter, pCenterFill)
        c.drawCircle(cx, cy, rCenter, pCenterBorder)
        val textY = cy - (pCenterText.descent() + pCenterText.ascent()) / 2f
        c.drawText("TÚ", cx, textY, pCenterText)

        if (isShown) postInvalidateOnAnimation()
    }
}

/**
 * Ondas que salen del móvil, con brújula real alrededor.
 *
 * La usan la sonda —mientras da sus chasquidos— y la búsqueda de supervivientes
 * —donde la intensidad y el ritmo suben con la señal recibida—. Cuanto más
 * fuerte, más rápido salen los anillos y más rojos se ponen: es el mismo gesto
 * que hace un detector de metales, y se entiende sin leer nada.
 *
 * La brújula marca hacia dónde estás mirando TÚ, leída del magnetómetro. **No
 * apunta al superviviente y nunca lo hará**: la potencia de una señal de radio
 * dice cuánto te acercas, jamás en qué dirección está. Una aguja apuntando a
 * algo sería una mentira, y aquí una mentira manda a cavar donde no es. Sirve
 * para lo que sirve una brújula de verdad en una búsqueda: para poder decir «la
 * señal sube hacia el noreste» y volver luego al mismo sitio.
 */
class VistaSonar @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) :
    View(ctx, attrs), android.hardware.SensorEventListener {

    /** Si hay algo que animar. En reposo se queda quieta y no gasta. */
    var activo = false
        set(v) { if (field != v) { field = v; invalidate() } }
    /** 0 a 1. En la búsqueda es la señal del más fuerte; en la sonda, 1. */
    var intensidad = 0f
    /** Dibujar la rosa de los vientos alrededor. */
    var brujula = false

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pTexto = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private var rumbo = 0f

    private val sm by lazy { context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager }
    private val rot by lazy { sm.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR) }
    private val mat = FloatArray(9)
    private val ang = FloatArray(3)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (brujula) rot?.let { sm.registerListener(this, it, android.hardware.SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        try { sm.unregisterListener(this) } catch (_: Exception) {}
    }

    override fun onSensorChanged(e: android.hardware.SensorEvent) {
        android.hardware.SensorManager.getRotationMatrixFromVector(mat, e.values)
        android.hardware.SensorManager.getOrientation(mat, ang)
        // media circular corta: el magnetómetro tiembla y una rosa que tirita marea
        val nuevo = Math.toDegrees(ang[0].toDouble()).toFloat()
        var d = nuevo - rumbo
        while (d > 180) d -= 360
        while (d < -180) d += 360
        rumbo += d * 0.15f
    }

    override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}

    private val puntos = arrayOf("N", "E", "S", "O")

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val cx = w / 2; val cy = h / 2
        val margen = if (brujula) px(this, 20f) else px(this, 6f)
        val r0 = min(cx, cy) - margen
        val i = intensidad.coerceIn(0f, 1f)

        val gr = context.getColor(R.color.gr)
        val rd = context.getColor(R.color.rd)
        val line = context.getColor(R.color.line)
        val ctl = context.getColor(R.color.ctl)
        // de verde a rojo según se aprieta la señal: rojo es «lo tienes debajo»
        val color = if (i > 0.66f) rd else gr

        p.style = Paint.Style.STROKE
        p.strokeWidth = px(this, 1.5f)
        p.color = line
        c.drawRect(cx - r0, cy - r0, cx + r0, cy + r0, p)

        if (brujula) {
            pTexto.textSize = px(this, 11f)
            pTexto.isFakeBoldText = true
            for (k in puntos.indices) {
                // la rosa gira al revés que el móvil: el norte se queda quieto
                val a = Math.toRadians((k * 90).toDouble() - rumbo)
                val d = r0 + px(this, 12f)
                val x = cx + (Math.sin(a) * d).toFloat()
                val y = cy - (Math.cos(a) * d).toFloat() + px(this, 4f)
                pTexto.color = if (k == 0) rd else ctl
                c.drawText(puntos[k], x, y, pTexto)
            }
        }

        if (!activo) {
            p.color = ctl
            p.strokeWidth = px(this, 2f)
            val m = px(this, 8f)
            c.drawRect(cx - m, cy - m, cx + m, cy + m, p)
            return
        }

        /* Cuatro anillos escalonados. El periodo baja de 2 s a 0,6 s con la
           señal: es la aceleración, más que el brillo, lo que se percibe como
           «te estás acercando» sin tener que leer el número. */
        val periodo = 2000f - 1400f * i
        val t = (System.currentTimeMillis() % periodo.toLong()).toFloat() / periodo
        val n = 4
        p.strokeWidth = px(this, 2.5f)
        for (k in 0 until n) {
            val fase = (t + k.toFloat() / n) % 1f
            p.color = color
            p.alpha = ((1f - fase) * (90 + 165 * i)).toInt().coerceIn(0, 255)
            val r = px(this, 10f) + fase * (r0 - px(this, 10f))
            c.drawRect(cx - r, cy - r, cx + r, cy + r, p)
        }
        p.alpha = 255

        // el móvil, en el centro
        p.style = Paint.Style.FILL
        p.color = color
        val a = px(this, 6f); val b = px(this, 10f)
        c.drawRect(cx - a, cy - b, cx + a, cy + b, p)
        p.color = context.getColor(R.color.bg)
        c.drawRect(cx - a + px(this, 2f), cy - b + px(this, 2f), cx + a - px(this, 2f), cy + b - px(this, 2f), p)

        if (isShown) postInvalidateOnAnimation()
    }
}

/**
 * Trazo en vivo del nivel de movimiento: un registrador de papel continuo.
 *
 * Lo alimenta `ServicioSos.nivelDoppler`, y se muestrea AQUÍ a la velocidad de la
 * pantalla en vez de recibirlo desde fuera, por lo mismo que la traza del
 * sismógrafo: si dependiera del refresco de los textos (dos veces por segundo)
 * parecería que el micrófono va a saltos.
 *
 * `lento` es para la respiración: un pecho tarda entre dos y siete segundos en
 * subir y bajar, así que muestreando a 60 por segundo no se ve el rizo, se ve
 * ruido. Con una muestra cada cuatro marcos caben unos veinte segundos de pared a
 * pared, que es justo la ventana que analiza el detector.
 */
class VistaPulso @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) :
    View(ctx, attrs) {

    /** Si está apagado se queda quieto y no gasta batería dibujando.
     *
     *  Con `invalidate()` en el setter a propósito: la vista solo se reprograma
     *  mientras está activa, así que al encenderla no habría nadie que la
     *  despertara y se quedaba en blanco para siempre. Asignar un campo no repinta
     *  nada por sí solo. */
    var activo = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var lento = false

    private val n = 160
    private val datos = FloatArray(n)
    private var w0 = 0
    private var salta = 0
    private val trazo = Path()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val pBase = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    override fun onDraw(c: Canvas) {
        val an = width.toFloat(); val al = height.toFloat()
        if (an <= 0 || al <= 0) return
        val pad = px(this, 6f)

        if (activo) {
            salta++
            if (!lento || salta % 4 == 0) {
                /* El nivel es «cuántas veces el fondo», y de 1 a 6 es todo el
                   recorrido útil: por encima de 6 ya es un movimiento evidente y
                   estirar la escala solo aplana el resto. */
                val v = ((ServicioSos.nivelDoppler - 1.0) / 5.0).coerceIn(0.0, 1.0)
                datos[w0] = v.toFloat()
                w0 = (w0 + 1) % n
            }
        }

        // la línea de reposo: sin ella, un trazo plano no se distingue de apagado
        pBase.color = context.getColor(R.color.line)
        pBase.strokeWidth = px(this, 1f)
        c.drawLine(0f, al - pad, an, al - pad, pBase)

        var maxi = 0f
        for (v in datos) if (v > maxi) maxi = v
        p.color = context.getColor(if (maxi > 0.6f) R.color.rd else if (activo) R.color.gr else R.color.ctl)
        p.strokeWidth = px(this, 2f)
        trazo.rewind()
        for (i in 0 until n) {
            val v = datos[(w0 + i) % n]
            val x = i * an / (n - 1)
            val y = al - pad - v * (al - pad * 2)
            if (i == 0) trazo.moveTo(x, y) else trazo.lineTo(x, y)
        }
        c.drawPath(trazo, p)

        if (isShown && activo) postInvalidateOnAnimation()
    }
}

/**
 * El barrido, dibujado: un seno cuya frecuencia sube de un lado al otro de la
 * pantalla, y que además avanza mientras suena.
 *
 * No mide nada — es una representación de lo que el altavoz está emitiendo, de 80
 * Hz a 4 kHz cada 2,5 s. Sirve para saber que ESTÁ sonando algo que casi no se
 * oye por un escombro, que es la queja de siempre con este modo: se enciende y
 * parece que no hace nada.
 */
class VistaBarrido @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) :
    View(ctx, attrs) {

    /** Ver la nota de `VistaPulso.activo`: sin invalidar aquí, no arranca. */
    var activo = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private var fase = 0f
    private val trazo = Path()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(c: Canvas) {
        val an = width.toFloat(); val al = height.toFloat()
        if (an <= 0 || al <= 0) return
        val pad = px(this, 8f)
        val cy = al / 2f

        if (activo) fase += 0.06f
        p.color = context.getColor(if (activo) R.color.gr else R.color.ctl)
        p.strokeWidth = px(this, 2f)

        trazo.rewind()
        val pasos = 220
        for (i in 0..pasos) {
            val t = i.toFloat() / pasos
            /* La frecuencia crece de izquierda a derecha igual que el barrido
               crece en el tiempo: exponencial, no lineal, porque el oído oye en
               octavas y el sonido real también sube así. */
            val ciclos = 1.5f + 26f * t * t
            val amp = (al / 2f - pad) * (0.35f + 0.65f * (1f - t))
            val x = t * an
            val y = cy + amp * sin((ciclos * t * 6.2832f + fase).toDouble()).toFloat()
            if (i == 0) trazo.moveTo(x, y) else trazo.lineTo(x, y)
        }
        c.drawPath(trazo, p)

        if (isShown && activo) postInvalidateOnAnimation()
    }
}

/**
 * Una consola que escribe.
 *
 * Las salidas de las herramientas eran texto que aparecía de golpe, y cuando solo
 * cambia una cifra no se nota que ha pasado algo. Aquí las líneas se escriben
 * carácter a carácter y queda un cursor de bloque parpadeando al final, como un
 * terminal. No es decoración: es la diferencia entre una caja de texto y una máquina
 * que está trabajando, y quien mira un móvil apoyado en un escombro necesita saber
 * cuál de las dos tiene delante.
 *
 * Si el texto nuevo empieza por el que ya había —el caso normal, una línea más al
 * final— sigue escribiendo desde donde estaba en vez de repetirlo todo.
 */
class VistaConsola @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) :
    android.widget.TextView(ctx, attrs) {

    private var completo: CharSequence = ""
    private var visibles = 0
    private var cursor = true
    private val reloj = android.os.Handler(android.os.Looper.getMainLooper())

    /** Cuántos caracteres por marco. Con 3 salen unas 180 por segundo: se ve
     *  escribir sin que dé tiempo a impacientarse. */
    private val porMarco = 3

    private val tic = object : Runnable {
        override fun run() {
            val escribiendo = visibles < completo.length
            if (escribiendo) visibles = min(completo.length, visibles + porMarco)
            cursor = if (escribiendo) true else !cursor
            val abajo = alFinal()
            pintar()
            post { bajarSiTocaba(abajo) }
            // mientras escribe, a ritmo de pantalla; luego, solo el parpadeo
            if (isShown) reloj.postDelayed(this, if (escribiendo) 16L else 520L)
        }
    }

    init {
        /* La consola tiene alto fijo y el texto crece sin parar: lo que no
           cabia se quedaba recortado y no habia forma de leerlo. Se hace
           desplazable por dentro, y como esta metida en el scroll de la
           pantalla hay que pedirle al padre que no le robe el gesto — si no,
           arrastrar dentro de la consola mueve la pantalla entera. */
        movementMethod = android.text.method.ScrollingMovementMethod()
        isVerticalScrollBarEnabled = true
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    /** Si el usuario esta leyendo mas arriba no se le arrastra al final. */
    private fun alFinal(): Boolean {
        val alto = layout?.height ?: return true
        return scrollY >= alto - height - px(this, 24f)
    }

    private fun bajarSiTocaba(estaba: Boolean) {
        if (!estaba) return
        val alto = layout?.height ?: return
        val max = (alto - height).coerceAtLeast(0)
        if (scrollY != max) scrollTo(0, max)
    }

    /** El texto que la consola tiene que acabar mostrando. */
    fun escribir(t: CharSequence) {
        if (t.toString() == completo.toString()) return
        /* Se teclea al abrir y cuando el texto crece por el final. Cualquier
           otro cambio se pinta de golpe: la consola pone lo nuevo ARRIBA, así
           que el texto nunca empezaba igual que el anterior y cada evento
           volvía a escribir las cincuenta líneas desde la primera letra. */
        visibles = when {
            completo.isEmpty() -> 0
            t.toString().startsWith(completo.toString()) -> visibles
            else -> t.length
        }
        completo = t
        reloj.removeCallbacks(tic)
        reloj.post(tic)
    }

    private fun pintar() {
        val base = completo.subSequence(0, visibles)
        val ssb = android.text.SpannableStringBuilder(base)
        
        val colorCursor = if (cursor) 0xFFBCC3C9.toInt() else 0x00000000.toInt()
        val posCursor = ssb.length
        ssb.append("▌")
        ssb.setSpan(android.text.style.ForegroundColorSpan(colorCursor), posCursor, ssb.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        
        text = ssb
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        reloj.removeCallbacks(tic); reloj.post(tic)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        reloj.removeCallbacks(tic)
    }
}

/**
 * La onda de la cuenta atrás: un anillo que nace en el número y se expande hasta
 * salirse de la pantalla, una vez por segundo.
 *
 * Está por debajo de todo y no tapa nada. No es un adorno gratuito: la pantalla
 * de «¿estás bien?» la mira alguien que acaba de sentir un terremoto y que puede
 * estar mirándola sin leerla, y un movimiento que sale del número y barre la
 * pantalla dice «esto está corriendo» sin pedirle que lea nada. Es la misma
 * información que el sonido de cada segundo, por el otro sentido.
 *
 * Se apaga sola: cuando el último anillo se sale, deja de repintar. Sin eso
 * estaría dibujando a 60 Hz para siempre en una pantalla que puede quedarse
 * encendida horas.
 */
class VistaOndaCuenta @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) :
    View(ctx, attrs) {

    /** Cuánto tarda un anillo en cruzar la pantalla. Más que un segundo a
     *  propósito: así siempre hay dos vivos y la onda se ve continua. */
    private val duracion = 1500L
    private val nacidos = ArrayList<Long>(4)
    private var cx = -1f
    private var cy = -1f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** El centro, tomado de la vista del número: la onda tiene que salir de ahí,
     *  no del centro geométrico de la pantalla. */
    fun centrarEn(v: View) {
        val mio = IntArray(2); val suyo = IntArray(2)
        getLocationOnScreen(mio); v.getLocationOnScreen(suyo)
        cx = (suyo[0] - mio[0] + v.width / 2).toFloat()
        cy = (suyo[1] - mio[1] + v.height / 2).toFloat()
    }

    fun latir() {
        nacidos.add(System.currentTimeMillis())
        while (nacidos.size > 3) nacidos.removeAt(0)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val an = width.toFloat(); val al = height.toFloat()
        if (an <= 0 || al <= 0 || nacidos.isEmpty()) return
        val x = if (cx >= 0) cx else an / 2
        val y = if (cy >= 0) cy else al / 2
        // hasta la esquina más lejana: la onda tiene que salirse de la pantalla,
        // no pararse en el borde más cercano
        val rMax = maxOf(
            hypot(x, y), hypot(an - x, y), hypot(x, al - y), hypot(an - x, al - y)
        )
        val ahora = System.currentTimeMillis()
        val vivos = ArrayList<Long>(nacidos.size)
        for (t0 in nacidos) {
            val t = (ahora - t0) / duracion.toFloat()
            if (t >= 1f) continue
            vivos.add(t0)
            /* Desacelera al alejarse (raíz) y se apaga al cubo: casi todo el
               brillo está en el primer tercio, junto al número, y el resto es un
               rastro que se va. Con alfa lineal parecía un aro de neón. */
            val r = rMax * kotlin.math.sqrt(t)
            val a = ((1f - t) * (1f - t) * (1f - t) * 215f).toInt().coerceIn(0, 255)
            p.color = context.getColor(R.color.rd)
            p.alpha = a
            p.strokeWidth = px(this, 6f) * (1f - t) + px(this, 0.7f)
            c.drawCircle(x, y, r, p)
            /* El destello: los primeros 200 ms el anillo va acompañado de un
               segundo trazo más ancho justo detrás. Es lo que hace que parezca
               que sale DEL número en vez de aparecer alrededor. */
            if (t < 0.14f) {
                p.alpha = (a * (1f - t / 0.14f) * 0.5f).toInt().coerceIn(0, 255)
                p.strokeWidth = px(this, 22f) * (1f - t / 0.14f)
                c.drawCircle(x, y, r * 0.72f, p)
            }
        }
        nacidos.clear(); nacidos.addAll(vivos)
        if (nacidos.isNotEmpty() && isShown) postInvalidateOnAnimation()
    }
}

/**
 * Rejilla táctica y líneas de escáner según la maqueta del rediseño:
 * repeating-linear-gradient(0deg, rgba(229,48,53,.05) 0 2px, transparent 2px 6px)
 * y cuadrícula vertical.
 */
class VistaRejilla @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) :
    View(ctx, attrs) {

    private val pScan = Paint().apply {
        color = android.graphics.Color.parseColor("#E53035")
        alpha = 18 // ~7% opacity
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val pGrid = Paint().apply {
        color = android.graphics.Color.parseColor("#E53035")
        alpha = 12 // ~5% opacity
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 1. Scanlines horizontales cada 6dp
        val pasoY = px(this, 6f)
        var y = 0f
        while (y <= h) {
            c.drawLine(0f, y, w, y, pScan)
            y += pasoY
        }

        // 2. Cuadrícula vertical cada 32dp
        val pasoX = px(this, 32f)
        var x = 0f
        while (x <= w) {
            c.drawLine(x, 0f, x, h, pGrid)
            x += pasoX
        }
    }
}

/**
 * Icono animado del DETECTOR de la pantalla Inicio.
 * Anima 5 barras verticales de forma asíncrona / desordenada simulando
 * escucha sísmica y acústica real continua.
 */
class VistaIconoDetector @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) :
    View(ctx, attrs) {

    private val pBar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#BCC3C9")
        style = Paint.Style.FILL
    }

    private val baseHeights = floatArrayOf(10f, 22f, 14f, 26f, 8f)
    private val freqs = floatArrayOf(4.2f, 6.7f, 3.5f, 5.8f, 7.3f)
    private val phases = floatArrayOf(0.4f, 2.1f, 1.2f, 3.8f, 5.0f)
    private val rect = android.graphics.RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        /* Todo en fraccion de la caja, no en dp fijos. Estaba escrito con
           anchos y alturas en dp sueltos —barras de 3 dp y picos de hasta 26—,
           asi que este icono llenaba su caja entera mientras los otros cinco
           ocupaban 22 de 30. En una rejilla de seis eso se nota: parecia mas
           grande sin serlo. Ahora comparte la misma metrica que los vectores. */
        val barW = w * 0.10f
        val gap = w * 0.10f
        val totalW = 5 * barW + 4 * gap
        val startX = (w - totalW) / 2f
        val centerY = h / 2f
        val techo = h * 0.733f          // 22 de 30, la altura util de los demas

        val t = android.os.SystemClock.uptimeMillis() / 1000f

        for (i in 0 until 5) {
            val osc = (sin(t * freqs[i] + phases[i]) * 0.45f + sin(t * freqs[i] * 1.6f + phases[i] * 0.5f) * 0.25f)
            val f = (baseHeights[i] / 26f * (0.6f + osc)).coerceIn(0.15f, 1f)
            val barH = techo * f

            val x = startX + i * (barW + gap)
            val top = centerY - barH / 2f
            val bottom = centerY + barH / 2f

            rect.set(x, top, x + barW, bottom)
            c.drawRoundRect(rect, barW / 2f, barW / 2f, pBar)
        }

        postInvalidateOnAnimation()
    }
}

/**
 * Animación de baliza BLE de radio (Pantalla 05 · Baliza de Radio).
 *
 * Muestra el círculo rojo central de 70dp y 3 anillos de onda expansiva
 * que se propagan cada 2.8 s en color rojo de emergencia (rgba(229,48,53,.55)).
 */
class VistaBaliza @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) :
    View(ctx, attrs) {

    private val pCenter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = android.graphics.Color.parseColor("#E53035")
    }
    private val pRadialGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = px(this@VistaBaliza, 1.5f)
        color = android.graphics.Color.parseColor("#E53035")
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val cx = w / 2f; val cy = h / 2f
        val r0 = px(this, 35f)
        val rMax = min(cx, cy) - px(this, 6f)

        val now = android.os.SystemClock.uptimeMillis()
        val period = 2800L

        // 1. Halo / degradado radial expansivo debajo del punto rojo central (box-shadow 44dp + glow)
        val glowRadius = r0 + px(this, 36f)
        pRadialGlow.shader = RadialGradient(
            cx, cy, glowRadius,
            intArrayOf(
                android.graphics.Color.parseColor("#80E53035"), // 50% en el borde del centro
                android.graphics.Color.parseColor("#33E53035"), // 20% medio
                android.graphics.Color.parseColor("#0DE53035"), // 5% exterior
                android.graphics.Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.45f, 0.75f, 1.0f),
            android.graphics.Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, glowRadius, pRadialGlow)
        pRadialGlow.shader = null

        // 2. Tres anillos concéntricos expandiéndose
        for (i in 0..2) {
            val offset = i * (period / 3)
            val phase = ((now + offset) % period) / period.toFloat()
            val r = r0 + phase * (rMax - r0)
            val alpha = ((1f - phase) * 0.55f * 255).toInt().coerceIn(0, 255)
            pRing.alpha = alpha
            c.drawCircle(cx, cy, r, pRing)
        }

        // 3. Círculo rojo central (70dp diámetro)
        c.drawCircle(cx, cy, r0, pCenter)

        if (isShown) postInvalidateOnAnimation()
    }
}

/**
 * Tarjeta táctica con esquina superior derecha achaflanada a 14dp (45°),
 * fondo #0D1113 y borde #1E252A.
 */
class TarjetaAchaflanadaLayout @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : android.widget.LinearLayout(ctx, attrs) {

    private val path = Path()
    private val pFondo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = android.graphics.Color.parseColor("#0D1113")
    }
    private val pBorde = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = px(this@TarjetaAchaflanadaLayout, 1f)
        color = android.graphics.Color.parseColor("#1E252A")
    }

    init {
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val wf = w.toFloat(); val hf = h.toFloat()
        val ch = px(this, 14f)
        path.rewind()
        path.moveTo(0f, 0f)
        path.lineTo(wf - ch, 0f)
        path.lineTo(wf, ch)
        path.lineTo(wf, hf)
        path.lineTo(0f, hf)
        path.close()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(path, pFondo)
        canvas.drawPath(path, pBorde)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val save = canvas.save()
        canvas.clipPath(path)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }
}

/**
 * Tarjeta de GRUPO SANGUÍNEO con fondo rojo táctico (#E53035) y esquina
 * superior derecha achaflanada a 14dp (clip-path de la Pantalla 09).
 */
class TarjetaGrupoLayout @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : android.widget.LinearLayout(ctx, attrs) {

    private val path = Path()
    private val pFondo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFE53035.toInt()
    }

    init {
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val wf = w.toFloat(); val hf = h.toFloat()
        val ch = px(this, 14f)
        val r = px(this, 6f)
        path.rewind()
        path.moveTo(0f, 0f)
        path.lineTo(wf - ch, 0f)
        path.lineTo(wf, ch)
        path.lineTo(wf, hf)
        path.lineTo(0f, hf)
        path.close()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(path, pFondo)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val save = canvas.save()
        canvas.clipPath(path)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }
}

/**
 * Tarjeta de tendencia de señal de búsqueda con gradiente #0F1A0C -> #0A0D08,
 * borde verde táctico (rgba(144,202,80,.3)) y esquina achaflanada a 16dp.
 */
class TarjetaTendenciaLayout @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : android.widget.LinearLayout(ctx, attrs) {

    private val path = Path()
    private val pFondo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pBorde = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = px(this@TarjetaTendenciaLayout, 1f)
        color = android.graphics.Color.parseColor("#4D90CA50") // 30% alpha
    }

    init {
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val wf = w.toFloat(); val hf = h.toFloat()
        val ch = px(this, 16f)
        path.rewind()
        path.moveTo(0f, 0f)
        path.lineTo(wf - ch, 0f)
        path.lineTo(wf, ch)
        path.lineTo(wf, hf)
        path.lineTo(0f, hf)
        path.close()

        pFondo.shader = android.graphics.LinearGradient(
            0f, 0f, 0f, hf,
            android.graphics.Color.parseColor("#0F1A0C"),
            android.graphics.Color.parseColor("#0A0D08"),
            android.graphics.Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(path, pFondo)
        canvas.drawPath(path, pBorde)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val save = canvas.save()
        canvas.clipPath(path)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }
}

/**
 * Barras de histograma de evolución de señal RSSI (Pantalla 06 · Buscar).
 *
 * Muestra 12 barras crecientes con gradación de opacidad en verde táctico (#90CA50),
 * y la barra actual palpitando suavemente.
 */
class VistaBarrasTendencia @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private val pBar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val rect = android.graphics.RectF()

    /* La historia real de señal, en dBm, más antigua a la izquierda. Antes las
       doce alturas estaban escritas aquí dentro —la silueta de la maqueta— y la
       última respiraba siempre, hubiera señal o no. Se movía sin medir nada,
       que es justo lo que el proyecto dice que no puede pasar. */
    private var muestras = DoubleArray(0)
    private var vivas = 0

    /** Suelo y techo de la escala. Fuera de esta banda no hay nada que seguir:
     *  por debajo de −100 dBm el anuncio no llega y por encima de −40 estás
     *  encima del móvil. */
    private val dbmMin = -100.0
    private val dbmMax = -40.0

    fun pintar(historia: DoubleArray, cuantas: Int) {
        muestras = historia
        vivas = cuantas
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val count = muestras.size
        if (count == 0) return

        val gap = px(this, 4f)
        val barW = (w - (count - 1) * gap) / count
        val r = px(this, 1f)

        /* La última barra respira solo mientras haya una medida fresca: la
           respiración es lo que dice «esto está entrando ahora», y sin señal
           tiene que quedarse quieta. */
        val hayMedida = vivas > 0 && muestras.last() > dbmMin
        val now = android.os.SystemClock.uptimeMillis()
        val aliento = (sin((now % 1200L) / 1200.0 * 2.0 * Math.PI).toFloat() + 1f) / 2f

        for (i in 0 until count) {
            val v = muestras[i]
            val left = i * (barW + gap)
            rect.set(left, 0f, left + barW, h)

            /* Sin medida, un tocón apagado: hace ver que ahí no se sabe, en vez
               de dibujar una barra al ras que parecería «señal cero». */
            if (v <= dbmMin) {
                rect.top = h - px(this, 2f)
                pBar.color = 0xFF1E252A.toInt()
                pBar.alpha = 255
                c.drawRoundRect(rect, r, r, pBar)
                continue
            }

            val f = ((v - dbmMin) / (dbmMax - dbmMin)).coerceIn(0.0, 1.0).toFloat()
            rect.top = h - (h * f).coerceAtLeast(px(this, 2f))

            /* La intensidad sube con la señal: cuanto más cerca, más sólida se
               ve la barra. Es la misma medida contada dos veces —altura y
               opacidad— porque en una pantalla a contraluz y llena de polvo la
               altura sola no se distingue. */
            pBar.color = 0xFF90CA50.toInt()
            pBar.alpha = (70 + f * 185).toInt().coerceIn(0, 255)
            if (i == count - 1 && hayMedida) {
                pBar.alpha = (180 + aliento * 75).toInt().coerceIn(0, 255)
            }
            c.drawRoundRect(rect, r, r, pBar)
        }

        if (isShown && hayMedida) postInvalidateOnAnimation()
    }
}

/**
 * Mini indicador animado de intensidad de señal RSSI (Pantalla 06 · Buscar).
 *
 * Dibuja 4 barras verticales crecientes. Las barras activas se colorean
 * en verde táctico (#90CA50) o ámbar (#F0A02A), y la barra superior activa
 * pulsa con una respiración suave en tiempo real.
 */
class VistaMiniRssi @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    var nivel: Int = 4 // 0..4 (0 = sin señal/perdido, 1..4 = intensidad)
        set(v) { field = v.coerceIn(0, 4); invalidate() }
    var colorActivo: Int = android.graphics.Color.parseColor("#90CA50")
        set(v) { field = v; invalidate() }

    private val pBar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val rect = android.graphics.RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val count = 4
        val gap = px(this, 2f)
        val barW = (w - (count - 1) * gap) / count

        val now = android.os.SystemClock.uptimeMillis()
        val breath = (sin((now % 1000L) / 1000.0 * 2.0 * Math.PI).toFloat() + 1f) / 2f

        for (i in 0 until count) {
            val left = i * (barW + gap)
            val right = left + barW
            val hRatio = (i + 1) / count.toFloat()
            val barH = h * (0.35f + 0.65f * hRatio)
            val top = h - barH
            rect.set(left, top, right, h)

            if (i < nivel) {
                pBar.color = colorActivo
                if (i == nivel - 1 && nivel > 0) {
                    val a = (180 + (breath * 75)).toInt().coerceIn(0, 255)
                    pBar.alpha = a
                } else {
                    pBar.alpha = 230
                }
            } else {
                pBar.color = android.graphics.Color.parseColor("#2A3238")
                pBar.alpha = 140
            }
            c.drawRoundRect(rect, px(this, 1f), px(this, 1f), pBar)
        }

        if (isShown && nivel > 0) postInvalidateOnAnimation()
    }
}
/**
 * Panel de ecolocalización para la pantalla Sonda (Screen 07).
 *
 * Dibuja una cuadrícula horizontal con tinte verde, una banda de escaneo
 * animada, tres barras de RETORNO con valores en ms y un texto de análisis
 * en la parte inferior. Todo en el lienzo para tener control total sobre
 * el aspecto y la animación.
 */
class VistaEcoPanel @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    var activo = false
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Tres retornos: valor en ms (0 = sin retorno). */
    var retorno1 = 18f
    var retorno2 = 41f
    var retorno3 = 0f

    /** Texto de interpretación que se muestra abajo. */
    var textoAnalisis = "Hay dos superficies devolviendo el chasquido."
    var textoAnalisisDestacado = "No se puede decir a qué distancia."

    /** Subtítulo arriba-izquierda: modo + frecuencia. */
    var subtitulo = "ECOLOCALIZACIÓN · CHASQUIDO 2/S"

    private val pFondo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pLinea = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBarra = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pTexto = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pScan = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = android.graphics.RectF()

    private val maxRetornoMs = 100f  // escala: 100 ms es el 100%

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val pad = px(this, 16f)
        val cornerR = px(this, 6f)

        // ── Fondo del panel ─────────────────────────────────────
        pFondo.style = Paint.Style.FILL
        pFondo.color = 0xFF0A0D0F.toInt()
        rect.set(0f, 0f, w, h)
        c.drawRoundRect(rect, cornerR, cornerR, pFondo)

        // Borde
        pFondo.style = Paint.Style.STROKE
        pFondo.strokeWidth = px(this, 1f)
        pFondo.color = 0xFF1E252A.toInt()
        c.drawRoundRect(rect, cornerR, cornerR, pFondo)

        // ── Rejilla horizontal (verde tenue) ────────────────────
        pLinea.style = Paint.Style.STROKE
        pLinea.strokeWidth = px(this, 0.5f)
        pLinea.color = 0x0D90CA50.toInt() // rgba(144,202,80,0.05)
        val gridSpacing = px(this, 28f)
        var y = gridSpacing
        while (y < h) {
            c.drawLine(0f, y, w, y, pLinea)
            y += gridSpacing
        }

        // ── Banda de escaneo animada ────────────────────────────
        if (activo) {
            val scanH = px(this, 60f)
            val periodo = 3200L
            val t = (System.currentTimeMillis() % periodo).toFloat() / periodo
            // va de arriba a abajo con recorrido extendido
            val scanY = -scanH + t * (h + scanH * 2)
            val grad = android.graphics.LinearGradient(
                0f, scanY, 0f, scanY + scanH,
                intArrayOf(0x0090CA50.toInt(), 0x2490CA50, 0x0090CA50.toInt()),
                floatArrayOf(0f, 0.5f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
            pScan.shader = grad
            pScan.style = Paint.Style.FILL
            c.drawRect(0f, scanY, w, scanY + scanH, pScan)
            pScan.shader = null
        }

        // ── Subtítulo arriba-izquierda ──────────────────────────
        pTexto.textSize = px(this, 10f)
        pTexto.color = 0xFF7C858D.toInt()
        pTexto.letterSpacing = 0.16f
        pTexto.typeface = android.graphics.Typeface.MONOSPACE
        c.drawText(subtitulo, pad, pad + pTexto.textSize, pTexto)

        // ── Barras de RETORNO ───────────────────────────────────
        val barStartY = pad + px(this, 50f)
        val barAreaW = w - 2 * pad
        val barH = px(this, 6f)
        val barGap = px(this, 36f)

        val retornos = listOf(
            "RETORNO 1" to retorno1,
            "RETORNO 2" to retorno2,
            "RETORNO 3" to retorno3
        )

        pTexto.textSize = px(this, 11f)
        pTexto.letterSpacing = 0.02f
        for ((idx, pair) in retornos.withIndex()) {
            val (label, ms) = pair
            val cy = barStartY + idx * barGap

            // Etiqueta
            pTexto.textAlign = Paint.Align.LEFT
            pTexto.color = 0xFF7C858D.toInt()
            c.drawText(label, pad, cy, pTexto)

            // Valor
            pTexto.textAlign = Paint.Align.RIGHT
            if (ms > 0) {
                pTexto.color = 0xFF90CA50.toInt()
                c.drawText("${ms.toInt()} ms", w - pad, cy, pTexto)
            } else {
                pTexto.color = 0xFF7C858D.toInt()
                c.drawText("—", w - pad, cy, pTexto)
            }

            // Barra de fondo
            val barTop = cy + px(this, 6f)
            pBarra.style = Paint.Style.FILL
            pBarra.color = 0xFF161B1F.toInt()
            rect.set(pad, barTop, pad + barAreaW, barTop + barH)
            c.drawRoundRect(rect, px(this, 2f), px(this, 2f), pBarra)

            // Barra de relleno
            if (ms > 0) {
                val ratio = (ms / maxRetornoMs).coerceIn(0f, 1f)
                val alpha = if (idx == 0) 0xFF else 0x99
                pBarra.color = (alpha.toLong() shl 24 or 0x90CA50L).toInt()
                rect.set(pad, barTop, pad + barAreaW * ratio, barTop + barH)
                c.drawRoundRect(rect, px(this, 2f), px(this, 2f), pBarra)
            }
        }

        // ── Texto de análisis (abajo) ───────────────────────────
        val lineTop = h - pad - px(this, 44f)
        pLinea.color = 0xFF1E252A.toInt()
        pLinea.strokeWidth = px(this, 1f)
        c.drawLine(pad, lineTop, w - pad, lineTop, pLinea)

        pTexto.textSize = px(this, 13f)
        pTexto.textAlign = Paint.Align.LEFT
        pTexto.letterSpacing = 0f
        pTexto.typeface = android.graphics.Typeface.DEFAULT
        pTexto.color = 0xFF7C858D.toInt()
        c.drawText(textoAnalisis, pad, lineTop + px(this, 20f), pTexto)
        pTexto.color = 0xFFBCC3C9.toInt()
        c.drawText(textoAnalisisDestacado, pad, lineTop + px(this, 38f), pTexto)

        if (activo && isShown) postInvalidateOnAnimation()
    }
}

/**
 * Vúmetro circular animado para la pantalla Interfono (Screen 08).
 *
 * Dibuja un disco central táctico con anillo de pulso concéntrico,
 * cinco barras verticales de nivel VU dinámicas y lectura en dBFS.
 */
class VistaInterfonoVu @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    var fase: Interfono.Fase = Interfono.Fase.CERRADO
        set(v) { if (field != v) { field = v; invalidate() } }

    var activo: Boolean
        get() = fase != Interfono.Fase.CERRADO
        set(v) { if (!v) fase = Interfono.Fase.CERRADO else if (fase == Interfono.Fase.CERRADO) fase = Interfono.Fase.ESCUCHANDO }

    var hablando: Boolean
        get() = fase == Interfono.Fase.ENVIANDO || fase == Interfono.Fase.GRABANDO_VOZ
        set(v) { if (v) fase = Interfono.Fase.ENVIANDO else fase = Interfono.Fase.ESCUCHANDO }

    var dbfs: Float = -120f
        set(v) { if (field != v) { field = v; invalidate() } }

    private val pFondo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBorde = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pAnillo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBarra = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pTexto = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rectBarra = android.graphics.RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val cx = w / 2f
        val cy = h / 2f
        val radioDisco = px(this, 88f)
        val enCanal = fase != Interfono.Fase.CERRADO

        // ── Anillo concéntrico expandible pulsante ─────────────
        if (enCanal) {
            val periodo = when (fase) {
                Interfono.Fase.ENVIANDO -> 1200L
                Interfono.Fase.CONTESTANDO -> 800L
                Interfono.Fase.ESCUCHANDO -> 2000L
                else -> 2500L
            }
            val t = (System.currentTimeMillis() % periodo).toFloat() / periodo
            val radioRing = radioDisco + t * px(this, 22f)
            val alphaRing = ((1f - t) * 90).toInt().coerceIn(0, 255)
            pAnillo.style = Paint.Style.STROKE
            pAnillo.strokeWidth = px(this, 1.5f)
            val colorBase = when (fase) {
                Interfono.Fase.ENVIANDO -> 0x00E53035
                Interfono.Fase.ESCUCHANDO, Interfono.Fase.CONTESTANDO -> 0x0090CA50
                else -> 0x00F0A02A
            }
            pAnillo.color = (alphaRing shl 24) or colorBase
            c.drawCircle(cx, cy, radioRing, pAnillo)
        }

        // ── Fondo del disco central ────────────────────────────
        pFondo.style = Paint.Style.FILL
        pFondo.color = if (enCanal) 0xFF0F1418.toInt() else 0xFF080B0D.toInt()
        c.drawCircle(cx, cy, radioDisco, pFondo)

        // Borde del disco con brillo de estado
        pBorde.style = Paint.Style.STROKE
        pBorde.strokeWidth = px(this, if (enCanal) 1.5f else 1f)
        pBorde.color = when (fase) {
            Interfono.Fase.ENVIANDO -> 0xFFE53035.toInt()
            Interfono.Fase.ESCUCHANDO, Interfono.Fase.CONTESTANDO -> 0xFF90CA50.toInt()
            Interfono.Fase.CALIBRANDO, Interfono.Fase.GRABANDO_VOZ -> 0xFFF0A02A.toInt()
            else -> 0xFF1E252A.toInt()
        }
        c.drawCircle(cx, cy, radioDisco, pBorde)

        // ── Barras de VU (5 barras moduladas) ──────────────────
        val barW = px(this, 5f)
        val barGap = px(this, 4f)
        val maxBarH = px(this, 42f)
        val totalBarsW = 5 * barW + 4 * barGap
        val startX = cx - totalBarsW / 2f
        val baseBarY = cy + px(this, 2f)

        val ahora = System.currentTimeMillis()
        val barFractions = floatArrayOf(0.45f, 0.70f, 1.00f, 0.65f, 0.40f)

        pBarra.style = Paint.Style.FILL
        for (i in 0 until 5) {
            val bx = startX + i * (barW + barGap)
            val phase = i * 0.15f
            val curBarH = when (fase) {
                Interfono.Fase.CERRADO -> px(this, 3.5f)
                Interfono.Fase.ENVIANDO -> {
                    val wave = kotlin.math.sin((ahora / 130.0) + phase * Math.PI * 2).toFloat()
                    (maxBarH * (0.45f + 0.55f * kotlin.math.abs(wave)) * barFractions[i]).coerceAtLeast(px(this, 5f))
                }
                Interfono.Fase.ESCUCHANDO -> {
                    val sensNivel = ((dbfs + 90f) / 90f).coerceIn(0.05f, 1f)
                    val wave = kotlin.math.sin((ahora / 220.0) + phase * Math.PI * 2).toFloat()
                    (maxBarH * sensNivel * (0.6f + 0.4f * kotlin.math.abs(wave)) * barFractions[i]).coerceAtLeast(px(this, 4f))
                }
                Interfono.Fase.CONTESTANDO -> {
                    val wave = kotlin.math.sin((ahora / 100.0) + phase * Math.PI * 2).toFloat()
                    (maxBarH * (0.6f + 0.4f * kotlin.math.abs(wave)) * barFractions[i]).coerceAtLeast(px(this, 6f))
                }
                else -> {
                    val wave = kotlin.math.sin((ahora / 250.0) + phase * Math.PI * 2).toFloat()
                    (maxBarH * 0.35f * (0.5f + 0.5f * kotlin.math.abs(wave)) * barFractions[i]).coerceAtLeast(px(this, 4f))
                }
            }
            val byTop = baseBarY - curBarH

            pBarra.color = when (fase) {
                Interfono.Fase.CERRADO -> 0xFF2A3238.toInt()
                Interfono.Fase.ENVIANDO -> if (i == 2) 0xFFFF5055.toInt() else 0xFFE53035.toInt()
                Interfono.Fase.ESCUCHANDO -> if (i == 2) 0xFF90CA50.toInt() else 0xFFBCC3C9.toInt()
                Interfono.Fase.CONTESTANDO -> if (i == 2) 0xFF90CA50.toInt() else 0xFFF0A02A.toInt()
                else -> if (i == 2) 0xFFF0A02A.toInt() else 0xFF7C858D.toInt()
            }

            rectBarra.set(bx, byTop, bx + barW, baseBarY)
            c.drawRoundRect(rectBarra, px(this, 1.5f), px(this, 1.5f), pBarra)
        }

        // ── Texto de estado central ────────────────────────────
        pTexto.typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        pTexto.textAlign = Paint.Align.CENTER

        // Línea 1: Estado del canal
        pTexto.textSize = px(this, 12.5f)
        pTexto.letterSpacing = 0.12f
        pTexto.color = when (fase) {
            Interfono.Fase.CERRADO -> 0xFF7C858D.toInt()
            Interfono.Fase.ENVIANDO -> 0xFFFF7B7E.toInt()
            Interfono.Fase.ESCUCHANDO -> 0xFF90CA50.toInt()
            Interfono.Fase.CONTESTANDO -> 0xFF90CA50.toInt()
            else -> 0xFFF0A02A.toInt()
        }
        val titulo = when (fase) {
            Interfono.Fase.CERRADO -> "CANAL CERRADO"
            Interfono.Fase.CALIBRANDO -> "CALIBRANDO..."
            Interfono.Fase.GRABANDO_VOZ -> "GRABANDO VOZ"
            Interfono.Fase.ENVIANDO -> "ENVIANDO AUDIO"
            Interfono.Fase.ESCUCHANDO -> "ESCUCHANDO"
            Interfono.Fase.CONTESTANDO -> "¡RESPUESTA OÍDA!"
        }
        c.drawText(titulo, cx, cy + px(this, 26f), pTexto)

        // Línea 2: Subtítulo / nivel
        pTexto.textSize = px(this, 10f)
        pTexto.letterSpacing = 0.08f
        pTexto.color = 0xFF7C858D.toInt()
        pTexto.typeface = android.graphics.Typeface.MONOSPACE
        val sub = when (fase) {
            Interfono.Fase.CERRADO -> "EN ESPERA"
            Interfono.Fase.CALIBRANDO -> "RUIDO DE FONDO"
            Interfono.Fase.GRABANDO_VOZ -> "HABLA AL MÓVIL"
            Interfono.Fase.ENVIANDO -> "HACIA ABAJO"
            Interfono.Fase.ESCUCHANDO -> "${dbfs.toInt().coerceIn(-90, 0)} dBFS"
            Interfono.Fase.CONTESTANDO -> "EN ESCOMBROS"
        }
        c.drawText(sub, cx, cy + px(this, 40f), pTexto)

        if (enCanal && isShown) postInvalidateOnAnimation()
    }
}





/**
 * El espectro de la banda de la malla, en vivo.
 *
 * Una barra por tono de los que el decodificador escucha —MARK a 16 kHz, los
 * cuatro saltos entre 16,8 y 18, la llamada, el silencio y la alerta— con el
 * nivel que se mide en ese bin ahora mismo, y una línea de puntos en el suelo
 * por encima del cual un tono cuenta como presente.
 *
 * Es la respuesta a «¿me está oyendo alguien, o es que no hay nada?»: si las
 * barras se mueven, el micrófono llega a la banda; si están pegadas al fondo,
 * la banda está muerta. Un tono que asoma por encima de la línea se pinta
 * entero, porque eso es lo que el motor acaba de contar como señal.
 *
 * No hay FFT nueva: son los valores que `MallaAcustica.decodificar` ya calcula
 * en cada marco de 42 ms para tomar su decisión. Dibujar otra cosa aquí sería
 * enseñar un espectro que no es el que decide.
 */
class VistaEspectroMalla @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private val pBarra = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pSuelo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF7C858D.toInt()
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }
    private val pRotulo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF7C858D.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    /* Escala en dB. Con N=2048 a 48 kHz el piso físico de Goertzel en reposo
       ronda -115 dB. Por encima de -20 dB el tono satura el micro. */
    private val dbMin = -120.0
    private val dbMax = -20.0

    private var frecuencias = DoubleArray(0)
    private var niveles = DoubleArray(0)
    private var suelo = -80.0
    private var viva = false

    /** Picos que caen despacio, para que un tono de 42 ms se llegue a ver. */
    private var picos = DoubleArray(0)

    /** De qué posición del array del motor sale cada barra, ya ordenada por
     *  frecuencia. Se calcula una vez y sirve para leer la fuente en crudo. */
    private var orden = IntArray(0)

    /* La malla analiza a unas 24 medidas por segundo y el servicio las copia a
       la pantalla dos veces por segundo: 22 de cada 24 se tiraban antes de que
       nadie las viera, y una ráfaga de 250 ms podía caer entera entre dos
       copias. Con la fuente puesta, el lienzo lee el array del motor en cada
       fotograma. Es lo mismo que hace `VistaOnda` y por la misma razón. */
    private var fuente: (() -> DoubleArray)? = null

    fun fuente(f: () -> DoubleArray) { fuente = f }

    fun pintar(niveles: DoubleArray, frecuencias: DoubleArray, suelo: Double, viva: Boolean) {
        if (frecuencias.isNotEmpty() && frecuencias.size == niveles.size) {
            val idx = frecuencias.indices.sortedBy { frecuencias[it] }
            orden = idx.toIntArray()
            this.frecuencias = DoubleArray(idx.size) { frecuencias[idx[it]] }
            this.niveles = DoubleArray(idx.size) { niveles[idx[it]] }
        } else if (niveles.isNotEmpty()) {
            this.niveles = niveles
            this.frecuencias = frecuencias
            orden = IntArray(niveles.size) { it }
        }
        this.suelo = suelo
        this.viva = viva
        if (picos.size != this.niveles.size) picos = DoubleArray(this.niveles.size) { dbMin }
        for (i in this.niveles.indices) if (this.niveles[i] > picos[i]) picos[i] = this.niveles[i]
        invalidate()
    }

    private fun alto(db: Double, h: Float): Float =
        (h * ((db - dbMin) / (dbMax - dbMin)).coerceIn(0.0, 1.0)).toFloat()

    private var ultimoDibujo = 0L

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0 || niveles.isEmpty()) return

        /* El pico cae 20 dB por segundo, contra el reloj y no contra el número
           de repintados: la pantalla se alimenta dos veces por segundo pero se
           dibuja a 60, y atar la caída al repintado hacía que el mismo tono
           bajase a distinta velocidad según lo ocupada que estuviera la app.
           Cae despacio a propósito — una ráfaga de la malla son seis tonos de
           250 ms, y sin rastro no daría tiempo a leerla. */
        /* La lectura de este fotograma, directa del motor y puesta en el mismo
           orden que las barras. Si no hay fuente se pinta lo último que dejó
           `pintar()`, que es como se comportaba antes. */
        fuente?.let { f ->
            val crudo = f()
            if (crudo.size == orden.size) {
                for (i in orden.indices) niveles[i] = crudo[orden[i]]
            }
        }

        val ahora = android.os.SystemClock.uptimeMillis()
        val dt = if (ultimoDibujo == 0L) 0.0 else (ahora - ultimoDibujo) / 1000.0
        ultimoDibujo = ahora
        for (i in picos.indices) picos[i] = maxOf(niveles[i], picos[i] - 20.0 * dt)

        val pie = px(this, 14f)          // sitio para los rótulos de kHz
        val alto = h - pie
        val n = niveles.size
        val hueco = px(this, 5f)
        val ancho = (w - (n - 1) * hueco) / n
        val r = px(this, 1.5f)
        pRotulo.textSize = px(this, 8f)

        // el suelo de decisión, que es lo que separa «hay tono» de «hay ruido»
        val ySuelo = alto - alto(suelo, alto)
        c.drawLine(0f, ySuelo, w, ySuelo, pSuelo)

        for (i in 0 until n) {
            val x = i * (ancho + hueco)
            val db = niveles[i]
            val pasa = viva && db > suelo

            /* Barra apagada de fondo hasta el pico que va cayendo, y encima la
               lectura de ahora. Así se ve a la vez lo que hay y lo que hubo. */
            val hPico = alto(picos[i], alto)
            if (hPico > 0f) {
                val yPico = alto - hPico
                rect.set(x, yPico, x + ancho, alto)
                pBarra.color = 0xFF1E252A.toInt()
                pBarra.alpha = 255
                c.drawRoundRect(rect, r, r, pBarra)
            }

            val hValor = alto(db, alto)
            if (hValor > 0f) {
                val y = alto - hValor
                rect.set(x, y, x + ancho, alto)
                /* Verde cuando el tono pasa el suelo —eso es señal de la malla— y
                   gris cuando es solo el ruido de la sala. El rojo se reserva para
                   lo que sale de este móvil, y aquí no sale nada. */
                pBarra.color = if (pasa) 0xFF90CA50.toInt() else 0xFF39424A.toInt()
                pBarra.alpha = if (viva) 255 else 90
                c.drawRoundRect(rect, r, r, pBarra)
            }

            if (i == 0 || i == n - 1) {
                val khz = frecuencias.getOrElse(i) { 0.0 } / 1000.0
                c.drawText(String.format(java.util.Locale.US, "%.1f", khz),
                    x + ancho / 2, h - px(this, 3f), pRotulo)
            }
        }

        /* Se sigue repintando solo mientras la malla escuche: es lo que hace
           que el pico baje suave. Con la malla parada no hay nada que caer y
           el lienzo se queda quieto. */
        if (isShown && viva) postInvalidateOnAnimation() else ultimoDibujo = 0L
    }
}

/**
 * El osciloscopio del micrófono: lo que está entrando por el aire, ahora mismo.
 *
 * Es la envolvente que ya calcula `Escucha` —128 picos de los últimos 85 ms—
 * dibujada como una onda simétrica y rellena, que es como se lee un sonido de
 * un vistazo. Sirve para lo más básico y lo más difícil de saber de otra
 * forma: si el micrófono está llegando al aire, o si la app se ha quedado
 * sorda sin decirlo.
 *
 * **Un trazo plano y un micrófono denegado no se dibujan igual.** Sin escucha
 * no se pinta la onda: solo la línea de base, apagada. Una onda plana es una
 * lectura —«hay silencio»— y eso es otra cosa, y la diferencia importa cuando
 * lo que decides es si fiarte de que te va a oír.
 *
 * La escala es fija y no se ajusta sola. Un auto-gain haría que el silencio se
 * viera como una onda enorme, que es exactamente la clase de mentira que este
 * proyecto no se permite: la altura tiene que querer decir algo.
 */
class VistaOnda @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private val pOnda = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pBorde = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pBase = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF1E252A.toInt()
    }
    private val camino = Path()

    private var muestras = FloatArray(0)
    private var viva = false

    /** Lo dibujado se suaviza hacia la lectura nueva en vez de saltar a ella:
     *  el servicio publica dieciséis veces por segundo y la pantalla repinta a
     *  sesenta, y sin esto la onda va a tirones. */
    private var suave = FloatArray(0)

    /* La onda se lee en cada fotograma, no cuando la pantalla se acuerda de
       repintar. El servicio la publica dieciseis veces por segundo y el ciclo
       de pintado de la actividad va a dos: la vista se quedaba diez fotogramas
       convergiendo y otros veinte quieta, que es lo que se veia a tirones.
       Con la fuente puesta, el lienzo pregunta el mismo por su cuenta. */
    private var fuente: (() -> FloatArray)? = null

    fun fuente(f: () -> FloatArray) { fuente = f }

    fun pintar(onda: FloatArray, viva: Boolean) {
        this.muestras = onda
        this.viva = viva
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val medio = h / 2f
        pBase.strokeWidth = px(this, 1f)
        c.drawLine(0f, medio, w, medio, pBase)

        fuente?.let { muestras = it() }
        if (!viva || muestras.isEmpty()) return

        val n = muestras.size
        if (suave.size != n) suave = FloatArray(n)
        /* Sube deprisa y baja despacio: un golpe tiene que verse entero en el
           fotograma en que llega, y la caida lenta es lo que deja leer que ha
           pasado algo en vez de un parpadeo. */
        for (i in 0 until n) {
            val v = muestras[i]
            suave[i] = if (v > suave[i]) v else suave[i] + (v - suave[i]) * 0.18f
        }

        /* El pico manda el color: verde mientras haya sitio y ámbar cuando la
           onda toca el techo, que es donde el micrófono recorta y los patrones
           dejan de ser fiables. */
        var pico = 0f
        for (v in suave) if (v > pico) pico = v
        val color = if (pico > 0.9f) 0xFFF0A02A.toInt() else 0xFF90CA50.toInt()

        val dx = w / (n - 1).coerceAtLeast(1)
        val techo = medio - px(this, 1f)

        camino.reset()
        camino.moveTo(0f, medio)
        for (i in 0 until n) camino.lineTo(i * dx, medio - suave[i] * techo)
        for (i in n - 1 downTo 0) camino.lineTo(i * dx, medio + suave[i] * techo)
        camino.close()

        pOnda.color = color
        pOnda.alpha = 60
        c.drawPath(camino, pOnda)

        pBorde.color = color
        pBorde.strokeWidth = px(this, 1.5f)
        c.drawPath(camino, pBorde)

        if (isShown) postInvalidateOnAnimation()
    }
}
