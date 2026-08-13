package red.sismo

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Malla acústica: la alerta salta de móvil a móvil por el aire, sin red.
 *
 * Cada baliza son dos tonos simultáneos: la portadora MARK (16 kHz), presente
 * siempre, y un tono de salto que dice por cuántos móviles ha pasado ya. Quien
 * la oye la reemite con salto+1, hasta 4. Son frecuencias que casi nadie oye
 * pero que cualquier altavoz y cualquier micrófono de móvil manejan bien.
 *
 * Por qué acústico y no BLE: iOS no da Bluetooth al navegador, y en nativo
 * restringe el advertising en segundo plano al área de overflow, que casi solo
 * ven otros iPhone. El infrarrojo ya no existe. Micrófono y altavoz están en
 * todas partes y no piden emparejamiento.
 *
 * Es el port línea a línea del decodificador ya probado en la PWA
 * (SismoRed/app.js), con dos cambios obligados por Android:
 *
 *  - Se decodifica con Goertzel sobre cinco frecuencias conocidas en vez de con
 *    una FFT entera. Es el mismo resultado por una fracción del coste, y aquí
 *    el coste es batería de alguien atrapado.
 *  - La captura va a 48 kHz con la fuente sin procesar. Es el punto que hay que
 *    respetar sí o sí: cancelación de eco, supresión de ruido y control
 *    automático de ganancia borran los tonos de 16-18 kHz. Cualquiera de los
 *    tres deja la malla sorda.
 */
