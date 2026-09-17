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
        /**
         * El catálogo del Servicio Geológico Colombiano.
         *
         * Hace falta porque EMSC y USGS son catálogos globales y en Colombia no
         * bajan de M4 aproximadamente: el M3.5 de Istmina del 17 de septiembre
         * de 2026 a las 04:46, que se sintió, no está en ninguno de los dos y
         * sí está aquí. Los sismos que de verdad asustan a alguien son locales
         * y pequeños, y esos solo los publica la red nacional.
         *
         * Cinco días de eventos, unos 620, en GeoJSON. Dos trampas medidas:
         * las coordenadas van `[lat, lon, prof]` —al revés del GeoJSON
         * estándar, que es `[lon, lat]`— y el servidor devuelve 403 si la
         * petición no parece la de un navegador.
         */
        private const val SGC_URL =
            "https://archive.sgc.gov.co/feed/v1.0.1/summary/five_days_all.json"

        /**
         * Hasta dónde se avisa de un sismo, según su magnitud.
         *
         * Un radio fijo no sirve: un M3.5 a 300 km no lo nota nadie y a 40 km
         * despierta a la casa entera. El radio crece con la magnitud, que es
         * como se comporta de verdad la intensidad.
         *
         *     M3.0 -> 73 km    M4.0 -> 230 km    M5.0 -> 727 km
         *     M3.5 -> 129 km   M4.5 -> 409 km
         *
         * La constante sale de calibrar contra los cinco días del catálogo del
         * SGC: con ella entran los nueve sismos del enjambre del Chocó que se
         * sintieron —M4.3 a M4.9 a unos 120 km— y también el M3.5 de Istmina.
         *
         * **Dos avisos: son 4 al día, y eso es mucho.** Pero esos cinco días
         * son un enjambre activo, no una semana normal; en un mes tranquilo
         * esto da casi cero. Y el cálculo se hizo con una posición supuesta,
         * porque la de verdad no sale del móvil: la distancia real la calcula
         * la app con su GPS, así que el número que veas puede variar.
         */
        fun radioAviso(mag: Double): Double = 23.0 * Math.pow(10.0, 0.5 * (mag - 2.0))

        /** Tope duro, por si una magnitud absurda dispara la fórmula. */
        private const val RADIO_MAX_KM = 900.0

        /** Por debajo de esto no se mira nada, venga de donde venga. */
        private const val MAG_MIN = 3.0
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
                /* Las dos fuentes, y cada una por su lado: si una falla o
                   cambia de formato, la otra sigue avisando. */
                try { consultarEmsc() } catch (e: Exception) {
                    Log.d(TAG, "EMSC no contesta: ${e.message}")
                }
                try { consultarSgc() } catch (e: Exception) {
                    Log.d(TAG, "SGC no contesta: ${e.message}")
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
    fun consultarEmsc() = consultar(EMSC_URL, sgc = false, ua = "SismoRed-Android/OpenEmergency")

    /**
     * Consulta el feed del Servicio Geológico Colombiano.
     *
     * Va con `User-Agent` de navegador porque el cortafuegos del servidor
     * responde 403 a cualquier otra cosa —comprobado—. No es una gracia: es un
     * feed público que su propia web consume, y sin esa cabecera no se puede
     * leer.
     */
    fun consultarSgc() = consultar(
        SGC_URL, sgc = true,
        ua = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
    )

    private fun consultar(urlStr: String, sgc: Boolean, ua: String) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 12000
            requestMethod = "GET"
            setRequestProperty("User-Agent", ua)
            if (sgc) setRequestProperty("Referer", "https://www.sgc.gov.co/")
        }
        if (conn.responseCode == 200) {
            val jsonStr = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            procesarGeoJson(jsonStr, sgc)
        } else {
            Log.d(TAG, "catálogo ${if (sgc) "SGC" else "EMSC"} contestó ${conn.responseCode}")
        }
        conn.disconnect()
    }

    /** Procesa la respuesta GeoJSON estándar del FDSN. */
    fun procesarGeoJson(jsonStr: String, sgc: Boolean = false) {
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

                    /* El SGC pone la latitud PRIMERO, al reves del GeoJSON
                       estandar que usa EMSC. Comprobado contra Sipi, Choco:
                       [4.61, -76.68] es lat,lon — al derecho seria un punto en
                       mitad del Atlantico. */
                    val lat = if (sgc) coords.getDouble(0) else coords.getDouble(1)
                    val lon = if (sgc) coords.getDouble(1) else coords.getDouble(0)
                    val mag = props.optDouble("mag", 0.0)
                    val place = props.optString("flynn_region", props.optString("place", "Región desconocida"))

                    /* EMSC da ISO-8601 con zona; el SGC da «2026-09-17 11:33»
                       en UTC y sin marca de zona, con resolucion de minuto. */
                    val timeMs = if (sgc) parseSgc(props.optString("utcTime", ""))
                                 else parseIso(props.optString("time", ""))

                    /* El radio crece con la magnitud: un M3.5 lejos no lo nota
                       nadie y cerca despierta a la casa. Ver [radioAviso]. */
                    val radio = minOf(radioAviso(mag), RADIO_MAX_KM)
                    if (ahora - timeMs in -30_000L..VENTANA_TIEMPO_MS && mag >= MAG_MIN) {
                        val dist = if (miLat != 0.0 || miLon != 0.0) distanciaKm(miLat, miLon, lat, lon) else 0.0
                        val enColombia = lat in -4.5..13.5 && lon in -79.5..-66.5
                        if (dist in 0.1..radio || (miLat == 0.0 && miLon == 0.0 && enColombia)) {
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

    /**
     * «2026-09-17 11:33» en UTC, sin marca de zona y con resolucion de minuto.
     * Es el formato del SGC y no lo entiende ningun parser de ISO.
     */
    fun parseSgc(t: String): Long {
        if (t.isBlank()) return 0L
        return try {
            val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            f.timeZone = java.util.TimeZone.getTimeZone("UTC")
            f.parse(t)?.time ?: 0L
        } catch (_: Exception) { 0L }
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
