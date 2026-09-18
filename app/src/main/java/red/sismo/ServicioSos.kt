package red.sismo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.RemoteViews
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * El servicio en primer plano: lo que mantiene todo vivo con la pantalla
 * bloqueada y el móvil en el bolsillo.
 *
 * Es la pieza que la versión web no podía tener. El navegador suspende audio y
 * temporizadores al bloquear la pantalla; un servicio en primer plano con
 * wake lock parcial no se suspende.
 */
class ServicioSos : Service() {

    companion object {
        @Volatile var vivo = false; private set
        const val CANAL = "sismored_sos"
        const val ID_NOTIF = 1
        const val ACCION_PANICO = "red.sismo.PANICO"
        const val ACCION_PARAR = "red.sismo.PARAR"
        /** Arma o desarma la vigilancia sísmica automática. */
        const val ACCION_ARMAR = "red.sismo.ARMAR"
        /** Se manda cuando el usuario acaba de conceder el micrófono. */
        const val ACCION_MALLA = "red.sismo.MALLA"
        /** ESCUCHAR MALLA: enciende y apaga la escucha a mano. */
        const val ACCION_MALLA_CONMUTAR = "red.sismo.MALLA_CONMUTAR"
        /** EMITIR ALERTA AHORA: manda el tono de alerta a los móviles que oigan. */
        const val ACCION_MALLA_ALERTA = "red.sismo.MALLA_ALERTA"
        /** ESCUCHAR ENTORNO: los cinco detectores, aparte de la malla. */
        const val ACCION_ESCUCHA_CONMUTAR = "red.sismo.ESCUCHA_CONMUTAR"
        /** Emisión interna para que la pantalla pinte lo que va pasando en la malla. */
        const val ACCION_REGISTRO = "red.sismo.REGISTRO"
        /** Emisión interna: la pantalla tiene que dar un destello blanco. */
        const val ACCION_DESTELLO = "red.sismo.DESTELLO"
        /* La sonda vive en el servicio, no en la pantalla, porque el micrófono
           es único: si la actividad abriera el suyo, dejaría sorda a la malla. */
        const val ACCION_SONDA = "red.sismo.SONDA"
        /** Aprender qué devuelve el móvil oyéndose a sí mismo, para restarlo. */
        const val ACCION_APRENDER_MOVIL = "red.sismo.APRENDER_MOVIL"
        const val ACCION_DOPPLER = "red.sismo.DOPPLER"
        const val ACCION_BARRIDO = "red.sismo.BARRIDO"
        const val ACCION_RESPIRA = "red.sismo.RESPIRA"
        const val ACCION_INTERFONO = "red.sismo.INTERFONO"
        const val ACCION_INTERFONO_PARAR = "red.sismo.INTERFONO_PARAR"
        /** Un solo destello del flash. Lo pide la vista de Búsqueda al acercarse:
         *  el flash lo tiene la cámara y la cámara la lleva el servicio. */
        const val ACCION_PULSO = "red.sismo.PULSO"
        const val ACCION_DIAGNOSTICO = "red.sismo.DIAGNOSTICO"
        /** Modo rescate: un pulso cada 12 s en vez de la sirena continua. */
        const val ACCION_RESCATE = "red.sismo.RESCATE"
        /** El usuario ha cambiado una casilla: hay que aplicarla en caliente. */
        const val ACCION_OPCIONES = "red.sismo.OPCIONES"
        /** MARCAR EVENTO: deja la hora anotada en el registro. */
        const val ACCION_MARCAR = "red.sismo.MARCAR"
        /** El que busca llama hacia abajo: sirena audible + tono de llamada. */
        const val ACCION_LLAMAR = "red.sismo.LLAMAR"
        /** El que busca pide SILENCIO en la zona: todos los móviles que lo oigan
         *  dejan de sonar cinco minutos, sin dejar de emitir la baliza. */
        const val ACCION_SILENCIO_ZONA = "red.sismo.SILENCIO_ZONA"
        /** Apagar SismoRed del todo: nada de vigilancia, nada en segundo plano. */
        const val ACCION_APAGAR = "red.sismo.APAGAR"
        /** ESTOY BIEN: la respuesta a la pregunta de la cascada. Es la acción más
         *  importante de la app después de PÁNICO, porque es la que apaga todo lo
         *  demás — y por eso también está en la notificación, para poder
         *  contestar sin desbloquear. */
        const val ACCION_ESTOY_BIEN = "red.sismo.ESTOY_BIEN"

        /** Instante en que vence la pregunta «¿estás bien?». 0 = no hay pregunta
         *  en curso. La pantalla pinta la cuenta atrás a partir de esto. */
        @Volatile var preguntaHasta = 0L
        /** Lo último que la cascada se atreve a afirmar, para el rótulo. */
        @Volatile var cascadaQuien = Cascada.Quien.NADIE
        @Volatile var cascadaMotivo = ""

        /**
         * Ha contestado que está bien, así que este móvil pasa a ser un nodo de
         * la red en vez de un cliente de ella: escucha, no grita y **reenvía**.
         *
         * Es el multiplicador más grande que tiene SismoRed y no cuesta casi
         * nada. En un terremoto la mayoría de la gente está bien, y cada uno de
         * esos móviles tiene batería, altavoz y a alguien mirándolo: son los que
         * pueden llevar la alerta de quien no puede hacer nada. Un móvil que oye
         * y no reenvía es un agujero en la malla.
         */
        @Volatile var repetidor = false; private set
        /**
         * Cuánto dura el relevo antes de volver a vigilar para uno mismo.
         *
         * Decir «estoy bien» convierte el móvil en repetidor, y eso estaba
         * bien pensado —quien está de pie en un terremoto es justo el nodo
         * que la red necesita— pero no caducaba. Un falso positivo a las dos
         * de la madrugada dejaba el teléfono en relevo el resto de la noche:
         * a las cuatro la alerta de un vecino llegaba por la malla y este
         * móvil no sonaba, porque hacía dos horas su dueño había dicho que
         * estaba bien de otra cosa.
         *
         * Media hora. Sigue cubriendo la réplica de un sismo de verdad, y
         * devuelve la vigilancia cuando no pasa nada más.
         */
        const val REPETIDOR_MS = 30 * 60_000L

        /**
         * Cuanto vale un «estoy bien» antes de volver a preguntar.
         *
         * Cinco minutos: cubre de sobra lo que dura una sacudida y sus
         * replicas inmediatas, y no tanto como para dejar el movil mudo si
         * media hora despues llega un segundo terremoto de verdad.
         */
        const val CONTESTADO_VALE_MS = 5 * 60_000L

        /** El umbral que se está aplicando ahora mismo, y en qué régimen. Se
         *  pinta: un detector que cambia de sensibilidad solo tiene que decirlo. */
        @Volatile var umbralActivo = 0.0

        /** ¿Estamos dentro de la franja de vigilia y con la vigilia puesta? */
        fun enVigilia(op: Opciones): Boolean {
            if (!op.vigiliaNocturna) return false
            val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            return h >= Opciones.VIGILIA_DESDE_H && h < Opciones.VIGILIA_HASTA_H
        }
        /**
         * Cuánto suelo moviéndose hace falta esta noche: tres segundos, u
         * ocho si ya sonaba un motor ANTES de que empezara.
         *
         * Lo de «antes» es la clave y no cuesta nada: un camión ya está
         * rugiendo cuando el edificio empieza a vibrar —él lo hace vibrar—,
         * mientras que el crujido de un terremoto nace con el temblor. Así
         * el caso limpio no se retrasa ni un milisegundo, y el peaje solo
         * lo paga lo que ya sonaba.
         */
        fun exigidoNocturno(sostenidoMs: Long): Long {
            val racha = System.currentTimeMillis() - sostenidoMs
            val motorYaEstaba = motorSonando && motorDesde in 1 until racha
            return if (motorYaEstaba) Opciones.VIGILIA_SOSTENIDO_MOTOR_MS
                   else Opciones.VIGILIA_SOSTENIDO_MS
        }

        @Volatile var enReposoAhora = false
        /** Cuánto lleva el móvil sin que nada lo roce. Lo lee la pantalla
         *  para decir si la vigilia está armada o cuánto le falta. */
        @Volatile var quietoParaVigilia = 0L
        /** Cuánto hace que se encendió la pantalla. La vigilia no se arma
         *  con alguien delante, y eso hay que poder verlo. */
        @Volatile var pantallaHace = Long.MAX_VALUE
        /** Suena un motor cerca. Le pone peaje a la vigilia nocturna. */
        @Volatile var motorSonando = false
        /** Desde cuándo suena sin parar. Es lo que decide si estaba ANTES. */
        @Volatile var motorDesde = 0L
        /** La última posición que el servicio llegó a conocer. Nunca sale
         *  del móvil: solo sirve para poner los kilómetros en la lista. */
        @Volatile var ultimaUbicacion: Pair<Double, Double>? = null

        /**
         * La vigilia nocturna está armada AQUÍ: es de madrugada, el móvil
         * lleva su reposo hecho y nadie lo está tocando.
         *
         * Lo lee la malla para creerse una alerta sísmica a la primera. No
         * es un atajo gratis: en ese estado el contexto ya corrobora —dos
         * móviles quietos, de noche, y uno de ellos ha medido tres segundos
         * de suelo moviéndose—, y lo que se suelta es la espera de tres
         * segundos entre ecos, no la firma temporal de la trama, que es lo
         * que de verdad distingue una baliza de un ruido.
         */
        @Volatile var vigiliaArmadaAqui = false
        /** Lo que este móvil mide de sacudida donde está, sin que nadie lo toque.
         *  Es lo que permite proponer un umbral en vez de pedirlo. */
        @Volatile var calmaMedida = 0.0
        /** Cuánto ha girado el móvil. Es lo que distingue una mano de una mesa. */
        @Volatile var giroGrados = 0.0
        /** El umbral que se aplica de verdad, ya subido por el ruido del sitio. */
        @Volatile var umbralReal = 0.0
        /** Grados de desacuerdo entre la gravedad rápida y la lenta: si esto
         *  sube, hay una mano encima. */
        @Volatile var manoGrados = 0.0
        /** Qué parte de los últimos dos segundos va por encima del umbral. */
        @Volatile var cicloTrabajo = 0.0
        /** Cuándo se contestó ESTOY BIEN por última vez. Se pinta durante unos
         *  minutos: pulsar algo y que no pase nada visible es indistinguible de
         *  que el botón no funcione, y eso hace imposible diagnosticarlo. */
        @Volatile var contestoBien = 0L

        /** Cifras de la malla, para las pestañas Inicio y Red. */
        /** Candidatos oidos. Diagnostico, no aviso: cuenta tambien lo que el
         *  propio decodificador descarta por no corroborarse. */
        @Volatile var mallaRx = 0; private set
        /** Las que la app se cree de verdad. Es lo unico con lo que se puede
         *  avisar a alguien de que hay otro movil pidiendo ayuda. */
        @Volatile var mallaConfirmadas = 0; private set
        @Volatile var mallaTx = 0; private set
        @Volatile var mallaSalto = 0; private set

        /**
         * Hasta cuándo la notificación puede decir que hay alerta en la malla.
         *
         * **Los contadores no sirven para esto y era lo que se usaba.**
         * `mallaTx` y `mallaConfirmadas` solo suben: una vez emitida o recibida
         * una baliza, `> 0` se cumple para siempre, así que la barra se quedaba
         * en «Retransmitiendo señal de socorro a nodos cercanos» hasta que
         * alguien reiniciaba el servicio. Tras la prueba de campo del 18 de
         * septiembre en el edificio se quedó así el resto del día, anunciando
         * un rescate que había terminado hacía horas.
         *
         * Un aviso de la malla es un suceso, no un total, y por eso lo que se
         * guarda es un instante.
         */
        @Volatile var mallaActividadHasta = 0L; private set
        /** Lo que dura el aviso en la barra desde la última baliza. */
        private const val MALLA_AVISO_MS = 5 * 60_000L
        /** Balizas confirmadas por salto: es lo que dibuja el radar. */
        @Volatile var mallaPorSalto = IntArray(MallaAcustica.MAX_HOP); private set

        /* El espectro de la banda que ve el decodificador, para la pantalla de
           la malla. Se copia en cada refresco en vez de dejar que la vista lea
           el array del motor: lo escribe el hilo del microfono cada 42 ms. */
        @Volatile var mallaNiveles = DoubleArray(1 + MallaAcustica.TONOS.size) { -120.0 }; private set
        @Volatile var mallaSuelo = -80.0; private set
        /** Las frecuencias de `mallaNiveles`, en el mismo orden. */
        val mallaFrecuencias = doubleArrayOf(MallaAcustica.MARK) + MallaAcustica.TONOS

        /** El array VIVO del motor, sin la copia de medio segundo. Lo lee el
         *  espectro en cada fotograma; `mallaNiveles` sigue siendo la foto para
         *  quien no necesite ir tan deprisa. */
        @Volatile var mallaNivelesVivos: (() -> DoubleArray)? = null; private set

        /**
         * Hay alguien usando la vista de Búsqueda con el rastreo encendido.
         *
         * Lo pone la pantalla, igual que `mirando`, y sirve para una sola cosa: que
         * el móvil del rescatista no se ponga a sonar cuando oiga por la malla a la
         * víctima que está buscando. Un rescatista con su propia sirena encendida
         * no oye nada, y oír es todo lo que tiene.
         */
        @Volatile var buscando = false

        /**
         * El suelo se está moviendo, o se movía hace muy poco.
         *
         * Lo pone el propio servicio leyendo el sismógrafo, y sirve para que la
         * malla se crea una alerta ajena **a la primera**. Ver `corroborada()`.
         */
        @Volatile var temblando = false
        /** Cuánto sigue valiendo el temblor después de dejar de notarlo. Un minuto:
         *  las réplicas y los derrumbes secundarios vienen justo detrás, y es
         *  cuando más falta hace que la alerta corra. */
        private const val TEMBLOR_MS = 60000L

        /** Lo que la pestaña Entorno pinta en vivo. Lo rellena el propio servicio. */
        @Volatile var oyeNivelDb = -90.0; private set
        @Volatile var oyeTonoHz = 0.0; private set
        @Volatile var oyeImpactos = 0; private set
        @Volatile var oyeUltimos: List<String> = emptyList(); private set
        /** Evidencia acumulada por cada uno de los cinco detectores, 0..1, en el
         *  orden de `Escucha.CLAVES`. Es la rejilla de la PWA. */
        @Volatile var oyeProgreso = DoubleArray(Escucha.CLAVES.size); private set
        /** Cuándo disparó cada detector por última vez; 0 = nunca. */
        @Volatile var oyeCuando = LongArray(Escucha.CLAVES.size); private set
        /** Envolvente del micrófono para el osciloscopio. */
        @Volatile var oyeOnda = FloatArray(0); private set
        @Volatile var oyeEscuchando = false; private set
        /** Desde cuándo escucha, para el cronómetro de la etiqueta. */
        @Volatile var oyeDesde = 0L; private set

        /** Lo que la pestaña Inicio pinta del detector sísmico. */
        @Volatile var sacudida = 0.0; private set
        @Volatile var trazaSismo = FloatArray(0); private set

        /* Cuanto sacudio, en g, y si vale una alerta externa. Los dos existen
           para que «¿ESTAS BIEN?» pueda decir POR QUE ha salido: quien la lee
           acaba de despertarse, y un rotulo fijo que ponga «SISMORED» no le
           dice nada. Cero = no se sabe, y entonces no se escribe cifra. */
        @Volatile var ultimaSacudidaG = 0.0; private set
        val alertaExternaVale: Boolean
            get() = System.currentTimeMillis() < alertaExternaHasta
        @Volatile var armado = true; private set

        @Volatile var enAlarma = false
            private set
        /** Modo rescate: no es alarma, pero tampoco es reposo. */
        @Volatile var enRescate = false
            private set

        /** Lo lee la pantalla para no mentir sobre si la malla está viva. */
        @Volatile var mallaEscuchando = false
            private set
        @Volatile var ultimoRegistro = ""
            private set

        /** Las fichas completas que han llegado por Wi-Fi, ya formateadas. Vacio si
         *  no hay ninguna: entonces el bloque de la pantalla no se enseña. */
        @Volatile var fichasWifi = ""
        /** Qué está pasando con la ficha por Wi-Fi, con sus palabras. Sin esto,
         *  el canal fallaba en silencio y no había forma de saber por qué. */
        @Volatile var fichaLanEstado = "sin arrancar"

        /** Lo último que dijo la sonda. Va aquí y no al registro porque es lo que
         *  se lee en la propia tarjeta mientras la ráfaga está sonando. */
        /** Una salida por herramienta. Antes las cuatro escribian en la misma y la
         *  ultima en hablar borraba lo que habian dicho las otras. */
        @Volatile var ecoSalida = "—"
        @Volatile var dopplerSalida = "—"
        @Volatile var respiraSalida = "—"
        @Volatile var barridoSalida = "—"
        /** Cual de las dos herramientas de tono esta encendida, o vacio. */
        @Volatile var quienTono = ""
        /** Nivel de movimiento en vivo, para la animacion. 1 = quieto. */
        @Volatile var nivelDoppler = 1.0
        @Volatile var ecoActivo = false
        @Volatile var barridoActivo = false
        /** La sonda está midiendo ahora mismo: lo usa la animación. */
        @Volatile var sondaOcupada = false; private set

        /** Lo último que dijo el interfono. Mismo motivo que `sondaSalida`: es un
         *  ciclo de diez segundos con pasos distintos y hay que ir leyéndolo. */
        @Volatile var interfonoSalida = "—"
        @Volatile var interfonoOcupado = false; private set
        @Volatile var interfonoFase: Interfono.Fase = Interfono.Fase.CERRADO
        @Volatile var interfonoNivelDb: Float = -120f

        /** Si la baliza de radio está emitiendo, y si no, por qué no. */
        @Volatile var radioEmitiendo = false; private set
        @Volatile var radioMotivo = "apagada"; private set

        /** Lo que la vista Diagnóstico tiene que poder decir sin abrir nada:
         *  la tasa real que dio el sistema y si la fuente es la sin procesar.
         *  Este Redmi no ofrece UNPROCESSED, y saberlo importa — con la fuente
         *  normal los tonos de 17 kHz desaparecen sin dar ningún síntoma. */
        @Volatile var micSr = 0; private set
        @Volatile var micCrudo = false; private set

        /** La pantalla avisa de cuándo hay un lienzo delante. Los lienzos se
         *  pintan a la velocidad de la pantalla, así que su dato hay que
         *  refrescarlo igual de rápido — pero solo mientras alguien mire. Con la
         *  app en el bolsillo esto vuelve a medio segundo y no cuesta nada. */
        @Volatile var mirando = false

        /** Silenciar la alarma calla este móvil, no la propagación. */
        private const val SILENCIO_MS = 60000L
        /** Un pulso cada 12 s: de una hora de sirena a muchas horas de baliza. */
        const val RESCATE_MS = 12000L

        /* Cuánto dura de verdad el tramo sonoro: tres pitidos de 0,20 s que
           arrancan cada 0,28 s. La maqueta ponía «PULSO 0.9 S» y la pantalla lo
           copió; son 0,76. */
        const val PULSO_MS = 760L

        /** Cuándo toca el siguiente pulso. La cuenta atrás de la pantalla de
         *  rescate la sacaba de `currentTimeMillis() % 12000`, o sea de un reloj
         *  que no tiene nada que ver con cuándo suena: bajaba a cero sin que
         *  sonase nada, y sonaba con la cuenta a mitad. */
        @Volatile var proximoPulso = 0L; private set
        /** Cuánto suena la sirena de llamada del que busca. */
        private const val LLAMADA_MS = 5000L
        /** Baliza acelerada mientras dura la respuesta a una llamada. */
        private const val RESPUESTA_MS = 1500L
        private const val RESPUESTA_DURA_MS = 180_000L
        /** Cuánto se anuncia por radio el que busca tras pulsar LLAMAR. */
        private const val LLAMADA_RADIO_MS = 120_000L
        /** Cada cuánto mira el enterrado si hay alguien buscando, y cuánto dura
         *  cada vistazo. Escuchar cuesta batería, así que se hace a ratos y solo
         *  con la alarma o el rescate en marcha — que es cuando puede haber
         *  alguien encima. */
        private const val OJEADA_CADA_MS = 30_000L
        private const val OJEADA_DURA_MS = 4_000L

        /* ---------- cuándo se pasa solo a modo rescate ----------
           Dos caminos, y basta con uno.

           El rápido: tres minutos sonando y dos sin que el móvil se mueva. Si
           nadie lo ha tocado y nadie lo ha movido, quien lo lleva no puede.

           El lento: DIEZ MINUTOS de sirena, se haya movido o no. Diez minutos a
           todo volumen son una mordida seria a la batería, y si nadie la ha
           parado en ese rato es que no puede o que no está — en los dos casos
           durar vale más que gritar. */
        private const val ESCALA_QUIETO_MS = 180_000L
        private const val ESCALA_SIN_MOVER_MS = 120_000L
        private const val ESCALA_TOPE_MS = 600_000L

        /* ---------- la pregunta ----------
           Sesenta segundos. Es un compromiso, y conviene saber entre qué: si es
           muy corto, alguien que está buscando a su hijo bajo una mesa no llega a
           contestar y su móvil se pone a emitir sin hacer falta. Si es muy largo,
           una persona inconsciente pierde ese minuto entero antes de que su
           baliza empiece a sonar. Sesenta segundos permiten salir de debajo de
           una mesa y mirar el móvil; hay que medirlo con gente, no decidirlo
           aquí. */
        private const val PREGUNTA_MS = 60_000L
        /** Cuánto tiene que pasar para volver a preguntar. Ver la nota de
         *  [preguntar]: sin esto la app interroga sola en bucle. */
        private const val PREGUNTA_REPOSO_MS = 300_000L

        /**
         * Cuánto tiempo puede durar un suceso abierto sin corroboración.
         *
         * Si el sismógrafo abre un suceso pero en quince segundos la cascada
         * se queda en NADA y nadie pregunta, el caso se cierra solo. Sin esto,
         * el suceso queda abierto para siempre y cualquier movimiento posterior
         * se suma a pruebas viejas, provocando falsas alarmas encadenadas.
         */
        private const val SUCESO_TIMEOUT_MS = 15_000L
        /** Simulacro: enseña «¿ESTÁS BIEN?» sin que haya pasado nada. No puede
         *  escalar — sin sacudida la cascada se queda en NADA aunque no se
         *  conteste. */
        const val ACCION_PROBAR_PREGUNTA = "red.sismo.PROBAR_PREGUNTA"

        /**
         * El simulacro que SÍ escala hasta la baliza.
         *
         * El de arriba solo saca la pantalla y por diseño no puede escalar: no
         * inyecta ninguna prueba, así que cuando vence la cuenta atrás la cascada
         * mira qué evidencia hay, no encuentra nada y decide NADA. Está bien
         * así — si escalara sin pruebas estaría probando un camino que en la
         * realidad no se recorre.
         *
         * Pero eso dejaba **la cadena entera sin poder ensayarse**: «preguntó,
         * nadie contestó → BALIZA» solo se veía con un terremoto de verdad, y por
         * eso lleva meses en la lista de pruebas pendientes. Este mete la
         * sacudida en el suceso, que es la única prueba que falta, y deja correr
         * todo lo demás sin trucar nada más: la misma pregunta, la misma cuenta
         * atrás y la misma decisión.
         *
         * Enciende la baliza y la malla **de verdad**, porque si no, no se estaría
         * probando nada. Por eso se pide confirmación antes.
         */
        const val ACCION_SIMULACRO_TOTAL = "red.sismo.SIMULACRO_TOTAL"

        /**
         * «Ya me encontraron», dicho por la persona que estaba pidiendo ayuda.
         *
         * **Es lo único que apaga la baliza.** Hasta ahora la ficha se abría sola
         * al oír la llamada del que busca y no había forma de decir que el
         * rescate había terminado: el móvil seguía gritando con alguien ya
         * delante, gastando batería y ocupando la malla.
         *
         * Y la regla que va con ello, que importa más que la función: **que
         * aparezca la ficha no puede cancelar nada**. Si el rescatista se
         * equivoca de hueco, oye la llamada, se abre la ficha y el móvil deja de
         * emitir por eso, se pierde a la persona. Hace falta que alguien lo diga
         * a propósito.
         */
        const val ACCION_RESCATADO = "red.sismo.RESCATADO"

        /** «Ya la he sacado», dicho por quien buscaba: deja de llamar. */
        const val ACCION_RESCATE_HECHO = "red.sismo.RESCATE_HECHO"

        /**
         * Ver la ficha tal y como la va a ver quien te encuentre.
         *
         * No es un adorno de diagnóstico: esa pantalla se abre sola, sobre el
         * bloqueo y con el brillo al máximo, en el peor momento de la vida de
         * alguien — y hasta ahora **no había forma de verla sin que pasara de
         * verdad**. Una ficha con el grupo sanguíneo mal escrito no se descubre
         * en un terremoto.
         */
        const val ACCION_VER_FICHA = "red.sismo.VER_FICHA"

        /**
         * Ha entrado una alerta sísmica de fuera: o la de Google por notificación
         * ([AlertaGoogle]), o la de otro móvil por la malla.
         *
         * **Arma, no dispara.** Ver [Cascada.Pruebas.alertaExterna] para el
         * porqué: la alerta llega segundos ANTES de que sacuda, así que en ese
         * instante todavía no ha pasado nada y preguntar «¿estás bien?» sería
         * gastar la pregunta justo antes del terremoto.
         */
        const val ACCION_ALERTA_EXTERNA = "red.sismo.ALERTA_EXTERNA"

        /**
         * Soltar el micrófono un rato para que otra app pueda grabar.
         *
         * **Hace falta porque la detección automática no llega a todo.** La
         * malla tiene el micrófono cogido a todas horas, y la grabadora de
         * MIUI —comprobado en el Redmi el 18 de septiembre de 2026— no se pone
         * en cola: mira si el micro está ocupado y contesta «no se puede
         * grabar cuando el mic está en uso» sin llegar a pedirlo. Como nunca
         * abre una captura, [Microfono.otroGrabando] no se entera y no hay
         * nada que ceder. Es un pez que se muerde la cola y solo lo rompe la
         * persona.
         *
         * Va en la notificación permanente y no dentro de la app: cuando te
         * hace falta estás en la grabadora, no en SismoRed.
         *
         * Y vuelve sola. Apagar la malla a mano deja el móvil sin vigilancia
         * hasta que alguien se acuerde de encenderla, y de eso nadie se
         * acuerda a las dos de la mañana.
         */
        const val ACCION_SOLTAR_MICRO = "red.sismo.SOLTAR_MICRO"

        /** Cuánto se suelta el micrófono de una vez. Da para una nota de voz
         *  larga, y es poco como para dejar un hueco que importe. */
        const val MICRO_LIBRE_MS = 5 * 60_000L

        /** Cuánto se da por vigente la petición de ayuda de un vecino. La
         *  baliza se repite cada 8 s mientras dure su alarma, así que un
         *  minuto sin oírla es que ha parado o se ha ido. */
        const val SOCORRO_VALE_MS = 60_000L

        /** Hasta cuándo vale una alerta externa. Volátil y estático porque lo
         *  mira la cascada desde el hilo del sensor. */
        @Volatile var alertaExternaHasta = 0L

        /** Como la de arriba, pero de un catálogo que publica lo YA ocurrido
         *  —EMSC/USGS— y no de una alerta temprana. Ver `Cascada.alertaCatalogo`. */
        @Volatile var alertaCatalogoHasta = 0L
        val alertaExterna: Boolean get() = System.currentTimeMillis() < alertaExternaHasta

        /** Manda un datagrama de prueba por la Wi-Fi. No enseña la ficha real ni
         *  enciende ninguna alarma: sirve para que dos personas comprueben que
         *  se ven en la red antes de necesitarlo. */
        const val ACCION_PROBAR_FICHA_LAN = "red.sismo.PROBAR_FICHA_LAN"

        /** Notificación aparte de la del servicio: la del servicio es
         *  permanente y no puede convertirse en la pregunta y luego volver. */
        const val ID_PREGUNTA = 2
        const val ID_FICHA = 3
        const val ID_PREGUNTA_DISCRETA = 14
        const val CANAL_DISCRETO = "sismored_discreto"
        const val ID_SISMO_CERCANO = 15
        /** El aviso de que quien pide ayuda es otro. Aparte de [ID_PREGUNTA]
         *  a propósito: no es la misma cosa y no puede pisarla. */
        const val ID_VECINO = 16
        const val CANAL_SISMO_CERCANO = "sismored_sismo_cercano"
        const val ACCION_FALSA_ALARMA = "red.sismo.FALSA_ALARMA"

        /** Cuánto vale una caída libre como prueba: pasado esto ya no cuenta. */
        private const val CAIDA_VALE_MS = 120_000L
    }

