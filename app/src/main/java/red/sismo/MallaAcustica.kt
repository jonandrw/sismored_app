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

        /* AUD-03: Tonos robustos sub-17.5 kHz para hardware de gama baja (Samsung Galaxy A10s).
           Los altavoces miniatura de smartphones económicos sufren una caída drástica de respuesta
           en frecuencia (25 a 40 dB de atenuación) a 18.4 - 18.8 kHz.
           Trasladamos el silencio y la llamada a frecuencias con excelente respuesta acústica
           (15.6 kHz y 15.2 kHz) manteniendo compatibilidad con los tonos legacy. */
        const val SILENCIO_ROBUSTO = 15600.0
        const val CODIGO_SILENCIO_ROBUSTO = 8

        const val LLAMADA_ROBUSTA = 15200.0
        const val CODIGO_LLAMADA_ROBUSTA = 9

        /**
         * La alerta, en la banda que sí sale por un altavoz barato.
         *
         * El silencio y la llamada tuvieron su version robusta y la alerta se
         * quedo sola arriba, en 16,4 kHz — justo el mensaje que mas lejos tiene
         * que llegar. Medido tres veces entre el Redmi y un Huawei STK-LX3 con
         * los dos juntos en la mesa: del Redmi al Huawei llegan 47 ecos, del
         * Huawei al Redmi uno o ninguno. El usuario OYE el pitido del Huawei,
         * asi que el altavoz emite algo; lo que no emite es la banda alta, y lo
         * que se oye es distorsion mas grave.
         *
         * 14,8 kHz: el siguiente hueco libre por debajo de [LLAMADA_ROBUSTA],
         * con la misma separacion de 400 Hz que el resto del plan.
         */
        const val ALERTA_ROBUSTA = 14800.0
        const val CODIGO_ALERTA_ROBUSTA = 10

        /**
         * «Te he oído». Lo contesta quien recibe una baliza de socorro y **no**
         * está pidiendo ayuda él mismo.
         *
         * Es el único mensaje de la malla que va hacia la víctima, y
         * probablemente lo más útil que un móvil puede hacer por alguien
         * atrapado cuando ya no queda nada más: la diferencia entre gritar al
         * vacío y saber que alguien lo sabe.
         *
         * A 14,4 kHz, un paso por debajo de [ALERTA_ROBUSTA], siguiendo el
         * mismo criterio con el que se bajaron el silencio y la llamada:
         * cuanto más abajo, mejor responde el altavoz y mejor atraviesa. El
         * precio es que a 14,4 kHz **hay oídos jóvenes que lo oyen**, y por eso
         * no se emite salvo que haya una víctima de verdad pidiendo ayuda.
         *
         * No es un salto, así que nunca puede caer en el reparto de `porSalto`,
         * que tiene cuatro huecos.
         */
        const val OIDO = 14400.0
        const val CODIGO_OIDO = 11

        /** Cada cuánto se anota «te han oído» en el registro. La baliza se
         *  repite cada 8 s y cada repetición se contesta, así que sin esto el
         *  registro de la víctima sería una sola línea repetida. */
        const val OIDO_ANOTA_MS = 30_000L

        val TONOS = HOP_TONE + doubleArrayOf(
            LLAMADA, SILENCIO, ALERTA, SILENCIO_ROBUSTO, LLAMADA_ROBUSTA, ALERTA_ROBUSTA, OIDO
        )

        /**
         * AUD-03: Síntesis de pulso Chirp CSS (Chirp Spread Spectrum) de penetración en escombros.
         * Realiza una modulación angular lineal de f0 a f1 (2.2 kHz a 3.2 kHz por defecto)
         * para difracción y propagación a través de huecos en derrumbes sin desvanecimiento multicamino.
         */
        fun sintetizarChirp(
            sr: Int = 48000,
            duracionS: Double = 0.20,
            f0: Double = 2200.0,
            f1: Double = 3200.0,
            amplitud: Double = 0.95
        ): ShortArray {
            val n = (sr * duracionS).toInt()
            val out = ShortArray(n)
            val rampa = (0.005 * sr).toInt()
            val k = (f1 - f0) / (2.0 * duracionS)
            for (i in 0 until n) {
                val t = i.toDouble() / sr
                val env = when {
                    i < rampa -> i.toDouble() / rampa
                    i > n - rampa -> (n - i).toDouble() / rampa
                    else -> 1.0
                }
                val fase = 2.0 * PI * (f0 * t + k * t * t)
                val s = sin(fase) * amplitud * env
                out[i] = (s * Short.MAX_VALUE).toInt().toShort()
            }
            return out
        }

        private const val BURST_ON = 0.25       // s de tono
        private const val BURST_OFF = 0.15      // s de silencio
        /**
         * Rafagas por trama.
         *
         * **Seis no dejaban margen.** Confirmar exige cuatro comienzos
         * seguidos a periodo estable, asi que con seis bastaba que dos
         * salieran mal para no llegar. Medido el 17 de septiembre de 2026 a
         * mas de veinte metros: el receptor oia 29 ecos, de sobra, y se
         * quedaba clavado en `cadencia=3/4`.
         *
         * Con diez, la trama dura 4 s y hay que acertar cuatro de diez en vez
         * de cuatro de seis. No mejora el alcance —el tono llega igual de
         * lejos— sino la probabilidad de enganchar en un enlace justo, que es
         * justo el caso del altavoz debil.
         */
        private const val BURST_N = 10          // trama de ~4 s
        /**
         * Cada cuánto se repite la trama mientras dura la alarma.
         *
         * **Estaba en 4000 y dejaba sordo al que retransmite.** La cuenta: una
         * trama son seis ráfagas de 400 ms = 2,4 s, y la puerta que evita oírse
         * a sí mismo dura 600 ms más, o sea 3,0 s sin micrófono. Repitiendo cada
         * 4,0 s quedaba **1,0 s de escucha**, y confirmar exige [RAFAGAS_MIN]
         * ráfagas seguidas = 1,6 s de escucha CONTINUA, porque la puerta llama a
         * `perderSincronismo()` y la racha empieza de cero cada vez.
         *
         * 1,0 < 1,6: no era mala suerte ni cuestión de fase, era imposible. Dos
         * móviles con la alarma puesta uno al lado del otro no se oían nunca.
         * Medido el 9 de septiembre de 2026 entre el Huawei y el Redmi: la
         * primera alerta cruzó en 3,9 s —el receptor aún no emitía— y a partir
         * de ahí ninguna.
         *
         * Con 6,0 s quedan 3,0 s de escucha, casi el doble de lo que hace falta.
         * Un relevo más lento que funciona vale más que uno rápido y sordo.
         */
        /* SUBIDO A 12 s PARA QUE A LA VÍCTIMA SE LE PUEDA CONTESTAR.

           Con 8 s no cabía. La trama dura 4 s y mientras emite, la puerta
           anti-eco deja sordo el micrófono propio, así que quedaban ~4,5 s de
           escucha — y en ellos tienen que caber el reenvío del vecino Y el
           acuse de [OIDO], que son otras dos tramas de 4 s. Medido el 18 de
           septiembre de 2026: el Huawei contestó tres veces y el Redmi no oyó
           ninguna, porque los dos acuses cayeron justo encima de una emisión
           suya. Los dos móviles se enganchan al mismo ciclo y colisionan.

           Con 12 s quedan 8 s de escucha, que es sitio para las dos. El precio
           es que la baliza se repite menos, y el argumento para pagarlo ya
           estaba escrito aquí arriba: un relevo más lento que funciona vale
           más que uno rápido y sordo. Además la baliza dura mientras dure la
           alarma, así que repetir cada 12 s en vez de cada 8 no pierde a nadie
           — y gasta menos batería, que debajo de un escombro es tiempo. */
        const val RELAY_MS = 12000L

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
        /* 180 y no 200: la rafaga dura 250 ms pero la histeresis la da por
           acabada en cuanto empieza a caer, asi que se mide algo corta aunque
           el antirrebote ya este compensado. Lo que sujeta la defensa no es
           este margen sino el PERIODO —340 a 460 ms— y que no se mueva de una
           rafaga a la siguiente. */
        private const val RAF_ON_MIN_MS = 180L
        private const val RAF_ON_MAX_MS = 400L
        private const val RAF_OFF_MIN_MS = 40L
        private const val RAF_OFF_MAX_MS = 220L
        private const val RAF_PER_MIN_MS = 340L
        private const val RAF_PER_MAX_MS = 460L
        /** Dos marcos. Entre ráfaga y ráfaga el periodo real no se mueve más. */
        /**
         * Cuánto puede moverse el periodo de una ráfaga a la siguiente.
         *
         * **Estaba en 45 ms, que es el ruido de la propia medida.** El
         * decodificador avanza de marco en marco, y un marco son
         * `SALTO/sr` = 21,3 ms. El periodo se mide sumando el OFF y el ON, cada
         * uno cuantizado a ese paso, así que dos ráfagas idénticas pueden
         * medirse con hasta **±43 ms** de diferencia sin que nada vaya mal.
         * Con el listón en 45 el margen era de 2 ms: cualquier marco perdido
         * rompía el tren, `cadencia` volvía a 1 y una trama de seis ráfagas ya
         * no daba las cuatro seguidas que se exigen.
         *
         * Medido el 9 de septiembre de 2026 entre el Redmi y el Huawei: el
         * receptor acumulaba 47 ecos con 12 s de span —de sobra para las otras
         * dos condiciones— y se quedaba en `cadencia=0/4`.
         *
         * 90 ms son cuatro marcos, el doble del error de cuantización. No
         * afloja la defensa: el periodo tiene que seguir cayendo dentro de
         * [RAF_PER_MIN_MS]..[RAF_PER_MAX_MS], que es una ventana de 120 ms, y
         * ese rango es el que descarta al ruido.
         */
        private const val RAF_PER_JITTER_MS = 90L

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

        /**
         * Amplitud de una rafaga con UN solo tono.
         *
         * Cuando la portadora y el tono van a la vez tienen que repartirse la
         * escala —0,45 cada uno— porque sumados llegan a 0,9 y por encima
         * recorta. Yendo por separado, cada uno se lleva la escala entera: son
         * **+6 dB**, mas que todo lo demas que se ha hecho hoy junto.
         */
        private const val AMP_SOLO = 0.95

        /** Cuanto vale una portadora oida. Las rafagas van cada 400 ms y la
         *  portadora alterna con el tono, asi que 1,5 s cubre de sobra. */
        private const val MARK_VALE_MS = 1500L
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
    /** Candidatos: todo lo que el decodificador ha leido como un salto, esten
     *  corroborados o no. Sirve para diagnostico, NO para avisar a nadie. */
    @Volatile var rx = 0; private set

    /** Las que han pasado la corroboracion, o sea las que la app se cree.
     *  Existe porque `rx` cuenta candidatos y la notificacion usaba `rx`:
     *  anunciaba «alerta de la malla» al mismo tiempo que el registro decia
     *  «he oido algo que puede ser una alerta; espero a confirmarlo». */
    @Volatile var confirmadas = 0; private set
    @Volatile var tx = 0; private set
    @Volatile var ultimoSalto = 0; private set
    /** Cuántas balizas confirmadas se han oído en cada salto, 1..MAX_HOP. Es lo
     *  que pinta el radar: el anillo 1 es «a tu lado» y el 4 «a cuatro móviles».
     *  Sin contarlo por salto, el radar tendría que inventarse dónde poner cada
     *  nodo, y aquí ningún indicador se inventa nada. */
    val porSalto = IntArray(MAX_HOP)
    /** Mientras esto sea > 0, el micrófono está silenciado por nuestra propia emisión. */
    @Volatile private var puertaHasta = 0L

    /**
     * El altavoz de este móvil está sacando algo AHORA: una baliza, un reenvío
     * o lo que haya pedido [ensordecer].
     *
     * Existe para el acelerómetro, no para el micrófono. Al micrófono ya lo
     * protege [puertaHasta]; al sismógrafo no lo protegía nadie, y una baliza
     * son cuatro segundos de tono a todo volumen por el mismo chasis en el que
     * está el sensor. Ver `Sismografo.vibracionPropia`.
     */
    val emitiendoAhora: Boolean get() = System.currentTimeMillis() < puertaHasta

    /** Cierra esa misma puerta [ms] milisegundos desde ahora. La usa el interfono:
     *  saca voz por el altavoz a todo volumen y la malla no puede tomar eso por
     *  una alerta de otro móvil. */
    fun ensordecer(ms: Long) {
        puertaHasta = maxOf(puertaHasta, System.currentTimeMillis() + ms)
    }

    private val h = Handler(Looper.getMainLooper())
    /** La tasa real la fija el micrófono compartido, no esta clase. */
    private val srRx get() = mic.sr
    private val oyente = Microfono.Oyente { marco, tMs ->
        try { procesar(marco, tMs) } catch (e: Exception) { Log.e(TAG, "malla decodificar", e) }
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
            for (t in TONOS) if (abs(f - t) < 250) cerca = true
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

    /** El pico de la rafaga que se esta oyendo ahora, para medir la caida. */
    private var picoRacha = -999.0

    /**
     * Cuanto tiene que caer el tono desde su propio pico para darlo por
     * acabado.
     *
     * Con ocho decibelios las rafagas se cortaban antes de tiempo —medido:
     * `onFuera` subia a 7 u 8 porque salian por debajo de los 200 ms— asi que
     * se espera un poco mas. Doce es todavia mucho menos de lo que cae una
     * rafaga al apagarse y mucho mas de lo que sube el ruido de fondo de golpe.
     */
    private val HIST_DB = 12.0

    /**
     * Si la rafaga sigue viva, para la cadencia. No es lo mismo que [tonoCrudo]:
     * aquel dice «hay tono por encima del ruido» y este dice «el tono todavia
     * no ha empezado a apagarse». La diferencia es el silencio de 150 ms entre
     * rafagas, que en una mesa dura no llega a bajar del ruido de fondo.
     */
    private var tonoVivo = false
    private var nivelAhora = -999.0

    /** Cuando se oyo la portadora por ultima vez. Ver [MARK_VALE_MS]. */
    private var ultimoMark = 0L

    /**
     * Marcos seguidos por debajo del pico antes de dar la rafaga por acabada.
     *
     * Sin esto la histeresis troceaba: un solo marco flojo —un desvanecimiento,
     * una reflexion que se cancela— cerraba la rafaga, reiniciaba el pico y
     * volvia a abrirla al marco siguiente. Medido: `onFuera` en 7 u 8 de cada
     * nueve rafagas, todas demasiado cortas.
     *
     * Tres marcos son 64 ms a 48 kHz. El silencio de verdad entre rafagas dura
     * 150, o sea siete marcos: sobrevive de sobra y los bajones sueltos no.
     */
    private val MARCOS_FIN = 3
    private var marcosBajos = 0

    /** Marcos desde el ultimo COMIENZO de rafaga. El periodo sale de aqui. */
    private var marcosDesdeOnset = 0

    /** Marcos que llevaba al empezar la rafaga que se esta oyendo, a la espera
     *  de saber si dura lo suficiente para contar como comienzo bueno. */
    private var marcosPendiente = 0
    private var confirma = 0
    private var confirmaHop = 0

    /** [tMs], cuándo se capturó el marco, decide la vigencia de la portadora.
     *  Por omisión el reloj de ahora: es lo que quieren los autotests, que
     *  inyectan marcos sintéticos sin micrófono de por medio. */
    private fun decodificar(x: ShortArray, tMs: Long = System.currentTimeMillis()): Int {
        val umbral = maxOf(dB(ruidoFondo(x)) + MARGEN_DB, SUELO_ABS_DB)
        val vMark = dB(pico(x, MARK))
        /* El espectro que ve la pantalla. No es un adorno ni una FFT aparte:
           son exactamente los valores con los que este decodificador acaba de
           decidir, medidos en los bins de los tonos que la malla escucha. Se
           publica siempre, incluso en los marcos que se descartan, porque
           «aquí no hay nada» también es una lectura y es la que más se ve. */
        niveles[0] = vMark
        sueloDb = umbral
        var mejor = 0; var mejorV = -999.0; var segundoV = -999.0
        for (hop in 1..TONOS.size) {
            val v = dB(pico(x, TONOS[hop - 1]))
            niveles[hop] = v
            if (v > mejorV) { segundoV = mejorV; mejorV = v; mejor = hop }
            else if (v > segundoV) { segundoV = v }
        }

        /* La portadora y el tono ya NO vienen a la vez: alternan rafaga a
           rafaga para que cada uno se lleve la escala entera. Asi que en cada
           marco manda el que suena mas fuerte, y eso mismo dice de que rafaga
           se trata sin necesidad de contar nada.
           Ojo con por que hace falta la comparacion y no basta el umbral: la
           portadora esta a 400 Hz de la alerta, y una portadora fuerte se
           derrama un poco en ese bin. Pidiendo que el tono GANE a la portadora,
           ese derrame no puede hacerse pasar por una alerta. */
        val hayMark = vMark > umbral && vMark >= mejorV
        val hayTono = mejorV > umbral && mejorV > vMark
        if (hayMark) ultimoMark = tMs

        /* Para la cadencia da igual cual de los dos sea: lo que se mide es el
           tren de rafagas, y siguen saliendo una cada 400 ms. */
        val nivelRafaga = maxOf(vMark, mejorV)
        if (!hayMark && !hayTono) {
            tonoCrudo = false; picoRacha = -999.0; marcosBajos = MARCOS_FIN; tonoVivo = false; confirma = 0; return 0
        }
        /* Hay tono: la cadencia lo cuenta igual. Lo que no hay es un salto
           legible, así que este marco no dice nada. */
        tonoCrudo = true
        /* Y aparte, si el tono esta SUBIENDO o ya viene cayendo. La cadencia se
           mide con esto y no con `tonoCrudo`.

           Medido el 17 de septiembre de 2026 entre el Huawei y el Redmi juntos
           en la mesa: 49 ecos, 21 «tono largo» y una racha de 789 ms cuando una
           rafaga dura 250. El silencio de 150 ms entre rafagas no llegaba a
           bajar del umbral —la cola del tono reverbera en una mesa dura— asi
           que el decodificador veia un tono continuo, decidia «esto no es una
           baliza» y tiraba la cadencia en cada marco.

           Comparar contra el pico de la propia rafaga y no contra el ruido de
           fondo: da igual lo fuerte que llegue, lo que importa es que baje. */
        nivelAhora = nivelRafaga
        if (nivelRafaga > picoRacha) picoRacha = nivelRafaga
        /* Viva mientras no lleve MARCOS_FIN seguidos caida por debajo de su
           propio pico. Comparar contra el pico de la rafaga y no contra el
           ruido de fondo: da igual lo fuerte que llegue, importa que baje. */
        if (picoRacha > -900.0 && nivelRafaga > picoRacha - HIST_DB) marcosBajos = 0 else marcosBajos++
        tonoVivo = marcosBajos < MARCOS_FIN
        if (!tonoVivo) picoRacha = -999.0
        /* Una rafaga de portadora no identifica ningun salto: solo sostiene el
           tren y avala las rafagas de tono que vengan al lado. */
        if (!hayTono) { confirma = 0; return 0 }
        if (mejorV - segundoV < SEPARACION_DB) { confirma = 0; return 0 }
        /* Y un tono sin portadora reciente no es de la malla: es un pitido
           cualquiera que ha caido en esa frecuencia. */
        if (tMs - ultimoMark > MARK_VALE_MS) { confirma = 0; return 0 }
        confirma = if (mejor == confirmaHop) confirma + 1 else 1
        confirmaHop = mejor
        /* DOS marcos, no tres. Se probó con tres y el autotest lo cazó: el
           decodificador tiene un contrato de dos marcos con todo lo que lo usa
           —el autotest los inyecta de dos en dos— y subirlo dejó la malla sorda
           en los cuatro saltos. La protección contra el ruido no puede venir de
           aquí. */
        if (confirma >= 2) {
            confirma = 0
            return when (mejor) {
                CODIGO_SILENCIO_ROBUSTO -> CODIGO_SILENCIO
                CODIGO_LLAMADA_ROBUSTA -> CODIGO_LLAMADA
                CODIGO_ALERTA_ROBUSTA -> CODIGO_ALERTA
                else -> mejor
            }
        }
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
    /* Por que se olvida la cadencia. Son tres causas con remedios distintos y
       desde fuera se ven iguales —todas dejan `cadencia=0`—, asi que se cuentan
       por separado y se dicen en el registro. */
    @Volatile var olvidoPuerta = 0; private set      // sordo por emision propia
    @Volatile var olvidoTonoLargo = 0; private set   // el tono no se corta nunca
    @Volatile var olvidoOnFuera = 0; private set     // la rafaga no dura lo que debe
    @Volatile var onMasLargoMs = 0L; private set     // la racha de tono mas larga vista

    private fun olvidarCadencia() { cadencia = 0; msPeriodoPrevio = 0L; marcosDesdeOnset = 0; marcosPendiente = 0 }

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
        marcosDesdeOnset++
        if (hayTono == habiaTono) {
            marcosRacha++
            /* Un tono que no se acaba nunca no es una baliza: es un generador, o
               alguien reproduciendo un tono. No hay que esperar a que la racha
               cierre para saberlo, y esperar dejaría viva una cadencia vieja. */
            if (hayTono) {
                val racha = msDe(marcosRacha)
                if (racha > onMasLargoMs) onMasLargoMs = racha
                if (racha > RAF_ON_MAX_MS) { olvidoTonoLargo++; olvidarCadencia() }
            }
            return
        }
        val ms = msDe(marcosRacha)
        val eraTono = habiaTono
        habiaTono = hayTono
        marcosRacha = 1

        if (hayTono) {
            /* Empieza algo. Todavia no se sabe si es una rafaga o un rebote, asi
               que solo se anota cuanto llevabamos desde el ultimo comienzo BUENO
               y se espera a ver cuanto dura. */
            marcosPendiente = marcosDesdeOnset
            return
        }
        if (!eraTono) return

        /* Se cerro el tono. Si fue demasiado corto NO era una rafaga: es un
           rebote en el hueco entre dos, o un desvanecimiento. Antes eso llamaba
           a `olvidarCadencia()` y tiraba el tren entero —medido: `onFuera` en
           cuatro a seis de cada nueve—. Un destello tiene que IGNORARSE, no
           destruir lo que ya se habia oido bien. */
        if (ms < RAF_ON_MIN_MS) { olvidoOnFuera++; return }

        /* Era una rafaga de verdad. El periodo va de comienzo a comienzo y no
           de sumar el tono mas el silencio: es el mismo numero cuando todo va
           bien, pero cuando el canal reparte mal ese numero entre las dos
           mitades —que es lo que pasa siempre— el de comienzo a comienzo no se
           entera. Cuatro comienzos seguidos cada 400 ms sin moverse no los da
           una habitacion. */
        val periodo = msDe(marcosPendiente)
        marcosDesdeOnset -= marcosPendiente
        val enRango = periodo in RAF_PER_MIN_MS..RAF_PER_MAX_MS
        val sigueElTren = enRango &&
            (msPeriodoPrevio == 0L || abs(periodo - msPeriodoPrevio) <= RAF_PER_JITTER_MS)
        when {
            sigueElTren -> { cadencia++; msPeriodoPrevio = periodo }
            enRango -> { cadencia = 1; msPeriodoPrevio = periodo }   // semilla de un tren nuevo
            else -> { cadencia = 1; msPeriodoPrevio = 0L }           // primera rafaga suelta
        }
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
    private fun corroborada(hop: Int, now: Long): Boolean {
        val antes = corrob.size
        corrob.retainAll { now - it.first < CORROB_VENTANA && it.second == hop }
        if (antes > 0 && corrob.isEmpty()) olvidarCadencia()      // se perdió la pista: a empezar
        corrob.add(now to hop)

        if (ServicioSos.temblando && cadencia >= RAFAGAS_MIN) {
            reg("está temblando: me creo la alerta a la primera")
            return true
        }

        /* Alerta máxima de madrugada.

           Si aquí la vigilia está armada —es de noche, el móvil lleva su
           reposo hecho y nadie lo toca— y lo que llega es una ALERTA
           SÍSMICA, no se espera a los tres segundos entre ecos. Medido el
           17 de septiembre: desde que un móvil detectó hasta que sonó el
           otro pasaron doce segundos, y de madrugada esos segundos son los
           que decide uno para levantarse de la cama.

           Sigue exigiéndose la cadencia completa, que es la firma temporal
           de la trama y lo único que un ruido no puede fingir. Lo que se
           suelta es la espera, no la prueba. Y solo para el código de
           alerta: una baliza de rescate no tiene esta prisa. */
        val esAlerta = hop == CODIGO_ALERTA || hop == CODIGO_ALERTA_ROBUSTA
        if (esAlerta && ServicioSos.vigiliaArmadaAqui && cadencia >= RAFAGAS_MIN) {
            reg("vigilia armada y alerta sísmica: no espero más")
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

    /**
     * Cuándo fue la última vez que alguien contestó «te he oído». Ver [OIDO].
     *
     * **No se cuenta cuántos son, y es a propósito.** El protocolo no lleva
     * identificador, así que dos confirmaciones pueden venir del mismo móvil:
     * decir «te han oído dos personas» sería inventarse un dato. Lo que sí se
     * puede afirmar es que alguien lo recibió, y esa es toda la diferencia que
     * importa cuando estás debajo.
     */
    @Volatile var oido = 0L; private set
    private var ultimoOido = 0L

    /** Para no llenar el registro: durante una trama el decodificador saca el
     *  salto una docena de veces, y el registro es la prueba de lo que pasó. */
    private var ultimoAvisoCandidato = 0L

    /** [tMs] es cuándo se capturó el marco. Todo lo que se decide aquí dentro
     *  se decide con ese reloj y no con el de ahora: la puerta anti-eco tiene
     *  que juzgar el audio por cuándo entró por el micrófono, no por cuándo la
     *  CPU llegó a mirarlo. Ver [Microfono.Oyente]. */
    private fun procesar(marco: ShortArray, tMs: Long) {
        // no oírse a sí mismo. La racha en curso queda partida: no se juzga.
        if (tMs < puertaHasta) { olvidoPuerta++; perderSincronismo(); return }
        /* Enmudecidos por el sistema: los marcos vienen a cero. Juzgarlos sería
           dar por buena una racha partida por un hueco que no oímos. */
        if (mic.silenciado) { perderSincronismo(); return }
        val hop = decodificar(marco, tMs)
        verCadencia(tonoVivo)
        if (hop == 0) return
        rx++
        if (!corroborada(hop, tMs)) {
            val now = tMs
            if (now - ultimoAvisoCandidato > RELAY_MS) {
                ultimoAvisoCandidato = now
                /* Con los tres numeros delante: cual de las tres condiciones
                   falta. Sin ellos hay que deducirlo desde fuera, y eso ya ha
                   costado tiempo una vez. */
                val span = if (corrob.isEmpty()) 0L else now - corrob[0].first
                reg("he oído algo que puede ser una alerta; espero a confirmarlo " +
                    "(cadencia=$cadencia/$RAFAGAS_MIN ecos=${corrob.size}/2 span=${span}/${CORROB_MIN}ms" +
                    " · olvidos: puerta=$olvidoPuerta tonoLargo=$olvidoTonoLargo onFuera=$olvidoOnFuera" +
                    " · onMax=${onMasLargoMs}ms)")
                olvidoPuerta = 0; olvidoTonoLargo = 0; olvidoOnFuera = 0; onMasLargoMs = 0L
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
        /* «Te he oído». Va hacia la víctima y no se retransmite: no tiene
           sentido propagar por cuatro saltos que alguien oyó algo aquí, y
           reenviarlo llenaría la banda justo cuando hace falta libre. */
        if (hop == CODIGO_OIDO) {
            corrob.clear(); olvidarCadencia()
            if (System.currentTimeMillis() - ultimoOido > OIDO_ANOTA_MS) {
                ultimoOido = System.currentTimeMillis()
                reg("TE HAN OÍDO · otro móvil ha recibido tu petición de ayuda")
            }
            oido = System.currentTimeMillis()
            return
        }
        if (hop == CODIGO_LLAMADA) {
            reg("TE ESTÁN BUSCANDO · alguien ha llamado desde arriba")
            corrob.clear(); olvidarCadencia()
            h.post { onLlamada() }
            return
        }
        ultimoSalto = hop
        confirmadas++
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
            /* Con un periodo fijo, dos móviles se enganchan en fase y sus
               ventanas de escucha coinciden con las emisiones del otro para
               siempre. El desorden es lo que garantiza que tarde o temprano se
               solapen; es el mismo motivo por el que las demás emisiones de
               este fichero salen con jitter. */
            override fun run() {
                emitirUna(h0)
                h.postDelayed(this, relayMs + (Math.random() * 1500).toLong())
            }
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
     *
     * Devuelve si de verdad ha arrancado una emisión. Quien llama lo necesita
     * para no anotar lo que no ha pasado: la vigilia nocturna pide emitir en
     * cada evaluación del sismógrafo mientras dure la sacudida, y sin esto el
     * registro se llenaba de «alerta emitida a la malla» —quince en tres
     * segundos el 18 de septiembre— por una sola baliza real.
     */
    fun emitirUna(hop: Int): Boolean {
        if (emitiendo) return false
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
            /* Con el volumen de alarma al maximo mientras dure la trama. Sin
               esto la malla emitia a lo que tuviera puesto el usuario. */
            val vol = try { Altavoz.aTope() } catch (_: Exception) { null }
            try {
                val pcm = ShortArray(total)
                /* Silencio y llamada se emiten en dos frecuencias: la robusta, que
                   sale con fuerza en altavoces baratos, y la de siempre, que es la
                   única que oye un móvil que aún no se ha actualizado. Sumarlas no
                   cabe —dos tonos ya dan 0,90 de amplitud y un tercero recorta—,
                   así que se alternan por ráfaga. Cada ráfaga dura 250 ms, unos
                   seis marcos, y al decodificador le bastan dos seguidos. */
                val fRobusto = when (h0) {
                    CODIGO_SILENCIO -> SILENCIO_ROBUSTO
                    CODIGO_LLAMADA -> LLAMADA_ROBUSTA
                    CODIGO_ALERTA -> ALERTA_ROBUSTA
                    else -> TONOS[h0 - 1]
                }
                val fLegado = TONOS[h0 - 1]
                val rampa = (0.008 * sr).toInt()          // 8 ms; un corte seco se
                                                          // derrama por toda la banda
                /* Las rafagas alternan PORTADORA y TONO en vez de llevar los dos
                   a la vez. Cada uno se lleva entonces la escala entera en vez
                   de la mitad: +6 dB. El receptor lo reconstruye porque sabe
                   que van alternas y porque en cada rafaga manda el que suena
                   mas fuerte. */
                for (b in 0 until BURST_N) {
                    val esMark = b % 2 == 0
                    val fHop = if ((b / 2) % 2 == 0) fRobusto else fLegado
                    val base = b * (nOn + nOff)
                    for (i in 0 until nOn) {
                        val t = i.toDouble() / sr
                        val env = when {
                            i < rampa -> i.toDouble() / rampa
                            i > nOn - rampa -> (nOn - i).toDouble() / rampa
                            else -> 1.0
                        }
                        val s = (if (esMark) sin(2.0 * PI * MARK * t) else sin(2.0 * PI * fHop * t)) * AMP_SOLO * env
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
                try { vol?.close() } catch (_: Exception) {}
                // el flujo NO se suelta aquí: se reutiliza en la siguiente ráfaga
                emitiendo = false
                // se reabre con margen por si el altavoz aún resuena
                puertaHasta = maxOf(puertaHasta, System.currentTimeMillis() + 400)
            }
        }
        return true
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
        val fHop = TONOS[h0 - 1]
        /* Desde que la portadora y el tono alternan, el autotest tiene que
           inyectar las dos clases de rafaga: primero una de portadora —que es
           la que avala— y luego las de tono. Sintetizarlos juntos, como se
           hacia antes, ya no representa lo que sale por el altavoz. */
        fun marco(f: Double): ShortArray {
            val x = ShortArray(N)
            for (i in 0 until N) {
                val t = i.toDouble() / srRx
                x[i] = (sin(2.0 * PI * f * t) * AMP_SOLO * Short.MAX_VALUE).toInt().toShort()
            }
            return x
        }
        val xMark = marco(MARK)
        val xTono = marco(fHop)
        confirma = 0; confirmaHop = 0; ultimoMark = 0L
        decodificar(xMark)             // la portadora avala lo que venga detras
        decodificar(xTono)             // el decodificador exige dos marcos de tono
        val got = decodificar(xTono)
        confirma = 0; confirmaHop = 0   // que la escucha real empiece limpia
        val expected = when (h0) {
            CODIGO_SILENCIO_ROBUSTO -> CODIGO_SILENCIO
            CODIGO_LLAMADA_ROBUSTA -> CODIGO_LLAMADA
            CODIGO_ALERTA_ROBUSTA -> CODIGO_ALERTA
            else -> h0
        }
        val ok = got == expected
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
            verCadencia(tonoVivo)
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
