package red.sismo

import android.accessibilityservice.AccessibilityService
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

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
             event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            registrarPulsacion()
        }
        return false          // false = no lo consumimos, el volumen sigue igual
    }

    private fun registrarPulsacion() {
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

/** ¿Ha activado el usuario el servicio en Ajustes? */
fun teclasActivas(ctx: Context): Boolean {
    val activos = android.provider.Settings.Secure.getString(
        ctx.contentResolver,
        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return activos.contains("${ctx.packageName}/${ServicioTeclas::class.java.name}")
}
