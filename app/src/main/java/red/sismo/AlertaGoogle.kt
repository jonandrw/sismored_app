package red.sismo

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.Locale

/**
 * Oír la alerta sísmica de Google y repartirla por la malla.
 *
 * **De dónde sale esto**: en un salón de clases, la alerta de Google no le llegó
 * al 80 % de los móviles Android. Necesita internet, servicios de Google y la
 * función activada, y el que la recibe la tiene segundos antes de que llegue la
 * sacudida. El móvil que SÍ la recibe puede repartirla por sonido a los que no
 * tienen internet, y ese es exactamente el caso que SismoRed resuelve y Google
 * no.
 *
 * No hay API pública para leerla, así que el único camino es escuchar la
 * notificación. Y eso es un permiso muy grande para una cosa muy pequeña, así
 * que las reglas de esta clase son estrictas y están escritas para poder
 * enseñárselas a cualquiera:
 *
 *  1. **Se sale inmediatamente si el paquete no es el de los servicios de
 *     Google.** Ni se mira el contenido de nada más. Es la primera línea de
 *     [onNotificationPosted] y no hay ninguna rama que la esquive.
 *  2. **No se guarda nada, ni se envía nada.** Lo único que sale de aquí es una
 *     llamada al propio servicio diciendo «ha entrado una alerta sísmica». Ni el
 *     texto, ni el paquete, ni la hora de ninguna otra notificación.
 *  3. **No se leen respuestas, ni contactos, ni mensajes.** No se toca
 *     `Notification.extras` de nada que no haya pasado el filtro de paquete.
 *
 * Y lo que hace cuando la reconoce **no es encender la alarma de víctima**: la
 * alerta llega ANTES del terremoto, así que en ese instante todavía no le ha
 * pasado nada a nadie. Lo que hace es avisar, repartir y ARMAR — ver
 * [Cascada.Pruebas.alertaExterna].
 */
class AlertaGoogle : NotificationListenerService() {

    companion object {
        private const val TAG = "SismoRed"

        /** Los servicios de Google. La alerta sísmica sale de aquí. */
        private val PAQUETES = setOf(
            "com.google.android.gms",
            "com.google.android.apps.safetyhub"
        )

        /**
         * Palabras que tiene que llevar para darla por buena.
         *
         * Se piden DOS listas a la vez: una de sismo y otra de aviso. Solo con
         * «terremoto» entraría cualquier noticia; pidiendo también «alerta» o
         * «prepárate» se queda con el aviso y no con el resumen posterior de «ha
         * habido un terremoto de magnitud X», que llega cuando ya ha pasado todo
         * y no hay nada que armar.
         */
        private val SISMO = listOf("terremoto", "sismo", "earthquake", "temblor")
        private val AVISO = listOf(
            "alerta", "alert", "prepár", "prepar", "protég", "protege",
            "agáchate", "agachate", "cúbrete", "cubrete", "drop", "shaking",
            "warning", "temprana", "early"
        )

        /** Reconoce el aviso por su texto. Separado y sin estado a propósito:
         *  así se puede comprobar sin notificaciones de verdad. */
        fun esAlertaSismica(titulo: String?, cuerpo: String?): Boolean {
            val t = ((titulo ?: "") + " " + (cuerpo ?: "")).lowercase(Locale.ROOT)
            if (t.isBlank()) return false
            return SISMO.any { t.contains(it) } && AVISO.any { t.contains(it) }
        }

        /** Los casos que definen el filtro, para el autotest de la app. */
        fun autotest(): Pair<Boolean, String> {
            val casos = listOf(
                Triple("alerta de Google en español", true,
                    "Alerta de terremoto" to "Sismo cerca de ti. Agáchate, cúbrete y espera."),
                Triple("alerta de Google en inglés", true,
                    "Earthquake alert" to "Shaking expected. Drop, cover and hold on."),
                Triple("aviso de alerta temprana", true,
                    "Alerta temprana de sismo" to "Prepárate."),
                /* Los tres que NO pueden colarse. El del resumen es el que más
                   importa: llega cuando el terremoto ya ha pasado, y armar el
                   móvil entonces sería armarlo tarde y para nada. */
                Triple("noticia de un terremoto lejano", false,
                    "Terremoto de magnitud 6,1 en Japón" to "Lee más en Noticias"),
                Triple("resumen posterior al sismo", false,
                    "Hubo un terremoto" to "Magnitud 5,2 a 30 km. ¿Lo sentiste?"),
                Triple("mensaje cualquiera", false,
                    "Mamá" to "¿Vienes a comer?")
            )
            val partes = ArrayList<String>()
            var todo = true
            for ((nombre, debe, texto) in casos) {
                val da = esAlertaSismica(texto.first, texto.second)
                val ok = da == debe
                if (!ok) todo = false
                partes.add("$nombre → ${if (da) "la reconoce" else "no"}" + if (ok) " OK" else " FALLÓ")
            }
            val txt = partes.joinToString(" | ")
            Log.i(TAG, "autotest alerta de Google · $txt")
            return todo to txt
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val s = sbn ?: return
        /* LA PRIMERA LÍNEA, y no hay ninguna rama que la esquive: si no viene de
           los servicios de Google, aquí se acaba. No se mira el contenido, no se
           anota el paquete y no se cuenta en ninguna parte. */
        if (s.packageName !in PAQUETES) return

        try {
            val ex = s.notification?.extras ?: return
            val titulo = ex.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
            val cuerpo = ex.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
            if (!esAlertaSismica(titulo, cuerpo)) return

            Log.i(TAG, "alerta sísmica de Google reconocida")
            /* Lo único que sale de aquí: un aviso al propio servicio. Sin el
               texto, que no hace falta para nada — lo que importa es que ha
               entrado, no lo que decía. */
            startService(Intent(this, ServicioSos::class.java)
                .setAction(ServicioSos.ACCION_ALERTA_EXTERNA))
        } catch (e: Exception) {
            Log.e(TAG, "alerta de Google", e)
        }
    }

    /** No se hace nada al quitarse una notificación: no se lleva ningún estado
     *  de nada, que es justo la garantía que se promete en Acerca de. */
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}
