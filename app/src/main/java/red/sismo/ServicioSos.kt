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
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

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
        /** ESCUCHAR ENTORNO: los cinco detectores, aparte de la malla. */
        const val ACCION_ESCUCHA_CONMUTAR = "red.sismo.ESCUCHA_CONMUTAR"
        /** Emisión interna para que la pantalla pinte lo que va pasando en la malla. */
        const val ACCION_REGISTRO = "red.sismo.REGISTRO"
        /** Emisión interna: la pantalla tiene que dar un destello blanco. */
        const val ACCION_DESTELLO = "red.sismo.DESTELLO"
        /* La sonda vive en el servicio, no en la pantalla, porque el micrófono
           es único: si la actividad abriera el suyo, dejaría sorda a la malla. */
        const val ACCION_SONDA = "red.sismo.SONDA"
        const val ACCION_DOPPLER = "red.sismo.DOPPLER"
        const val ACCION_BARRIDO = "red.sismo.BARRIDO"
        const val ACCION_RESPIRA = "red.sismo.RESPIRA"
        const val ACCION_INTERFONO = "red.sismo.INTERFONO"
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
        /** Intenta soltar la cola de partes ahora mismo. */
        const val ACCION_ENVIAR = "red.sismo.ENVIAR"
        /** El que busca llama hacia abajo: sirena audible + tono de llamada. */
        const val ACCION_LLAMAR = "red.sismo.LLAMAR"

        /** Cifras de la malla, para las pestañas Inicio y Red. */
        @Volatile var mallaRx = 0; private set
        @Volatile var mallaTx = 0; private set
        @Volatile var mallaSalto = 0; private set
        /** Balizas confirmadas por salto: es lo que dibuja el radar. */
        @Volatile var mallaPorSalto = IntArray(MallaAcustica.MAX_HOP); private set

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

        /** Partes esperando a que aparezca internet. */
        @Volatile var enCola = 0; private set
        /** Lo ultimo que dijo el envio de partes, para la consola de la tarjeta de
         *  internet. Sin esto, pulsar y no ver nada era indistinguible de que la
         *  funcion no existiera. */
        @Volatile var redSalida = "—"
        /** Las fichas completas que han llegado por Wi-Fi, ya formateadas. Vacio si
         *  no hay ninguna: entonces el bloque de la pantalla no se enseña. */
        @Volatile var fichasWifi = ""
        @Volatile var tipoRed = "—"; private set

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
        private const val RESCATE_MS = 12000L
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
    private lateinit var partes: Partes
    /** Saltos que lleva recorridos la alerta que estamos propagando. 0 = nace aquí. */
    private var saltoEntrante = 0
    private val reloj = Handler(Looper.getMainLooper())

    // SOS en morse: · · · — — — · · ·
    private val patronSos = longArrayOf(
        0, 200, 200, 200, 200, 200, 500,
        600, 200, 600, 200, 600, 500,
        200, 200, 200, 200, 200, 1400
    )

    override fun onCreate() {
        super.onCreate()
        opciones = Opciones(this)
        partes = Partes(this)
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
        sismo = Sismografo(this) { motivo -> panico(motivo) }
        sismo.umbral = opciones.umbral
        sismo.armado = opciones.armado
        armado = sismo.armado
        sismo.arrancar()
        vigilarInmovilidad()

        /* La malla es lo que convierte un móvil que grita en una red que avisa.
           Oír una baliza confirmada es exactamente igual de serio que notar el
           terremoto uno mismo: se dispara la alarma completa y se reemite. */
        mic = Microfono(this)
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
                if (buscando) {
                    anotar("ALERTA OÍDA a $hop saltos · no sueno porque estás buscando")
                } else {
                    panico("malla acústica (salto $hop)")
                }
            },
            onLlamada = { respuestaReforzada() },
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
            onEstruendo = { if (sismo.armado) panico("estruendo detectado por micrófono") },
            onRegistro = { m -> anotar(m) }
        )

        sonda = Sonda(mic!!, onRegistro = { m -> anotar(m) })
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
        vigilarRed()
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
            ACCION_ESCUCHA_CONMUTAR -> conmutarEscucha()
            ACCION_MARCAR -> marcar()
            /* Este es el boton PROBAR AHORA de la tarjeta de internet. Fuerza el
               intento saltandose la espera entre reintentos, y si la cola esta
               vacia encola un parte de PRUEBA: la cola solo se llena cuando salta
               una alarma de verdad, asi que sin esto no habia forma de comprobar
               que el camino funciona hasta el dia que hiciera falta. */
            ACCION_ENVIAR -> {
                if (partes.cuantos() == 0) {
                    partes.encolarPrueba()
                    anotar("Encolado un parte de prueba para comprobar el envio.")
                }
                partes.vaciar({ m -> anotar(m); redSalida = m }, forzar = true)
            }
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
            ACCION_DIAGNOSTICO -> comprobarTodo()
        }
        // ya estamos en primer plano: aquí sí se puede grabar. Si el usuario apagó
        // la malla a mano, no se le vuelve a encender por la espalda.
        if (intent?.action != ACCION_MALLA_CONMUTAR && !mallaApagadaAMano) arrancarMalla()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var tipo = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            if (malla?.hayPermiso() == true) tipo = tipo or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(ID_NOTIF, n, tipo)
        } else {
            startForeground(ID_NOTIF, n)
        }
    }

    /** Todo lo que pasa en el audio acaba aquí: al registro y a la pantalla. */
    private fun anotar(m: String) {
        ultimoRegistro = m
        malla?.let {
            mallaRx = it.rx; mallaTx = it.tx; mallaSalto = it.ultimoSalto
            mallaPorSalto = it.porSalto.copyOf()
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

    private fun arrancarMalla() {
        val m = malla ?: return
        if (m.escuchando) return
        mallaEscuchando = m.escuchar()
        // La malla se autocomprueba sola al arrancar (ver MallaAcustica.arrancarRx).
        if (mallaEscuchando) {
            if (!escuchaApagadaAMano) arrancarEscucha()
            sonda?.autotest()
            actualizarNotificacion()
        }
    }

    /* La escucha forense y la malla comparten micrófono pero no interruptor:
       `Microfono` cuenta usuarios, así que se puede apagar una sin dejar sorda a
       la otra. Y hacen falta por separado — alguien puede querer la malla toda
       la noche y los cinco detectores solo mientras esté atrapado. */
    private fun arrancarEscucha() {
        val e = escucha ?: return
        if (e.escuchando) return
        if (e.arrancar()) {
            e.autotest()
            comprobarDetectores()
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
    private fun panico(motivo: String, automatico: Boolean = true) {
        Log.i("SismoRed", "panico($motivo) auto=$automatico enAlarma=$enAlarma enRescate=$enRescate")
        if (enAlarma) return
        /* Quien está en modo rescate ya ha decidido durar horas en vez de gritar
           un rato. Una baliza ajena no puede deshacer esa decisión y fundirle la
           batería: se sigue retransmitiendo, pero no se enciende la sirena. */
        if (enRescate) { anotar("alerta recibida en modo rescate: se retransmite sin sirena"); return }
        enAlarma = true
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
        try { fichaLan?.emitir(true) } catch (_: Exception) {}
        /* El parte se encola siempre, haya red o no y esté el envío activado o
           no. Sin consentimiento no sale del móvil nunca; con él, saldrá cuando
           aparezca cobertura, que en un terremoto es horas después. */
        try {
            partes.encolar(motivo, saltoEntrante, mallaPorSalto)
            enCola = partes.cuantos()
            partes.vaciar({ m -> anotar(m); redSalida = m })
        } catch (_: Exception) {}
        try { actualizarNotificacion() } catch (_: Exception) {}
        anotar("ALARMA: $motivo")
    }

    private fun parar() {
        val estaba = enAlarma || enRescate
        enAlarma = false
        enRescate = false
        rescateTarea?.let { reloj.removeCallbacks(it) }
        rescateTarea = null
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
            override fun run() { pulsoRescate(); reloj.postDelayed(this, RESCATE_MS) }
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
        val ok = radio?.emitir(
            estado, saltoEntrante,
            Baliza.codigoSangre(f.sangre),
            f.nombre
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

    private fun comprobarTodo() {
        /* Instancia aparte para la malla: la comprobación toca el estado del
           decodificador, y hacerlo sobre el que está escuchando de verdad podría
           hacerle perder una baliza real justo mientras se comprueba. */
        var mallaOk = true
        try {
            mic?.let { m ->
                val prueba = MallaAcustica(m, onConfirmada = {}, onRegistro = {})
                for (hop in 1..MallaAcustica.MAX_HOP) mallaOk = prueba.autotest(hop) && mallaOk
            }
        } catch (_: Exception) { mallaOk = false }

        val oidoOk = try {
            (escucha?.autotest() ?: false) && comprobarDetectores()
        } catch (_: Exception) { false }
        val sondaOk = try { sonda?.autotest() ?: false } catch (_: Exception) { false }

        val fallan = ArrayList<String>()
        if (!mallaOk) fallan.add("la malla")
        if (!oidoOk) fallan.add("el oído")
        if (!sondaOk) fallan.add("la sonda")
        anotar(
            if (fallan.isEmpty()) "Todo funciona: la malla, el oído y la sonda responden bien."
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
        sismo.umbral = opciones.umbral
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
                    mallaRx = it.rx; mallaTx = it.tx; mallaSalto = it.ultimoSalto
                    mallaPorSalto = it.porSalto.copyOf()
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
                fichaLan?.let { fichasWifi = if (it.fichas().isEmpty()) "" else it.comoTexto() }
                /* La baliza contesta por callback, así que justo después de
                   pedirla el motivo todavía dice «sin arrancar». Sin refrescarlo
                   aquí, Diagnóstico se quedaba enseñando ese texto para siempre
                   con la baliza emitiendo. */
                radio?.let { radioEmitiendo = it.emitiendo; radioMotivo = it.motivo }
                enCola = partes.cuantos()
                tipoRed = partes.tipoRed()
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
     * Cuando aparece red, se intenta soltar la cola. No hay `WorkManager` porque
     * este servicio ya está vivo por obligación: es lo que sostiene la sirena, y
     * añadir otra pieza para repetir lo que ya hay sería pagar dos veces.
     */
    private fun vigilarRed() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (partes.cuantos() > 0) partes.vaciar({ m -> anotar(m); redSalida = m })
                    }
                }
            )
        } catch (e: Exception) {
            Log.e("SismoRed", "no se puede vigilar la red", e)
        }
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
        try { sismo.parar() } catch (_: Exception) {}
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
        val b = Notification.Builder(this, CANAL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(
                when {
                    alarma -> "ALARMA ACTIVA"
                    enRescate -> "MODO RESCATE"
                    else -> "SismoRed vigilando"
                }
            )
            .setContentText(
                when {
                    alarma -> "Sirena y baliza activas · DETENER para silenciar"
                    enRescate -> "Pulso cada 12 s para durar horas"
                    mallaEscuchando -> "Escuchando la malla · volumen ×3 para pedir ayuda"
                    else -> "Volumen ×3 para pedir ayuda · malla sin micrófono"
                }
            )
            .setOngoing(true)
            .setContentIntent(abrir)

        if (alarma || enRescate) b.addAction(Notification.Action.Builder(null, "DETENER", pi(ACCION_PARAR)).build())
        else b.addAction(Notification.Action.Builder(null, "PÁNICO", pi(ACCION_PANICO)).build())
        return b.build()
    }

    private fun actualizarNotificacion() {
        (getSystemService(NotificationManager::class.java))
            .notify(ID_NOTIF, notificacion(enAlarma))
    }
}