    private lateinit var sirena: Sirena
    lateinit var sismo: Sismografo
        private set
    /** Un solo micrófono para la malla y la escucha forense: Android no deja dos. */
    private var mic: Microfono? = null
    private var malla: MallaAcustica? = null
    private var escucha: Escucha? = null
    private var sonda: Sonda? = null
    private var interfono: Interfono? = null
    private var fichaLan: FichaLan? = null
    private var barridoOn = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var alarmaDesde = 0L
    private var vibrador: Vibrator? = null
    private lateinit var opciones: Opciones
    private var linterna: Linterna? = null
    /** Baliza de radio: la que permite encontrarte desde arriba. */
    private var radio: Baliza? = null
    /** Saltos que lleva recorridos la alerta que estamos propagando. 0 = nace aquí. */
    private var saltoEntrante = 0
    private val reloj = Handler(Looper.getMainLooper())

    private var receptorOnline: ReceptorSismicoOnline? = null

    /** A cuántos móviles está el vecino que pide ayuda, y hasta cuándo vale
     *  ese dato. Ver [Cascada.Pruebas.socorroVecino]. */
    @Volatile private var socorroVecino = 0
    @Volatile private var socorroVecinoHasta = 0L

    /* ---------- la cascada ----------
       El estado del suceso en curso. Todo esto es de un solo suceso: se pone en
       marcha con la primera prueba y se limpia al parar. */
    private var postura: Postura? = null
    /** La última posición conocida, sin encender el GPS nunca. */
    private var ubicacion: Ubicacion? = null
    /** Cuándo empezó el suceso que se está evaluando. 0 = no hay ninguno. */
    private var sucesoDesde = 0L
    /** Pasos que llevaba el móvil cuando empezó el suceso, para poder restar. */
    private var pasosAlSuceso = -1L
    private var ultimoEstruendo = 0L
    /** Cuándo se soltó el móvil y golpeó. Es una prueba, no una alarma. */
    private var ultimaCaida = 0L
    private val huboCaida: Boolean
        get() = ultimaCaida > 0 && System.currentTimeMillis() - ultimaCaida < CAIDA_VALE_MS
    /** Ya se ha preguntado y se ha agotado la cuenta atrás. */
    private var preguntaVencida = false
    /** Ha pulsado ESTOY BIEN. Mata la cascada de este suceso entero. */
    private var haContestado = false
    private var preguntaTarea: Runnable? = null
    /** Cuándo se preguntó por última vez, para no encadenar preguntas. */
    private var ultimaPregunta = 0L

    /* ---------- la evidencia del suceso, que NO caduca ----------
       Se vio en una prueba de campo y es el fallo más grave que ha tenido la
       cascada: se preguntó «¿estás bien?», pasó el minuto sin respuesta, y al
       volver a decidir salió NADA — «sacudida con el móvil encima y sin
       confirmar». ¿Por qué? Porque `temblando` dura un minuto y la pregunta dura
       otro: **la prueba que justificó preguntar se había evaporado justo cuando
       tocaba juzgar el silencio.**

       Una vez abierto un suceso, lo que se vio se vio. La evidencia se acumula y
       no se borra hasta cerrar el caso. Lo contrario es preguntar y luego olvidar
       por qué se preguntaba. */
    private var sucesoSacudida = false
    private var sucesoFuerte = false

    /** Cual de los dos terminos puso `sacudidaFuerte`. Solo para el registro.
     *  Se evaluan los dos SIEMPRE: con `||` el segundo no se calcularia cuando
     *  el primero es cierto, y el registro diria «false» sin haberlo mirado. */
    @Volatile private var fuertePorSuceso = false
    @Volatile private var fuertePorReciente = false

    private fun fuerteAhora(): Boolean {
        fuertePorSuceso = sucesoFuerte
        fuertePorReciente = System.currentTimeMillis() - sismo.ultimaFuerte < 60_000L
        return fuertePorSuceso || fuertePorReciente
    }
    private var sucesoEstruendo = false
    private var sucesoCorroborada = false
    private var sucesoRegimen = Postura.Regimen.DESCONOCIDO

    // SOS en morse: · · · — — — · · ·
    private val patronSos = longArrayOf(
        0, 200, 200, 200, 200, 200, 500,
        600, 200, 600, 200, 600, 500,
        200, 200, 200, 200, 200, 1400
    )

