package red.sismo

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.exp
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
 * La traza del sismógrafo, con la línea de umbral a trazos.
 *
 * La escala llega a 6 m/s² y deja 10 px de margen a propósito: sin margen, la
 * línea plana de la calma queda pegada al borde y no se distingue de «esto no
 * está midiendo nada».
 */
class VistaTraza @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) :
    View(ctx, attrs) {

    /** El umbral lo cambia una persona con el paso numérico, no cada marco. */
    var umbral = 3.0

    private val trazo = Path()

    private val pLinea = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.MITER
        strokeCap = Paint.Cap.SQUARE
    }
    private val pUmbral = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val pad = px(this, 6f)
        fun y(v: Double) = h - pad - min(1.0, v / 6.0).toFloat() * (h - pad * 2)

        pUmbral.color = context.getColor(R.color.rd)
        pUmbral.strokeWidth = px(this, 1.5f)
        pUmbral.pathEffect = DashPathEffect(floatArrayOf(px(this, 4f), px(this, 4f)), 0f)
        c.drawLine(0f, y(umbral), w, y(umbral), pUmbral)

        /* Se lee el estado del servicio aquí y no se recibe desde fuera: la
           traza tiene que ir a la velocidad de la pantalla, no a la del refresco
           de los textos, o parece que el acelerómetro va a saltos. */
        val datos = ServicioSos.trazaSismo
        if (datos.size >= 2) {
            pLinea.color = context.getColor(if (ServicioSos.armado) R.color.gr else R.color.ctl)
            pLinea.strokeWidth = px(this, 2f)
            // un único Path: 200 drawLine sueltos cuestan mucho más que un trazo
            trazo.rewind()
            trazo.moveTo(0f, y(datos[0].toDouble()))
            for (i in 1 until datos.size) {
                trazo.lineTo(i * w / (datos.size - 1), y(datos[i].toDouble()))
            }
            c.drawPath(trazo, pLinea)
        }
        if (isShown) postInvalidateOnAnimation()
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
class VistaRadar @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) :
    View(ctx, attrs) {

    var viva = false
    private var porSalto = IntArray(MallaAcustica.MAX_HOP)
    private var tx = 0
    /** Cuándo se emitió cada trama que todavía se está viendo expandirse. */
    private val pulsos = ArrayList<Long>()

    fun pintar(viva: Boolean, porSalto: IntArray, tx: Int) {
        this.viva = viva
        this.porSalto = porSalto
        if (tx != this.tx) { this.tx = tx; pulsos.add(System.currentTimeMillis()) }
        invalidate()
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pTexto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private fun cuadro(c: Canvas, cx: Float, cy: Float, r: Float) {
        c.drawRect(cx - r, cy - r, cx + r, cy + r, p)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val cx = w / 2; val cy = h / 2
        val r0 = min(cx, cy) - px(this, 14f)
        p.style = Paint.Style.STROKE

        val gr = context.getColor(R.color.gr)
        val ctl = context.getColor(R.color.ctl)
        val line = context.getColor(R.color.line)

        // los cuatro anillos, de fuera a dentro
        for (hop in MallaAcustica.MAX_HOP downTo 1) {
            val r = r0 * hop / MallaAcustica.MAX_HOP
            val activo = viva && porSalto[hop - 1] > 0
            p.style = Paint.Style.STROKE
            p.color = if (activo) gr else line
            p.strokeWidth = px(this, if (activo) 2f else 1.5f)
            cuadro(c, cx, cy, r)
            pTexto.color = if (activo) gr else ctl
            pTexto.textSize = px(this, 11f)
            pTexto.isFakeBoldText = true
            c.drawText(hop.toString(), cx, cy - r + px(this, 15f), pTexto)
        }

        // ejes: solo para que el cuadro no parezca una caja vacía
        p.color = line
        p.strokeWidth = px(this, 1f)
        c.drawLine(cx - r0, cy, cx + r0, cy, p)
        c.drawLine(cx, cy - r0, cx, cy + r0, p)
        c.drawLine(cx - r0, cy - r0, cx + r0, cy + r0, p)
        c.drawLine(cx + r0, cy - r0, cx - r0, cy + r0, p)

        // pulsos de emisión propia, expandiéndose hacia fuera
        val now = System.currentTimeMillis()
        pulsos.retainAll { now - it < 2400 }
        val minimo = px(this, 22f)
        for (t in pulsos) {
            val k = (now - t) / 2400f
            p.color = gr
            p.alpha = ((1 - k) * 150).toInt().coerceIn(0, 255)
            p.strokeWidth = px(this, 2f)
            cuadro(c, cx, cy, minimo + k * (r0 - minimo))
        }
        p.alpha = 255

        // un nodo por baliza oída, repartidos por el perímetro de su anillo
        val s = px(this, 6f)
        for (hop in 1..MallaAcustica.MAX_HOP) {
            val n = min(6, porSalto[hop - 1])
            if (n == 0) continue
            val r = r0 * hop / MallaAcustica.MAX_HOP
            val per = 8 * r
            for (i in 0 until n) {
                val d = (((i + 0.5f) / n + hop * 0.09f) % 1f) * per
                val x: Float; val y: Float
                when {
                    d < 2 * r -> { x = cx - r + d; y = cy - r }
                    d < 4 * r -> { x = cx + r; y = cy - r + (d - 2 * r) }
                    d < 6 * r -> { x = cx + r - (d - 4 * r); y = cy + r }
                    else -> { x = cx - r; y = cy + r - (d - 6 * r) }
                }
                p.style = Paint.Style.FILL
                p.color = context.getColor(R.color.bg)
                c.drawRect(x - s, y - s, x + s, y + s, p)
                p.style = Paint.Style.STROKE
                p.color = gr
                p.strokeWidth = px(this, 2.2f)
                c.drawRect(x - s, y - s, x + s, y + s, p)
            }
        }

        // este móvil, en el centro
        val m = px(this, 24f)
        p.style = Paint.Style.FILL
        p.color = if (viva) gr else ctl
        p.alpha = if (viva) 40 else 70
        c.drawRect(cx - m, cy - m, cx + m, cy + m, p)
        p.alpha = 255
        p.style = Paint.Style.STROKE
        p.color = if (viva) gr else ctl
        p.strokeWidth = px(this, 2f)
        c.drawRect(cx - m, cy - m, cx + m, cy + m, p)
        p.style = Paint.Style.FILL
        val a = px(this, 6f); val b = px(this, 10f)
        c.drawRect(cx - a, cy - b, cx + a, cy + b, p)
        p.color = context.getColor(R.color.bg)
        c.drawRect(cx - a + px(this, 2f), cy - b + px(this, 2f), cx + a - px(this, 2f), cy + b - px(this, 2f), p)

        if (isShown && (pulsos.isNotEmpty() || viva)) postInvalidateOnAnimation()
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

    private var completo = ""
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
            pintar()
            // mientras escribe, a ritmo de pantalla; luego, solo el parpadeo
            if (isShown) reloj.postDelayed(this, if (escribiendo) 16L else 520L)
        }
    }

    /** El texto que la consola tiene que acabar mostrando. */
    fun escribir(t: String) {
        if (t == completo) return
        visibles = if (completo.isNotEmpty() && t.startsWith(completo)) visibles else 0
        completo = t
        reloj.removeCallbacks(tic)
        reloj.post(tic)
    }

    private fun pintar() {
        // el cursor va como bloque lleno y hueco para que no salte el alto de la caja
        text = completo.take(visibles) + if (cursor) "\u2588" else "\u2002"
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
