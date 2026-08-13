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
        val medicacion: String,
        val contacto: String,
        @Volatile var visto: Long
    )

    private val recibidas = ConcurrentHashMap<String, Recibida>()

    @Volatile var emitiendo = false; private set
    @Volatile var escuchando = false; private set

    private var socketRx: DatagramSocket? = null

    /* ---------- emitir ---------- */

    fun emitir(encender: Boolean) {
        if (encender == emitiendo) return
        emitiendo = encender
        if (!encender) { onRegistro("Ficha por Wi-Fi: dejo de emitirla"); return }
        thread(name = "ficha-lan-tx", isDaemon = true) {
            var avisado = false
            while (emitiendo) {
                try {
                    val f = Ficha(ctx)
                    if (!f.vacia()) {
                        val bytes = paquete(f)
                        DatagramSocket().use { s ->
                            s.broadcast = true
                            for (dir in difusiones()) {
                                try {
                                    s.send(DatagramPacket(bytes, bytes.size, dir, PUERTO))
                                } catch (_: Exception) {
                                    // una interfaz que no deja difundir no invalida las demás
                                }
                            }
                        }
                        if (!avisado) {
                            avisado = true
                            onRegistro("Emitiendo la ficha completa por Wi-Fi a quien esté en esta red")
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

    private fun paquete(f: Ficha): ByteArray = JSONObject()
        .put("v", VERSION)
        .put("nombre", f.nombre.trim())
        .put("sangre", f.sangre.trim().uppercase())
        .put("edad", f.edad.trim())
        .put("med", f.medicacion.trim())
        .put("contacto", f.contacto.trim())
        .toString().toByteArray(Charsets.UTF_8)

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
            return
        }
        thread(name = "ficha-lan-rx", isDaemon = true) {
            try {
                val s = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(java.net.InetSocketAddress(PUERTO))
                }
                socketRx = s
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
            j.optString("med"), j.optString("contacto"),
            System.currentTimeMillis()
        )
        if (nueva) {
            val n = j.optString("nombre").ifBlank { ip }
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
            if (r.medicacion.isNotBlank()) l.add("Avisos:    ${r.medicacion}")
            if (r.contacto.isNotBlank()) l.add("Contacto:  ${r.contacto}")
            l.add("Por Wi-Fi desde ${r.ip}")
            l.add("")
        }
        return if (l.isEmpty()) "—" else l.joinToString(System.lineSeparator()).trimEnd()
    }
}