    override fun onCreate() {
        super.onCreate()
        vivo = true
        try { WatchdogReceiver.programar(this) } catch (_: Exception) {}

        /* La cascada, contra sus escenarios, en cada arranque del servicio.
           Cuesta microsegundos y es lo unico que comprueba las DECISIONES en vez
           de los sensores: si alguien toca una regla y con ello deja de
           encenderse la baliza de una persona enterrada, se sabe aqui y no en un
           terremoto. Estaba solo detras del boton de COMPROBAR TODO, o sea que
           en la practica no se corria nunca. */
        try {
            val (ok, txt) = Cascada.autotest()
            if (ok) Log.i("SismoRed", "cascada · $txt")
            else Log.e("SismoRed", "CASCADA FALLA · $txt")
        } catch (e: Exception) { Log.e("SismoRed", "cascada: no se pudo comprobar", e) }

        opciones = Opciones(this)
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        /* Para que la sonda y el interfono puedan subir el canal de alarma, no
           solo la sirena: era la razón de que los chasquidos no se oyeran. */
        Altavoz.audio = am
        sirena = Sirena(am)
        linterna = Linterna(this)
        radio = Baliza(this)

        vibrador = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        // El disparo por teclas lo hace ServicioTeclas (accesibilidad), que es el
        // único que las recibe con la pantalla apagada. Aquí no se toca el volumen:
        // la MediaSession que se probó antes secuestraba el control y dejaba la
        // alarma clavada al 50 % sin poder subirla.
        // Con el servicio en primer plano, el acelerómetro sigue leyendo con la
        // pantalla apagada. Es lo que permite disparar sin que nadie toque nada.
        /* El sismógrafo ya no llama a panico() directamente: ahora entrega lo que
           ha visto a la cascada, que es quien decide qué hacer con ello. La
           diferencia se nota sobre todo en la caída libre — que el móvil se te
           caiga de la mesa ya no puede encender la sirena si el suelo no se ha
           movido. */
        sismo = Sismografo(this) { motivo -> pruebaNueva(motivo) }
        sismo.umbral = opciones.umbral
        sismo.armado = opciones.armado
        sismo.ajustarPerfil(opciones.perfilEntorno)
        armado = sismo.armado
        sismo.arrancar()
        postura = Postura(this) { m -> anotar(m) }
        try { postura?.arrancar() } catch (_: Exception) {}
        /* Una mirada a la última posición conocida al arrancar. No enciende el
           GPS: lee lo que ya había. */
        ubicacion = Ubicacion(this)
        try { ubicacion?.refrescar() } catch (_: Exception) {}
        vigilarInmovilidad()

        /* La malla es lo que convierte un móvil que grita en una red que avisa.
           Oír una baliza confirmada es exactamente igual de serio que notar el
           terremoto uno mismo: se dispara la alarma completa y se reemite. */
        mic = Microfono(this)
        receptorOnline = ReceptorSismicoOnline(
            getUbicacion = {
                val u = if (ubicacion?.hay() == true) Pair(ubicacion!!.lat(), ubicacion!!.lon()) else null
                /* Cacheada para que la pantalla de sismos pueda calcular
                   distancias sin volver a pedirle la posición al sistema. */
                if (u != null) ultimaUbicacion = u
                u
            },
            onAlertaSismica = { mag, dist, lugar, fuente, fechaMs ->
                /* El receptor olvida lo que ya vio cuando el proceso muere,
                   asi que al arrancar vuelve a avisar de todo lo que siga
                   dentro de su ventana de veinte minutos. Con un reinicio
                   eso es una notificacion repetida; con varios, el mismo
                   sismo tres veces en el historial. La memoria tiene que
                   sobrevivir al proceso.

                   LA HUELLA ERA `fuente|mag|lugar` Y ASÍ NO DEDUPLICA NADA.
                   Los dos catálogos publican el mismo terremoto con distinta
                   magnitud y distinto nombre del sitio, así que los tres
                   campos cambian y el aviso salía dos veces. Medido la noche
                   del 17 al 18 de septiembre de 2026:

                       01:19:38  SGC   M4.5  El Litoral del San Juán  ~55 km
                       01:35:42  EMSC  M4.7  COLOMBIA                 ~56 km

                   El mismo sismo, preguntado dos veces a la una de la mañana.
                   Y una tercera cuando el SGC le revisó la magnitud a 4.6,
                   porque eso también cambia la huella.

                   Lo único que los dos dicen igual es la hora de ORIGEN. Se
                   agrupa por ella en cubos de cinco minutos, que es lo que ya
                   hacía `reportesRecientes` para la lista; aquí nunca se
                   aplicó. El precio es no avisar de una réplica que caiga en
                   el mismo cubo —el M3.2 de las 06:18 tras el M4.6 de las
                   06:17—, y para avisar a alguien eso es lo que se quiere. */
                val huella = "sismo|" + (fechaMs / 300_000L)
                val prefs = getSharedPreferences("sismored", MODE_PRIVATE)
                val visto = prefs.getLong("visto_" + huella.hashCode(), 0L)
                if (System.currentTimeMillis() - visto < 30 * 60_000L) {
                    /* Con el sismo entero, no solo la huella: la huella es un
                       número de cubo y no se lee. Esta línea es la prueba de
                       que la deduplicación entre catálogos funciona. */
                    Log.i("SismoRed",
                        "alerta repetida, ya avisada: $fuente M$mag en $lugar (cubo $huella)")
                    return@ReceptorSismicoOnline
                }
                prefs.edit().putLong("visto_" + huella.hashCode(),
                    System.currentTimeMillis()).apply()
                alertaExternaHasta = System.currentTimeMillis() + 180_000L
                /* Marcado aparte de la alerta temprana: un catálogo publica lo
                   que YA pasó, así que aquí no hay nada que anticipar y sí algo
                   que contar. Es lo que enciende el aviso discreto.
                   Diez minutos, y no los tres de la alerta temprana. El motivo
                   es que son relojes distintos: la alerta temprana caduca sola
                   porque la sacudida llega en segundos, mientras que un catálogo
                   tarda minutos en publicar y el receptor solo anuncia cada
                   evento UNA vez —deduplica por identificador—. Con tres
                   minutos, un catálogo lento dejaba la prueba caducada antes de
                   que nadie la usara. */
                alertaCatalogoHasta = System.currentTimeMillis() + 600_000L
                anotar("alerta externa ($fuente): M$mag en $lugar (~" + dist.toInt() + " km)")
                avisarSismoCercano(mag, dist, lugar, fuente)
                evaluar("alerta sísmica online $fuente M$mag")
            },
            onRegistro = { m -> anotar(m) },
            intervaloMs = { intervaloCatalogo() }
        )
        /* Solo si el usuario lo ha encendido. Llegaba arrancando siempre, y una
           app que promete no salir a internet no puede salir a internet de
           fabrica por muy publico que sea lo que va a leer. */
        if (opciones.sismoOnline) try { receptorOnline?.arrancar() } catch (_: Exception) {}
        mallaNivelesVivos = { malla?.niveles ?: mallaNiveles }
        malla = MallaAcustica(mic!!,
            onConfirmada = { hop ->
                saltoEntrante = hop
                /* Quien está buscando NO grita. Oír la baliza de la víctima a la
                   que te estás acercando dispararía la alarma completa del
                   rescatista —sirena, linterna, vibración— y entonces no oye los
                   escombros, que es lo único que tiene. La alerta se anota y se
                   sigue retransmitiendo para la red; lo que se calla es este
                   móvil. Al que busca se le avisa por vibración, destello de
                   pantalla y flash, y eso lo lleva la vista de Búsqueda. */
                /* Y quien ya ha contestado que está bien, tampoco. Pero los dos
                   TIENEN que pasar la alerta al siguiente: un móvil que oye y no
                   reenvía es un agujero en la malla, y era justo lo que pasaba —
                   el que buscaba la anotaba y ahí se acababa el viaje. */
                /* SIEMPRE se reenvía, decida lo que decida la cascada: un
                   móvil que oye y no reenvía es un agujero en la malla. */
                if (malla?.reenviar(hop) != true)
                    anotar("no la reenvío: ya ha dado los $hop saltos o he emitido demasiado")

                if (buscando || repetidor) {
                    val quien = if (buscando) "estás buscando" else "has dicho que estás bien"
                    anotar("ALERTA OÍDA a $hop saltos · no sueno porque $quien")
                } else {
                    /* AQUÍ SE LLAMABA A `panico()` DIRECTO, y ese era el fallo.
                       Quien oía a un vecino atrapado se convertía él mismo en
                       víctima: sirena, baliza propia en bucle, linterna. En un
                       salón con varios móviles, una sola pulsación los dejaba a
                       todos gritando, y eso tapa a la víctima y corrompe el
                       radar de saltos —lo único que orienta a quien busca—.

                       Además había DOS decisores para lo mismo y no estaban de
                       acuerdo: esto encendía la sirena mientras la cascada
                       decía PREGUNTAR. Ahora decide ella sola, con
                       [Cascada.Pruebas.socorroVecino]. */
                    socorroVecino = hop
                    socorroVecinoHasta = System.currentTimeMillis() + SOCORRO_VALE_MS
                    anotar("un vecino pide ayuda a $hop " +
                           (if (hop > 1) "móviles de distancia" else "móvil, justo al lado"))
                    evaluar("baliza de un vecino (salto $hop)")
                }
            },
            onLlamada = {
                respuestaReforzada()
                /* Y la ficha, sola y a pantalla completa. Oír la llamada
                   significa que quien busca está al lado —ese tono no atraviesa
                   casi nada—, y lo que se va a encontrar es un móvil bloqueado.
                   Solo si este teléfono está pidiendo ayuda: en el del que busca
                   no se abre nunca. */
                if (enAlarma || enRescate) mostrarFichaSola()
            },
            /* Silencio pedido desde arriba. Se calla TODO lo que hace ruido y se
               deja lo que no molesta: la baliza de radio sigue, porque no suena y
               es lo único que atraviesa el escombro. Callarse entero sería
               desaparecer justo cuando te están buscando. */
            /* Callarse quiere decir dejar de tapar lo que el equipo escucha, y lo
               que escuchan son geófonos y micrófonos de contacto en la BANDA
               BAJA. Ahí es donde estorba la sirena, que son 2-4 kHz.

               La malla emite a 16-18,8 kHz. Eso no le tapa nada a un geófono: no
               está ni cerca de su banda. Pararla no le daba silencio a nadie y en
               cambio dejaba mudo el único canal acústico del que está debajo,
               justo mientras lo buscan. Aquí se paraba, y era un error — el más
               caro posible, porque se pagaba en el móvil de la víctima.

               Así que la malla NO se toca. Se calla lo que hace ruido audible:
               sirena, vibración y la sonda, que además barre con tonos que sí
               entran en la banda de trabajo del equipo. */
            /* Otro móvil ha repartido una alerta sísmica por la malla. Entra
               por el mismo sitio que la de Google y hace lo mismo: avisar y
               armar. No enciende ninguna baliza. */
            onAlertaSismica = {
                try {
                    startService(Intent(this, ServicioSos::class.java)
                        .setAction(ACCION_ALERTA_EXTERNA).putExtra("malla", true))
                } catch (_: Exception) {}
            },
            onSilencio = {
                try { sirena.stop() } catch (_: Exception) {}
                try { vibrador?.cancel() } catch (_: Exception) {}
                try { sonda?.pararTodo(); barridoOn = false } catch (_: Exception) {}
                anotar(
                    "SILENCIO pedido por quien busca: me callo 5 minutos. " +
                    "La baliza sigue, por radio y por sonido: no es lo que tapa a un geófono."
                )
            },
            onRegistro = { m -> anotar(m) }
        )
        /* La escucha forense comparte el micrófono con la malla. Un derrumbe
           dispara la alarma entera: si el edificio se está cayendo, nadie va a
           estar mirando el móvil para pulsar nada. */
        escucha = Escucha(mic!!,
            /* Solo dispara si la vigilancia está armada, igual que en la PWA. La
               escucha informa; quien decide disparar la alarma es el servicio.
               Un falso positivo aquí es la sirena a todo volumen en el bolsillo,
               así que el umbral del estruendo NO está calibrado en campo: hasta
               que se mida en un móvil real, este es el único freno que hay. */
            /* El estruendo del microfono YA NO dispara solo.
               El banco con audio real dice que acierta 3 de 8 derrumbes y que un
               generador diesel le da tres falsos. Un derrumbe de verdad SACUDE el
               movil; un motor a diez metros no. Asi que se exige que el
               acelerometro lo corrobore, y con eso se cae la mayoria de los falsos
               sin bajar la sensibilidad del microfono, que es lo que se hacia antes
               y costaba perder derrumbes de verdad.

               `temblando` vale un minuto desde la ultima sacudida (ver arriba), asi
               que el orden no importa: da igual si primero se oye y luego tiembla o
               al reves.

               Cuando oye pero no tiembla NO se calla: lo anota. Esa linea es el
               material para saber cuantas veces habria disparado de mas, que es la
               cifra que de verdad decide si este detector sirve — falsos por hora,
               no porcentaje de aciertos. */
            onEstruendo = {
                if (sismo.armado) {
                    ultimoEstruendo = System.currentTimeMillis()
                    if (sucesoDesde > 0L) {
                        // El suelo ya se estaba moviendo: el estruendo se suma como evidencia del suceso
                        evaluar("estruendo durante el temblor")
                    } else {
                        // El micrófono oye la habitación, no un sismo: sin sacudida previa no se abre suceso
                        anotar("estruendo oído por micrófono (sin sacudida previa: se ignora)")
                    }
                }
            },
            onRegistro = { m -> anotar(m) }
        )

        sonda = Sonda(mic!!, onRegistro = { m -> anotar(m) }, op = opciones)
        sonda?.fDoppler = opciones.dopplerKhz * 1000
        /* La ficha completa por Wi-Fi. Escuchar se enciende ya y no se apaga: recibir
           un datagrama cada tres segundos no se nota, y quien busca no siempre se
           acuerda de encender las cosas. Emitir solo con alarma o rescate. */
        fichaLan = FichaLan(this) { m -> anotar(m) }
        fichaLan?.escuchar(true)
        interfono = Interfono(
            mic!!,
            onAltavoz = { ms -> malla?.ensordecer(ms); escucha?.ensordecer(ms) },
            onRegistro = { m -> anotar(m) }
        )

        // Nada de esto se arranca aquí: grabar exige que el servicio ya esté en
        // primer plano con el tipo «micrófono». Se arranca al final de
        // onStartCommand, que es cuando eso ya es cierto.

        crearCanal()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SismoRed:sos").apply {
            setReferenceCounted(false)
            acquire()
        }

