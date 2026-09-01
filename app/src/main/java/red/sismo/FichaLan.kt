package red.sismo

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * La ficha COMPLETA por Wi-Fi, cuando hay Wi-Fi.
 *
 * El anuncio de radio son 31 bytes y solo caben el grupo sanguíneo y trece letras
 * de nombre. Pero si hay una red local —el punto de acceso de un campamento de
 * rescate, el compartido de cualquiera, un router que aún tenga corriente— cabe la
 * ficha entera sin inventar nada: unos cientos de bytes en un datagrama.
 *
 * Va por **difusión UDP**, no por conexión: en una red local eso ahorra descubrir a
 * nadie, emparejar nada y montar un servidor. El que está enterrado grita su ficha
 * cada tres segundos y quien esté en la misma Wi-Fi la recibe, sin configurar nada.
 * Se manda a la difusión genérica y a la de cada interfaz, porque hay Android que
 * descarta la genérica y hay routers que descartan la otra.
 *
 * **Solo emite con la alarma o el modo rescate activos**, igual que la baliza de
 * radio y por el mismo motivo. Y hay que ser claro con lo que esto significa: por
 * aquí va la ficha ENTERA —nombre y apellido, alergias, medicación y contacto— a
 * cualquiera que esté en esa red, no trece bytes como en la radio. Es una cesión
 * mucho mayor, está escrita en la propia pantalla de la ficha, y se corta en cuanto
 * se detiene la alarma.
 *
 * Escuchar, en cambio, no se condiciona a nada: recibir un datagrama cada tres
 * segundos no se nota en la batería, y quien busca no siempre se acuerda de
 * encender las cosas.
 */
