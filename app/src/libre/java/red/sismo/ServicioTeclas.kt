package red.sismo

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat

/**
 * Captura las teclas de volumen con la pantalla apagada y bloqueada.
 *
 * Es la tercera vía que probamos, después de descartar dos:
 *
 *  1. ContentObserver sobre Settings.System — inútil: Android ya no guarda ahí
 *     los volúmenes desde hace varias versiones.
 *  2. MediaSession con VolumeProvider remoto — funcionaba por herencia de la
 *     sesión de otra app, no por mérito propio; al reinstalar se perdió.
 *
 * Un servicio de accesibilidad SÍ recibe onKeyEvent con la pantalla apagada.
 * Es lo que usan las apps de botón de pánico. El precio es que el usuario tiene
 * que activarlo a mano en Ajustes, y hay que explicarle por qué.
 *
 * No consume el evento: el volumen sigue funcionando con normalidad.
 *
 * Exclusivo de la distribución libre (GitHub / F-Droid).
 */
class ServicioTeclas : AccessibilityService() {

    private var pulsaciones = mutableListOf<Long>()

    companion object {
        const val NECESARIAS = 3
        const val VENTANA_MS = 3000L
        /** Colchón tras disparar antes de aceptar el silenciado. Sin él, la
         *  tercera pulsación que enciende la alarma valdría también como la
         *  primera que la apaga, y no sonaría nunca. */
        const val GRACIA_MS = 1500L
    }

    private var desdeAlarma = 0L

    /**
     * El atajo **solo cuenta con la pantalla apagada o bloqueada**.
     *
     * Salió de una prueba de uso normal: viendo un vídeo se sube y se baja el
     * volumen sin pensar, y tres toques en tres segundos es algo que pasa todos
     * los días. Ahí el atajo no aporta nada —si estás mirando el móvil, tienes el
     * botón de PÁNICO en la pantalla— y en cambio dispara una alarma en toda la
     * red por nada.
     *
     * Con la pantalla apagada o bloqueada es al revés: es el único camino que
     * queda, y nadie ajusta el volumen tres veces seguidas con el móvil guardado
     * y bloqueado sin querer algo.
     *
     * Silenciar una alarma que ya suena SÍ vale siempre: eso no puede depender de
     * si la pantalla está encendida, porque quien la quiere callar la está
     * mirando.
     */
    private fun pantallaDisponible(): Boolean {
        // si no se puede saber, se comporta como antes
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            /* Ojo con empezar la línea siguiente por `!`: Kotlin lo pega al tipo
               de la línea de arriba y lo lee como `KeyguardManager!`, el tipo de
               plataforma. El error que da no menciona el signo. */
            val despierta = pm.isInteractive
            val bloqueada = km.isKeyguardLocked
            (!despierta) || bloqueada
        } catch (_: Exception) {
            true
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
             event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            registrarPulsacion(subir = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP)
        }
        return false          // false = no lo consumimos, el volumen sigue igual
    }

    private fun registrarPulsacion(subir: Boolean) {
        val ahora = System.currentTimeMillis()
        pulsaciones.add(ahora)
        pulsaciones = pulsaciones.filter { ahora - it < VENTANA_MS }.toMutableList()
        Log.i("SismoRed", "tecla ${pulsaciones.size}/$NECESARIAS")

        /* CON LA ALARMA SONANDO, UNA SOLA PULSACIÓN LA CALLA.
           Pedir tres para apagar sería absurdo: quien la ha disparado sin querer
           tiene el móvil aullando en el bolsillo y lo primero que hace es
           agarrar el botón de volumen. Es la misma tecla que ya ha aprendido, y
           funciona con la pantalla bloqueada, que es cuando pasa. */
        if ((ServicioSos.enAlarma || ServicioSos.enRescate) &&
            ahora - desdeAlarma > GRACIA_MS) {
            pulsaciones.clear()
            Log.i("SismoRed", "SILENCIO por tecla de volumen")
            ContextCompat.startForegroundService(
                this,
                Intent(this, ServicioSos::class.java).setAction(ServicioSos.ACCION_PARAR)
            )
            return
        }

        /* Disparar, solo con SUBIR, que es lo que dicen las instrucciones.
           Bajar con la pantalla apagada lo reclaman los fabricantes para la
           cámara: en el Huawei, el 22 de septiembre de 2026, tres pulsaciones
           hicieron una foto y la alarma no se encendió; con subir, sí. */
        if (!subir) { pulsaciones.clear(); return }

        /* Aquí, y no antes: callar la alarma vale siempre, disparar no. */
        if (!pantallaDisponible()) {
            if (pulsaciones.size >= NECESARIAS) {
                pulsaciones.clear()
                Log.i("SismoRed", "atajo ignorado: estás usando el móvil")
            }
            return
        }

        if (pulsaciones.size >= NECESARIAS) {
            pulsaciones.clear()
            Log.i("SismoRed", "DISPARO por teclas de volumen")
            if (!ServicioSos.enAlarma) {
                desdeAlarma = ahora
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, ServicioSos::class.java).setAction(ServicioSos.ACCION_PANICO)
                )
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i("SismoRed", "servicio de teclas conectado")
        // Con el servicio activo, la vigilancia debe estar corriendo.
        ContextCompat.startForegroundService(this, Intent(this, ServicioSos::class.java))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}

/** ¿Está disponible el atajo por accesibilidad en esta variante de la app? */
fun teclasDisponibles(): Boolean = true

/** ¿Ha activado el usuario el servicio en Ajustes? */
fun teclasActivas(ctx: Context): Boolean {
    /* Por qué no basta con mirar la cadena de Ajustes:
       `ENABLED_ACCESSIBILITY_SERVICES` guarda los componentes, pero cada
       fabricante los escribe como le parece — unos ponen la forma larga
       («red.sismo/red.sismo.ServicioTeclas») y otros la corta
       («red.sismo/.ServicioTeclas»). Buscando solo la larga, el atajo salía como
       SIN ACTIVAR estando activo, y por eso el estado parecía inconsistente. Y
       hay un segundo interruptor, el maestro de accesibilidad, que puede estar
       apagado con el servicio marcado.

       Así que primero se le pregunta al gestor, que es quien lo sabe de verdad, y
       la cadena queda solo de respaldo — con sus dos formas. */
    try {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as android.view.accessibility.AccessibilityManager
        val mio = ComponentName(ctx, ServicioTeclas::class.java)
        val lista = am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        for (s in lista) {
            val id = s.id ?: continue
            if (ComponentName.unflattenFromString(id) == mio) return true
        }
        // el gestor ha contestado y no está: no hace falta mirar la cadena
        if (lista != null) return false
    } catch (_: Exception) {}

    val activos = android.provider.Settings.Secure.getString(
        ctx.contentResolver,
        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val p = ctx.packageName
    val corto = "$p/.${ServicioTeclas::class.java.simpleName}"
    val largo = "$p/${ServicioTeclas::class.java.name}"
    return activos.contains(largo) || activos.contains(corto)
}
