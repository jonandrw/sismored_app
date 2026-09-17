package red.sismo

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.*

/**
 * Receptor de eventos y alertas sísmicas en tiempo real vía redes abiertas (EMSC / USGS / FDSN).
 *
 * **Filosofía**: Zero librerías externas de terceros. Usa `HttpURLConnection` nativo
 * y `org.json` de Android.
 *
 * Si el teléfono tiene conexión a internet (WiFi o datos móviles), consulta periódicamente
 * o recibe transmisiones de los centros sismológicos internacionales (EMSC / USGS).
 * Si se reporta un sismo de magnitud >= 3.8 a menos de 450 km del usuario en los últimos
 * 3 minutos, activa `alertaExterna = true`, proporcionando la corroboración que hoy
 * faltó al fallar la notificación de Google Play Services.
 */
class ReceptorSismicoOnline(
    private val getUbicacion: () -> Pair<Double, Double>? = { null },
    private val onAlertaSismica: (mag: Double, distKm: Double, lugar: String) -> Unit,
    private val onRegistro: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "SismoRed"
        /**
         * La consulta al catálogo. **`limit=10` sin filtro dejaba esto ciego.**
         *
         * Diez huecos sin filtrar se los come la sismicidad mundial: medido el
         * 17 de septiembre de 2026, los diez eventos más recientes cubrían
         * ochenta y seis minutos y eran de Guatemala, Chile, Turquía, Indonesia,
         * Costa Rica, Sudáfrica y Tonga — con la mitad por debajo de M3. Un
         * sismo colombiano caía de la lista en minutos, y con el receptor
         * preguntando cada 45 segundos era cuestión de suerte pillarlo. El M4.4
         * de esa madrugada no se pilló.
         *
         * Con `minmag` y cien huecos, la misma consulta cubre treinta y tres
         * horas y salen los cinco sismos colombianos de los últimos dos días.
         *
         * El filtro de magnitud va en el servidor y el de DISTANCIA no, a
         * propósito: mandar un recuadro geográfico —o peor, la posición— sería
         * decirle al servidor dónde está quien pregunta, y la política de
         * privacidad promete que eso no viaja. Se piden los sismos grandes del
         * mundo entero y se descartan aquí los que quedan lejos.
         *
         * `minmag` 3,5 y no [MAG_MIN] 3,8: un poco de margen para que un evento
         * no se pierda si luego le revisan la magnitud a la baja.
         */
        private const val EMSC_URL =
            "https://www.seismicportal.eu/fdsnws/event/1/query?format=json&limit=100&minmag=3.5"
        private const val RADIO_MAX_KM = 450.0
        private const val MAG_MIN = 3.8
        /**
         * Qué antigüedad se le admite a un sismo del catálogo.
         *
         * **Estaban en tres minutos y con eso esto no podía dispararse nunca.**
         * El filtro mira la hora de ORIGEN del terremoto, pero un catálogo no
         * publica en el instante en que tiembla: primero llega la onda a las
         * estaciones, luego se calcula la solución y luego se publica. El M5.0
         * del 16 de septiembre de 2026 tiene origen a las 20:12:52 UTC y USGS lo
         * revisó a las 20:47 y otra vez a las 21:01. Con la ventana en tres
         * minutos, para cuando el evento aparecía en la consulta ya era
         * demasiado viejo y se descartaba — para siempre, porque cada evento se
         * mira una sola vez.
         *
         * Veinte minutos es generoso a propósito: todavía no sabemos cuánto
         * tarda EMSC en publicar, y equivocarse por corto deja la función
         * muerta mientras que equivocarse por largo solo avisa de algo que pasó
         * hace un rato — que es justo lo que uno quiere saber después de notar
         * un temblor. El destinatario de esta prueba ya la acota: la ventana de
         * `alertaCatalogo` dura diez minutos desde que llega.
         */
        private const val VENTANA_TIEMPO_MS = 20 * 60_000L
    }

    @Volatile var corriendo = false; private set
    private var hilo: Thread? = null
    private val eventosVistos = HashSet<String>()

    fun arrancar() {
        if (corriendo) return
        corriendo = true
        hilo = thread(name = "ReceptorSismicoOnline", isDaemon = true) {
            onRegistro("receptor sísmico online iniciado (red abierta EMSC)")
            while (corriendo) {
                try {
                    consultarEmsc()
                } catch (e: Exception) {
                    Log.d(TAG, "ReceptorSismicoOnline error temporal: ${e.message}")
                }
                // Consulta cada 45 segundos mientras haya conexión
                try { Thread.sleep(45_000L) } catch (_: InterruptedException) { break }
            }
        }
    }

    fun parar() {
        corriendo = false
        hilo?.interrupt()
        hilo = null
    }

    /** Consulta el endpoint público y abierto de EMSC (FDSN GeoJSON). */
    fun consultarEmsc() {
        val url = URL(EMSC_URL)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "SismoRed-Android/OpenEmergency")
        }

        if (conn.responseCode == 200) {
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val jsonStr = reader.readText()
            reader.close()
            procesarGeoJson(jsonStr)
        }
        conn.disconnect()
    }

    /** Procesa la respuesta GeoJSON estándar del FDSN. */
    fun procesarGeoJson(jsonStr: String) {
        val ubi = getUbicacion()
        val miLat = ubi?.first ?: 0.0
        val miLon = ubi?.second ?: 0.0
        val ahora = System.currentTimeMillis()

        // 1. Intento estándar con org.json (Android runtime)
        try {
            val json = JSONObject(jsonStr)
            val features = json.optJSONArray("features")
            if (features != null && features.length() > 0) {
                for (i in 0 until features.length()) {
                    val feat = features.getJSONObject(i)
                    val id = feat.optString("id", "")
                    if (id.isBlank() || eventosVistos.contains(id)) continue

                    val props = feat.optJSONObject("properties") ?: continue
                    val geom = feat.optJSONObject("geometry") ?: continue
                    val coords = geom.optJSONArray("coordinates") ?: continue
                    if (coords.length() < 2) continue

                    val lon = coords.getDouble(0)
                    val lat = coords.getDouble(1)
                    val mag = props.optDouble("mag", 0.0)
                    val place = props.optString("flynn_region", props.optString("place", "Región desconocida"))

                    val timeStr = props.optString("time", "")
                    val timeMs = parseIso(timeStr)

                    if (ahora - timeMs in -30_000L..VENTANA_TIEMPO_MS && mag >= MAG_MIN) {
                        val dist = if (miLat != 0.0 || miLon != 0.0) distanciaKm(miLat, miLon, lat, lon) else 0.0
                        val enColombia = lat in -4.5..13.5 && lon in -79.5..-66.5
                        if (dist in 0.1..RADIO_MAX_KM || (miLat == 0.0 && miLon == 0.0 && enColombia)) {
                            eventosVistos.add(id)
                            onRegistro("ALERTA SÍSMICA ONLINE RECIBIDA: M$mag en $place (~${dist.toInt()} km)")
                            onAlertaSismica(mag, dist, place)
                        }
                    }
                }
                return
            }
        } catch (_: Exception) {}

        // 2. Fallback por regex (en caso de que org.json esté stubbed en tests host JVM)
        try {
            val magMatch = Regex("\"mag\"\\s*:\\s*([0-9.]+)").find(jsonStr)
            val coordsMatch = Regex("\"coordinates\"\\s*:\\s*\\[\\s*([-\\d.]+)\\s*,\\s*([-\\d.]+)").find(jsonStr)
            val placeMatch = Regex("\"(?:flynn_region|place)\"\\s*:\\s*\"([^\"]+)\"").find(jsonStr)
            val idMatch = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(jsonStr)

            val id = idMatch?.groupValues?.getOrNull(1) ?: "evento_online"
            if (eventosVistos.contains(id)) return

            if (magMatch != null && coordsMatch != null) {
                val mag = magMatch.groupValues[1].toDoubleOrNull() ?: 0.0
                val lon = coordsMatch.groupValues[1].toDoubleOrNull() ?: 0.0
                val lat = coordsMatch.groupValues[2].toDoubleOrNull() ?: 0.0
                val place = placeMatch?.groupValues?.getOrNull(1) ?: "Región desconocida"

                if (mag >= MAG_MIN) {
                    val dist = if (miLat != 0.0 || miLon != 0.0) distanciaKm(miLat, miLon, lat, lon) else 0.0
                    val enColombia = lat in -4.5..13.5 && lon in -79.5..-66.5
                    if (dist in 0.1..RADIO_MAX_KM || (miLat == 0.0 && miLon == 0.0 && enColombia)) {
                        eventosVistos.add(id)
                        onRegistro("ALERTA SÍSMICA ONLINE RECIBIDA: M$mag en $place (~${dist.toInt()} km)")
                        onAlertaSismica(mag, dist, place)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun parseIso(iso: String): Long {
        if (iso.isBlank()) return System.currentTimeMillis()
        return try {
            java.time.Instant.parse(iso).toEpochMilli()
        } catch (_: Exception) {
            try {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                sdf.parse(iso)?.time ?: System.currentTimeMillis()
            } catch (_: Exception) {
                System.currentTimeMillis()
            }
        }
    }

    /** Fórmula de Haversine para distancia en gran círculo (km). */
    fun distanciaKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    /** Autotest sintético para verificar distancia y parsing. */
    fun autotest(): Pair<Boolean, String> {
        val partes = ArrayList<String>()
        var todo = true

        // 1. Distancia Istmina, Chocó (5.16, -76.68) a Pereira, Risaralda (4.81, -75.69) ~116 km
        val d = distanciaKm(5.16, -76.68, 4.81, -75.69)
        val okDist = abs(d - 116.0) < 10.0
        if (okDist) partes.add("Haversine Chocó-Pereira (~${d.toInt()} km) OK")
        else { todo = false; partes.add("Haversine Chocó-Pereira FALLÓ ($d km)") }

        // 2. Parser sintético de evento M4.9
        var recibidaMag = 0.0
        val receptor = ReceptorSismicoOnline(
            getUbicacion = { Pair(4.81, -75.69) },
            onAlertaSismica = { m, _, _ -> recibidaMag = m }
        )
        val ahoraIso = java.time.Instant.now().toString()
        val jsonMock = """{"features":[{"id":"test_choco_49","properties":{"mag":4.9,"flynn_region":"COLOMBIA","time":"$ahoraIso"},"geometry":{"coordinates":[-76.68,5.16,10.0]}}]}"""
        receptor.procesarGeoJson(jsonMock)
        if (abs(recibidaMag - 4.9) < 0.1) partes.add("Parser GeoJSON M4.9 OK")
        else { todo = false; partes.add("Parser GeoJSON FALLÓ ($recibidaMag)") }

        val txt = partes.joinToString(" | ")
        Log.i(TAG, "autotest receptor sísmico online -> $txt")
        return todo to txt
    }
}