class FichaLan(
    private val ctx: Context,
    private val onRegistro: (String) -> Unit = {}
) {

    companion object {
        private const val TAG = "SismoRed"
        /** Puerto propio, alto y fuera de lo asignado. */
        const val PUERTO = 51789
        private const val CADA_MS = 3000L
        /** Sin volver a oírla en un minuto se da por ida: puede haberse quedado sin
         *  batería o haber salido de la red, y decir que sigue ahí sería mentir. */
        private const val CADUCA_MS = 60000L
        private const val VERSION = 1
    }

    /** Una ficha que ha llegado. Se indexa por IP: es lo único estable que hay en
     *  una red local sin montar identidades, y basta para no duplicar. */
    class Recibida(
        val ip: String,
        val nombre: String,
        val sangre: String,
        val edad: String,
        /** Lo que llega en `med`: las alergias. Nombre de campo heredado. */
        val alergias: String,
        val medicacion: String,
        val contacto: String,
        val telefono: String,
        @Volatile var visto: Long
    )

    private val recibidas = ConcurrentHashMap<String, Recibida>()

    @Volatile var emitiendo = false; private set
    @Volatile var escuchando = false; private set
    /** Por qué no está funcionando, si no está funcionando. Esto se pinta en
     *  Diagnóstico: un canal que falla en silencio es peor que no tenerlo. */
    @Volatile var motivo = "sin arrancar"; private set

    private var socketRx: DatagramSocket? = null

    /* ---------- el candado que hacía falta ----------
       Sin `MulticastLock` el chip de Wi-Fi **descarta los paquetes de difusión**
       antes de que lleguen a Android: filtra todo lo que no venga dirigido a este
       móvil para ahorrar batería. No da error, no da aviso y no llega nada.

       Y depende del fabricante, que es lo que lo hace tan difícil de ver: el
       Redmi los deja pasar y el Samsung A10s no. O sea que probándolo en un
       móvil parecía funcionar y en el otro «no accedía a la red».

       Cuesta batería mientras está cogido, así que se suelta al dejar de
       escuchar. */
    private val wifi = ctx.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
    private var candado: android.net.wifi.WifiManager.MulticastLock? = null

    private fun cogerCandado() {
        if (candado != null) return
        try {
            candado = wifi?.createMulticastLock("sismored-ficha")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "ficha Wi-Fi: candado de difusión ${if (candado?.isHeld == true) "cogido" else "NO"}")
        } catch (e: Exception) { Log.w(TAG, "ficha Wi-Fi candado: ${e.message}") }
    }

    private fun soltarCandado() {
        try { candado?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        candado = null
        escuchaFuerte(false)
    }

    /* ---------- el SEGUNDO candado, y esto costó encontrarlo ----------
       Con el `MulticastLock` cogido, el A10s **seguía sin recibir nada con la
       pantalla apagada**, y recibía perfectamente en cuanto se encendía. Medido
       en los dos sentidos: el Samsung emitía y el Redmi lo recibía siempre; el
       Redmi emitía y el Samsung solo lo recibía con la pantalla encendida.

       Son dos filtros distintos y hacen falta los dos candados:
         · `MulticastLock` quita el filtro de difusión del chip.
         · `WifiLock` en alto rendimiento le quita el ahorro de energía, que es
           lo que apaga la radio entre balizas con la pantalla apagada.

       Pero el segundo mantiene la radio despierta y eso **cuesta batería de
       verdad**, así que no se coge siempre: solo cuando hay un motivo para no
       perder un paquete —alguien buscando, la alarma, el rescate o el modo
       repetidor—. Escuchando de fondo, sin nada pasando, se prefiere durar. */
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    fun escuchaFuerte(v: Boolean) {
        try {
            if (v) {
                if (wifiLock?.isHeld == true) return
                @Suppress("DEPRECATION")
                wifiLock = wifi?.createWifiLock(
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "sismored-ficha"
                )?.apply { setReferenceCounted(false); acquire() }
                Log.i(TAG, "ficha Wi-Fi: escucha fuerte ${if (wifiLock?.isHeld == true) "cogida" else "NO"}")
            } else {
                wifiLock?.let { if (it.isHeld) it.release() }
                wifiLock = null
            }
        } catch (e: Exception) { Log.w(TAG, "ficha Wi-Fi wifiLock: ${e.message}") }
    }

    fun escuchandoFuerte(): Boolean = wifiLock?.isHeld == true

    /** La red Wi-Fi, si la hay. Hace falta para mandar POR ELLA: con datos
     *  móviles encendidos la ruta por defecto suele ser la del operador, y una
     *  difusión que sale por ahí no la ve nadie de la red local — otro fallo que
     *  no da ningún error. */
    private fun redWifi(): android.net.Network? {
        try {
            val cn = ctx.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager ?: return null
            @Suppress("DEPRECATION")
            for (n in cn.allNetworks) {
                val c = cn.getNetworkCapabilities(n) ?: continue
                if (c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) return n
            }
        } catch (_: Exception) {}
        return null
    }

    /** La IP local en la Wi-Fi, para poder decirla en pantalla. */
    fun ipLocal(): String? {
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) {
                    if (!a.isLoopbackAddress && a is java.net.Inet4Address) return a.hostAddress
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /** Una línea para Diagnóstico: qué está pasando de verdad con este canal. */
    fun estado(): String {
        val ip = ipLocal()
        val hayWifi = redWifi() != null
        return when {
            !hayWifi && ip == null -> "sin red local"
            !hayWifi -> "sin Wi-Fi (solo datos móviles)"
            !escuchando -> "no escucha"
            candado?.isHeld != true -> "escucha SIN candado de difusión"
            emitiendo -> "emitiendo y escuchando · $ip"
            escuchandoFuerte() -> "escuchando sin ahorro · $ip"
            else -> "escuchando · $ip (con la pantalla apagada puede perder alguna)"
        }
    }

    /* ---------- emitir ---------- */

    fun emitir(encender: Boolean) {
        if (encender == emitiendo) return
        emitiendo = encender
        if (!encender) { onRegistro("Ficha por Wi-Fi: dejo de emitirla"); return }
        thread(name = "ficha-lan-tx", isDaemon = true) {
            var avisado = ""
            while (emitiendo) {
                try {
                    val f = Ficha(ctx)
                    /* Los dos motivos por los que esto no salía y no se decía en
                       ninguna parte: la ficha está vacía, o no hay Wi-Fi. Los dos
                       se avisan una vez, y se vuelven a avisar si cambian. */
                    val red = redWifi()
                    val queja = when {
                        f.vacia() -> "Ficha por Wi-Fi: no la mando porque tu ficha está vacía. Rellénala en la pestaña Ficha."
                        red == null && ipLocal() == null -> "Ficha por Wi-Fi: no hay red local. Con datos móviles no se puede: hace falta una Wi-Fi."
                        else -> ""
                    }
                    if (queja.isNotEmpty()) {
                        if (avisado != queja) { avisado = queja; onRegistro(queja); motivo = queja }
                    } else {
                        val bytes = paquete(f)
                        var enviados = 0
                        DatagramSocket().use { s ->
                            s.broadcast = true
                            /* Mandar POR LA WI-FI, no por donde caiga. Con datos
                               móviles encendidos la ruta por defecto es la del
                               operador, y una difusión que sale por ahí no la
                               recibe nadie de la red local — sin error ninguno. */
                            try { red?.bindSocket(s) } catch (_: Exception) {}
                            for (dir in difusiones() + conocidos()) {
                                try {
                                    s.send(DatagramPacket(bytes, bytes.size, dir, PUERTO))
                                    enviados++
                                } catch (_: Exception) {
                                    // una interfaz que no deja difundir no invalida las demás
                                }
                            }
                        }
                        val ok = "Emitiendo la ficha completa por Wi-Fi desde ${ipLocal() ?: "?"} " +
                                 "a quien esté en esta red"
                        if (enviados == 0) {
                            val e = "Ficha por Wi-Fi: ninguna interfaz aceptó la difusión."
                            if (avisado != e) { avisado = e; onRegistro(e); motivo = e }
                        } else if (avisado != ok) {
                            avisado = ok; onRegistro(ok); motivo = "emitiendo por $enviados vía(s)"
                        }
                    }
                } catch (e: Exception) {
                    Log.i(TAG, "ficha Wi-Fi tx: ${e.javaClass.simpleName}")
                }
                var esperado = 0L
                while (emitiendo && esperado < CADA_MS) { Thread.sleep(200); esperado += 200 }
            }
        }
    }

    /**
     * Un solo datagrama de prueba, con un nombre falso y sin tocar la ficha real.
     *
     * Este canal falla en silencio de tres maneras distintas —el chip filtrando
     * la difusión, la ruta saliendo por los datos móviles, la ficha vacía— y
     * ninguna avisa. Con esto dos personas pueden comprobar en diez segundos que
     * se ven, sin esperar a un terremoto y sin enseñarle a nadie su ficha.
     */
    fun probar() {
        thread(name = "ficha-lan-prueba", isDaemon = true) {
            val red = redWifi()
            if (red == null && ipLocal() == null) {
                onRegistro("Prueba de ficha por Wi-Fi: no hay red local. Hace falta una Wi-Fi, con datos móviles no vale.")
                return@thread
            }
            val bytes = JSONObject()
                .put("v", VERSION)
                .put("nombre", "PRUEBA · no es una ficha real")
                .put("sangre", "").put("edad", "").put("med", "").put("contacto", "")
                .toString().toByteArray(Charsets.UTF_8)
            var enviados = 0
            try {
                DatagramSocket().use { s ->
                    s.broadcast = true
                    try { red?.bindSocket(s) } catch (_: Exception) {}
                    for (dir in difusiones() + conocidos()) {
                        try {
                            // tres veces: un datagrama suelto se pierde y no se reintenta
                            repeat(3) { s.send(DatagramPacket(bytes, bytes.size, dir, PUERTO)); Thread.sleep(150) }
                            enviados++
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
            Log.i(TAG, "ficha Wi-Fi: prueba enviada por $enviados vía(s) desde ${ipLocal()}")
            onRegistro(
                if (enviados > 0)
                    "Prueba enviada desde ${ipLocal()}. En el otro móvil tiene que aparecer «PRUEBA» en Buscar."
                else "Prueba de ficha por Wi-Fi: ninguna interfaz aceptó la difusión."
            )
        }
    }

    private fun paquete(f: Ficha): ByteArray = JSONObject()
        .put("v", VERSION)
        .put("nombre", f.nombreCompleto())
        .put("sangre", f.sangre.trim().uppercase())
        .put("edad", f.edad.trim())
        /* Aquí sí caben los dos por separado: esto va por UDP y no por los 31
           bytes de la baliza. `med` mantiene el nombre que ya entienden los
           móviles con la versión anterior y lleva las alergias, que es lo que
           llevaba antes; la medicación viaja en su propio campo. */
        .put("med", f.alergias.trim())
        .put("medicacion", f.medicacion.trim())
        .put("contacto", f.contacto.trim())
        .put("telefono", f.telefono.trim())
        /* La última posición conocida, si la hay y no está caducada. Va aquí y
           no en la baliza de radio porque en 31 bytes no cabe, y porque este
           canal ya lleva la ficha entera con la misma regla: solo con la alarma
           o el rescate activos. */
        .also { j ->
            Ubicacion(ctx).paraEnviar()?.let { (lat, lon, t) ->
                j.put("lat", lat).put("lon", lon).put("ubi_t", t)
            }
        }
        .toString().toByteArray(Charsets.UTF_8)

    /**
     * Y además, a quien ya se ha oído alguna vez, por unicast.
     *
     * Medido entre dos móviles: el A10s **no recibe difusiones con la pantalla
     * apagada** ni con el candado de difusión ni con el de rendimiento cogidos —
     * el Redmi sí—. Es cosa del firmware de ese Wi-Fi y no se arregla desde la
     * app. Lo que sí pasa el filtro dormido es un paquete dirigido A ESE MÓVIL,
     * así que a los que ya han contestado alguna vez se les manda también
     * directo. No arregla el primer contacto, pero una vez que dos móviles se
     * han visto, ya no se pierden aunque uno se duerma.
     */
    private fun conocidos(): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        for (r in recibidas.values) {
            try { out.add(InetAddress.getByName(r.ip)) } catch (_: Exception) {}
        }
        return out
    }

    /** La difusión genérica y la de cada interfaz que esté levantada. */
    private fun difusiones(): List<InetAddress> {
        val out = ArrayList<InetAddress>(3)
        try { out.add(InetAddress.getByName("255.255.255.255")) } catch (_: Exception) {}
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) ia.broadcast?.let { out.add(it) }
            }
        } catch (_: Exception) {}
        return out
    }

    /* ---------- escuchar ---------- */

    fun escuchar(encender: Boolean) {
        if (encender == escuchando) return
        escuchando = encender
        if (!encender) {
            try { socketRx?.close() } catch (_: Exception) {}
            socketRx = null
            soltarCandado()
            return
        }
        /* ANTES de abrir el socket: si el chip ya está filtrando, los paquetes
           que lleguen mientras tanto se pierden sin dejar rastro. */
        cogerCandado()
        thread(name = "ficha-lan-rx", isDaemon = true) {
            try {
                val s = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(java.net.InetSocketAddress(PUERTO))
                }
                socketRx = s
                motivo = estado()
                Log.i(TAG, "ficha Wi-Fi rx: escuchando en $PUERTO · ${estado()}")
                val buf = ByteArray(2048)
                while (escuchando) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    try { guardar(p) } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                // cerrar el socket para parar levanta una excepción: es lo normal
                if (escuchando) Log.i(TAG, "ficha Wi-Fi rx: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun guardar(p: DatagramPacket) {
        val j = JSONObject(String(p.data, 0, p.length, Charsets.UTF_8))
        if (j.optInt("v") != VERSION) return
        val ip = p.address?.hostAddress ?: return
        // la propia no interesa: un móvil no tiene que encontrarse a sí mismo
        if (esMia(ip)) return
        val nueva = recibidas[ip] == null
        recibidas[ip] = Recibida(
            ip,
            j.optString("nombre"), j.optString("sangre"), j.optString("edad"),
            j.optString("med"), j.optString("medicacion"),
            j.optString("contacto"), j.optString("telefono"),
            System.currentTimeMillis()
        )
        if (nueva) {
            val n = j.optString("nombre").ifBlank { ip }
            Log.i(TAG, "ficha Wi-Fi rx: FICHA COMPLETA de $ip ($n)")
            onRegistro("FICHA COMPLETA recibida por Wi-Fi: $n")
        }
    }

    private fun esMia(ip: String): Boolean {
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                for (a in ni.inetAddresses) if (a.hostAddress == ip) return true
            }
        } catch (_: Exception) {}
        return false
    }

    /** Las vivas, de la más reciente a la más vieja. */
    fun fichas(): List<Recibida> {
        val ahora = System.currentTimeMillis()
        recibidas.entries.removeAll { ahora - it.value.visto > CADUCA_MS }
        return recibidas.values.sortedByDescending { it.visto }
    }

    /** Lo que la pantalla enseña: una ficha por bloque, campo por línea. */
    fun comoTexto(): String {
        val l = ArrayList<String>()
        for (r in fichas()) {
            if (r.nombre.isNotBlank()) l.add("Nombre:    ${r.nombre}")
            if (r.sangre.isNotBlank()) l.add("Grupo:     ${r.sangre}")
            if (r.edad.isNotBlank()) l.add("Edad:      ${r.edad} años")
            if (r.alergias.isNotBlank()) l.add("Alergias:  ${r.alergias}")
            if (r.medicacion.isNotBlank()) l.add("Medicación:${r.medicacion}")
            if (r.contacto.isNotBlank()) l.add("Contacto:  ${r.contacto}")
            if (r.telefono.isNotBlank()) l.add("Teléfono:  ${r.telefono}")
            l.add("Por Wi-Fi desde ${r.ip}")
            l.add("")
        }
        return if (l.isEmpty()) "—" else l.joinToString(System.lineSeparator()).trimEnd()
    }
}
