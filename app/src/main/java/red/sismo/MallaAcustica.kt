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
    /** Alguien de arriba ha pedido silencio en la zona. */
    private val onSilencio: () -> Unit = {},
    /** Otro móvil ha repartido una alerta sísmica entrante por la malla. */
    private val onAlertaSismica: () -> Unit = {},
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

        /* Un sexto tono: SILENCIO. Lo emite quien busca cuando el equipo pide
           silencio en la zona, y todo móvil que lo oiga se calla cinco minutos
           sin dejar de emitir la baliza de radio.

           Por qué hace falta: los equipos de rescate escuchan con micrófonos de
           contacto y geófonos, en la banda baja, la que atraviesa el hormigón, y
           necesitan silencio absoluto en el sitio. Una sirena tapa exactamente lo
           que buscan — golpes flojos y quejidos. Si SismoRed no puede callarse
           cuando se lo piden, deja de ser una ayuda y pasa a ser un estorbo.

           Y va por el canal acústico a propósito, sabiendo lo que NO alcanza: a
           17-18 kHz esto no atraviesa una losa, así que no va a llegar al móvil
           enterrado. Pero el ruido del que hay que callar no es ese —ese ya está
           en modo rescate, un pulso cada 12 s—: son los móviles de la superficie
           y de alrededor, que son muchos y están al aire. A esos sí llega. */
        const val SILENCIO = 18800.0
        const val CODIGO_SILENCIO = 6
        /** Cuánto se calla quien recibe la orden. Cinco minutos es lo que dura
         *  una ventana de escucha con margen; si hace falta más, se repite. */
        const val SILENCIO_ORDEN_MS = 300_000L

        /* Un séptimo tono: ALERTA SÍSMICA ENTRANTE. Lo emite el móvil que ha
           recibido la alerta de Google —o el que ha oído a otro emitirla— y no es
           una baliza de nadie: es «viene un terremoto, tomad medidas».

           Tiene que ser un código propio y no un salto, porque el que lo oiga
           NO debe encender la alarma de víctima. Si viajara como baliza, cada
           móvil que la recibiera se creería que hay alguien enterrado al lado y
           la red se llenaría de alarmas de gente que está perfectamente. Lo que
           hace quien la oye es lo mismo que hizo el primero: avisar, armarse y
           reemitir.

           Y va a 16,4 kHz, por debajo de los saltos y no por encima del silencio,
           que es donde tocaría por orden. El motivo es físico: 19,2 kHz —el
           siguiente hueco hacia arriba— está en el límite de lo que reproduce un
           altavoz de móvil y de lo que capta su micrófono, y este es justo el
           mensaje que más lejos tiene que llegar. Entre MARK y el primer salto
           hay 800 Hz de sitio y ahí cabe con la misma separación que todos los
           demás. El número de código lo da el orden del array, no la frecuencia. */
        const val ALERTA = 16400.0
        const val CODIGO_ALERTA = 7
        /** Cuánto vale una alerta entrante: el tiempo en que puede llegar la
         *  sacudida después del aviso, con margen de sobra. */
        const val ALERTA_VALE_MS = 300_000L

        val TONOS = HOP_TONE + doubleArrayOf(LLAMADA, SILENCIO, ALERTA)

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
        /* MEDIDO EN CAMPO, y por eso subió de 10 a 20: con el móvil quieto en una
           mesa y NINGÚN otro emitiendo, el decodificador dio **866 candidatos y
           27 balizas confirmadas** en una sola sesión, y dos de ellas llegaron a
           lanzar la alarma entera. No era un falso puntual: la malla alucinaba de
           continuo con el ruido ultrasónico de la habitación —cargadores,
           pantallas, focos—, que tiene energía de sobra para pasar 10 dB por
           encima del suelo.

           Una baliza real llega muy por encima de eso: la emite un altavoz a todo
           volumen a pocos metros. Veinte decibelios son cien veces la potencia del
           fondo, y eso el ruido ambiente no lo hace. */
        private const val MARGEN_DB = 20.0
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
        /* De 30 s a 12. Una baliza de verdad se repite cada ~4 s mientras dura la
           emergencia, así que dos detecciones en doce segundos le sobran. Con
           treinta, dos ruidos sin ninguna relación entre sí se emparejaban y
           firmaban una alerta que nunca existió. */
        private const val CORROB_VENTANA = 12000L
        private const val TX_MAX_MIN = 30       // freno de amplificación: 30 emisiones/minuto

        /* ---------- la firma temporal de la trama ----------
           Subir el margen a 20 dB quitó los 866 falsos candidatos por sesión,
           pero era un parche: el protocolo es «portadora más uno de cuatro
           tonos», y cualquier ruido con energía en dos bins lo imita. Lo que de
           verdad separa una baliza del ruido no es el nivel, es la FORMA — 250 ms
           de tono, 150 de silencio, seis veces —, igual que cualquier enlace
           digital se engancha a su palabra de sincronismo y no a su volumen.

           Estos números están MEDIDOS, no estimados, sintetizando la trama de
           emitirUna() y pasándola por este mismo decodificador
           (`fx sounds/cadencia.py`). Y la primera medida desmintió la aritmética
           obvia: los marcos se solapan al 50 %, así que cae uno cada 21,3 ms y no
           cada 42,7, y la ventana de análisis de 42,7 ms desborda los bordes de la
           ráfaga. Por eso una ráfaga de 250 ms se ve de 235 a 363 ms —según la
           reverberación y lo lejos que esté— y el silencio de 150 se ve de 43 a
           171. Poner las ventanas «en 250 ± algo» habría dejado la malla muda.

           | señal                        | ON       | OFF      | periodo  |
           |---                           |---       |---       |---       |
           | al lado                      | 277-299  | 107-128  | 384-405  |
           | lejos (1/800 de amplitud)    | 235-256  | 149-171  | 384-405  |
           | con reverberación fuerte     | 213-363  | 43-64    | 341-406  |

           El PERIODO es el que no se mueve: la reverberación cambia el reparto
           entre tono y silencio, pero no cuándo empieza la ráfaga siguiente. Por
           eso el corte fino va ahí y no en el ciclo de trabajo. */
        private const val RAF_ON_MIN_MS = 200L
        private const val RAF_ON_MAX_MS = 400L
        private const val RAF_OFF_MIN_MS = 40L
        private const val RAF_OFF_MAX_MS = 220L
        private const val RAF_PER_MIN_MS = 340L
        private const val RAF_PER_MAX_MS = 460L
        /** Dos marcos. Entre ráfaga y ráfaga el periodo real no se mueve más. */
        private const val RAF_PER_JITTER_MS = 45L

        /* CUATRO ráfagas seguidas, de las seis que trae la trama. Medido contra
           ruido que parpadea a todas las velocidades posibles (`rechazo.py`),
           una hora de escucha por caso:

           | regla                        | peor caso  |
           |---                           |---         |
           | dos flancos de subida (antes)| SIEMPRE    |
           | tres ráfagas seguidas        | 19/hora    |
           | cuatro ráfagas seguidas      | 2/hora     |
           | cinco ráfagas seguidas       | 0/hora     |

           Cinco tienta, y es justo lo que no se puede hacer: la trama trae seis,
           así que pedir cinco es no dejar margen para perder una — y quien
           escucha se engancha a mitad de trama constantemente. Con cuatro se
           pueden perder dos ráfagas y la baliza sigue llegando. Es la misma
           lección que dejó escrita subir el decodificador de dos marcos a tres:
           un umbral que no deja margen no cambia un fallo por un acierto, lo
           cambia por un fallo mudo. */
        private const val RAFAGAS_MIN = 4

        private const val AMP = 0.45            // dos tonos sumados = 0,9; por encima recorta
        private const val TAG = "SismoRed"
    }

    /* ---------- estado observable ---------- */
    /* ---------- lo que se está oyendo ahora mismo ----------
       Nivel en dB de cada tono de la malla —MARK primero y detrás los de
       `TONOS`— y el suelo por encima del cual un tono cuenta. Lo rellena
       `decodificar` en cada marco, o sea cada 42 ms.

       Existe porque la pantalla de la malla enseñaba una banda dibujada con un
       «17,4 kHz» que no es la portadora de nada: el espectro estaba medido en
       el motor desde el principio y no se sacaba a ninguna parte. */
    val niveles = DoubleArray(1 + TONOS.size) { -120.0 }
    @Volatile var sueloDb = SUELO_ABS_DB; private set

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
        val vMark = dB(pico(x, MARK))
        /* El espectro que ve la pantalla. No es un adorno ni una FFT aparte:
           son exactamente los valores con los que este decodificador acaba de
           decidir, medidos en los bins de los tonos que la malla escucha. Se
           publica siempre, incluso en los marcos que se descartan, porque
           «aquí no hay nada» también es una lectura y es la que más se ve. */
        niveles[0] = vMark
        sueloDb = umbral
        if (vMark < umbral) {
            for (i in TONOS.indices) niveles[i + 1] = dB(pico(x, TONOS[i]))
            tonoCrudo = false; confirma = 0; return 0
        }
        var mejor = 0; var mejorV = -999.0; var segundoV = -999.0
        for (hop in 1..TONOS.size) {
            val v = dB(pico(x, TONOS[hop - 1]))
            niveles[hop] = v
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
        /* DOS marcos, no tres. Se probó con tres y el autotest lo cazó: el
           decodificador tiene un contrato de dos marcos con todo lo que lo usa
           —el autotest los inyecta de dos en dos— y subirlo dejó la malla sorda
           en los cuatro saltos. La protección contra el ruido no puede venir de
           aquí. */
        if (confirma >= 2) { confirma = 0; return mejor }
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

    /* Estado de la firma temporal. `cadencia` ya no cuenta flancos de subida
       —eso lo cumplía cualquier parpadeo del ruido—: cuenta ráfagas SEGUIDAS que
       tienen la forma y el periodo de la trama. */
    private var cadencia = 0
    private var habiaTono = false
    private var marcosRacha = 0             // longitud de la racha en curso
    private var msOffPrevio = 0L            // duración del último silencio cerrado
    private var offPrevioOk = false
    private var msPeriodoPrevio = 0L        // 0 = aún no hay periodo con el que comparar

    /** Cuánto dura en ms una racha de [marcos] marcos de análisis. Los marcos se
     *  solapan al 50 %, así que cae uno cada SALTO muestras y no cada N: es la
     *  cuenta que hay que hacer con Microfono delante, no de memoria. */
    private fun msDe(marcos: Int): Long = marcos.toLong() * Microfono.SALTO * 1000L / srRx

    /** Se pierde el tren de ráfagas que se estaba siguiendo. */
    private fun olvidarCadencia() { cadencia = 0; msPeriodoPrevio = 0L }

    /** Hemos dejado de oír por nuestra propia culpa —emisión propia, interfono—,
     *  así que la racha en curso está partida y juzgarla sería inventar. Se
     *  empieza de cero cuando vuelva a haber micrófono. */
    private fun perderSincronismo() {
        marcosRacha = 0; habiaTono = false
        offPrevioOk = false
        olvidarCadencia()
    }

    /**
     * ¿Esto tiene la forma de una baliza?
     *
     * Una trama real son 250 ms de tono y 150 de silencio, seis veces. Antes
     * aquí solo se contaban los flancos de subida, y eso lo cumple cualquier
     * ruido que parpadee dos veces. Ahora se mide cada racha y se exigen
     * [RAFAGAS_MIN] seguidas con el ON, el OFF y sobre todo el PERIODO en su
     * sitio — y que el periodo no se mueva de una a la siguiente, que es lo que
     * un ruido no puede fingir y una baliza no puede evitar.
     *
     * De regalo quita el ataque trivial de disparar la red entera reproduciendo
     * un tono: un tono no tiene ráfagas.
     */
    private fun verCadencia(hayTono: Boolean) {
        if (hayTono == habiaTono) {
            marcosRacha++
            /* Un tono que no se acaba nunca no es una baliza: es un generador, o
               alguien reproduciendo un tono. No hay que esperar a que la racha
               cierre para saberlo, y esperar dejaría viva una cadencia vieja. */
            if (hayTono && msDe(marcosRacha) > RAF_ON_MAX_MS) olvidarCadencia()
            return
        }
        val ms = msDe(marcosRacha)
        habiaTono = hayTono
        marcosRacha = 1
        if (hayTono) {
            // se cerró un silencio: se guarda para medir el periodo de la ráfaga que viene
            msOffPrevio = ms
            offPrevioOk = ms in RAF_OFF_MIN_MS..RAF_OFF_MAX_MS
            return
        }
        // se cerró una ráfaga de tono: es aquí donde se juzga
        if (ms !in RAF_ON_MIN_MS..RAF_ON_MAX_MS) { olvidarCadencia(); return }
        val periodo = msOffPrevio + ms
        val sigueElTren = offPrevioOk &&
            periodo in RAF_PER_MIN_MS..RAF_PER_MAX_MS &&
            (msPeriodoPrevio == 0L || abs(periodo - msPeriodoPrevio) <= RAF_PER_JITTER_MS)
        if (sigueElTren) { cadencia++; msPeriodoPrevio = periodo }
        else { cadencia = 1; msPeriodoPrevio = 0L }     // la primera ráfaga de un tren
    }

    /**
     * ¿Me creo esta alerta?
     *
     * Normalmente hace falta oírla dos veces, separadas por al menos
     * [CORROB_MIN], y con la firma temporal de la trama completa —[RAFAGAS_MIN]
     * ráfagas seguidas con su periodo, ver [verCadencia]—. Esa espera es
     * deliberada: sin ella, cualquiera con un altavoz podría disparar las alarmas
     * de todos los móviles de una calle.
     *
     * Lo que cuesta exigir la firma entera: la confirmación llega en la quinta
     * ráfaga y no en la segunda, o sea **1,7 s de trama en vez de 0,5**. Se paga
     * a gusto, porque lo que compra es que el ruido de una habitación deje de
     * poder firmar una alerta que nadie ha emitido.
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
        if (antes > 0 && corrob.isEmpty()) olvidarCadencia()      // se perdió la pista: a empezar
        corrob.add(now to hop)

        if (ServicioSos.temblando && cadencia >= RAFAGAS_MIN) {
            reg("está temblando: me creo la alerta a la primera")
            return true
        }
        return corrob.size >= 2 && (now - corrob[0].first) >= CORROB_MIN && cadencia >= RAFAGAS_MIN
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

    /** Para no llenar el registro: durante una trama el decodificador saca el
     *  salto una docena de veces, y el registro es la prueba de lo que pasó. */
    private var ultimoAvisoCandidato = 0L

    private fun procesar(marco: ShortArray) {
        // no oírse a sí mismo. La racha en curso queda partida: no se juzga.
        if (System.currentTimeMillis() < puertaHasta) { perderSincronismo(); return }
        val hop = decodificar(marco)
        verCadencia(tonoCrudo)
        if (hop == 0) return
        rx++
        if (!corroborada(hop)) {
            val now = System.currentTimeMillis()
            if (now - ultimoAvisoCandidato > RELAY_MS) {
                ultimoAvisoCandidato = now
                reg("he oído algo que puede ser una alerta; espero a confirmarlo")
            }
            return
        }
        /* La llamada no es una alerta: es alguien de arriba pidiendo que le
           contesten. No dispara alarmas ni se retransmite — se responde. */
        /* La orden de callar. No es una alerta, no se retransmite como tal y no
           enciende nada: apaga. Se reemite una vez para que corra por la zona,
           porque el que la manda no alcanza a todos. */
        if (hop == CODIGO_SILENCIO) {
            reg("SILENCIO PEDIDO desde arriba · me callo 5 minutos")
            corrob.clear(); olvidarCadencia()
            silenciadoHasta = System.currentTimeMillis() + SILENCIO_ORDEN_MS
            h.post { onSilencio() }
            if (puedeEmitir()) {
                val jitter = 600L + (Math.random() * 1500).toLong()
                h.postDelayed({ emitirUna(CODIGO_SILENCIO) }, jitter)
            }
            return
        }
        /* La alerta sísmica entrante. No es una baliza y no puede tratarse como
           un salto: quien la oye avisa, se arma y la reemite una vez, pero no
           enciende ninguna alarma de víctima. Va antes que nada porque un código
           que no sea un salto NUNCA puede caer en el reparto de abajo —`porSalto`
           tiene cuatro huecos y esto es el siete—. */
        if (hop == CODIGO_ALERTA) {
            reg("ALERTA SÍSMICA recibida por la malla · viene un terremoto")
            corrob.clear(); olvidarCadencia()
            h.post { onAlertaSismica() }
            if (puedeEmitir()) {
                val jitter = 500L + (Math.random() * 1500).toLong()
                h.postDelayed({ emitirUna(CODIGO_ALERTA) }, jitter)
            }
            return
        }
        if (hop == CODIGO_LLAMADA) {
            reg("TE ESTÁN BUSCANDO · alguien ha llamado desde arriba")
            corrob.clear(); olvidarCadencia()
            h.post { onLlamada() }
            return
        }
        ultimoSalto = hop
        porSalto[hop - 1]++
        reg("ALERTA RECIBIDA de otro móvil" + if (hop > 1) ", a $hop móviles de distancia" else ", justo al lado")
        Log.i(TAG, "malla: baliza confirmada salto=$hop cadencia=$cadencia rx=$rx")
        corrob.clear(); olvidarCadencia()

        val silenciado = System.currentTimeMillis() < silenciadoHasta
        if (!ServicioSos.enAlarma && !silenciado) {
            // quien dispara la alarma ya reemite con salto+1 en bucle
            h.post { onConfirmada(hop) }
        } else {
            reenviar(hop)
        }
    }

    /**
     * Pasar la alerta al siguiente salto sin sonar.
     *
     * Estaba metido dentro del `else` de arriba, y por eso **el que buscaba
     * rompía la cadena**: su móvil no está en alarma, así que entraba por la
     * primera rama, anotaba «no sueno porque estás buscando» y ahí se acababa el
     * viaje de la alerta — aunque el comentario dijera que se seguía
     * retransmitiendo. Ahora es una función, y la usan los tres que oyen sin
     * gritar: el silenciado, el que busca y el que ya ha contestado que está
     * bien.
     */
    fun reenviar(hop: Int): Boolean {
        if (hop >= MAX_HOP || !puedeEmitir()) return false
        val jitter = 400L + (Math.random() * 1200).toLong()          // anticolisión
        h.postDelayed({ emitirUna(hop + 1) }, jitter)
        reg("reenviando la alerta para que llegue más lejos")
        return true
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
        /* Los SEIS códigos, no los cuatro saltos. Estaba recortado a MAX_HOP y
           por eso la llamada y el silencio salían como «salto 4»: se probaban
           cuatro veces las mismas cuatro frecuencias y las dos de arriba —18,4 y
           18,8 kHz, las más difíciles de reproducir y de oír— no se probaban
           nunca. */
        val h0 = hop.coerceIn(1, TONOS.size)
        val x = ShortArray(N)
        val fHop = TONOS[h0 - 1]
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

    /* ---------- autotest de la firma temporal ----------
       El de arriba inyecta dos marcos iguales y comprueba el TONO. Este comprueba
       la FORMA, y para eso no valen dos marcos: hace falta la trama entera, con
       sus silencios y con el mismo solape del 50 % que da el micrófono.

       Existe porque el fallo que hay que cazar aquí es mudo. Una ventana mal
       puesta no da error, no se ve usando la app y no la nota nadie: la malla
       simplemente deja de oír, y eso solo se descubre el día que hacía falta.
       Ya pasó una vez —subir el decodificador de dos marcos a tres dejó los
       cuatro saltos sordos— y lo cazó el autotest, no el uso.

       Tarda unas décimas: son 3,2 s de audio a 21 ms por marco, dos veces. */

    private fun muestra(v: Double): Short =
        (v.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()

    /** La misma trama que emite [emitirUna], con silencio y ruido de sala a los
     *  lados; o el mismo tono sin cortes, que es lo que hay que rechazar. */
    private fun tramaDePrueba(hop: Int, continuo: Boolean, ruido: Double, semilla: Long): ShortArray {
        val sr = srRx
        val nOn = (BURST_ON * sr).toInt()
        val nOff = (BURST_OFF * sr).toInt()
        val rampa = (0.008 * sr).toInt()
        val pad = (0.4 * sr).toInt()
        val cuerpo = BURST_N * (nOn + nOff)
        val pcm = ShortArray(pad + cuerpo + pad)
        val fHop = TONOS[hop - 1]
        if (continuo) {
            for (i in 0 until cuerpo) {
                val t = i.toDouble() / sr
                pcm[pad + i] = muestra((sin(2.0 * PI * MARK * t) + sin(2.0 * PI * fHop * t)) * AMP)
            }
        } else {
            for (b in 0 until BURST_N) {
                val base = pad + b * (nOn + nOff)
                for (i in 0 until nOn) {
                    val t = i.toDouble() / sr
                    val env = when {
                        i < rampa -> i.toDouble() / rampa
                        i > nOn - rampa -> (nOn - i).toDouble() / rampa
                        else -> 1.0
                    }
                    pcm[base + i] = muestra((sin(2.0 * PI * MARK * t) + sin(2.0 * PI * fHop * t)) * AMP * env)
                }
            }
        }
        /* Semilla fija: una regresión que depende del sorteo no es una regresión.
           Y con ruido, no en silencio absoluto: sin fondo, el umbral se cae al
           suelo absoluto y la prueba deja de parecerse a una habitación. */
        if (ruido > 0.0) {
            val rnd = java.util.Random(semilla)
            for (i in pcm.indices) pcm[i] = muestra(pcm[i] / 32768.0 + rnd.nextGaussian() * ruido)
        }
        return pcm
    }

    /** Deja el decodificador y la firma temporal como recién arrancados. */
    private fun limpiarDecodificador() {
        confirma = 0; confirmaHop = 0; tonoCrudo = false
        habiaTono = false; marcosRacha = 0
        msOffPrevio = 0L; offPrevioOk = false
        olvidarCadencia()
    }

    /** Pasa [pcm] por el decodificador marco a marco y devuelve la cadencia
     *  máxima alcanzada y el último salto legible. */
    private fun correrFirma(pcm: ShortArray): Pair<Int, Int> {
        val x = ShortArray(N)
        var maxCad = 0; var hopVisto = 0; var k = 0
        while (k + N <= pcm.size) {
            System.arraycopy(pcm, k, x, 0, N)
            val hop = decodificar(x)
            verCadencia(tonoCrudo)
            if (hop != 0) hopVisto = hop
            if (cadencia > maxCad) maxCad = cadencia
            k += Microfono.SALTO
        }
        return maxCad to hopVisto
    }

    /**
     * ¿Sigue oyendo la baliza de verdad, y sigue sin creerse un tono?
     *
     * Las dos mitades importan, y la primera más: cualquiera puede endurecer un
     * umbral hasta que no pase nada. Se corre con el hilo de RX parado o sobre
     * una instancia aparte, igual que [autotest].
     */
    fun autotestCadencia(): Pair<Boolean, String> {
        var cadBaliza = -1; var hopBaliza = -1; var cadTono = -1
        try {
            limpiarDecodificador()
            correrFirma(tramaDePrueba(1, continuo = false, ruido = 0.001, semilla = 7L)).let {
                cadBaliza = it.first; hopBaliza = it.second
            }
            limpiarDecodificador()
            cadTono = correrFirma(tramaDePrueba(1, continuo = true, ruido = 0.001, semilla = 9L)).first
        } catch (e: Exception) {
            Log.e(TAG, "autotest cadencia", e)
            return false to "la firma temporal no se ha podido comprobar: ${e.message}"
        } finally {
            limpiarDecodificador()
        }
        val fallos = ArrayList<String>()
        if (hopBaliza != 1) fallos.add("no lee el salto de una baliza real (dio $hopBaliza)")
        if (cadBaliza < RAFAGAS_MIN) fallos.add("una baliza real solo da $cadBaliza ráfagas y hacen falta $RAFAGAS_MIN: la malla está SORDA")
        if (cadTono >= RAFAGAS_MIN) fallos.add("un tono continuo pasa por baliza ($cadTono ráfagas)")
        val txt =
            if (fallos.isEmpty()) "firma temporal: la baliza da $cadBaliza ráfagas de $RAFAGAS_MIN y un tono continuo $cadTono"
            else "firma temporal · " + fallos.joinToString(" · ")
        Log.i(TAG, if (fallos.isEmpty()) "autotest cadencia OK · $txt" else "autotest cadencia FALLA · $txt")
        return fallos.isEmpty() to txt
    }

    private fun reg(m: String) {
        Log.i(TAG, "malla: $m")
        h.post { onRegistro(m) }
    }
}
