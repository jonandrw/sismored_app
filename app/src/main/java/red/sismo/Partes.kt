package red.sismo

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Partes que se enviarán **si** aparece internet. Port de `SismoRed/net.js`.
 *
 * Todo lo que hay que entender de esta clase está en lo que NO manda. Un parte
 * es la hora, el motivo de la alarma y los saltos de la malla. No lleva la
 * ficha médica, ni la ubicación, ni un segundo de audio, ni un identificador
 * del móvil. No porque no se pueda: porque una app que se lleva instalada por
 * si hay un terremoto no puede ser también algo que reporte dónde estás.
 *
 * La app no busca redes ni se conecta a ninguna: aprovecha la que el móvil ya
 * tenga. Y no manda nada hasta que alguien enciende el interruptor a mano.
 *
 * A diferencia de la PWA, aquí la cola sobrevive a que la maten: vive en
 * `SharedPreferences` y el servicio en primer plano la vacía cuando la red
 * aparece. Se hace con `ConnectivityManager` y no con `WorkManager` porque el
 * servicio ya está vivo por obligación — es lo que sostiene la sirena — y meter
 * una dependencia más para repetir lo que ya hay sería pagar dos veces.
 */
class Partes(private val ctx: Context) {

    companion object {
        private const val TAG = "SismoRed"
        private const val CLAVE = "cola_partes"
        private const val MAX = 50
        private const val REINTENTO_MS = 15000L

        /* Una función de Cloudflare Pages que valida y guarda, nada más. En la
           web era `/api/aviso` sobre el propio origen; una app nativa no tiene
           origen, así que va la URL entera.

           El receptor devuelve 2xx sólo cuando el parte está guardado de
           verdad. Importa porque abajo la cola se borra con cualquier 2xx: un
           200 de cortesía perdería los partes para siempre. */
        const val DESTINO = "https://sismored.app/api/aviso"
    }

    private val p = ctx.getSharedPreferences("sismored", Context.MODE_PRIVATE)
    private val cn = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    @Synchronized
    private fun leer(): JSONArray =
        try { JSONArray(p.getString(CLAVE, "[]")) } catch (e: Exception) { JSONArray() }

    @Synchronized
    private fun guardar(a: JSONArray) {
        // se quedan los últimos: si el móvil lleva días encolando, lo viejo importa menos
        val recorte = if (a.length() <= MAX) a else JSONArray().also { out ->
            for (i in a.length() - MAX until a.length()) out.put(a.get(i))
        }
        p.edit().putString(CLAVE, recorte.toString()).apply()
    }

    /** Un parte se encola SIEMPRE, haya red o no y esté el envío activado o no.
     *  Sin red se queda esperando; es lo que lo hace útil en un terremoto, donde
     *  la cobertura va y viene. Sin consentimiento no sale de aquí nunca. */
    fun encolar(motivo: String, hop: Int, porSalto: IntArray) {
        val o = JSONObject()
            .put("t", System.currentTimeMillis())
            .put("motivo", motivo.take(80))
            .put("hop", hop.coerceIn(0, MallaAcustica.MAX_HOP))
            .put("saltos", JSONArray().also { for (n in porSalto) it.put(n) })
            .put("v", 1)
        guardar(leer().put(o))
    }

    fun cuantos(): Int = leer().length()

    /** Encola un parte de PRUEBA, marcado como tal. Sirve para comprobar el camino
     *  entero sin esperar a que haya una alarma de verdad: la cola solo se llena
     *  cuando salta una, y hasta entonces no había forma de saber si esto funciona. */
    fun encolarPrueba() = encolar("PRUEBA MANUAL, no es una alerta real", 0, IntArray(MallaAcustica.MAX_HOP))

    fun borrar() { p.edit().remove(CLAVE).apply() }

    /** Solo garantiza el negativo, igual que el `navigator.onLine` de la PWA:
     *  que el sistema diga que hay internet no quiere decir que se llegue. */
    fun hayRed(): Boolean {
        val red = cn?.activeNetwork ?: return false
        val cap = cn.getNetworkCapabilities(red) ?: return false
        return cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Para la fila «Conexión». Si no se sabe se dice, no se inventa. */
    fun tipoRed(): String {
        val red = cn?.activeNetwork ?: return "sin red"
        val cap = cn.getNetworkCapabilities(red) ?: return "no disponible"
        val medida = if (cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) "" else " · de pago"
        return when {
            cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi$medida"
            cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "móvil$medida"
            cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "cable"
            else -> "otra"
        }
    }

    @Volatile private var ultimoIntento = 0L
    @Volatile private var enviando = false

    /**
     * Intenta soltar la cola entera de una vez. No hace nada si el usuario no lo
     * ha activado, si no hay red o si se intentó hace menos de 15 s.
     *
     * La cola solo se borra con un 2xx. Un portal cautivo devolviendo su página
     * de bienvenida da un 200 con basura, pero eso ya lo filtra el receptor
     * validando el JSON; lo que no puede pasar es tirar partes porque un
     * intermediario contestó cualquier cosa con un 4xx.
     */
    /**
     * Suelta la cola si se puede, y DICE por qué no cuando no se puede.
     *
     * Antes se rendía en silencio en cuatro sitios distintos —envío apagado, cola
     * vacía, sin red, o dentro de la ventana de reintento— y desde fuera eso era
     * indistinguible de que la función no existiera. Ahora cada salida tiene su
     * frase, y con [forzar] se salta la espera entre intentos para poder
     * comprobarlo a mano en el momento.
     */
    fun vaciar(onRegistro: (String) -> Unit, forzar: Boolean = false) {
        if (!Opciones(ctx).envio) {
            if (forzar) onRegistro("El envío está apagado. Enciende ENVIAR SI HAY RED.")
            return
        }
        if (enviando) { if (forzar) onRegistro("Ya hay un envío en marcha."); return }
        val cola = leer()
        if (cola.length() == 0) {
            if (forzar) onRegistro("No hay nada que enviar: la cola está vacía.")
            return
        }
        if (!hayRed()) {
            if (forzar) onRegistro("Sin red. Los ${cola.length()} parte(s) se quedan en cola.")
            return
        }
        val now = System.currentTimeMillis()
        if (!forzar && now - ultimoIntento < REINTENTO_MS) return
        ultimoIntento = now
        enviando = true
        if (forzar) onRegistro("Enviando ${cola.length()} parte(s) a $DESTINO…")

        val cuerpo = JSONObject().put("partes", cola).toString()
        val n = cola.length()
        thread(name = "partes", isDaemon = true) {
            var c: HttpURLConnection? = null
            try {
                c = (URL(DESTINO).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 8000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                c.outputStream.use { it.write(cuerpo.toByteArray(Charsets.UTF_8)) }
                val codigo = c.responseCode
                if (codigo in 200..299) {
                    borrar()
                    onRegistro("Enviados $n parte(s). El servidor contestó $codigo.")
                } else {
                    onRegistro("El servidor contestó HTTP $codigo. Sigue en cola.")
                }
            } catch (e: Exception) {
                // Sin red de verdad, DNS que no resuelve o tiempo agotado: se queda en cola.
                Log.i(TAG, "partes: envío pendiente (${e.javaClass.simpleName})")
                onRegistro("No se pudo enviar: ${e.message ?: "sin respuesta"}. Sigue en cola.")
            } finally {
                try { c?.disconnect() } catch (_: Exception) {}
                enviando = false
            }
        }
    }
}