class MallaAcustica(
    private val mic: Microfono,
    private val onConfirmada: (hop: Int) -> Unit,
    /** Alguien está buscando ahí arriba y ha llamado. */
    private val onLlamada: () -> Unit = {},
    private val onRegistro: (String) -> Unit = {}
) {

    companion object {
        /* ---------- protocolo (idéntico al de la PWA: tienen que entenderse) ---------- */
        const val MARK = 16000.0                                       // portadora en toda baliza
        val HOP_TONE = doubleArrayOf(16800.0, 17200.0, 17600.0, 18000.0) // saltos 1..4
        const val MAX_HOP = 4

        /* Un quinto tono, a la misma distancia que los otros, que NO es un salto:
           es la llamada del que busca desde arriba. Va por el mismo canal y el
           mismo decodificador porque es lo único que ya está probado — un tono
           de 18,4 kHz con la misma cadencia de ráfagas y la misma corroboración.
           Lo emite quien busca; quien lo oye contesta con la baliza a tope. */
        const val LLAMADA = 18400.0
        const val CODIGO_LLAMADA = 5
        val TONOS = HOP_TONE + doubleArrayOf(LLAMADA)

        private const val BURST_ON = 0.25       // s de tono
        private const val BURST_OFF = 0.15      // s de silencio
        private const val BURST_N = 6           // trama de ~2,4 s
        const val RELAY_MS = 4000L              // repetición de trama mientras dure la alarma

        /* ---------- análisis ----------
           Ventana de 2048 muestras (42 ms a 48 kHz), que es la que sirve
           Microfono. La resolución sale a 23 Hz, de sobra para separar tonos que
           distan 400. La ventana corta no es por precisión: es para poder contar
           la cadencia de la ráfaga. Con la ventana de 170 ms de la PWA, los
           silencios de 150 ms se emborronan y los cortes dejan de verse. */
        private const val N = Microfono.N

        /* ~10 dB sobre el ruido de fondo. En la PWA eran 30 pasos de la escala de
           bytes del AnalyserNode sobre un rango de 90 dB, que es justo esto. */
        private const val MARGEN_DB = 10.0
        private const val TOL_HZ = 60.0         // deriva de reloj entre móviles distintos

        /* Suelo absoluto, además del relativo. En una habitación en silencio el
           ruido de la banda de 17 kHz se va al fondo de la cuantificación, y
           entonces «10 dB sobre el ruido» lo cumple cualquier cosa. Por debajo de
           esto no hay tono, hay bits sueltos. La PWA lo tenía por la puerta de
           atrás: el AnalyserNode recortaba a -100 dB. */
        private const val SUELO_ABS_DB = -80.0

        /* El salto ganador tiene que despegarse del segundo. Con la sirena local
           a todo volumen, el altavoz satura y la intermodulación deja algo de
           energía en los otros tonos de salto; sin este margen el móvil lee un
           salto que no es. Leerlo de más solo corta la cadena antes de tiempo,
           pero leerlo de menos reinyecta la alerta y la deja dando vueltas.
           Ante la duda no se adivina: se descarta el marco. */
        private const val SEPARACION_DB = 6.0

        /* ---------- corroboración ---------- */
        private const val CORROB_MIN = 3000L
        private const val CORROB_VENTANA = 30000L
        private const val CADENCIA_MIN = 2
        private const val TX_MAX_MIN = 30       // freno de amplificación: 30 emisiones/minuto

        private const val AMP = 0.45            // dos tonos sumados = 0,9; por encima recorta
        private const val TAG = "SismoRed"
    }

    /* ---------- estado observable ---------- */
    @Volatile var escuchando = false; private set
    @Volatile var rx = 0; private set
    @Volatile var tx = 0; private set
    @Volatile var ultimoSalto = 0; private set
    /** Cuántas balizas confirmadas se han oído en cada salto, 1..MAX_HOP. Es lo
     *  que pinta el radar: el anillo 1 es «a tu lado» y el 4 «a cuatro móviles».
     *  Sin contarlo por salto, el radar tendría que inventarse dónde poner cada
     *  nodo, y aquí ningún indicador se inventa nada. */
    val porSalto = IntArray(MAX_HOP)
    /** Mientras esto sea > 0, el micrófono está silenciado por nuestra propia emisión. */
    @Volatile private var puertaHasta = 0L

    /** Cierra esa misma puerta [ms] milisegundos desde ahora. La usa el interfono:
     *  saca voz por el altavoz a todo volumen y la malla no puede tomar eso por
     *  una alerta de otro móvil. */
    fun ensordecer(ms: Long) {
        puertaHasta = maxOf(puertaHasta, System.currentTimeMillis() + ms)
    }

    private val h = Handler(Looper.getMainLooper())
    /** La tasa real la fija el micrófono compartido, no esta clase. */
    private val srRx get() = mic.sr
    private val oyente = Microfono.Oyente { marco ->
        try { procesar(marco) } catch (e: Exception) { Log.e(TAG, "malla decodificar", e) }
    }

    /* ---------- ventana de Hann, precalculada ----------
       Sin ventana, la sirena local (2-4 kHz a todo volumen) se derrama por toda
       la banda y tapa los tonos. Hann la deja caer lo bastante rápido como para
       decodificar mientras el propio móvil está aullando. */
    private val ventana = DoubleArray(N) { 0.5 - 0.5 * cos(2.0 * PI * it / (N - 1)) }

    /* Frecuencias donde se mide el ruido de fondo: la banda 14-19 kHz, saltando
       nuestros propios tonos. Es la referencia contra la que se compara todo. */
    private val sondasRuido: DoubleArray = run {
        val fuera = ArrayList<Double>()
        var f = 14000.0
        while (f <= 19000.0) {
            var cerca = abs(f - MARK) < 250
            for (t in HOP_TONE) if (abs(f - t) < 250) cerca = true
            if (!cerca) fuera.add(f)
            f += 250.0
        }
        fuera.toDoubleArray()
    }

    fun hayPermiso(): Boolean = mic.hayPermiso()

    /* ================= RX ================= */

    /** Devuelve false si no hay permiso o el micrófono no arranca. Nunca lanza. */
    fun escuchar(): Boolean {
        if (escuchando) return true
        if (!hayPermiso()) { reg("la malla necesita el micrófono para oír a otros móviles"); return false }
        return try { arrancarRx(); true } catch (e: Exception) {
            Log.e(TAG, "malla RX falló", e); reg("no he podido encender la malla"); false
        }
    }

    private fun arrancarRx() {
        if (!mic.abrir(Microfono.USA_MALLA)) throw IllegalStateException("el micrófono no abre")

        /* Autocomprobación antes de engancharse al micrófono, no después: así no
           hay un hilo de captura con el que competir por el estado del
           decodificador, y ya se conoce la tasa real que ha dado el sistema. Si
           esto falla, la malla está sorda y hay que decirlo, no seguir
           aparentando que escucha. */
        var sano = true
        for (hop in 1..MAX_HOP) sano = autotest(hop) && sano
        if (!sano) reg("aviso: en este móvil la malla puede oír peor de lo normal")

        mic.registrar(oyente)
        escuchando = true
        Log.i(TAG, "malla activa a $srRx Hz" + if (mic.fuenteCruda) " (fuente cruda)" else "")
        reg("malla encendida: ya puedo recibir avisos de otros móviles")
    }

    fun parar() {
        if (!escuchando) { pararEmision(); return }
        escuchando = false
        mic.quitar(oyente)
        mic.cerrar(Microfono.USA_MALLA)
        pararEmision()
        reg("malla apagada")
    }

    /* ---------- decodificador ---------- */

    /**
     * Magnitud de una frecuencia concreta por Goertzel. Es una FFT de un solo
     * bin: el mismo número que daría la transformada completa, calculando solo
     * lo que se mira.
     */
    private fun magnitud(x: ShortArray, f: Double): Double {
        val w = 2.0 * PI * f / srRx
        val k = 2.0 * cos(w)
        var s1 = 0.0; var s2 = 0.0
        for (i in 0 until N) {
            val s = x[i] / 32768.0 * ventana[i] + k * s1 - s2
            s2 = s1; s1 = s
        }
        val m2 = s1 * s1 + s2 * s2 - k * s1 * s2
        return if (m2 > 0) sqrt(m2) * 2.0 / N else 0.0
    }

    private fun dB(m: Double) = 20.0 * ln(m + 1e-12) / ln(10.0)

    /** Máximo en ±TOL_HZ: dos móviles distintos no tienen el mismo reloj. */
    private fun pico(x: ShortArray, f: Double): Double =
        maxOf(magnitud(x, f - TOL_HZ), magnitud(x, f), magnitud(x, f + TOL_HZ))

    /** Mediana de la banda saltando nuestros tonos: contra qué se compara un pico. */
    private fun ruidoFondo(x: ShortArray): Double {
        val v = DoubleArray(sondasRuido.size) { magnitud(x, sondasRuido[it]) }
        v.sort()
        return v[v.size / 2]
    }

    /* tonoCrudo = ¿suena el tono AHORA MISMO? No es lo mismo que lo que devuelve
       el decodificador: este exige dos marcos y luego se reinicia, así que su
       salida parpadea aunque el tono sea continuo. La cadencia se mide en el
       aire, no en la salida del decodificador. */
    private var tonoCrudo = false
    private var confirma = 0
    private var confirmaHop = 0

    private fun decodificar(x: ShortArray): Int {
        val umbral = maxOf(dB(ruidoFondo(x)) + MARGEN_DB, SUELO_ABS_DB)
        if (dB(pico(x, MARK)) < umbral) { tonoCrudo = false; confirma = 0; return 0 }
        var mejor = 0; var mejorV = -999.0; var segundoV = -999.0
        for (hop in 1..TONOS.size) {
            val v = dB(pico(x, TONOS[hop - 1]))
            if (v > mejorV) { segundoV = mejorV; mejorV = v; mejor = hop }
            else if (v > segundoV) { segundoV = v }
        }
        if (mejorV <= umbral) { tonoCrudo = false; confirma = 0; return 0 }
        /* Hay tono: la cadencia lo cuenta igual. Lo que no hay es un salto
           legible, así que este marco no dice nada. */
        tonoCrudo = true
        if (mejorV - segundoV < SEPARACION_DB) { confirma = 0; return 0 }
        confirma = if (mejor == confirmaHop) confirma + 1 else 1
        confirmaHop = mejor
        if (confirma >= 2) { confirma = 0; return mejor }   // dos marcos seguidos
        return 0
    }

    /* ---------- corroboración: la malla no autentica a nadie ----------
       Con dos tonos desde cualquier altavoz se disparaba la alarma de todos los
       móviles al alcance en 0,2 s, y además se propagaba. No hay forma de firmar
       criptográficamente un canal de cuatro tonos, pero sí de exigir constancia:
       una baliza real se repite cada 4 s mientras dure la emergencia; una broma
       o un pitido suelto, no. Se piden DOS detecciones separadas al menos 3 s y
       que la señal venga troceada en ráfagas. Cuesta unos segundos de retraso y
       quita de en medio el ataque trivial. */
    private val corrob = ArrayList<Pair<Long, Int>>()
    private val txStamps = ArrayList<Long>()
    private var cadencia = 0
    private var habiaTono = false

    /** Una baliza real llega a trozos: 250 ms de tono, 150 de silencio, seis
     *  veces. Un tono continuo de un generador no tiene esa forma. Contar los
     *  cortes obliga a reproducir la trama entera, no solo dos tonos. */
    private fun verCadencia(hayTono: Boolean) {
        if (hayTono != habiaTono) { habiaTono = hayTono; if (hayTono) cadencia++ }
    }

    /**
     * ¿Me creo esta alerta?
     *
     * Normalmente hace falta oírla dos veces, separadas por al menos
     * [CORROB_MIN], y con la cadencia de ráfagas correcta. Esa espera es
     * deliberada: sin ella, cualquiera con un altavoz podría disparar las alarmas
     * de todos los móviles de una calle.
     *
     * **Salvo si aquí está temblando.** Si el acelerómetro de este móvil acaba de
     * notar el terremoto, una sola escucha basta y la alarma salta ya. El motivo
     * es que en un terremoto esos segundos son justo los que no hay — y que quien
     * quisiera falsificar la alerta tendría que estar dentro del mismo terremoto,
     * con lo cual ya no está falsificando nada. La cadencia se sigue exigiendo:
     * eso descarta un ruido cualquiera, no a un impostor.
     */
    private fun corroborada(hop: Int): Boolean {
        val now = System.currentTimeMillis()
        val antes = corrob.size
        corrob.retainAll { now - it.first < CORROB_VENTANA && it.second == hop }
        if (antes > 0 && corrob.isEmpty()) cadencia = 0      // se perdió la pista: a empezar
        corrob.add(now to hop)

        if (ServicioSos.temblando && cadencia >= CADENCIA_MIN) {
            reg("está temblando: me creo la alerta a la primera")
            return true
        }
        return corrob.size >= 2 && (now - corrob[0].first) >= CORROB_MIN && cadencia >= CADENCIA_MIN
    }

    /** Freno de amplificación: ni un bucle ni un atacante pueden hacernos emitir sin fin. */
    private fun puedeEmitir(): Boolean {
        val now = System.currentTimeMillis()
        txStamps.retainAll { now - it < 60000 }
        if (txStamps.size >= TX_MAX_MIN) return false
        txStamps.add(now); return true
    }

    /** Silenciar la alarma local nunca detiene la propagación: solo calla este móvil. */
    @Volatile var silenciadoHasta = 0L

    private fun procesar(marco: ShortArray) {
        if (System.currentTimeMillis() < puertaHasta) return    // no oírse a sí mismo
        val hop = decodificar(marco)
        verCadencia(tonoCrudo)
        if (hop == 0) return
        rx++
        if (!corroborada(hop)) { reg("he oído algo que puede ser una alerta; espero a confirmarlo"); return }
        /* La llamada no es una alerta: es alguien de arriba pidiendo que le
           contesten. No dispara alarmas ni se retransmite — se responde. */
        if (hop == CODIGO_LLAMADA) {
            reg("TE ESTÁN BUSCANDO · alguien ha llamado desde arriba")
            corrob.clear(); cadencia = 0
            h.post { onLlamada() }
            return
        }
        ultimoSalto = hop
        porSalto[hop - 1]++
        reg("ALERTA RECIBIDA de otro móvil" + if (hop > 1) ", a $hop móviles de distancia" else ", justo al lado")
        Log.i(TAG, "malla: baliza confirmada salto=$hop cadencia=$cadencia rx=$rx")
        corrob.clear(); cadencia = 0

        val silenciado = System.currentTimeMillis() < silenciadoHasta
        if (!ServicioSos.enAlarma && !silenciado) {
            // quien dispara la alarma ya reemite con salto+1 en bucle
            h.post { onConfirmada(hop) }
        } else if (hop < MAX_HOP && puedeEmitir()) {
            val jitter = 400L + (Math.random() * 1200).toLong()      // anticolisión
            h.postDelayed({ emitirUna(hop + 1) }, jitter)
            reg("reenviando la alerta para que llegue más lejos")
        }
    }

    /* ================= TX ================= */

    private var relay: Runnable? = null
    @Volatile private var emitiendo = false

    /* El flujo de salida se queda ABIERTO entre ráfagas.
       Crearlo y soltarlo en cada baliza —una cada 4 s mientras dura la alarma—
       hacía que Samsung sacara la barra de volumen del sistema en cada una y
       dejara el móvil inservible justo cuando más falta hacía usarlo: cada vez
       que arranca un flujo con uso de ALARMA, One UI enseña el control. Dejarlo
       abierto cuesta un AudioTrack en silencio y quita el problema entero. */
    private var txTrack: AudioTrack? = null
    private var txSr = 0

    /** Emite ya y sigue emitiendo cada RELAY_MS mientras dure la alarma. */
    /** Cada cuánto se repite la baliza. Se acorta al contestar una llamada. */
    @Volatile var relayMs = RELAY_MS

    fun emitirEnBucle(hop: Int) {
        // solo se cancela el temporizador: el flujo de salida se reaprovecha
        relay?.let { h.removeCallbacks(it) }
        val h0 = hop.coerceIn(1, TONOS.size)
        val tarea = object : Runnable {
            override fun run() { emitirUna(h0); h.postDelayed(this, relayMs) }
        }
        relay = tarea
        h.post(tarea)
    }

    fun pararEmision() {
        relay?.let { h.removeCallbacks(it) }
        relay = null
        cerrarTx()
    }

    /** Suelta el flujo de salida. Solo al dejar de emitir del todo. */
    @Synchronized
    private fun cerrarTx() {
        val t = txTrack ?: return
        txTrack = null
        try { t.pause(); t.flush(); t.release() } catch (_: Exception) {}
    }

    /** Devuelve el flujo de salida, creándolo la primera vez. */
    @Synchronized
    private fun abrirTx(sr: Int, buf: Int): AudioTrack {
        txTrack?.let { if (txSr == sr) return it }
        cerrarTx()
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)          // suena en silencio
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(buf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.setVolume(AudioTrack.getMaxVolume())
        t.play()
        txTrack = t
        txSr = sr
        return t
    }

    /**
     * Una trama: seis ráfagas de MARK + tono de salto. Se sintetiza entera y se
     * escribe de un tirón; a 48 kHz son 2,4 s, medio megabyte de nada.
     */
    fun emitirUna(hop: Int) {
        if (emitiendo) return
        val h0 = hop.coerceIn(1, TONOS.size)
        val sr = 48000
        val nOn = (BURST_ON * sr).toInt()
        val nOff = (BURST_OFF * sr).toInt()
        val total = BURST_N * (nOn + nOff)

        /* La puerta se abre ANTES de emitir, no después: entre que se llena el
           buffer y sale por el altavoz pasa un rato, y en ese rato el hilo de RX
           no puede estar dando la baliza propia por buena. */
        val durMs = (total * 1000L) / sr
        puertaHasta = System.currentTimeMillis() + durMs + 600

        emitiendo = true
        thread(name = "malla-tx", isDaemon = true) {
            try {
                val pcm = ShortArray(total)
                val fHop = TONOS[h0 - 1]
                val rampa = (0.008 * sr).toInt()          // 8 ms; un corte seco se
                                                          // derrama por toda la banda
                for (b in 0 until BURST_N) {
                    val base = b * (nOn + nOff)
                    for (i in 0 until nOn) {
                        val t = i.toDouble() / sr
                        val env = when {
                            i < rampa -> i.toDouble() / rampa
                            i > nOn - rampa -> (nOn - i).toDouble() / rampa
                            else -> 1.0
                        }
                        val s = (sin(2.0 * PI * MARK * t) + sin(2.0 * PI * fHop * t)) * AMP * env
                        pcm[base + i] = (s * Short.MAX_VALUE).toInt().toShort()
                    }
                    // el silencio ya viene a cero
                }

                val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                // en bytes y múltiplo del tamaño de trama (2 bytes en PCM 16 mono),
                // o AudioTrack lo rechaza con "Invalid audio buffer size"
                val buf = ((maxOf(minBuf, sr / 2)) / 2) * 2

                val t = abrirTx(sr, buf)
                var esc = 0
                while (esc < total) {
                    val n = t.write(pcm, esc, minOf(buf / 2, total - esc))
                    if (n <= 0) break
                    esc += n
                }
                // que termine de vaciarse antes de soltar el track
                try { Thread.sleep(300) } catch (_: InterruptedException) {}
                tx++
                Log.i(TAG, "malla: emitida baliza salto=$h0 tx=$tx")
            } catch (e: Exception) {
                Log.e(TAG, "malla TX falló", e)
            } finally {
                // el flujo NO se suelta aquí: se reutiliza en la siguiente ráfaga
                emitiendo = false
                // se reabre con margen por si el altavoz aún resuena
                puertaHasta = maxOf(puertaHasta, System.currentTimeMillis() + 400)
            }
        }
    }

    /* ================= autotest =================
       El mismo del botón de la PWA, y por la misma razón: comprobar que el
       decodificador funciona sin depender de que haya un altavoz, un micrófono
       y otro móvil delante. Se le inyectan las muestras directamente.

       Solo se llama con el hilo de RX parado — desde arrancarRx() antes de
       grabar, o sobre una instancia aparte desde la pantalla. Toca el estado
       del decodificador, así que en paralelo se pisaría con la escucha real. */
    fun autotest(hop: Int): Boolean {
        val h0 = hop.coerceIn(1, MAX_HOP)
        val x = ShortArray(N)
        val fHop = HOP_TONE[h0 - 1]
        for (i in 0 until N) {
            val t = i.toDouble() / srRx
            val s = (sin(2.0 * PI * MARK * t) + sin(2.0 * PI * fHop * t)) * AMP
            x[i] = (s * Short.MAX_VALUE).toInt().toShort()
        }
        confirma = 0; confirmaHop = 0
        decodificar(x)                 // el decodificador exige dos marcos
        val got = decodificar(x)
        confirma = 0; confirmaHop = 0   // que la escucha real empiece limpia
        val ok = got == h0
        // solo al registro tecnico: al usuario se le da una frase entera desde el servicio
        Log.i(TAG, if (ok) "autotest malla OK · salto $h0 -> $got" else "autotest malla FALLA · salto $h0 -> $got")
        return ok
    }

    private fun reg(m: String) {
        Log.i(TAG, "malla: $m")
        h.post { onRegistro(m) }
    }
}
