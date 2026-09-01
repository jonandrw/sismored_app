package red.sismo

import android.app.Activity
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

/**
 * «¿ESTÁS BIEN?» a pantalla completa, con el móvil bloqueado y en la mano de
 * alguien que acaba de vivir un terremoto.
 *
 * Es la pieza que cierra la cascada. Todo lo demás son sensores intentando
 * adivinar; esto pregunta. Y por eso tiene que salir **sí o sí**: si hay que
 * desbloquear el móvil para decir que estás bien, la mitad de la gente no llega
 * a tiempo y su baliza se pone a emitir sin hacer falta.
 *
 * Tres cosas que la hacen distinta de una pantalla normal:
 *
 *  - `showWhenLocked` + `turnScreenOn`: aparece sobre la pantalla de bloqueo y
 *    enciende el móvil. Es lo mismo que hace una llamada entrante, y por el mismo
 *    motivo.
 *  - **No se puede cerrar con Atrás.** Cerrarla sin querer sería contestar sin
 *    contestar, y la respuesta a esta pregunta no puede ser un descuido.
 *  - **La cuenta atrás no vive aquí.** El que manda es el servicio
 *    (`ServicioSos.preguntaHasta`); esta pantalla solo lo pinta. Si el sistema la
 *    mata, el temporizador sigue corriendo y la baliza se enciende igual.
 *
 * Suena un tic por segundo y lanza una onda desde el número, las dos cosas por
 * lo mismo: quien mira esta pantalla puede estar mirándola sin leerla, y tiene
 * que enterarse de que hay un reloj corriendo sin tener que leer nada. La sirena
 * —cuando toca— la pone el servicio, no esta pantalla.
 */
class PreguntaActivity : Activity() {

    private val reloj = Handler(Looper.getMainLooper())
    private var tarea: Runnable? = null

    /* El tic de cada segundo. Va por el canal de ALARMA a propósito: es el único
       que suena con el móvil en silencio, que es exactamente como está un móvil
       en una mesilla de noche. Un reloj que no se oye no sirve aquí — quien mira
       esta pantalla puede estar mirándola sin leerla.

       Se crea una vez y se suelta al salir: crear un ToneGenerator por segundo
       abre y cierra el camino de audio sesenta veces y algunos móviles se comen
       los primeros milisegundos de cada tono. */
    private var tono: ToneGenerator? = null
    /** El último segundo que se pintó, para sonar solo cuando el número cambia y
     *  no cuatro veces por segundo, que es a lo que va el repintado. */
    private var ultimoSeg = -1

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        /* Encender la pantalla y salir por encima del bloqueo. Las banderas
           viejas siguen haciendo falta por debajo de Android 8.1: los métodos
           nuevos no existen ahí, y ahí es donde está media la gente que va a usar
           esto. */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        /* Al máximo de brillo: puede haber polvo, puede ser de noche y puede
           estar mirándola alguien que no ve bien de cerca. Es la misma decisión
           que la ficha a pantalla completa. */
        window.attributes = window.attributes.apply { screenBrightness = 1.0f }

        setContentView(R.layout.pregunta)

        findViewById<View>(R.id.pr_bien).setOnClickListener {
            enviar(ServicioSos.ACCION_ESTOY_BIEN)
            /* Acuse de recibo inmediato, y no del servicio: de la propia pulsación.
               Si el servicio estuviera muerto —en algunos móviles el sistema lo
               mata— al menos se sabe que el botón se pulsó, que es la mitad del
               diagnóstico. */
            android.widget.Toast.makeText(this, R.string.pr_gracias,
                android.widget.Toast.LENGTH_LONG).show()
            finish()
        }
        /* El segundo botón no es «cancelar»: es el atajo para quien está bien
           pero ve que hay alguien que no. Se salta la espera. */
        findViewById<View>(R.id.pr_ayuda).setOnClickListener {
            enviar(ServicioSos.ACCION_PANICO)
            finish()
        }
        pintar()
    }

    private fun enviar(accion: String) {
        try {
            val i = Intent(this, ServicioSos::class.java).setAction(accion)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
            else startService(i)
        } catch (_: Exception) {}
    }

    private fun pintar() {
        val num = findViewById<TextView>(R.id.pr_cuenta)
        val onda = findViewById<VistaOndaCuenta>(R.id.pr_onda)
        // la onda sale del número, así que hay que esperar a que esté medido
        num.post { onda.centrarEn(num) }
        try { tono = ToneGenerator(AudioManager.STREAM_ALARM, 80) } catch (_: Exception) {}

        val t = object : Runnable {
            override fun run() {
                val quedan = ServicioSos.preguntaHasta - System.currentTimeMillis()
                if (ServicioSos.preguntaHasta == 0L || quedan <= 0) {
                    /* Se acabó el tiempo o alguien contestó desde la
                       notificación. El servicio ya ha hecho lo que tocaba; aquí
                       solo hay que quitarse de en medio. */
                    finish(); return
                }
                val seg = ((quedan + 999) / 1000).toInt()
                if (seg != ultimoSeg) {
                    ultimoSeg = seg
                    num.text = "%d".format(seg)
                    onda.latir()
                    /* Los últimos diez segundos suenan distinto. No es un adorno:
                       es la diferencia entre «va corriendo» y «se acaba», y quien
                       está mirando sin leer necesita enterarse de esa segunda
                       parte sin mirar el número. */
                    try {
                        if (seg <= 10) tono?.startTone(ToneGenerator.TONE_PROP_BEEP2, 140)
                        else tono?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
                    } catch (_: Exception) {}
                }
                reloj.postDelayed(this, 120)
            }
        }
        tarea = t
        reloj.post(t)
    }

    /** Atrás no contesta. La única forma de salir de aquí es decir algo. */
    @Deprecated("se mantiene por compatibilidad con API < 33")
    override fun onBackPressed() {}

    override fun onDestroy() {
        tarea?.let { reloj.removeCallbacks(it) }
        try { tono?.release() } catch (_: Exception) {}
        tono = null
        super.onDestroy()
    }
}