        publicar()
        publicarLienzos()
        vigilarLlamadas()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        alPrimerPlano()
        when (intent?.action) {
            // pulsado por una persona: se respetan sus opciones
            ACCION_PANICO -> panico("botón de pánico", automatico = false)
            ACCION_PARAR -> parar()
            ACCION_RESCATE -> if (enRescate) parar() else rescate()
            ACCION_ARMAR -> armar(!sismo.armado)
            ACCION_OPCIONES -> aplicarOpciones()
            ACCION_MALLA_CONMUTAR -> conmutarMalla()
            ACCION_MALLA_ALERTA -> emitirAlertaMalla()
            ACCION_ESCUCHA_CONMUTAR -> conmutarEscucha()
            ACCION_MARCAR -> marcar()
            ACCION_LLAMAR -> llamar()
            /* El progreso va solo a la pantalla y el resultado también al
               registro: si cada paso intermedio se anotara, ocho chasquidos y
               cincuenta lecturas de doppler dejarían el registro inservible
               justo cuando hace falta leerlo. */
            /* Las cuatro herramientas de la sonda son INTERRUPTORES, no
               disparos: la misma accion enciende y apaga. Y cada una escribe en su
               propia salida, porque antes las cuatro compartian `sondaSalida` y la
               ultima en hablar borraba a las demas.

               El tono es uno y el altavoz es uno, asi que encender movimiento o
               respiracion apaga a la otra: se hace aqui, no en la pantalla, para
               que valga igual si la orden llega del atajo de volumen. */
            /* Aprender la firma del propio móvil: una ráfaga con el teléfono
               lejos de todo. Sin esto el eco mide el teléfono, no la sala. */
            ACCION_APRENDER_MOVIL -> sonda?.aprenderMovil(
                { p -> ecoSalida = p },
                { r -> ecoSalida = r; anotar(r) }
            )
            ACCION_SONDA -> if (sonda?.ecoContinuo == true) {
                sonda?.eco(false, {}, {})
                ecoSalida = "Apagado."
            } else {
                sonda?.eco(true,
                    { p -> ecoSalida = p },
                    { r -> ecoSalida = r; anotar(r) }
                )
            }
            ACCION_DOPPLER -> if (sonda?.quienTono == "movimiento") {
                sonda?.doppler(false, {}, { r -> dopplerSalida = r })
            } else {
                sonda?.pararTono()
                sonda?.fDoppler = opciones.dopplerKhz * 1000
                sonda?.volTono = opciones.volSenal / 10.0
                sonda?.doppler(true,
                    { p -> dopplerSalida = p },
                    { r -> dopplerSalida = r; anotar(r) }
                )
            }
            /* El interfono usa el altavoz a tope y el microfono alternandose, asi
               que mientras dure hay que dejarle el sitio: la malla se recupera
               sola al terminar, por el arrancarMalla() del final. */
            ACCION_INTERFONO -> interfono?.ciclo(
                { p -> logInterfono(p, paso = true) },
                { r -> logInterfono(r, paso = false); anotar(r) }
            )
            ACCION_INTERFONO_PARAR -> {
                interfono?.parar()
                interfonoOcupado = false
                interfonoFase = Interfono.Fase.CERRADO
                anotar("interfono cerrado")
            }
            ACCION_RESPIRA -> if (sonda?.quienTono == "respiracion") {
                sonda?.respiracion(false, {}, { r -> respiraSalida = r })
            } else {
                sonda?.pararTono()
                sonda?.fDoppler = opciones.dopplerKhz * 1000
                sonda?.volTono = opciones.volSenal / 10.0
                sonda?.respiracion(true,
                    { p -> respiraSalida = p },
                    { r -> respiraSalida = r; anotar(r) }
                )
            }
            ACCION_BARRIDO -> {
                barridoOn = !barridoOn
                sonda?.volTono = opciones.volSenal / 10.0
                sonda?.barrido(barridoOn)
                barridoSalida = if (barridoOn)
                    "Encendido. Sube de 80 Hz a 4 kHz cada 2,5 s, sin parar."
                else "Apagado."
                anotar("Barrido: " + barridoSalida)
            }
            /* Un destello y nada más: sin sirena y sin vibración propia. Es el
               aviso de que te estás acercando, y tiene que poder verse de reojo
               sin tapar el sonido de los escombros. */
            ACCION_PULSO -> try { linterna?.destello(90) } catch (_: Exception) {}
            ACCION_ESTOY_BIEN -> estoyBien()
            ACCION_FALSA_ALARMA -> falsaAlarma()
            ACCION_SILENCIO_ZONA -> silencioZona()
            ACCION_APAGAR -> { apagarDelTodo(); return START_NOT_STICKY }
            ACCION_PROBAR_FICHA_LAN -> {
                anotar("Probando la ficha por Wi-Fi: " + (fichaLan?.estado() ?: "sin arrancar"))
                /* Durante la prueba se escucha a tope dos minutos, aunque se
                   apague la pantalla: es la única forma de comprobar el canal en
                   las mismas condiciones en que va a hacer falta. Se suelta solo,
                   porque mantener la radio despierta cuesta batería. */
                fichaLan?.escuchaFuerte(true)
                reloj.postDelayed({
                    if (!buscando && !enAlarma && !enRescate && !repetidor)
                        try { fichaLan?.escuchaFuerte(false) } catch (_: Exception) {}
                }, 120_000L)
                fichaLan?.probar()
            }
            ACCION_PROBAR_PREGUNTA -> {
                anotar("Simulacro: esta es la pantalla que sale sola tras un terremoto.")
                preguntar(false, "simulacro")
            }
            ACCION_SIMULACRO_TOTAL -> simulacroCompleto()
            ACCION_RESCATADO -> rescatado()
            ACCION_RESCATE_HECHO -> rescateHecho()
            ACCION_ALERTA_EXTERNA -> alertaSismicaExterna(intent.getBooleanExtra("malla", false))
            ACCION_SOLTAR_MICRO -> {
                micLibreHasta = System.currentTimeMillis() + MICRO_LIBRE_MS
                cederMicrofono()
            }
            ACCION_VER_FICHA -> mostrarFichaSola(previa = true)
            ACCION_DIAGNOSTICO -> comprobarTodo()
        }
        // ya estamos en primer plano: aquí sí se puede grabar. Si el usuario apagó
        // la malla a mano, no se le vuelve a encender por la espalda.
        if (intent?.action != ACCION_MALLA_CONMUTAR && !mallaApagadaAMano && !micCedido) arrancarMalla()
        // Si el sistema mata el proceso, que lo vuelva a levantar.
        return START_STICKY
    }

    /**
     * Desde Android 10 el tipo de servicio se declara al pasar a primer plano, y
     * desde Android 14 usar el micrófono exige declararlo. El tipo se calcula
     * cada vez: pedir el tipo «micrófono» sin tener concedido RECORD_AUDIO lanza
     * SecurityException y se lleva por delante el servicio entero — con él, la
     * sirena y el atajo de volumen. La malla es importante; el resto lo es más.
     */
    private fun alPrimerPlano() {
        val n = notificacion(enAlarma)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try { startForeground(ID_NOTIF, n) } catch (e: Exception) {
                Log.e("SismoRed", "no se pudo pasar a primer plano", e)
            }
            return
        }

        /* Y NO BASTA CON TENER EL PERMISO.
           Esto tiraba la app entera con SecurityException aun teniendo
           RECORD_AUDIO concedido: desde Android 14, un servicio con tipo
           «micrófono» solo puede arrancar con la app EN PRIMER PLANO, porque el
           micrófono es un permiso «mientras se usa» y no se puede tomar desde
           atrás. Con la app en segundo plano —que es de donde arranca esto la
           mitad de las veces: al encender el móvil, al recibir una alerta, al
           revivir el servicio— la excepción se llevaba por delante el proceso, y
           con él la sirena, la malla y el atajo de volumen.

           Además, desde Android 14 (API 34) usar Bluetooth/BLE exige declarar
           FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE tanto en el Manifest como al
           llamar a startForeground, o el sistema lanzará SecurityException.

           Así que se calcula el tipo deseado (mediaPlayback + connectedDevice si
           hay permiso de radio) y se intenta con micrófono. Si el micrófono es
           rechazado por el sistema (arranque en segundo plano), se hace fallback
           a reproducción + radio, y si falla, a reproducción pura.
           Perder la escucha o radio es malo; perder el servicio es perderlo todo. */
        var base = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        val quiereRadio = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && (radio?.hayPermiso() == true)
        if (quiereRadio) {
            base = base or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        val conMicro = base or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val quiereMicro = malla?.hayPermiso() == true

        if (quiereMicro) {
            try { startForeground(ID_NOTIF, n, conMicro); return } catch (e: Exception) {
                Log.w("SismoRed", "sin micrófono en primer plano: ${e.message}")
                /* Se apaga la escucha de verdad, no solo el tipo declarado:
                   dejar el micrófono abierto sin haberlo declarado es lo que el
                   sistema castiga, y además la pantalla diría que oye. */
                try { escucha?.parar() } catch (_: Exception) {}
                try { malla?.parar() } catch (_: Exception) {}
                mallaEscuchando = false
                oyeEscuchando = false
            }
        }
        try { startForeground(ID_NOTIF, n, base); return } catch (e: Exception) {
            Log.w("SismoRed", "sin radio/dispositivo conectado en primer plano: ${e.message}")
        }
        try { startForeground(ID_NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) } catch (e: Exception) {
            Log.e("SismoRed", "no se pudo pasar a primer plano", e)
        }
    }

    /** Todo lo que pasa en el audio acaba aquí: al registro y a la pantalla. */
    @Volatile private var anotaciones = 0

    private fun anotar(m: String) {
        ultimoRegistro = m
        malla?.let {
            if (it.tx > mallaTx || it.confirmadas > mallaConfirmadas)
                mallaActividadHasta = System.currentTimeMillis() + MALLA_AVISO_MS
            mallaRx = it.rx; mallaTx = it.tx; mallaSalto = it.ultimoSalto
            mallaConfirmadas = it.confirmadas
            mallaPorSalto = it.porSalto.copyOf()
            mallaNiveles = it.niveles.copyOf(); mallaSuelo = it.sueloDb
        }
        
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val db = red.sismo.data.SismoDatabase.getDatabase(this@ServicioSos)
                val evento = red.sismo.data.EventoBD(
                    tipo = 1,
                    fechaMs = System.currentTimeMillis(),
                    mensaje = m
                )
                db.eventoDao().insertar(evento)
                /* `podar()` existia y no la llamaba nadie. En el Redmi habia
                   14.010 eventos del 1 al 8 de septiembre y creciendo, en el
                   aparato que tiene que aguantar encendido justo cuando ya no
                   puedes liberar espacio a mano. Se poda cada 200 anotaciones y
                   no en cada una, que serian 14.000 barridos de tabla. */
                if (++anotaciones % 200 == 0) db.eventoDao().podar()
            } catch (e: Exception) {
                // Ignore errors
            }
        }
        
        try {
            sendBroadcast(Intent(ACCION_REGISTRO).setPackage(packageName).putExtra("texto", m))
        } catch (_: Exception) {}
    }

    /* ================= malla ================= */

    /** Si el usuario las apaga a mano, no pueden volver solas en el siguiente
     *  `onStartCommand` — que llega con cada botón que se pulsa. */
    private var mallaApagadaAMano = false
    private var escuchaApagadaAMano = false

    /** Hemos soltado el micrófono porque otra app lo quería. */
    private var micCedido = false
    /** Hasta cuándo lo hemos soltado a petición de la persona. Ver [ACCION_SOLTAR_MICRO]. */
    @Volatile private var micLibreHasta = 0L

    /**
     * Cederle el micrófono a otra aplicación que quiera grabar.
     *
     * Una app de emergencia no puede cobrarse el precio de que el teléfono
     * deje de tener grabadora. Ver [Microfono.otroGrabando] para lo que se
     * midió: con la malla en marcha, la grabadora del Redmi no arranca.
     *
     * **Salvo en emergencia.** Si esto está en alarma, en rescate o buscando,
     * el micrófono es lo único que le queda a quien está debajo, y ahí no se
     * cede por mucho que otra app lo pida.
     */
    private fun cederMicrofono() {
        val m = mic ?: return
        val emergencia = enAlarma || enRescate || buscando
        val aMano = System.currentTimeMillis() < micLibreHasta
        val ceder = (m.otroGrabando || aMano) && !emergencia
        if (ceder == micCedido) return
        micCedido = ceder
        if (ceder) {
            try { escucha?.parar() } catch (_: Exception) {}
            oyeEscuchando = false
            try { malla?.parar() } catch (_: Exception) {}
            mallaEscuchando = false
            anotar(if (aMano) "micrófono libre ${MICRO_LIBRE_MS / 60_000} minutos: ya puedes grabar"
                   else "otra app está grabando: le dejo el micrófono y dejo de escuchar")
        } else {
            micLibreHasta = 0L
            anotar("el micrófono vuelve a estar libre: retomo la escucha")
            if (!mallaApagadaAMano) arrancarMalla()
        }
        try { actualizarNotificacion() } catch (_: Exception) {}
    }

    private fun arrancarMalla() {
        val m = malla ?: return
        if (m.escuchando) return
        mallaEscuchando = m.escuchar()
        // La malla se autocomprueba sola al arrancar (ver MallaAcustica.arrancarRx).
        if (mallaEscuchando) {
            /* Antes arrancaba los detectores sin más. Ahora decide `ajustarEscucha`,
               que además mira la hora, si suena audio y si hay emergencia. */
            ajustarEscucha()
            /* La autocomprobación de la sonda, FUERA del hilo principal. Son
               filtros adaptados sobre decenas de ráfagas simuladas y tardan
               segundos; corriendo aquí bloqueaban la interfaz al arrancar, y si
               el usuario tocaba la pantalla en ese rato Android sacaba «SismoRed
               no responde». Es el «a veces falla» que se veía en el Samsung.
               Comprobar que todo funciona no puede impedir usar la app. */
            thread(name = "autotest-sonda", isDaemon = true) {
                try { sonda?.autotest() } catch (_: Exception) {}
            }
            actualizarNotificacion()
        }
    }

    /* La escucha forense y la malla comparten micrófono pero no interruptor:
       `Microfono` cuenta usuarios, así que se puede apagar una sin dejar sorda a
       la otra. Y hacen falta por separado — alguien puede querer la malla toda
       la noche y los cinco detectores solo mientras esté atrapado. */
    /**
     * Decide si los detectores de audio deben estar corriendo, y lo aplica.
     *
     * La malla NO pasa por aquí: se queda escuchando siempre. Trabaja en
     * 16-18 kHz, donde ni la música ni una voz tienen energía, y apagarla
     * dejaría a este móvil sordo a la alerta del de al lado. Lo que se apaga
     * son los detectores caros —estruendo, grito, voz, tono—, que son los que
     * se comen la CPU y los que de día no sirven para nada.
     *
     * Medido: el micrófono abierto y su procesado fueron el 53 % del consumo
     * de aplicaciones en un día, con los sensores de movimiento en 0,03.
     *
     * Las cuatro reglas, en orden de mando:
     *
     *  1. **Alarma o rescate: encendidos siempre.** Aunque sea mediodía y
     *     aunque suene música. Es cuando hay que oír golpes bajo el escombro,
     *     y quedarse sordo ahí sería el único fallo que no se puede permitir.
     *  2. **Apagados a mano: apagados.** La decisión del usuario manda.
     *  3. **Con audio sonando: apagados.** Con música o un vídeo, lo único que
     *     producen son falsos — una noche dieron 26 gritos de auxilio, todos
     *     de la música.
     *  4. **Si no, solo con la vigilia armada.** De día, en reposo, el oído
     *     no aporta nada que el acelerómetro no tenga.
     */
    private fun ajustarEscucha() {
        val e = escucha ?: return
        val emergencia = enAlarma || enRescate
        val debe = when {
            emergencia -> true
            micCedido -> false                 // se lo hemos dejado a otra app
            escuchaApagadaAMano -> false
            sonandoAudio() -> false
            else -> vigiliaArmadaAqui
        }
        if (debe == e.escuchando) return
        if (debe) arrancarEscucha() else {
            e.parar()
            oyeEscuchando = false
        }
    }

    /** ¿Está el móvil reproduciendo algo? Música, un vídeo. O sonando el teléfono. */
    private fun sonandoAudio(): Boolean = try {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        am?.isMusicActive == true || enLlamada()
    } catch (_: Exception) { false }

    /**
     * El teléfono está sonando, o hay una llamada en curso.
     *
     * **Esto lo decía el comentario de [sonandoAudio] y el código no lo hacía**:
     * `isMusicActive` mira el canal de música y un timbre va por el de llamada,
     * así que una llamada entrante pasaba entera por debajo de la puerta.
     *
     * Lo que cuesta se midió el 18 de septiembre de 2026 a las 13:51, con la
     * vigilia armada y los dos móviles en la mesa:
     *
     *     13:51:59  GRITO DE AUXILIO · 83% (1260 Hz sostenido)
     *     13:52:04  malla: emitida baliza tx=1
     *     13:52:09  panico(malla acústica salto 1)  ->  sirena
     *
     * El tono del timbre es un sostenido de 1260 Hz, y el detector de pánico
     * mide volumen, no palabras: se lo creyó al 83 %. El acelerómetro no se
     * movió en ningún momento —`quieto 86 s`, `calma 0,003`— o sea que el
     * teléfono llamó a los vecinos por su propio timbre.
     *
     * Y tapa de paso lo otro: **al sonar también vibra**, y una vibración de
     * llamada es sacudida continua de varios segundos acoplada directamente al
     * acelerómetro, que es la firma exacta que busca la vigilia nocturna. Por
     * eso esto veta las dos vías, la del micrófono y la del sismógrafo.
     *
     * `getMode` no pide ningún permiso, al revés que `TelephonyManager`.
     */
    /**
     * Este teléfono está produciendo él mismo la sacudida que va a medir.
     *
     * **La invariante correcta no es «no pasar a víctima», es que el
     * acelerómetro calle siempre que suene el altavoz propio.** Se llegó a
     * ella tapando fuentes de una en una —primero la vibración de la llamada—
     * hasta ver que eran la misma: medido el 18 de septiembre de 2026, la
     * sirena del Huawei movió su propio acelerómetro 0,67 m/s² durante 3,4 s y
     * su cascada lo leyó como «terremoto confirmado».
     *
     * Son tres fuentes y todas valen igual:
     *  - el timbre o una llamada en curso ([enLlamada]),
     *  - la sirena y la vibración de la alarma o del rescate,
     *  - **una baliza de la malla o un reenvío**, que son cuatro segundos de
     *    tono a todo volumen por el mismo chasis donde está el sensor.
     *
     * Al micrófono ya lo protegía la puerta anti-eco; al sismógrafo no lo
     * protegía nadie.
     */
    private fun altavozPropio(): Boolean =
        enLlamada() || enAlarma || enRescate || malla?.emitiendoAhora == true

    private fun enLlamada(): Boolean = try {
        when ((getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.mode) {
            AudioManager.MODE_RINGTONE,
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION -> true
            else -> false
        }
    } catch (_: Exception) { false }

    /** Los detectores ahora se encienden y se apagan solos varias veces al día.
     *  El autotest tarda segundos y comprueba código que no ha cambiado entre
     *  una vez y la siguiente, así que corre una sola vez por arranque. */
    private var detectoresComprobados = false

    private fun arrancarEscucha() {
        val e = escucha ?: return
        if (e.escuchando) return
        if (e.arrancar()) {
            if (!detectoresComprobados) {
                detectoresComprobados = true
                // igual que la sonda: comprobar no puede colgar la pantalla
                thread(name = "autotest-escucha", isDaemon = true) {
                    try { e.autotest(); comprobarDetectores() } catch (_: Exception) {}
                }
            }
            oyeEscuchando = true
            oyeDesde = System.currentTimeMillis()
        }
    }

    private fun conmutarEscucha() {
        val e = escucha ?: return
        if (e.escuchando) {
            escuchaApagadaAMano = true
            e.parar()
            oyeEscuchando = false
            oyeDesde = 0
            anotar("escucha del entorno apagada a mano")
        } else {
            escuchaApagadaAMano = false
            arrancarEscucha()
            anotar(if (e.escuchando) "escucha del entorno activa" else "sin micrófono para escuchar el entorno")
        }
    }

    private fun conmutarMalla() {
        val m = malla ?: return
        if (m.escuchando) {
            mallaApagadaAMano = true
            escucha?.parar()
            m.parar()
            mallaEscuchando = false
            oyeEscuchando = false
            oyeDesde = 0
            anotar("malla apagada a mano")
            actualizarNotificacion()
        } else {
            mallaApagadaAMano = false
            arrancarMalla()
            anotar(if (mallaEscuchando) "malla encendida" else "la malla no arranca: falta el micrófono")
        }
    }

    /* ================= alarma ================= */

    /* Blindado entero: si la sirena o el vibrador fallan en algún fabricante,
       el servicio tiene que seguir en pie. Un crash aquí deja a alguien sin
       nada, que es el peor resultado posible. */
    /**
     * @param automatico true cuando la alarma la dispara la app —sismógrafo,
     * malla o derrumbe oído—, es decir, cuando NADIE ha pulsado nada.
     *
     * En ese caso se despliega todo sin mirar los interruptores. El motivo es
     * simple: si la persona no ha reaccionado, tampoco ha podido decidir qué
     * dejar encendido, y un interruptor que alguien apagó hace tres semanas no
     * puede ser lo que decida si esta noche la encuentran. Cuando el botón lo
     * pulsa una persona sí se respetan sus opciones — está delante y ha elegido.
     */
    /**
     * «Silencio en la zona»: lo que se pulsa cuando el equipo de rescate pide
     * silencio para escuchar con sus micrófonos de contacto.
     *
     * Manda el tono a la malla tres veces —una sola ráfaga se pierde— y se calla
     * también este móvil, que si no sería el único gritando. Lo que NO se apaga
     * es la baliza de radio: no hace ruido, no interfiere con nadie y es lo único
     * que atraviesa el escombro.
     */
    /**
     * Cada cuánto preguntar al catálogo, según lo que cueste preguntar.
     *
     * Enchufado y en wifi no cuesta nada: cada quince segundos. Con datos o con
     * la batería tirando, preguntar a menudo se nota y lo que se gana es poco —
     * el catálogo tarda MINUTOS en publicar, así que afinar el sondeo a
     * segundos no adelanta el aviso, solo evita perderlo.
     *
     * Y por debajo del 15 % sin cargador se espacia a tres minutos: una app de
     * emergencia que agota la batería deja de ser una ayuda, y lo que de verdad
     * tiene que durar es la sirena y la baliza, no esto.
     */
    private fun intervaloCatalogo(): Long {
        return try {
            val cn = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val cap = cn?.activeNetwork?.let { cn.getNetworkCapabilities(it) }
            val wifi = cap?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
            val bm = getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            val cargando = bm?.isCharging == true
            val pct = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            when {
                pct in 0..15 && !cargando -> 180_000L
                cargando && wifi -> 15_000L
                wifi -> 45_000L
                else -> 90_000L
            }
        } catch (_: Exception) { 45_000L }
    }

    /** El botón EMITIR ALERTA AHORA de la pantalla de malla. */
    private fun emitirAlertaMalla(motivo: String = "a mano") {
        val m = malla
        if (m == null) {
            anotar("no se puede avisar: la malla no está encendida")
            return
        }
        /* Solo se anota si de verdad ha salido. Ver [MallaAcustica.emitirUna]:
           la vigilia nocturna llama aquí en cada evaluación del sismógrafo. */
        val salio = try { m.emitirUna(MallaAcustica.CODIGO_ALERTA) } catch (_: Exception) { false }
        if (salio) anotar("alerta emitida a la malla · $motivo")
    }

    private fun silencioZona() {
        anotar("SILENCIO EN LA ZONA: aviso a todos los móviles que me oigan.")
        try { sirena.stop() } catch (_: Exception) {}
        try { sonda?.pararTodo(); barridoOn = false } catch (_: Exception) {}
        try { malla?.silenciadoHasta = System.currentTimeMillis() + MallaAcustica.SILENCIO_ORDEN_MS } catch (_: Exception) {}
        val m = malla ?: return
        for (i in 0 until 3) {
            reloj.postDelayed({ try { m.emitirUna(MallaAcustica.CODIGO_SILENCIO) } catch (_: Exception) {} },
                i * 3500L)
        }
    }

    /**
     * Apagar SismoRed entera: la vigilancia, el micrófono, la radio y el propio
     * servicio.
     *
     * Existe porque tiene que existir. Una app que se queda corriendo en segundo
     * plano pase lo que pase, con micrófono y con un servicio que sobrevive a
     * cerrarla, necesita una puerta de salida clara — y que esa puerta no la
     * anule ella sola al siguiente arranque. Por eso se recuerda en `Opciones`.
     *
     * `START_NOT_STICKY` al devolver: sin eso el sistema volvería a levantar el
     * servicio que acabamos de parar.
     */
    private fun apagarDelTodo() {
        anotar("SismoRed apagada del todo. No vigila, no escucha y no queda nada en segundo plano.")
        vivo = false
        try { WatchdogReceiver.cancelar(this) } catch (_: Exception) {}
        opciones.apagada = true
        opciones.deberiaVigilar = false
        try { parar() } catch (_: Exception) {}
        try { sismo.armado = false; sismo.parar() } catch (_: Exception) {}
        try { postura?.parar() } catch (_: Exception) {}
        try { escucha?.parar() } catch (_: Exception) {}
        try { malla?.parar() } catch (_: Exception) {}
        try { sonda?.pararTodo() } catch (_: Exception) {}
        try { fichaLan?.escuchar(false); fichaLan?.emitir(false) } catch (_: Exception) {}
        try { radio?.parar() } catch (_: Exception) {}
        mallaEscuchando = false; oyeEscuchando = false; armado = false
        try { wakeLock?.release() } catch (_: Exception) {}
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        } catch (_: Exception) {}
        Log.i("SismoRed", "apagada del todo por el usuario")
        stopSelf()
    }

    /**
     * Abrir la ficha a pantalla completa desde el servicio.
     *
     * Se hace por notificación con `fullScreenIntent` además de por lanzamiento
     * directo, por lo mismo que la pregunta: lanzar una actividad desde segundo
     * plano está limitado, y esto tiene que salir sí o sí — es lo único que le
     * dice a quien te encuentra tu grupo sanguíneo.
     */
    private fun mostrarFichaSola(previa: Boolean = false) {
        /* En el camino real, sin ficha no hay nada que enseñar y abrir una
           pantalla en blanco encima del bloqueo solo estorba al que rescata.
           Pero en la vista previa la ficha vacía es exactamente el dato que hace
           falta ver: así es como te van a encontrar si no la rellenas. */
        if (Ficha(this).vacia()) {
            if (!previa) return
            anotar("Tu ficha está VACÍA: esto es lo que verá quien te encuentre.")
        }
        val i = Intent(this, FichaActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pi = PendingIntent.getActivity(this, 9, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            val n = Notification.Builder(this, CANAL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Ficha médica")
                .setContentText("Alguien te está buscando muy cerca")
                .setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .build()
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID_FICHA, n)
        } catch (_: Exception) {}
        try { startActivity(i) } catch (_: Exception) {}
        anotar("Te buscan al lado: enseño tu ficha en la pantalla.")
    }

    /* ===================== la cascada ===================== */

    /**
     * Ha llegado una prueba nueva: el sismógrafo, el micrófono o la malla. Se
     * juntan todas las que hay ahora mismo, se pregunta a [Cascada] y se hace lo
     * que diga. Nadie más decide.
     *
     * Antes cada detector llamaba a `panico()` por su cuenta, y de ahí venían los
     * dos falsos que podían encender la sirena sin que se hubiera movido nada: el
     * móvil cayéndose de la mesa y el generador diésel. Ahora los dos llegan
     * hasta aquí y aquí se paran.
     */
    private var sucesoWatchdog: Runnable? = null

    private fun cancelarWatchdogSuceso() {
        sucesoWatchdog?.let { reloj.removeCallbacks(it) }
        sucesoWatchdog = null
    }

    private fun reprogramarWatchdogSuceso() {
        cancelarWatchdogSuceso()
        val w = Runnable {
            if (sucesoDesde > 0L && preguntaHasta == 0L && !enAlarma && !enRescate) {
                anotar("suceso cerrado: sin corroboración tras ${SUCESO_TIMEOUT_MS / 1000} s")
                cerrarSuceso()
            }
        }
        sucesoWatchdog = w
        reloj.postDelayed(w, SUCESO_TIMEOUT_MS)
    }

    private fun pruebaNueva(motivo: String) {
        if (motivo.startsWith("caída")) ultimaCaida = System.currentTimeMillis()
        if (!sismo.armado && !enAlarma) { anotar("$motivo (vigilancia desarmada)"); return }
        if (sucesoDesde == 0L) {
            sucesoDesde = System.currentTimeMillis()
            pasosAlSuceso = postura?.pasos ?: -1L
            /* El régimen de ANTES del suceso, no el de ahora: sacudir un móvil
               que está en una mesa reinicia el reloj de quietud, y entonces el
               propio terremoto descalificaba al sismógrafo que tenía que verlo. */
            sucesoRegimen = try {
                postura?.regimenRecordado(sismo.quietoDesdeHace(), sismo.giroGrados)
                    ?: Postura.Regimen.DESCONOCIDO
            } catch (_: Exception) { Postura.Regimen.DESCONOCIDO }
            /* La luz solo se mira a partir de aquí: bajo escombros es de noche a
               las tres de la tarde, pero un bolsillo también es oscuro, así que
               sola no decide nada y no merece estar encendida todo el día. */
            try { postura?.vigilarLuz(true) } catch (_: Exception) {}
            try { ubicacion?.refrescar() } catch (_: Exception) {}
            reprogramarWatchdogSuceso()
        }
        evaluar(motivo)
    }

    /** Las pruebas de este instante, tal como las ve el servicio. */
    private fun pruebas(): Cascada.Pruebas {
        val p = postura
        val quieto = try { sismo.quietoDesdeHace() } catch (_: Exception) { 0L }
        val pasosDespues = if (p != null && p.hayPasos && pasosAlSuceso >= 0)
            (p.pasos - pasosAlSuceso).coerceAtLeast(0L).toInt() else -1

        // la evidencia se acumula mientras el suceso esté abierto, y no caduca
        val estruendoAhora = System.currentTimeMillis() - ultimoEstruendo < 60_000L
        if (sucesoDesde > 0L) {
            if (temblando) sucesoSacudida = true
            if (System.currentTimeMillis() - sismo.ultimaFuerte < 60_000L) sucesoFuerte = true
            /* En g, que es como lo dice la maqueta y como se entiende: el motor
               mide en m/s2. Se queda el pico del suceso, no el de ahora. */
            val gAhora = sismo.sacudida / 9.81
            if (gAhora > ultimaSacudidaG) ultimaSacudidaG = gAhora
            if (estruendoAhora) sucesoEstruendo = true
            if (saltoEntrante > 0) sucesoCorroborada = true
        }
        return Cascada.Pruebas(
            regimen = if (sucesoDesde > 0L) sucesoRegimen
                      else p?.regimenRecordado(quieto, sismo.giroGrados) ?: Postura.Regimen.DESCONOCIDO,
            sacudida = sucesoSacudida || temblando,
            /* Y si fue lo bastante grande como para no confundirse con una mano.
               Se acumula igual que el resto de la evidencia del suceso. */
            sacudidaFuerte = fuerteAhora(),
            ratioStaLta = sismo.ratioStaLta,
            ondaP = sismo.hayOndaP,
            estruendo = sucesoEstruendo || estruendoAhora,
            corroborada = sucesoCorroborada || saltoEntrante > 0,
            /* La alerta de fuera. Se suma a lo que mide el móvil, no lo
               sustituye: sola no abre nada, pero mientras esté en pie una
               sacudida ya no necesita que además se oiga el derrumbe. */
            alertaExterna = alertaExterna,
            alertaCatalogo = System.currentTimeMillis() < alertaCatalogoHasta,
            /* La vigilia nocturna dispara lo más caro que tiene la app sin
               preguntar a nadie, así que se le exigen cuatro cosas a la vez.
               Las dos últimas son por el mismo caso: a las tres de la
               mañana, después de media hora quieto, coger el móvil para ver
               la hora produce exactamente la misma señal que un sismo. Lo
               que no produce un sismo es girar el teléfono ni encender la
               pantalla. */
            vigiliaArmada = vigiliaArmadaAqui,
            sostenidaNocturna = enVigilia(opciones) && enReposoAhora &&
                sismo.sostenidoMs >= exigidoNocturno(sismo.sostenidoMs) &&
                sismo.quietoAntesDeLaRacha >= Opciones.VIGILIA_REPOSO_MIN_MS &&
                !sismo.hayMano &&
                /* Aquí había un `!enLlamada()`. Sobra desde que
                   [Sismografo.vibracionPropia] rompe la racha en origen con
                   cualquier altavoz propio sonando: si el móvil se está
                   sacudiendo a sí mismo, `sostenidoMs` ya vale cero y esta
                   condición no puede cumplirse. Dos guardias para lo mismo
                   solo sirven para que un día discrepen. Ver [altavozPropio]. */
                (p == null || p.interaccionHace() > 30_000L),
            caidaImpacto = huboCaida,
            preguntado = preguntaVencida,
            /* Y que la respuesta DURE. `estoyBien()` marca la bandera y
               acto seguido llama a `cerrarSuceso()`, que la borra: medido
               el 17 de septiembre, 180 ms despues de pulsar ESTOY BIEN el
               movil volvia a entrar en panico porque el suelo seguia
               moviendose. Contestar no puede depender de que el temblor
               haya parado; si ha dicho que esta bien, lo esta. */
            contestado = haContestado ||
                (contestoBien > 0L && System.currentTimeMillis() - contestoBien < CONTESTADO_VALE_MS),
            pasosDespues = pasosDespues,
            /* Solo cuenta si desbloqueó DESPUÉS del suceso: que hubiera mirado el
               móvil hace una hora no dice nada de ahora. */
            interaccion = p != null && sucesoDesde > 0 &&
                p.ultimaInteraccion > sucesoDesde,
            /* La mano vale igual, y con el mismo corte: tiene que aparecer
               DESPUÉS del suceso. Si ya estaba antes, el sismógrafo ni siquiera
               habría disparado. */
            manoDespues = sucesoDesde > 0 && sismo.ultimaMano > sucesoDesde,
            /* Y cuánto hace de la última vez, que es lo que decide si la sirena
               tiene sentido. −1 si no se sabe: no saber no puede costarle la
               sirena a quien duerme. */
            /* «Cuánto hace que alguien tocó el móvil» tiene que incluir
               tocarlo, no solo desbloquearlo. En el fallo de campo la app dijo
               «lleva horas sin tocarse: puede estar dormida» y sonó la sirena
               con el teléfono literalmente en la mano, porque `ultimaInteraccion`
               solo cuenta desbloqueos de pantalla. El movimiento también es
               alguien: `quietoDesdeHace()` se pone a cero con cualquier empujón
               de más de 0,6 m/s². */
            msDesdeInteraccion = minOf(
                p?.ultimaInteraccion?.let {
                    if (it > 0L) System.currentTimeMillis() - it else Long.MAX_VALUE
                } ?: Long.MAX_VALUE,
                quieto
            ).let { if (it == Long.MAX_VALUE) -1L else it },
            quietoMs = quieto,
            /* Golpes y grito, cada uno por su lado desde el 18 de septiembre de
               2026: no valen lo mismo y juntarlos hacía que el grito arrastrase
               al rescatista la certeza de los golpes. Ver [Cascada.Pruebas.gritoCerca].

               Aquí decía que el detector de grito «no se enciende nunca contra
               grabaciones humanas reales, comprobado contra las 21 del banco».
               Eso ya no es cierto y probablemente nunca lo fue: es justo la
               frase que `banco.py` avisa en su cabecera de haber producido
               mientras el port medía un detector que la app ya no llevaba.
               Pasado hoy el banco, de las 21 de Humanos salen 7 como GRITO.

               Y con el móvil en la mano esto NO cuenta. De campo: viendo un
               vídeo, el micrófono llamó «GRITO DE AUXILIO 85 %» a 706 Hz
               sostenidos del propio vídeo, y eso subió la decisión de PREGUNTAR
               a AUXILIO — baliza completa con sirena, saltándose el escalón
               silencioso.

               La frase que esta prueba sostiene es «se oye a alguien JUNTO AL
               MÓVIL», y el móvil de alguien enterrado no está en una mano ni
               reproduciendo nada. Si lo estás sujetando, lo que oye el micrófono
               eres tú o tu teléfono, no una persona bajo un escombro. */
            golpesCerca = oidoDeFiar && oidoReciente("golpes"),
            gritoCerca = oidoDeFiar && oidoReciente("grito"),
            socorroVecino = if (System.currentTimeMillis() < socorroVecinoHasta) socorroVecino else 0
        )
    }

    /** Se puede creer lo que oye el micrófono: nadie lo está sujetando y lleva
     *  un rato quieto. Sin esto, lo que se oye eres tú o tu propio teléfono. */
    private val oidoDeFiar: Boolean
        get() = !sismo.hayMano && sismo.quietoAntes > 20_000L

    /** ¿Ha saltado ese detector de [Escucha] en el último minuto? */
    private fun oidoReciente(clave: String): Boolean {
        val i = Escucha.CLAVES.indexOf(clave)
        if (i < 0) return false
        val t = oyeCuando.getOrNull(i) ?: return false
        return t > 0L && System.currentTimeMillis() - t < 60_000L
    }

    private fun evaluar(motivo: String) {
        val pr = pruebas()
        val d = Cascada.decidir(pr)
        cascadaQuien = d.quien
        cascadaMotivo = d.motivo
        /* Las cinco condiciones de la vigilia, una por una. Deducir cual
           falta desde fuera ya ha costado tres pruebas de campo. */
        Log.i("SismoRed", "cascada($motivo) -> $d · vigilia[" +
            "ventana=${enVigilia(opciones)} reposo=$enReposoAhora " +
            "sost=${sismo.sostenidoMs}/${Opciones.VIGILIA_SOSTENIDO_MS} " +
            "calma=${sismo.quietoAntesDeLaRacha / 1000}s/${Opciones.VIGILIA_REPOSO_MIN_MS / 1000}s " +
            "motor=${if (motorSonando) "sí" else "no"}(%.2f/%.2f) ".format(
                escucha?.graveFrac ?: 0.0, escucha?.planitudEsp ?: 1.0) +
            "mano=${sismo.hayMano} pantalla=${postura?.interaccionHace() ?: -1}]")
        /* La decision iba SOLO a logcat, que se borra en minutos. O sea que el
           registro guardaba lo que la app vio y el motivo, pero no lo que
           decidio hacer ni sobre quien: justo la linea que hoy explico por que
           la onda P disparaba sola. Sin ella hay que deducirlo. */
        if (d.accion != Cascada.Accion.NADA) anotar("decision · ${d.accion}/${d.quien}")
        /* Y la foto de las pruebas con las que se decidio, cuando la decision
           NO es «nada». Sin esto, un registro de campo dice que la app pregunto
           pero no con que evidencia, y las reglas de la cascada dejan de poder
           comprobarse contra lo que de verdad paso. */
        if (d.accion != Cascada.Accion.NADA) anotar(
            /* El motivo va DELANTE, y esto no es cosmetica. El motivo —que trae
               la amplitud y el ciclo del disparo— solo se anotaba en la rama
               NADA, asi que del disparo que SI decidia algo no quedaba escrita
               ni la amplitud. Analizando la noche del 8 al 9 de septiembre
               emparejé la linea de un NADA de 0,25 m/s² con las pruebas del
               disparo siguiente, un segundo despues, y sali persiguiendo una
               contradiccion que no existia.

               Y `fuerte` tiene dos origenes que el registro daba juntos: el
               latch del suceso abierto, o que el sismografo marcara algo fuerte
               en los ultimos 60 s. Van separados por lo mismo. */
            "pruebas · $motivo · ${pr.regimen} sac=${pr.sacudida} " +
            "fuerte=${pr.sacudidaFuerte}(suceso=$fuertePorSuceso reciente=$fuertePorReciente " +
            /* Sin sacudida fuerte previa el reloj vale 0, y restarlo daba
               «hace=1789671390s» —cincuenta y seis años— en una línea cuyo
               único trabajo es que los números se puedan creer. */
            "hace=${if (sismo.ultimaFuerte == 0L) "nunca"
                    else "${(System.currentTimeMillis() - sismo.ultimaFuerte) / 1000}s"}) " +
            "estruendo=${pr.estruendo} malla=${pr.corroborada} alerta=${pr.alertaExterna} " +
            /* Para poder ver la bandera sin tener que fingir un terremoto: es
               la que decide si la baliza de rescate se escala o se calla. */
            "mano=${pr.manoDespues} " +
            /* Los dos relojes de la vigilia nocturna. Sin ellos, cuando no
               dispara hay que deducir por que desde fuera, y eso ya costo una
               prueba de campo entera. */
            "vig=${sismo.sostenidoMs}/${Opciones.VIGILIA_SOSTENIDO_MS}ms " +
            "calma=${sismo.quietoAntesDeLaRacha / 1000}/${Opciones.VIGILIA_REPOSO_MIN_MS / 1000}s " +
            "ciclo=${"%.0f".format(sismo.cicloTrabajo * 100)}%"
        )
        when (d.accion) {
            Cascada.Accion.NADA -> {
                anotar("$motivo · ${d.motivo}")
                /* Quien está bien no es un espectador: su móvil es un nodo con
                   batería, con red y con alguien mirándolo. Se queda escuchando
                   y retransmitiendo la malla, que es el multiplicador más grande
                   que tiene esta red y no cuesta nada. */
                if (sucesoDesde > 0 && (haContestado || preguntaVencida)) {
                    cerrarSuceso()
                } else if (sucesoDesde > 0 && preguntaHasta == 0L && !enAlarma && !enRescate) {
                    reprogramarWatchdogSuceso()
                }
            }
            Cascada.Accion.PREGUNTAR_DISCRETA -> {
                cancelarWatchdogSuceso()
                preguntarDiscreta(d.motivo)
            }
            Cascada.Accion.PREGUNTAR -> {
                cancelarWatchdogSuceso()
                preguntar(false, d.motivo)
            }
            Cascada.Accion.AVISAR -> {
                cancelarWatchdogSuceso()
                preguntar(true, d.motivo)
            }
            Cascada.Accion.BALIZA -> {
                cancelarWatchdogSuceso()
                balizaSilenciosa(d)
            }
            Cascada.Accion.DESPERTAR -> {
                cancelarWatchdogSuceso()
                despertar(d)
            }
            Cascada.Accion.AVISAR_VECINO -> avisarVecino(d)
            Cascada.Accion.AUXILIO -> {
                cancelarWatchdogSuceso()
                anotar("${Cascada.rotulo(d.quien)} · ${d.motivo}")
                panico(d.motivo)
                /* De madrugada el aviso no puede quedarse en este móvil. Quien
                   duerme en el piso de al lado tiene los mismos segundos que
                   tú, y es el único momento en que este teléfono sabe algo
                   antes que su dueño. Va aquí y no dentro de `panico` porque
                   un pánico por voz no es un terremoto: esto solo sale cuando
                   lo ha dicho el sismógrafo. */
                if (pr.sostenidaNocturna) emitirAlertaMalla("vigilia nocturna")
            }
        }
    }

    /**
     * La pregunta que resuelve lo que ningún sensor puede resolver.
     *
     * No se intenta adivinar si está enterrada: se le pregunta, y el silencio es
     * la respuesta. Es el mismo mecanismo que la detección de caídas de los
     * relojes, y cubre de una vez los cuatro escenarios — la que huye contesta o
     * sigue andando, la que duerme no contesta, la inconsciente no contesta y la
     * que tiene el móvil a tres metros tampoco.
     *
     * Con [conRuido] suena además la sirena: es el caso de la que está dormida en
     * un quinto piso y no se ha enterado de nada. Es la única razón por la que
     * existe una sirena que se enciende sola.
     */
    private fun preguntar(conRuido: Boolean, motivo: String) {
        cancelarWatchdogSuceso()
        val ahoraP = System.currentTimeMillis()
        if (preguntaHasta > ahoraP) return                          // ya está preguntada
        /* Y no se vuelve a preguntar en un rato. De campo: «se disparaba más de
           una vez la pregunta». Pasa porque la cascada se reevalúa con cada
           prueba nueva, y si entre medias el caso se cierra —basta con que
           alguien toque el móvil, que eso cuenta como señal de vida— la
           siguiente vibración vuelve a abrirlo y a preguntar. Encadenado, eso
           es una app que interroga sola cada minuto.
        
           Cinco minutos. Si de verdad hay un terremoto con réplicas, la primera
           pregunta ya resolvió el caso: o contestaste, o se encendió la baliza. */
        if (ahoraP - ultimaPregunta < PREGUNTA_REPOSO_MS) {
            anotar("ya te pregunté hace poco: no vuelvo a preguntar todavía")
            return
        }
        ultimaPregunta = ahoraP
        preguntaHasta = System.currentTimeMillis() + PREGUNTA_MS
        /* CON EL MOTIVO, que es lo que faltaba. `preguntar` recibia `motivo`
           —la regla de la cascada que decidio— y lo tiraba a la basura: el
           registro ponia siempre la misma frase. Al mirar los registros de campo
           del 3 de septiembre no habia forma de saber POR QUE habia preguntado
           cuatro veces en una hora, ni de distinguir una decision real de un
           simulacro. Guardar la razon justo donde se toma la decision es la
           diferencia entre diagnosticar y adivinar. */
        anotar((if (conRuido) "TERREMOTO · te despierto y te pregunto si estás bien"
                else "TERREMOTO · ¿estás bien? Tienes ${PREGUNTA_MS / 1000} s para contestar") +
               " · $motivo")
        if (conRuido) {
            try { sirena.start() } catch (_: Exception) {}
            try { destello("pregunta") } catch (_: Exception) {}
        }
        try { reloj.post { sacarPregunta() } } catch (_: Exception) {}
        try { actualizarNotificacion() } catch (_: Exception) {}
        preguntaTarea?.let { reloj.removeCallbacks(it) }
        val t = Runnable {
            /* Se acabó el tiempo. A partir de aquí el silencio ya es una
               respuesta, y la cascada vuelve a decidir con eso encima de la
               mesa. */
            preguntaVencida = true
            preguntaHasta = 0L
            quitarPregunta()
            if (conRuido) try { sirena.stop() } catch (_: Exception) {}
            evaluar("nadie ha contestado")
        }
        preguntaTarea = t
        reloj.postDelayed(t, PREGUNTA_MS)
    }

    /**
     * Sacar la pregunta a la cara, pase lo que pase.
     *
     * Se intenta por los dos caminos a la vez y a propósito:
     *
     *  1. **Lanzar la pantalla.** Funciona con la app abierta o recién usada.
     *  2. **Una notificación con `fullScreenIntent`.** Es la que funciona con el
     *     móvil bloqueado en la mesilla, que es el caso que importa.
     *
     * El segundo camino puede estar cerrado: desde Android 14 el permiso de
     * pantalla completa solo se concede solo a apps de llamada o de alarma. Si
     * está denegado, la notificación sigue saliendo como aviso de máxima
     * prioridad — se ve, se oye y se puede contestar desde ella. **Lo que no
     * puede pasar nunca es que no se pregunte**, y por eso hay dos caminos y
     * ninguno depende del otro.
     */
    private fun preguntarDiscreta(motivo: String) {
        cancelarWatchdogSuceso()
        val ahoraP = System.currentTimeMillis()
        if (preguntaHasta > ahoraP) return
        if (ahoraP - ultimaPregunta < PREGUNTA_REPOSO_MS) {
            anotar("aviso discreto omitido: ya pregunté hace poco")
            return
        }
        ultimaPregunta = ahoraP
        preguntaHasta = ahoraP + PREGUNTA_MS
        /* Quién lo pide cambia lo que hay que decir, y decir lo que no es
           deja a la persona sin saber qué hacer: si cree que lo ha detectado
           SU móvil se queda quieta esperando; sabiendo que viene del de al
           lado, puede ir. */
        val porVecino = System.currentTimeMillis() < socorroVecinoHasta
        anotar((if (porVecino) "UN VECINO PIDE AYUDA — aviso discreto (sin sirena)"
                else "SISMO EN REPOSO — aviso discreto no invasivo (sin sirena)") + " — $motivo")
        try { reloj.post { sacarPreguntaDiscreta(porVecino) } } catch (_: Exception) {}
        preguntaTarea?.let { reloj.removeCallbacks(it) }
        val t = Runnable {
            // El aviso discreto expira en silencio si nadie contesta.
            // NUNCA escala a sirena, baliza ni pánico para evitar falsos positivos y desinstalaciones.
            preguntaHasta = 0L
            quitarPreguntaDiscreta()
            anotar("aviso discreto expiró sin respuesta: cerrado en silencio (seguridad ante falsos positivos)")
            cerrarSuceso()
        }
        preguntaTarea = t
        reloj.postDelayed(t, PREGUNTA_MS)
    }

    /**
     * El aviso de que ha temblado cerca, y que **no se borra solo**.
     *
     * Va aparte de la pregunta discreta a propósito. La pregunta caduca al
     * minuto porque la pregunta caduca: pasado ese rato, «¿estás bien?» ya no
     * tiene sentido. Pero «ha habido un M3.6 a 72 km» sigue siendo verdad una
     * hora después, y borrarlo dejaba al usuario sin enterarse de nada si no
     * miraba el móvil en ese minuto exacto. Pasó dos veces el 17 de septiembre,
     * con los sismos de Istmina de las 12:22 y las 13:52.
     *
     * Sin sonido y sin vibración: esto no despierta a nadie, solo está ahí
     * cuando coges el móvil. Y se descarta como cualquier otra notificación.
     */
    private fun avisarSismoCercano(mag: Double, distKm: Double, lugar: String, fuente: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(NotificationManager::class.java)
                if (nm.getNotificationChannel(CANAL_SISMO_CERCANO) == null) {
                    nm.createNotificationChannel(NotificationChannel(
                        CANAL_SISMO_CERCANO, "Sismos cerca",
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply {
                        description = "Terremotos publicados por un catálogo público cerca de ti"
                        setShowBadge(true)
                        enableVibration(false)
                        setSound(null, null)
                    })
                }
            }
            val abrir = PendingIntent.getActivity(
                this, 15, Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val hora = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date())
            val n = Notification.Builder(this, CANAL_SISMO_CERCANO)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Temblor cerca · M%.1f".format(mag))
                .setContentText("$lugar · ${distKm.toInt()} km · $hora · $fuente")
                .setCategory(Notification.CATEGORY_EVENT)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(abrir)
                .build()
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(ID_SISMO_CERCANO, n)
        } catch (e: Exception) {
            Log.e("SismoRed", "no se pudo avisar del sismo cercano", e)
        }
    }

    private fun sacarPreguntaDiscreta(porVecino: Boolean) {
        val abrir = PendingIntent.getActivity(
            this, 14, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val canalId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CANAL_DISCRETO) == null) {
                val c = NotificationChannel(
                    CANAL_DISCRETO, "Avisos no invasivos", NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Avisos suaves de corroboración sísmica sin falsa alarma"
                    setShowBadge(true)
                }
                nm.createNotificationChannel(c)
            }
            CANAL_DISCRETO
        } else CANAL

        val b = Notification.Builder(this, canalId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            /* Ni «detectamos movimiento en reposo» —falso cuando dispara el
               catálogo— ni el `motivo`, que está escrito para el registro. */
            .setContentTitle(if (porVecino) getString(R.string.vecino_tit) else "¿Sentiste un temblor?")
            .setContentText(if (porVecino) getString(R.string.vecino_txt)
                            else "Pulsa si estás bien o si fue falsa alarma.")
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(abrir)
            .addAction(Notification.Action.Builder(null, "ESTOY BIEN", pi(ACCION_ESTOY_BIEN)).build())
            .addAction(Notification.Action.Builder(null, "FALSA ALARMA", pi(ACCION_FALSA_ALARMA)).build())

        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(ID_PREGUNTA_DISCRETA, b.build())
        } catch (_: Exception) {}
    }

    private fun quitarPreguntaDiscreta() {
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(ID_PREGUNTA_DISCRETA) }
        catch (_: Exception) {}
    }

    private fun falsaAlarma() {
        preguntaHasta = 0L
        quitarPreguntaDiscreta()
        preguntaTarea?.let { reloj.removeCallbacks(it); preguntaTarea = null }
        anotar("usuario descartó el movimiento como falsa alarma")
        cerrarSuceso()
    }

    private fun sacarPregunta() {
        val abrir = PendingIntent.getActivity(
            this, 7, Intent(this, PreguntaActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(this, CANAL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("¿Estás bien?")
            .setContentText("Si no contestas, este móvil empezará a emitir tu señal.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(abrir)
            .setFullScreenIntent(abrir, true)
            .addAction(Notification.Action.Builder(null, "ESTOY BIEN", pi(ACCION_ESTOY_BIEN)).build())
            .addAction(Notification.Action.Builder(null, "NECESITO AYUDA", pi(ACCION_PANICO)).build())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) b.setForegroundServiceBehavior(1)
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(ID_PREGUNTA, b.build())
        } catch (_: Exception) {}
        try { startActivity(Intent(this, PreguntaActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) }
        catch (_: Exception) {}
    }

    private fun quitarPregunta() {
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(ID_PREGUNTA) }
        catch (_: Exception) {}
        quitarPreguntaDiscreta()
    }

    /** Ha pulsado ESTOY BIEN, en la pantalla o en la notificación. */
    private fun estoyBien() {
        haContestado = true
        preguntaHasta = 0L
        /* Lo primero, cortar la rampa: si esta subiendo y alguien contesta, no
           puede llegar el siguiente peldano por estar ya encolado. */
        pararRampa()
        quitarPregunta()
        preguntaTarea?.let { reloj.removeCallbacks(it) }
        preguntaTarea = null
        try { sirena.stop() } catch (_: Exception) {}
        /* Callar la sirena no era contestar: paraba el ruido y dejaba encendido
           todo lo demás que dice «aquí hay una víctima». Quien acaba de decir
           que está bien seguía con la linterna haciendo SOS, la pantalla
           parpadeando y la baliza de radio emitiendo su ficha médica, o sea
           mandando a los rescatistas a buscar a alguien que está de pie.
           `enAlarma` se apaga con ellos; el relevo no depende de esa bandera
           sino de `repetidor`, así que la malla sigue en pie. */
        enAlarma = false
        try { linterna?.parar() } catch (_: Exception) {}
        try { vibrador?.cancel() } catch (_: Exception) {}
        try { radio?.parar(); radioEmitiendo = false; radioMotivo = "estoy bien" } catch (_: Exception) {}
        try { fichaLan?.emitir(false) } catch (_: Exception) {}
        /* Y CALLAR LA MALLA, que se quedaba fuera de esta lista.

           Medido entre dos móviles el 17 de septiembre: uno dice «estoy bien»,
           se apaga todo lo demás y el pulso de rescate sigue saliendo cada
           12 s para siempre. El otro lo oye y lo reemite, este oye el reenvío,
           y a los noventa segundos uno de los dos vuelve a entrar en pánico él
           solo. Dos móviles bastan para realimentarse; en un edificio con
           veinte esto no se para nunca.

           Se corta la EMISIÓN, no la escucha: el relevo sigue en pie y por eso
           justo debajo se enciende la malla si estaba apagada. Quien está bien
           deja de pedir ayuda y pasa a pasarla. */
        try { malla?.pararEmision() } catch (_: Exception) {}
        if (enRescate) {
            enRescate = false
            anotar("modo rescate apagado: has dicho que estás bien")
        }
        /* Y desde este momento el móvil trabaja para los demás. Encender la
           malla aquí es la única vez que se enciende sin que la pida el usuario,
           y se dice en el registro en vez de hacerlo por la espalda: quien acaba
           de decir que está bien en un terremoto es exactamente el nodo que la
           red necesita, y no va a acordarse de encenderlo. */
        repetidor = true
        contestoBien = System.currentTimeMillis()
        Log.i("SismoRed", "estoyBien(): paso a repetidor")
        if (!mallaEscuchando) { mallaApagadaAMano = false; arrancarMalla() }
        anotar("Estás bien. Este móvil pasa a repetidor: escucha, no suena y reenvía lo que oiga.")
        cerrarSuceso()
        try { actualizarNotificacion() } catch (_: Exception) {}
    }

    /**
     * La baliza sin ruido. Es la acción barata de la escalera: no despierta a
     * nadie, no gasta apenas y puede ser lo único que quede de alguien. Por eso
     * su listón es mucho más bajo que el de la sirena — y por eso se enciende
     * aunque no haya ninguna prueba de que la persona esté aquí, diciendo con
     * todas las letras lo que se sabe y lo que no.
     */
    /* Cuanto dura cada peldano de la rampa. Cortos a proposito: si de verdad
       hay alguien, se despierta en el primero o en el segundo, y si no hay
       nadie no tiene sentido alargar la duda. */
    private val rampaMs = longArrayOf(4000L, 5000L)
    private var rampaTarea: Runnable? = null

    /**
     * Despertar a quien no ha contestado, en rampa.
     *
     * Es la unica sirena automatica que queda, y no la dispara un sensor: la
     * dispara el silencio de alguien a quien se acaba de preguntar. Por eso se
     * puede permitir sonar sin una segunda opinion, y por eso sube por peldanos
     * en vez de arrancar a todo volumen — equivocarse cuesta un zumbido.
     *
     *   1. Vibracion larga. Despierta al que tiene el movil en la mesilla y no
     *      molesta a nadie mas.
     *   2. Pulso sonoro, el mismo del modo rescate: se oye en una habitacion sin
     *      ser una sirena.
     *   3. Todo. Si a los nueve segundos sigue sin contestar nadie, o esta
     *      inconsciente o no esta.
     *
     * Cualquier señal de vida corta la rampa, y eso lo hace `cerrarSuceso` y
     * `estoyBien` al llamar a `pararRampa`.
     */
    /**
     * Despertar a alguien porque **un vecino** necesita ayuda.
     *
     * No es una alarma de este móvil y no puede parecerlo: no enciende baliza,
     * no entra en modo víctima y no escala si nadie contesta. Ver
     * [Cascada.Accion.AVISAR_VECINO].
     *
     * Lo importante de la pantalla es que diga **por qué**. «¿Estás bien?» a
     * las tres de la mañana, sin contexto, no deja actuar a nadie: saber que
     * el aviso viene del móvil de al lado y no del tuyo es lo que convierte a
     * quien despiertas en alguien que puede ayudar.
     */
    private fun avisarVecino(d: Cascada.Decision) {
        if (enAlarma || enRescate) return
        val ahora = System.currentTimeMillis()
        if (ahora - ultimoAvisoVecino < SOCORRO_VALE_MS) return
        ultimoAvisoVecino = ahora
        anotar("VECINO PIDE AYUDA · ${d.motivo}")
        /* Vibración larga y pulso, que es la rampa de despertar sin la sirena
           de víctima al final. Suena distinto a propósito: quien llegue a
           buscar tiene que poder distinguir de oído a quién están avisando de
           quién pide ayuda. */
        try { vibrarLargo() } catch (_: Exception) {}
        try { destello("vecino") } catch (_: Exception) {}
        reloj.postDelayed({ if (!enAlarma && !enRescate) try { pulsoRescate() } catch (_: Exception) {} }, 1500L)
        sacarAvisoVecino(d.motivo)
    }

    private var ultimoAvisoVecino = 0L

    /** La notificación del aviso, a pantalla completa si el sistema deja: el
     *  caso que importa es el móvil bloqueado en la mesilla. */
    private fun sacarAvisoVecino(motivo: String) {
        val abrir = PendingIntent.getActivity(
            this, 21, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_stat_sismored)
            .setContentTitle(getString(R.string.vecino_tit))
            .setContentText(getString(R.string.vecino_txt))
            .setStyle(Notification.BigTextStyle().bigText(getString(R.string.vecino_largo)))
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(abrir)
            .setFullScreenIntent(abrir, true)
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(ID_VECINO, b.build())
        } catch (_: Exception) {}
    }

    private fun despertar(d: Cascada.Decision) {
        if (enAlarma || enRescate) return
        if (rampaTarea != null) return                      // ya esta subiendo
        anotar("${Cascada.rotulo(d.quien)} · ${d.motivo}")

        /* La baliza se enciende YA, en el primer peldano y no al final: si esta
           inconsciente, los nueve segundos de rampa no pueden ser nueve segundos
           sin emitir. La rampa es para despertarla, no para decidir. */
        balizaSilenciosa(d)

        var peldano = 0
        val tarea = object : Runnable {
            override fun run() {
                if (haContestado || enAlarma) { pararRampa(); return }
                when (peldano) {
                    0 -> vibrarLargo()
                    1 -> pulsoRescate()
                    else -> { pararRampa(); panico(d.motivo); return }
                }
                reloj.postDelayed(this, rampaMs[peldano])
                peldano++
            }
        }
        rampaTarea = tarea
        reloj.post(tarea)
    }

    private fun pararRampa() {
        rampaTarea?.let { reloj.removeCallbacks(it) }
        rampaTarea = null
    }

    private fun vibrarLargo() {
        if (!opciones.vibracion) return
        try {
            val patron = longArrayOf(0, 800, 300, 800, 300, 800)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrador?.vibrate(VibrationEffect.createWaveform(patron, -1))
            } else {
                @Suppress("DEPRECATION") vibrador?.vibrate(patron, -1)
            }
        } catch (_: Exception) {}
    }

    private fun balizaSilenciosa(d: Cascada.Decision) {
        anotar("${Cascada.rotulo(d.quien)} · ${d.motivo}")
        if (enAlarma || enRescate) return
        try { emitirRadio(Baliza.ALARMA) } catch (_: Exception) {}
        try { fichaLan?.emitir(true); fichaLan?.escuchaFuerte(true) } catch (_: Exception) {}
        try { malla?.emitirEnBucle(saltoEntrante + 1) } catch (_: Exception) {}
        /* Y se pasa a rescate directamente, sin la sirena de por medio: quien no
           contesta no va a apagarla, y diez minutos de sirena son una mordida
           seria a la batería que aquí no compra nada. */
        rescate()
    }

    /**
     * Ha entrado una alerta sísmica de fuera. Arma el móvil; no lo dispara.
     *
     * Las tres cosas que hace, en orden de importancia:
     *
     *  1. **Repartirla por la malla**, si no viene ya de ahí. Es la razón de ser
     *     de todo esto: en un salón de clases la alerta de Google no le llegó al
     *     80 % de los móviles, porque hace falta internet y la función activada.
     *     Los que no la reciben están al lado de alguien que sí.
     *  2. **Avisar**, que es lo que sirve en los segundos que quedan. Suena y
     *     destella, y no calla la sirena de nadie.
     *  3. **Armar la vigilancia**: umbral al mínimo y `alertaExterna` en pie
     *     durante [MallaAcustica.ALERTA_VALE_MS]. Cuando llegue la sacudida, la
     *     cascada ya no tiene que dudar de ella.
     *
     * Lo que NO hace: encender la baliza. Ver [Cascada.Pruebas.alertaExterna].
     */
    private fun alertaSismicaExterna(porLaMalla: Boolean) {
        val ahora = System.currentTimeMillis()
        /* Anti-eco: la malla reemite y podría volver a entrar por donde salió.
           Diez segundos bastan para que la vuelta muera sola. */
        if (ahora < alertaExternaHasta - MallaAcustica.ALERTA_VALE_MS + 10_000L) return
        alertaExternaHasta = ahora + MallaAcustica.ALERTA_VALE_MS

        anotar(
            if (porLaMalla) "ALERTA SÍSMICA por la malla · viene un terremoto. Protégete."
            else "ALERTA SÍSMICA de Google · viene un terremoto. Protégete."
        )
        /* Y se reparte, que es lo único que Google no puede hacer: los móviles
           sin internet de alrededor no la han recibido. Si vino por la malla, la
           propia malla ya se encarga de reemitirla una vez. */
        if (!porLaMalla) try { malla?.emitirUna(MallaAcustica.CODIGO_ALERTA) } catch (_: Exception) {}
        try { destello("alerta sísmica") } catch (_: Exception) {}
        /* Tres pulsos, no el SOS: esto es «viene un terremoto», no «hay alguien
           enterrado». Confundir los dos avisos en la mano es confundirlos en la
           cabeza. */
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                vibrador?.vibrate(VibrationEffect.createWaveform(
                    longArrayOf(0, 400, 200, 400, 200, 400), -1))
            else @Suppress("DEPRECATION")
                vibrador?.vibrate(longArrayOf(0, 400, 200, 400, 200, 400), -1)
        } catch (_: Exception) {}
        /* La vigilancia al mínimo mientras dure la ventana: si el terremoto llega
           de verdad, que no se pierda el primer segundo discutiendo el umbral. */
        try {
            sismo.armado = true
            sismo.umbral = opciones.umbralReposo
            umbralActivo = sismo.umbral
        } catch (_: Exception) {}
        try { actualizarNotificacion() } catch (_: Exception) {}
    }

    /**
     * El simulacro que llega hasta el final.
     *
     * Se inyecta UNA sola cosa —la sacudida, con el móvil en reposo— y a partir
     * de ahí no se toca nada: la misma cascada, la misma pregunta, la misma
     * cuenta atrás y la misma decisión. Si nadie contesta en [PREGUNTA_MS], sale
     * BALIZA y se enciende de verdad.
     *
     * Que sea de verdad es el punto. Un simulacro que no encienda la baliza no
     * prueba lo único que hacía falta probar, que es justo el tramo que nunca se
     * ha visto funcionar fuera del autotest.
     */
    private fun simulacroCompleto() {
        if (enAlarma || enRescate) { anotar("Ya hay una alarma en marcha: el simulacro no hace nada."); return }
        anotar("SIMULACRO COMPLETO · me creo una sacudida y dejo correr la cascada entera. " +
               "Si no contestas, la baliza se enciende DE VERDAD.")
        if (sucesoDesde == 0L) {
            sucesoDesde = System.currentTimeMillis()
            pasosAlSuceso = postura?.pasos ?: -1L
            try { postura?.vigilarLuz(true) } catch (_: Exception) {}
            try { ubicacion?.refrescar() } catch (_: Exception) {}
        }
        /* Las dos únicas cosas que se dan por puestas, y son las que un
           terremoto pondría: que el móvil estaba en reposo y que ha temblado.
           Todo lo demás —los pasos, la interacción, el silencio— se mide igual
           que siempre, que es lo que hace que esto sea un ensayo y no una
           maqueta. */
        sucesoRegimen = Postura.Regimen.EN_REPOSO
        sucesoSacudida = true
        evaluar("simulacro completo")
    }

    /**
     * Lo ha dicho la persona que pedía ayuda: ya la han encontrado.
     *
     * Apaga la baliza entera —sirena, radio, malla, ficha por Wi-Fi— y cierra el
     * suceso, pero **deja el móvil escuchando y retransmitiendo**. Quien acaba
     * de ser rescatado tiene un teléfono con batería en medio de una zona sin
     * red, y eso es exactamente el nodo que hace falta ahí: la misma decisión
     * que se toma con quien contesta que está bien.
     */
    private fun rescatado() {
        if (!enAlarma && !enRescate) { anotar("No había ninguna baliza encendida."); return }
        anotar("TE HAN ENCONTRADO · apago la baliza. Este móvil se queda de repetidor para los demás.")
        parar()
        repetidor = true
        contestoBien = System.currentTimeMillis()
        if (!mallaEscuchando) { mallaApagadaAMano = false; arrancarMalla() }
        try { actualizarNotificacion() } catch (_: Exception) {}
    }

    /**
     * Lo ha dicho quien buscaba: ya la ha sacado.
     *
     * Deja de llamar hacia abajo, que es lo que hay que parar — la llamada hace
     * ruido a propósito y pide a los móviles enterrados que emitan más seguido,
     * y las dos cosas sobran cuando ya no se busca a nadie ahí.
     */
    private fun rescateHecho() {
        anotar("RESCATADO · dejo de llamar hacia abajo.")
        try { sirena.stop() } catch (_: Exception) {}
        try { malla?.relayMs = MallaAcustica.RELAY_MS } catch (_: Exception) {}
        try { malla?.pararEmision() } catch (_: Exception) {}
        try { if (!enAlarma && !enRescate) radio?.parar() } catch (_: Exception) {}
        try { actualizarNotificacion() } catch (_: Exception) {}
    }

    /** El suceso se ha resuelto: se limpia para poder ver el siguiente. */
    private fun cerrarSuceso() {
        cancelarWatchdogSuceso()
        pararRampa()
        ultimaSacudidaG = 0.0
        sucesoDesde = 0L
        sucesoSacudida = false; sucesoFuerte = false; sucesoEstruendo = false; sucesoCorroborada = false
        sucesoRegimen = Postura.Regimen.DESCONOCIDO
        pasosAlSuceso = -1L
        preguntaVencida = false
        haContestado = false
        preguntaHasta = 0L
        preguntaTarea?.let { reloj.removeCallbacks(it) }
        preguntaTarea = null
        quitarPregunta()
        quitarPreguntaDiscreta()
        try { postura?.vigilarLuz(false) } catch (_: Exception) {}
    }

    private fun panico(motivo: String, automatico: Boolean = true) {
        Log.i("SismoRed", "panico($motivo) auto=$automatico enAlarma=$enAlarma enRescate=$enRescate")
        if (enAlarma) return
        /* Quien está en modo rescate ya ha decidido durar horas en vez de gritar
           un rato. Una baliza ajena no puede deshacer esa decisión y fundirle la
           batería: se sigue retransmitiendo, pero no se enciende la sirena. */
        if (enRescate) { anotar("alerta recibida en modo rescate: se retransmite sin sirena"); return }
        enAlarma = true
        /* Se acabó lo de repetir para otros: ahora el que necesita la red es
           este móvil. */
        repetidor = false
        alarmaDesde = System.currentTimeMillis()
        // con disparo automático manda la autonomía, no las casillas
        val todo = automatico
        if (todo || opciones.sirena) try { sirena.start() } catch (_: Exception) {}
        if (todo || opciones.vibracion) try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrador?.vibrate(VibrationEffect.createWaveform(patronSos, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrador?.vibrate(patronSos, 0)
            }
        } catch (_: Exception) {}
        if (todo || opciones.linterna) try { linterna?.sos(true) } catch (_: Exception) {}
        if (todo || opciones.pantalla) try { destello("sos") } catch (_: Exception) {}
        /* Propagar es la mitad del sentido de la app: quien recibe la alerta la
           reemite con un salto más, y así la alerta llega más lejos que el grito
           de nadie. Se emite en bucle mientras dure la alarma. */
        if (todo || opciones.baliza) try { malla?.emitirEnBucle(saltoEntrante + 1) } catch (_: Exception) {}
        if (automatico) anotar("nadie ha reaccionado: se enciende todo sin esperar")
        try { emitirRadio(Baliza.ALARMA) } catch (_: Exception) {}
        try { fichaLan?.emitir(true); fichaLan?.escuchaFuerte(true) } catch (_: Exception) {}
        try { actualizarNotificacion() } catch (_: Exception) {}
        anotar("ALARMA: $motivo")
    }

    private fun parar() {
        val estaba = enAlarma || enRescate
        enAlarma = false
        enRescate = false
        rescateTarea?.let { reloj.removeCallbacks(it) }
        rescateTarea = null
        /* DETENER también cancela la pregunta y el suceso en curso: quien pulsa
           DETENER está delante del móvil, y eso ya es la respuesta. Y sale del
           modo repetidor: si alguien para la app entera, la para entera. */
        repetidor = false
        cerrarSuceso()
        try { sirena.stop() } catch (_: Exception) {}
        try { vibrador?.cancel() } catch (_: Exception) {}
        try { linterna?.parar() } catch (_: Exception) {}
        /* DETENER tiene que callar TODO lo que suene, no solo la alarma. El
           barrido y el tono del doppler los lanza una persona y se quedaban
           sonando por su cuenta: el barrido no tenía botón de apagado visible y
           el tono sobrevivía a cualquier excepción de la medida. Un pitido que no
           se puede callar es peor que uno que no suena. */
        try { sonda?.pararTodo(); barridoOn = false } catch (_: Exception) {}
        try { interfono?.parar() } catch (_: Exception) {}
        try { radio?.parar(); radioEmitiendo = false; radioMotivo = "apagada" } catch (_: Exception) {}
        try { fichaLan?.emitir(false) } catch (_: Exception) {}
        try { destello("off") } catch (_: Exception) {}
        /* Silenciar calla este móvil, no la red: se deja de emitir en bucle y se
           ignora la alarma local un minuto, pero la malla sigue escuchando y
           sigue retransmitiendo lo que oiga. Quien se harta del ruido no debería
           poder cortar la cadena para todos los que vienen detrás. */
        try {
            malla?.pararEmision()
            if (estaba) malla?.silenciadoHasta = System.currentTimeMillis() + SILENCIO_MS
        } catch (_: Exception) {}
        saltoEntrante = 0
        // que la siguiente vigilancia no arranque con lo que arrastraba
        try { sismo.reiniciar() } catch (_: Exception) {}
        try { actualizarNotificacion() } catch (_: Exception) {}
    }

    /* ================= llamada y respuesta ================= */

    /**
     * Llama hacia abajo. Lo pulsa quien busca, de pie sobre los escombros.
     *
     * Van dos cosas a la vez y cada una es para un oyente distinto:
     *
     *  - **La sirena**, que es para una PERSONA. Si quien está debajo está
     *    consciente, saber que hay alguien arriba cambia lo que hace y cuánto
     *    aguanta. Es lo más importante de este botón.
     *  - **El tono de llamada**, que es para los MÓVILES. Quien lo oiga contesta
     *    con la baliza a tope durante unos minutos.
     *
     * Aviso honesto sobre el alcance: 18,4 kHz atraviesa muy mal una capa de
     * escombros — el ultrasonido se absorbe enseguida. Entre móviles al aire
     * libre funciona; enterrado, lo que va a llegar es la sirena.
     */
    private fun llamar() {
        anotar("llamando: sirena, tono acústico y aviso por radio")
        /* Por radio, que es lo único que de verdad cruza una capa de escombros:
           el móvil del que busca se anuncia como BUSCANDO y los enterrados, que
           escuchan a ratos, contestan con la baliza a tope. */
        try {
            // el que busca no emite ficha: no es una víctima, es un aviso
            radio?.emitir(Baliza.BUSCANDO, 0, 0, "")
            reloj.postDelayed({
                try { if (enAlarma || enRescate) emitirRadio(if (enRescate) Baliza.RESCATE else Baliza.ALARMA)
                      else radio?.parar() } catch (_: Exception) {}
            }, LLAMADA_RADIO_MS)
        } catch (_: Exception) {}
        try { sirena.start() } catch (_: Exception) {}
        reloj.postDelayed({ try { sirena.stop() } catch (_: Exception) {} }, LLAMADA_MS)
        // repetida, porque el otro lado exige corroboración antes de fiarse
        val tarea = object : Runnable {
            var n = 0
            override fun run() {
                try { malla?.emitirUna(MallaAcustica.CODIGO_LLAMADA) } catch (_: Exception) {}
                if (++n < 4) reloj.postDelayed(this, 3200)
            }
        }
        reloj.post(tarea)
    }

    /**
     * Alguien ha llamado desde arriba. Se contesta con todo.
     *
     * La baliza pasa de una cada 4 s a una cada segundo y medio durante tres
     * minutos: es el momento en que más falta hace que se oiga, porque hay
     * alguien buscando justo encima. Y se vibra y se destella aunque la alarma
     * esté silenciada — si quien está debajo está consciente, tiene que saber
     * que le han oído.
     */
    private fun respuestaReforzada() {
        anotar("TE ESTÁN BUSCANDO · alguien ha llamado desde arriba")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrador?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300, 150, 300), -1))
            } else {
                @Suppress("DEPRECATION") vibrador?.vibrate(longArrayOf(0, 300, 150, 300, 150, 300), -1)
            }
        } catch (_: Exception) {}
        try { destello("uno") } catch (_: Exception) {}
        try { linterna?.destello(400) } catch (_: Exception) {}
        try {
            malla?.relayMs = RESPUESTA_MS
            malla?.emitirEnBucle(maxOf(1, saltoEntrante + 1))
            reloj.postDelayed({
                try { malla?.relayMs = MallaAcustica.RELAY_MS } catch (_: Exception) {}
            }, RESPUESTA_DURA_MS)
        } catch (_: Exception) {}
        actualizarNotificacion()
    }

    /* ================= modo rescate ================= */

    private var rescateTarea: Runnable? = null

    /**
     * Consumo mínimo y señal periódica. Es lo que se enciende cuando ya se sabe
     * que el rescate va a tardar: la sirena continua dura una hora larga de
     * batería, y este pulso dura muchas más pidiendo ayuda igual.
     *
     * Tres de cualquier cosa significa socorro en todos los manuales del mundo,
     * y por eso son tres pitidos y no dos ni cuatro. La doble banda es a
     * propósito: 110 Hz atraviesa la masa de escombros y 3 kHz es donde mejor
     * oye el oído humano, que es lo que permite localizar de dónde viene.
     */
    private fun rescate() {
        val hop = saltoEntrante
        parar()
        enRescate = true
        saltoEntrante = hop
        // el rescate no se silencia a sí mismo
        try { malla?.silenciadoHasta = 0 } catch (_: Exception) {}
        val tarea = object : Runnable {
            override fun run() {
                pulsoRescate()
                proximoPulso = System.currentTimeMillis() + RESCATE_MS
                reloj.postDelayed(this, RESCATE_MS)
            }
        }
        rescateTarea = tarea
        reloj.post(tarea)
        try { emitirRadio(Baliza.RESCATE) } catch (_: Exception) {}
        try { fichaLan?.emitir(true) } catch (_: Exception) {}
        anotar("modo rescate: pulso cada 12 s")
        actualizarNotificacion()
    }

    private fun pulsoRescate() {
        thread(name = "rescate", isDaemon = true) {
            try {
                val sr = 48000
                val n = (sr * 1.0).toInt()          // 3 pitidos en 0,28 s cada uno
                val pcm = ShortArray(n)
                val rampa = (0.005 * sr).toInt()
                /* Dos ondas CUADRADAS, no dos senos. Se probó con senos puros —110 Hz
                   y un barrido de 2,2 a 3,2 kHz— y suena peor de oír, que es de lo
                   único que se trata: un altavoz de móvil no da 110 Hz, así que un
                   seno grave se pierde entero, y la cuadrada de 3 kHz reparte energía
                   en 9, 15 y 21 kHz, que es lo que hace que corte. Los armónicos aquí
                   no son suciedad: son el motivo de que se oiga desde debajo. */
                for (rep in 0..2) {
                    val ini = (rep * 0.28 * sr).toInt()
                    val dur = (0.20 * sr).toInt()
                    for (i in 0 until dur) {
                        if (ini + i >= n) break
                        val env = when {
                            i < rampa -> i.toDouble() / rampa
                            i > dur - rampa -> (dur - i).toDouble() / rampa
                            else -> 1.0
                        }
                        val grave = if (sin(2 * PI * 110.0 * i / sr) >= 0) 1.0 else -1.0
                        val agudo = if (sin(2 * PI * 3000.0 * i / sr) >= 0) 0.9 else -0.9
                        val s = (grave + agudo) * 0.5 * env
                        pcm[ini + i] = (s * Short.MAX_VALUE * 0.95).toInt().toShort()
                    }
                }
                Altavoz.reproducir(pcm, sr)
            } catch (e: Exception) {
                Log.e("SismoRed", "pulso de rescate", e)
            }
        }
        if (opciones.vibracion) try {
            val corto = longArrayOf(0, 120, 80, 120, 80, 120)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrador?.vibrate(VibrationEffect.createWaveform(corto, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrador?.vibrate(corto, -1)
            }
        } catch (_: Exception) {}
        if (opciones.linterna) try { linterna?.destello() } catch (_: Exception) {}
        if (opciones.pantalla) try { destello("uno") } catch (_: Exception) {}
        if (opciones.baliza) try { malla?.emitirUna(saltoEntrante + 1) } catch (_: Exception) {}
    }

    /**
     * Enciende la baliza de radio.
     *
     * Solo se llama con la alarma o el rescate activos, y por eso es el único
     * sitio donde salen del móvil el grupo sanguíneo y la edad: quien te busca
     * llega sabiendo qué no puede darte. Ni las alergias ni el contacto ni el
     * nombre caben en un anuncio BLE, y tampoco iban a ir: eso se enseña en la
     * pantalla cuando te encuentran.
     */
    /* ---------- la consola del interfono ----------
       Un ciclo son cinco pasos y cada uno dice algo distinto, así que sustituir el
       texto entero en cada paso hacía que la información se borrara a sí misma y
       que lo leído fuera siempre un trozo. Ahora se APILA, con su hora delante y
       una línea por informe, como un terminal: lo que ya pasó se queda arriba y lo
       nuevo entra por abajo.

       Los pasos (grabando, hablando, escuchando) se reemplazan entre ellos en vez
       de acumularse, porque son estados del mismo momento y llenarían la caja de
       ruido; los resultados se quedan. */
    private val lineasInterfono = ArrayDeque<String>()
    private var ultimoEraPaso = false
    private val relojInterfono = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())

    private fun logInterfono(texto: String, paso: Boolean) {
        val hora = relojInterfono.format(java.util.Date())
        if (ultimoEraPaso && lineasInterfono.isNotEmpty()) {
            repeat(pasoLineas) { if (lineasInterfono.isNotEmpty()) lineasInterfono.removeLast() }
        }
        val nuevas = texto.lines().filter { it.isNotBlank() }
        pasoLineas = if (paso) nuevas.size else 0
        ultimoEraPaso = paso
        for ((i, l) in nuevas.withIndex()) {
            lineasInterfono.addLast(if (i == 0) "$hora  $l" else "          $l")
        }
        while (lineasInterfono.size > 16) lineasInterfono.removeFirst()
        interfonoSalida = lineasInterfono.joinToString(System.lineSeparator())
    }

    private var pasoLineas = 0

    private fun emitirRadio(estado: Int) {
        val f = Ficha(this)
        /* Con la alarma o el rescate activos va la ficha ENTERA, en tramas: la
           trama 0 es la de siempre —nombre y grupo, legible ella sola— y detrás
           edad, avisos y contacto. Fuera de eso no se manda nada personal, que es
           la misma regla que ya tenía la baliza. */
        /* Alergias delante de la medicación: en 31 bytes lo que se pierde es
           la cola, y de los dos datos el que no puede perderse es qué NO
           pueden darte. Van juntos porque el hueco de «avisos» es uno solo. */
        val avisos = listOf(f.alergias.trim(), f.medicacion.trim())
            .filter { it.isNotBlank() }.joinToString(" · ")
        val resto = if (estado == Baliza.ALARMA || estado == Baliza.RESCATE)
            Baliza.restoDeFicha(f.edad, avisos, f.contacto) else ByteArray(0)
        val ok = radio?.emitir(
            estado, saltoEntrante,
            Baliza.codigoSangre(f.sangre),
            f.nombre,
            resto
        ) ?: false
        radioEmitiendo = ok
        radioMotivo = radio?.motivo ?: "sin baliza"
        Log.i("SismoRed", "baliza de radio: $radioMotivo")
        anotar(if (ok) "baliza de radio encendida" else "baliza de radio: $radioMotivo")
    }

    /**
     * La comprobación que puede hacer cualquiera sin salir de casa.
     *
     * A cada pieza se le inyectan señales conocidas y se mira si las reconoce:
     * a la malla, las cuatro balizas; al oído, una voz, un grito, un ladrido,
     * unos golpes y un derrumbe; a la sonda, un eco a 1,20 m. Nada de esto usa
     * el altavoz ni el micrófono, así que funciona en cualquier sitio y sin
     * segundo móvil.
     *
     * El detalle técnico se queda en el registro del sistema. Aquí sale una
     * frase, porque quien pulsa el botón quiere saber si puede confiar en la
     * app, no leer un volcado.
     */
    /**
     * El clasificador se comprueba SIEMPRE sobre una instancia aparte.
     *
     * `tick` lleva estado acumulado —la envolvente del nivel, los ataques, la
     * deriva del tono—, así que meterle sonidos sintéticos a la que está oyendo
     * de verdad la deja mintiendo. Y peor: la de verdad la está usando el hilo
     * del micrófono a la vez, y dos hilos escribiendo en las mismas listas
     * tiraban la app entera al arrancar. Esta instancia no abre el micrófono
     * nunca; solo le pide la tasa de muestreo.
     */
    private fun comprobarDetectores(): Boolean =
        try { mic?.let { Escucha(it, onEstruendo = {}, onRegistro = {}).autotestClasificador() } ?: false }
        catch (e: Exception) { Log.e("SismoRed", "comprobar detectores", e); false }

    /**
     * El diagnóstico completo, **fuera del hilo principal**.
     *
     * Estaba corriendo en el hilo del servicio, y al crecer las pruebas de la
     * sonda —filtros adaptados sobre decenas de ráfagas simuladas— tardó lo
     * bastante como para que Android sacara «SismoRed no responde». Un
     * diagnóstico que cuelga la app es peor que no tenerlo: la vigilancia se
     * queda sin atender justo mientras se comprueba que funciona.
     */
    private fun comprobarTodo() {
        thread(name = "diagnostico", isDaemon = true) { comprobarTodoAhora() }
    }

    private fun comprobarTodoAhora() {
        /* Instancia aparte para la malla: la comprobación toca el estado del
           decodificador, y hacerlo sobre el que está escuchando de verdad podría
           hacerle perder una baliza real justo mientras se comprueba. */
        var mallaOk = true
        try {
            mic?.let { m ->
                val prueba = MallaAcustica(m, onConfirmada = {}, onRegistro = {})
                /* Los seis códigos, no solo los cuatro saltos: la llamada y el
                   SILENCIO van por el mismo canal, y el de silencio está a
                   18,8 kHz — lo más alto del protocolo. Si un altavoz o un
                   micrófono no llegan ahí, hay que saberlo aquí y no el día que
                   un equipo de rescate pida silencio. */
                for (hop in 1..MallaAcustica.TONOS.size) mallaOk = prueba.autotest(hop) && mallaOk

                /* Y la firma temporal, que es lo único que comprueba que la
                   malla sigue OYENDO una trama entera. Los seis de arriba miran
                   el tono; este mira la forma, y es el que caza el fallo mudo:
                   una ventana mal puesta no da error, solo deja la malla sorda.
                   Se anota siempre, salga bien o mal, porque el número es la
                   prueba de que no se ha endurecido el umbral hasta no oír. */
                val (cadOk, cadTxt) = prueba.autotestCadencia()
                anotar(cadTxt)
                mallaOk = cadOk && mallaOk
            }
        } catch (_: Exception) { mallaOk = false }

        val oidoOk = try {
            (escucha?.autotest() ?: false) && comprobarDetectores()
        } catch (_: Exception) { false }
        val sondaOk = try { sonda?.autotest() ?: false } catch (_: Exception) { false }

        /* La cascada entera contra los doce escenarios reales: el móvil que se
           cae de la mesa, el generador, la que huye corriendo, la que duerme en
           un quinto, la que queda enterrada con el móvil al lado y la que lo
           tiene a tres metros. Tarda microsegundos y es lo único que comprueba
           las DECISIONES en vez de los sensores. */
        val cascadaOk = try {
            val (ok, txt) = Cascada.autotest()
            if (!ok) anotar("cascada: $txt")
            ok
        } catch (_: Exception) { false }

        /* El sismógrafo, contra una mesa real y contra un terremoto real. Es el
           único detector que no se puede comprobar usándolo —haría falta un
           terremoto—, y sus dos fallos son mudos: ni un falso negativo ni un
           umbral mal escalado se ven desde fuera. Se anota siempre. */
        val sismoOk = try {
            val (ok, txt) = sismo.autotest()
            anotar("sismógrafo · $txt")
            ok
        } catch (_: Exception) { false }

        /* El filtro de la alerta de Google. Se comprueba SIEMPRE, tenga o no el
           permiso: lo que se está probando no es si llega la notificación, es
           que el filtro no se cuela con una noticia ni con el resumen de después
           del terremoto — y eso se puede comprobar sin permiso ninguno. */
        val alertaOk = try {
            val (ok, txt) = AlertaGoogle.autotest()
            anotar("alerta de Google · $txt")
            ok
        } catch (_: Exception) { false }

        /* Y qué se sabe de la postura, que es de lo que depende todo lo demás.
           No es un aprobado o un suspenso: hay móviles sin contador de pasos, y
           lo que importa es que se vea cuál es este. */
        try {
            val r = postura?.resumen(sismo.quietoDesdeHace()) ?: "sin postura"
            Log.i("SismoRed", "autotest $r")
            anotar(r)
        } catch (_: Exception) {}

        val fallan = ArrayList<String>()
        if (!mallaOk) fallan.add("la malla")
        if (!oidoOk) fallan.add("el oído")
        if (!sondaOk) fallan.add("la sonda")
        if (!cascadaOk) fallan.add("la cascada de decisión")
        if (!sismoOk) fallan.add("el sismógrafo")
        anotar(
            if (fallan.isEmpty()) "Todo funciona: la malla, el oído, la sonda y la cascada responden bien."
            else "Algo no va bien en " + fallan.joinToString(" y ") +
                 ". Vuélvelo a probar en un sitio en silencio."
        )
    }

    /* ================= opciones ================= */

    /**
     * Aplicar en caliente lo que el usuario acaba de tocar. Sin esto, apagar la
     * sirena con la alarma sonando no la callaría hasta la siguiente alarma, que
     * es justo cuando nadie quiere descubrir que el interruptor no servía.
     */
    private fun aplicarOpciones() {
        /* El volumen y la frecuencia de la senal, en caliente. El PCM del tono se
           genera al arrancar cada herramienta, asi que un cambio con algo ya sonando
           se nota al volver a encenderlo; sin esto no se notaba nunca. */
        try {
            sonda?.volTono = opciones.volSenal / 10.0
            sonda?.fDoppler = opciones.dopplerKhz * 1000
        } catch (_: Exception) {}
        val nuevo = if (enReposoAhora) opciones.umbralReposo else opciones.umbral
        sismo.umbral = nuevo
        umbralActivo = nuevo
        sismo.ajustarPerfil(opciones.perfilEntorno)
        anotar("perfil sísmico: ${opciones.perfilEntorno.name} · reposo ${opciones.umbralReposo} m/s²")
        /* Encender y apagar en caliente, y decirlo: salir a internet es lo único
           que hace esta app fuera del móvil, y tiene que verse en el registro. */
        try {
            if (opciones.sismoOnline) {
                receptorOnline?.arrancar()
                anotar("consulta de sismos por internet ENCENDIDA (catálogo público del EMSC)")
            } else {
                receptorOnline?.parar()
                anotar("consulta de sismos por internet apagada")
            }
        } catch (_: Exception) {}
        sonda?.fDoppler = opciones.dopplerKhz * 1000
        if (enAlarma) {
            if (opciones.sirena) { if (!sirena.estaSonando()) try { sirena.start() } catch (_: Exception) {} }
            else try { sirena.stop() } catch (_: Exception) {}
            if (!opciones.linterna) try { linterna?.parar() } catch (_: Exception) {}
            else try { linterna?.sos(true) } catch (_: Exception) {}
            if (!opciones.vibracion) try { vibrador?.cancel() } catch (_: Exception) {}
            try { destello(if (opciones.pantalla) "sos" else "off") } catch (_: Exception) {}
            if (!opciones.baliza) try { malla?.pararEmision() } catch (_: Exception) {}
        }
        actualizarNotificacion()
    }

    private fun armar(v: Boolean) {
        sismo.reiniciar()
        sismo.armado = v
        opciones.armado = v
        armado = v
        anotar(if (v) "detector armado · umbral ${"%.1f".format(sismo.umbral)} m/s²" else "detector desarmado")
        actualizarNotificacion()
    }

    private fun marcar() {
        val hora = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        anotar("$hora  EVENTO MARCADO POR EL USUARIO")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrador?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") vibrador?.vibrate(60)
            }
        } catch (_: Exception) {}
    }

    /**
     * El destello de pantalla lo tiene que dar la actividad: un servicio no
     * pinta. Si la pantalla no está delante no pasa nada — para eso están la
     * linterna y la sirena, que no dependen de que nadie esté mirando.
     *
     * `sos` arranca el parpadeo en morse, `uno` es el destello suelto del modo
     * rescate y `off` lo apaga.
     */
    private fun destello(modo: String) {
        sendBroadcast(
            Intent(ACCION_DESTELLO).setPackage(packageName).putExtra("modo", modo)
        )
    }

    /* ================= publicación a la pantalla ================= */

    /**
     * Un solo latido de medio segundo que copia el estado del audio y del sensor
     * a los campos que lee la pantalla. Va aquí y no en la actividad porque el
     * micrófono y el acelerómetro viven en el servicio: la pantalla solo lee lo
     * último publicado y no toca ningún recurso.
     */
    private fun publicar() {
        reloj.post(object : Runnable {
            override fun run() {
                escucha?.let {
                    oyeNivelDb = it.nivelDb
                    oyeTonoHz = it.tonoHz
                    oyeImpactos = it.impactos
                    oyeUltimos = it.ultimasDetecciones()
                    oyeProgreso = it.progreso()
                    oyeCuando = it.cuandoPorTipo()
                    oyeEscuchando = it.escuchando
                }
                sacudida = sismo.sacudida
                armado = sismo.armado
                temblando = sismo.ultimoTemblor > 0 &&
                    System.currentTimeMillis() - sismo.ultimoTemblor < TEMBLOR_MS
                malla?.let {
                    if (it.tx > mallaTx || it.confirmadas > mallaConfirmadas)
                        mallaActividadHasta = System.currentTimeMillis() + MALLA_AVISO_MS
                    mallaRx = it.rx; mallaTx = it.tx; mallaSalto = it.ultimoSalto
                    mallaConfirmadas = it.confirmadas
                    mallaPorSalto = it.porSalto.copyOf()
                    mallaNiveles = it.niveles.copyOf(); mallaSuelo = it.sueloDb
                    mallaEscuchando = it.escuchando
                }
                mic?.let { if (it.abierto) { micSr = it.sr; micCrudo = it.fuenteCruda } }
                sondaOcupada = sonda?.ocupada ?: false
                sonda?.let {
                    quienTono = it.quienTono
                    nivelDoppler = it.nivelDoppler
                    ecoActivo = it.ecoContinuo
                    barridoActivo = barridoOn
                }
                interfonoOcupado = interfono?.ocupado ?: false
                interfonoFase = interfono?.fase ?: Interfono.Fase.CERRADO
                interfonoNivelDb = interfono?.vozDb?.toFloat() ?: -120f
                fichaLan?.let {
                    fichasWifi = if (it.fichas().isEmpty()) "" else it.comoTexto()
                    fichaLanEstado = it.estado()
                }
                /* La baliza contesta por callback, así que justo después de
                   pedirla el motivo todavía dice «sin arrancar». Sin refrescarlo
                   aquí, Diagnóstico se quedaba enseñando ese texto para siempre
                   con la baliza emitiendo. */
                radio?.let { radioEmitiendo = it.emitiendo; radioMotivo = it.motivo }
                ajustarEscucha()
                reloj.postDelayed(this, 500)
            }
        })
    }

    /**
     * Lo que alimenta a los lienzos, a la velocidad de la pantalla.
     *
     * Va separado del latido de medio segundo porque son dos cosas distintas: un
     * texto que cambia dos veces por segundo se lee perfectamente, pero una onda
     * que se redibuja dos veces por segundo no parece una onda — parece que la
     * app se ha colgado. Solo corre a 60 ms mientras la pantalla dice que hay un
     * lienzo delante; el resto del tiempo late igual de lento que lo demás.
     */
    private fun publicarLienzos() {
        reloj.post(object : Runnable {
            override fun run() {
                if (mirando) {
                    escucha?.let { if (it.escuchando) oyeOnda = it.onda() }
                    trazaSismo = sismo.traza()
                }
                reloj.postDelayed(this, if (mirando) 60L else 500L)
            }
        })
    }

    /**
     * Escucha a ratos por si hay alguien buscando ahí arriba.
     *
     * Solo mira con la alarma o el rescate en marcha: fuera de eso no hay a
     * quien contestar y escanear sería tirar la batería que justo hay que
     * estirar. Cuatro segundos cada treinta bastan — quien busca se anuncia
     * durante dos minutos seguidos.
     */
    private fun vigilarLlamadas() {
        val rastreador = Rastreador(this)
        var ultimaRespuesta = 0L
        reloj.postDelayed(object : Runnable {
            override fun run() {
                if ((enAlarma || enRescate) && rastreador.hayPermiso() && rastreador.hayHardware()) {
                    rastreador.arrancar { }
                    reloj.postDelayed({
                        val busca = rastreador.hallazgos().any { it.estado == Baliza.BUSCANDO }
                        rastreador.parar()
                        val now = System.currentTimeMillis()
                        if (busca && now - ultimaRespuesta > RESPUESTA_DURA_MS) {
                            ultimaRespuesta = now
                            respuestaReforzada()
                        }
                    }, OJEADA_DURA_MS)
                }
                reloj.postDelayed(this, OJEADA_CADA_MS)
            }
        }, OJEADA_CADA_MS)
    }

    /**
     * Escalado automático a modo rescate. Bajar a un pulso espaciado convierte
     * una hora de batería en muchas horas de baliza, y esa es la diferencia
     * entre que te encuentren el primer día o el tercero.
     *
     * Nadie va a estar pendiente de pulsarlo, así que la app lo hace sola —
     * pero solo hacia abajo: del rescate no se vuelve a la sirena por su cuenta.
     */
    private fun vigilarInmovilidad() {
        val tarea = object : Runnable {
            override fun run() {
                /* Sin ahorro de Wi-Fi solo cuando hay un motivo: buscando, en
                   alarma, en rescate o de repetidor. Con la pantalla apagada y
                   el ahorro puesto este canal PIERDE paquetes —medido en el
                   A10s—, y quien busca no puede perderlos. El resto del tiempo
                   manda durar.

                   Y va aquí, en el latido del servicio, y no en el bucle que
                   pinta la pantalla: ese se para al apagarla, que es justo
                   cuando hace falta. */
                /* Latido en disco: si la app se abre y esto es reciente pero el
                   servicio no está, es que lo han matado por detrás. */
                try {
                    opciones.latido = System.currentTimeMillis()
                    opciones.deberiaVigilar = !opciones.apagada
                    if (opciones.deberiaVigilar) {
                        WatchdogReceiver.programar(this@ServicioSos)
                    }
                } catch (_: Exception) {}
                try {
                    fichaLan?.escuchaFuerte(buscando || enAlarma || enRescate || repetidor)
                } catch (_: Exception) {}
                /* El umbral sísmico según DÓNDE está el móvil, no según la hora.
                   Quieto en una mesilla no anda, no corre y no se mete en un
                   bolsillo: casi todo lo que obliga a poner el listón alto no
                   existe ahí, y ahí es justo donde hay alguien durmiendo que no
                   se va a enterar. Encima de una persona manda el conservador.

                   Va por el estado y no por el reloj a propósito: la hora se
                   equivoca con quien trabaja de noche, con la siesta y con el
                   móvil olvidado en la mesa toda la tarde. El estado se mide. */
                try {
                    val quieto = sismo.quietoDesdeHace()
                    val enReposo = postura?.regimenRecordado(quieto, sismo.giroGrados) ==
                        Postura.Regimen.EN_REPOSO
                    /* De madrugada, quieto y con la vigilia puesta, el listón
                       baja al MMI IV. Solo en reposo: si lo lleva encima, la
                       vigilia no aplica porque lo que la hace fiable es que el
                       movil no se esté moviendo por su cuenta. */
                    val nuevo = when {
                        enReposo && enVigilia(opciones) -> Opciones.UMBRAL_VIGILIA
                        enReposo -> opciones.umbralReposo
                        else -> opciones.umbral
                    }
                    /* DENTRO DEL `if` SOLO VA EL UMBRAL. Todo lo demás vivía
                       aquí dentro y se congelaba.

                       La condición es «el umbral ha cambiado», y una vez el
                       móvil se asienta el umbral deja de cambiar: se queda en
                       el de reposo, o en el de vigilia, y ya no se mueve. A
                       partir de ese momento no se recalculaba `vigiliaArmadaAqui`
                       —que es la bandera que arma la vigilia nocturna entera y
                       decide si el motor de audio escucha—, ni el contador de
                       la tarjeta, ni si hay un motor cerca, ni la caducidad del
                       relevo.

                       Se ve en dos sitios: el 18 de septiembre el Huawei se
                       quedó clavado en «esperando 6 s de reposo» con el
                       acelerómetro marcando 134 s, y la noche del 17 el móvil
                       siguió de repetidor 4 h 41 min en vez de la media hora
                       que dice el mensaje. El Redmi se armaba bien por pura
                       suerte de a qué altura de la quietud le tocó el último
                       cambio de umbral. */
                    if (abs(sismo.umbral - nuevo) > 0.01) {
                        sismo.umbral = nuevo
                        umbralActivo = nuevo
                        anotar("Móvil %s: vigilo a %.2f m/s²".format(
                            if (enReposo) "en reposo" else "encima de ti", nuevo))
                    }
                    enReposoAhora = enReposo
                    sismo.vigiliaArmada = enVigilia(opciones)
                    /* Que el sismógrafo sepa que la sacudida la está poniendo
                       el propio teléfono. Aquí y no solo en la cascada: tiene
                       que romper la racha mientras dura, no solo callarla.
                       Ver [Sismografo.vibracionPropia]. */
                    sismo.vibracionPropia = altavozPropio()
                    /* El reloj que sobrevive al disturbio, no el
                       instantáneo: cuando llega la alerta del vecino
                       este móvil está encima de la misma mesa que se
                       mueve, así que `quietoDesdeHace` vale cero y la
                       vía rápida no se activaba nunca. Medido el 17 de
                       septiembre: el segundo móvil tardó 11,7 s. */
                    vigiliaArmadaAqui = enVigilia(opciones) && enReposo &&
                        maxOf(sismo.quietoDesdeHace(), sismo.quietoAntesDeLaRacha) >=
                            Opciones.VIGILIA_REPOSO_MIN_MS
                    quietoParaVigilia = sismo.quietoDesdeHace()
                    pantallaHace = postura?.interaccionHace() ?: Long.MAX_VALUE
                    val m = escucha?.motorCerca == true
                    if (m && !motorSonando) motorDesde = System.currentTimeMillis()
                    if (!m) motorDesde = 0L
                    motorSonando = m
                    cederMicrofono()
                    /* El relevo caduca solo si no ha vuelto a pasar nada. */
                    if (repetidor && contestoBien > 0L &&
                        System.currentTimeMillis() - contestoBien > REPETIDOR_MS) {
                        repetidor = false
                        anotar("media hora sin novedad: dejo de ser solo repetidor y vuelvo a vigilar")
                    }
                    calmaMedida = sismo.calmaMedida
                    giroGrados = sismo.giroGrados
                    umbralReal = sismo.umbralReal
                    manoGrados = sismo.manoGrados
                    cicloTrabajo = sismo.cicloTrabajo
                    Log.i("SismoRed", "postura %s · giro %.1f° · mano %.1f° · quieto %d s · calma %.3f · umbral %.2f → real %.2f · ciclo %.0f%%%s".format(
                        if (enReposo) "EN_REPOSO" else "ENCIMA",
                        sismo.giroGrados, sismo.manoGrados, quieto / 1000,
                        sismo.calmaMedida, nuevo, sismo.umbralReal,
                        sismo.cicloTrabajo * 100,
                        if (sismo.sitioDemasiadoRuidoso) " · SITIO MUY RUIDOSO" else ""))
                } catch (_: Exception) {}
                val sonando = System.currentTimeMillis() - alarmaDesde
                val quieto = sonando > ESCALA_QUIETO_MS && sismo.quietoDesdeHace() > ESCALA_SIN_MOVER_MS
                val tope = sonando > ESCALA_TOPE_MS
                if (enAlarma && !enRescate && (quieto || tope)) {
                    Log.i("SismoRed", "escalado a modo rescate (quieto=$quieto tope=$tope)")
                    anotar(
                        if (quieto) "sin movimiento ni respuesta: paso a modo rescate para durar"
                        else "10 minutos de alarma: paso a modo rescate para que la batería aguante"
                    )
                    rescate()
                }
                reloj.postDelayed(this, 10_000)
            }
        }
        reloj.postDelayed(tarea, 10_000)
    }

    override fun onDestroy() {
        vivo = false
        if (!opciones.apagada && opciones.deberiaVigilar) {
            try { WatchdogReceiver.programar(this) } catch (_: Exception) {}
        } else {
            try { WatchdogReceiver.cancelar(this) } catch (_: Exception) {}
        }
        try { receptorOnline?.parar() } catch (_: Exception) {}
        try { sismo.parar() } catch (_: Exception) {}
        try { postura?.parar() } catch (_: Exception) {}
        try { escucha?.parar() } catch (_: Exception) {}
        try { malla?.parar() } catch (_: Exception) {}
        mallaEscuchando = false
        oyeEscuchando = false
        parar()
        try { wakeLock?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Alguien ha cerrado la app deslizándola fuera de recientes.
     *
     * Eso cierra la pantalla, no la vigilancia. Aquí no se para nada: se vuelve
     * a pedir el primer plano por si algún fabricante aprovecha para degradar el
     * servicio. Cerrar la ventana por error no puede dejar a nadie sin sirena.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i("SismoRed", "app cerrada desde recientes: la vigilancia sigue")
        try { alPrimerPlano() } catch (_: Exception) {}
        super.onTaskRemoved(rootIntent)
    }

    /* ---------- notificación ---------- */

    private fun crearCanal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val canal = NotificationChannel(
            CANAL, "Vigilancia de emergencia", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Mantiene SismoRed activo con la pantalla apagada"
            setShowBadge(false)
            setSound(null, null)          // la sirena la pone la app, no la notificación
        }
        (getSystemService(NotificationManager::class.java)).createNotificationChannel(canal)
    }

    private fun pi(accion: String): PendingIntent {
        val i = Intent(this, ServicioSos::class.java).setAction(accion)
        return PendingIntent.getService(
            this, accion.hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notificacion(alarma: Boolean): Notification {
        val abrir = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        /* LA PREGUNTA NO ES UNA ALARMA, y estaba contada como tal.

           Con `preguntaHasta` aquí dentro, el aviso discreto —el que sale
           cuando un catálogo confirma un sismo cerca y el móvil no ha notado
           nada— pintaba la barra de rojo, la titulaba «ALARMA ACTIVA», ponía
           debajo «Sirena y linterna en marcha · baliza emitiendo» y ofrecía un
           botón DETENER. No sonaba nada: la pregunta es justo el estado
           anterior a encender la sirena, y existe para no encenderla.

           Pasó cinco veces la noche del 17 al 18 de septiembre, una por cada
           aviso de catálogo. Es la misma clase de error que el de `mallaRx`
           documentado aquí abajo: la notificación afirmando lo que el motor no
           se cree. La pregunta ya tiene su propia notificación
           ([ID_PREGUNTA_DISCRETA] y [ID_PREGUNTA]); la permanente no tiene que
           gritar por ella. */
        val esAlarma = alarma || enRescate
        /* LA NOTIFICACION SE CREIA LO QUE EL MOTOR NO SE CREE.
        
           Estaba escrita contra `mallaRx`, que cuenta CANDIDATOS: todo lo que el
           decodificador lee como un salto, corroborado o no. Y justo debajo, el
           mismo motor escribe en el registro «he oido algo que puede ser una
           alerta; espero a confirmarlo». O sea que la app decia a la vez las dos
           cosas, y en la barra de notificaciones —que es lo que ve la persona—
           decia la que no era.
        
           En el registro de campo del 3 de septiembre pasa exactamente eso a las
           14:56:37, sin un solo movil con la app alrededor. */
        /* Y por instante, no por contador: ver [mallaActividadHasta]. */
        val esMalla = !esAlarma && System.currentTimeMillis() < mallaActividadHasta

        val layoutId = when {
            esAlarma -> R.layout.notif_alarma
            esMalla -> R.layout.notif_malla
            else -> R.layout.notif_servicio
        }

        val rv = RemoteViews(packageName, layoutId)

        if (esAlarma) {
            val tit = if (enRescate) "SISMORED · MODO RESCATE" else "SISMORED · ALARMA ACTIVA"
            val sub = if (enRescate) "Pulso de bajo consumo cada 12 s · linterna y baliza"
                      else "Sirena y linterna en marcha · baliza emitiendo"
            rv.setTextViewText(R.id.notif_titulo, tit)
            rv.setTextViewText(R.id.notif_texto, sub)
            rv.setTextViewText(R.id.notif_tiempo, "ahora")
            rv.setOnClickPendingIntent(R.id.btn_notif_parar, pi(ACCION_PARAR))
            rv.setOnClickPendingIntent(R.id.btn_notif_rescate, pi(if (enRescate) ACCION_PANICO else ACCION_RESCATE))
            if (enRescate) {
                rv.setTextViewText(R.id.btn_notif_rescate, "PÁNICO")
            }
        } else if (esMalla) {
            /* Y el salto no se inventa. Ponia un 3 cuando no se sabia —el
               numero de la maqueta— asi que la notificacion afirmaba haber
               contado tres saltos que nadie habia contado. */
            val tit = if (mallaSalto > 0)
                "Alerta de la malla · salto $mallaSalto de ${MallaAcustica.MAX_HOP}"
            else "Alerta de la malla · sin saber a cuántos saltos"
            val sub = if (mallaTx > 0) "Retransmitiendo señal de socorro a nodos cercanos"
                      else "Un móvil cercano pidió ayuda hace unos segundos"
            rv.setTextViewText(R.id.notif_titulo, tit)
            rv.setTextViewText(R.id.notif_texto, sub)
        } else if (micCedido) {
            val quedan = ((micLibreHasta - System.currentTimeMillis()) / 60_000L) + 1
            rv.setTextViewText(R.id.notif_titulo, "Micrófono libre")
            rv.setTextViewText(R.id.notif_texto,
                if (micLibreHasta > 0) "Puedes grabar · vuelvo a escuchar en $quedan min"
                else "Otra app está grabando · vuelvo cuando lo suelte")
        } else {
            rv.setTextViewText(R.id.notif_titulo, "Servicio activo")
            rv.setTextViewText(R.id.notif_texto, "Sigue vivo con la pantalla apagada · permanente")
        }

        val colorAcento = when {
            esAlarma -> 0xFFE53035.toInt()
            esMalla -> 0xFF90CA50.toInt()
            else -> 0xFF161B1F.toInt()
        }

        val b = Notification.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_stat_sismored)
            .setColor(colorAcento)
            .setOngoing(true)
            .setContentIntent(abrir)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        /* El botón para poder grabar, en la barra y no dentro de la app:
           cuando hace falta estás en la grabadora. Ver [ACCION_SOLTAR_MICRO].
           En alarma no aparece —ahí el micrófono no se negocia— y mientras
           está cedido tampoco, que ya lo dice el propio texto. */
        if (!esAlarma && !micCedido) {
            b.addAction(Notification.Action.Builder(
                null as android.graphics.drawable.Icon?,
                /* Lo que hace SismoRed, no lo que consigue la otra app: es el
                   mismo verbo que usa el registro y la tarjeta de la malla. */
                "DEJAR DE ESCUCHAR", pi(ACCION_SOLTAR_MICRO)
            ).build())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            b.setStyle(Notification.DecoratedCustomViewStyle())
            b.setCustomContentView(rv)
            if (esAlarma) {
                b.setCustomBigContentView(rv)
                b.setCustomHeadsUpContentView(rv)
            }
        } else {
            b.setContent(rv)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b.setColorized(esAlarma)
        }

        return b.build()
    }

    private fun actualizarNotificacion() {
        (getSystemService(NotificationManager::class.java))
            .notify(ID_NOTIF, notificacion(enAlarma))
    }
}
