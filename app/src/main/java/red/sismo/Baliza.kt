package red.sismo

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * La baliza de radio: lo que permite que alguien de pie sobre los escombros
 * sepa que debajo hay un móvil, y hacia dónde.
 *
 * La malla acústica llega lejos entre móviles, pero no sirve para buscar: un
 * tono de 17 kHz dice a cuántos saltos está quien lo emitió, nunca hacia dónde.
 * Para eso hace falta algo cuya POTENCIA cambie según te acercas, y eso es
 * radio. Los 2,4 GHz atraviesan escombros mal pero atraviesan, y lo único que
 * hace falta es que la señal suba al acercarse.
 *
 * Se eligió BLE y no una red wifi por dos razones que no son negociables:
 *
 *  - **Android no deja a una app elegir el nombre de la red que crea.** Desde
 *    Android 8 solo existe `startLocalOnlyHotspot`, que genera un SSID
 *    aleatorio. El de arriba no puede buscar «SismoRed» si el de abajo no puede
 *    llamarse así, y nadie va a leerle el nombre a nadie desde debajo de una losa.
 *  - **La batería.** Un punto de acceso wifi se come en una hora lo que esta
 *    baliza tarda un día en gastar. Justo en el modo pensado para durar.
 *
 * Va en un anuncio, no en una conexión: el rescatista no tiene que emparejar ni
 * conectarse a nada, solo escanear mientras camina. Y así aparecen varios
 * atrapados a la vez, cada uno con su propia señal.
 */
class Baliza(private val ctx: Context) {

    companion object {
        private const val TAG = "SismoRed"

        /* UUID de 16 bits (0x516D) en vez de uno de 128: el anuncio BLE solo
           tiene 31 bytes en total, y los 14 que ahorra son la diferencia entre
           poder meter la ficha mínima o no. */
        val SERVICIO: ParcelUuid =
            ParcelUuid(UUID.fromString("0000516d-0000-1000-8000-00805f9b34fb"))

        const val VERSION = 1

        /* Estado, en un byte. El rescatista tiene que poder ordenar por esto:
           quien está en alarma pidió ayuda hace poco; quien está en rescate
           lleva horas y no se ha movido. */
        const val REPOSO = 0
        const val ALARMA = 1
        const val RESCATE = 2
        /** No es una víctima: es alguien BUSCANDO, de pie sobre los escombros.
         *  Los móviles enterrados lo escuchan y contestan con todo. */
        const val BUSCANDO = 3

        /** Los ocho grupos, en un byte. 0 = no lo ha rellenado. */
        val SANGRE = arrayOf("—", "O+", "O-", "A+", "A-", "B+", "B-", "AB+", "AB-")
        fun codigoSangre(s: String): Int {
            val n = s.trim().uppercase().replace(" ", "")
            val i = SANGRE.indexOf(n)
            return if (i > 0) i else 0
        }

        /* ---------- la cuenta de los 31 bytes ----------
           El anuncio BLE clásico son 31 bytes para TODAS las estructuras, y hay
           tres antes de la nuestra:

             banderas                      3
             nivel de potencia de emisión  3   (el rescatista lo necesita)
             UUID de servicio de 16 bits   4
             cabecera del service data     4   (largo + tipo + los 2 del UUID)
                                          ──
                                          14  →  quedan 17 para el contenido

           Con versión, estado, salto y grupo sanguíneo se van cuatro, así que el
           nombre puede ocupar trece bytes. Por eso va **solo el nombre de pila**:
           el completo no cabe, y de todas formas lo que se grita encima de un
           escombro es el nombre de pila. La edad se cayó del anuncio para hacerle
           sitio: entre saber cómo se llama quien está debajo y saber que tiene 34
           años, lo primero sirve para llamarlo y lo segundo no. */
        const val MAX_NOMBRE = 13

        /** El nombre de pila, recortado a lo que cabe sin partir un carácter por
         *  la mitad. Un recorte a ciegas dentro de una «ñ» o una tilde deja un
         *  byte suelto que al otro lado se lee como basura. */
        fun nombreCorto(nombre: String): ByteArray {
            val pila = nombre.trim().substringBefore(' ').trim()
            if (pila.isEmpty()) return ByteArray(0)
            var bytes = pila.toByteArray(Charsets.UTF_8)
            if (bytes.size <= MAX_NOMBRE) return bytes
            var n = pila.length
            while (n > 0) {
                bytes = pila.substring(0, n).toByteArray(Charsets.UTF_8)
                if (bytes.size <= MAX_NOMBRE) return bytes
                n--
            }
            return ByteArray(0)
        }
    }

    private val adaptador: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var emisor: BluetoothLeAdvertiser? = null
    @Volatile var emitiendo = false; private set
    /** Por qué no emite, para poder decirlo en Diagnóstico en vez de callar. */
    @Volatile var motivo = "sin arrancar"; private set

    /* Solo si el móvil tiene BLE. NO se mira `isMultipleAdvertisementSupported`:
       eso dice si caben VARIAS balizas a la vez y aquí hace falta una, así que
       preguntarlo dejaba fuera a móviles perfectamente capaces. Y devuelve falso
       con el bluetooth apagado, que es un problema distinto y hay que decirlo
       con su nombre en vez de soltar «este móvil no puede». */
    fun hayHardware(): Boolean =
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    /**
     * Enciende el bluetooth si esta apagado, cuando el sistema lo permite.
     *
     * Hay gente que no quiere el bluetooth abierto todo el dia, y es razonable: la
     * app solo lo necesita cuando salta la alarma. El problema es que Android cambio
     * las reglas por el camino:
     *
     *  - Hasta Android 12 (API 32) una app con `BLUETOOTH_ADMIN` puede encenderlo
     *    sola, sin preguntar. Eso es lo que hace falta aqui: en panico nadie va a
     *    estar tocando ajustes.
     *  - Desde Android 13, `enable()` esta retirado y **no hay forma de encenderlo
     *    en silencio**. Lo unico que queda es el dialogo del sistema
     *    (`ACTION_REQUEST_ENABLE`), y eso lo lanza la pantalla, no el servicio.
     *
     * Devuelve true si al salir esta encendido. Si devuelve false, el motivo ya dice
     * ENCIENDE EL BLUETOOTH y la pantalla se encarga de pedirlo.
     */
    fun encenderSiHaceFalta(): Boolean {
        val ad = adaptador ?: return false
        if (ad.isEnabled) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return false
        return try {
            @Suppress("DEPRECATION") ad.enable()
            // `enable()` es asincrono: se le da un momento antes de creerselo
            for (i in 0 until 20) {
                if (ad.isEnabled) return true
                Thread.sleep(100)
            }
            ad.isEnabled
        } catch (_: Exception) { false }
    }

    fun hayPermiso(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED

    private val callback = object : AdvertiseCallback() {
        override fun onStartSuccess(s: AdvertiseSettings?) {
            emitiendo = true; motivo = "emitiendo"
            Log.i(TAG, "baliza BLE activa")
        }
        override fun onStartFailure(codigo: Int) {
            emitiendo = false
            /* Si no cabe, se reintenta SIN el nombre. Una baliza sin nombre sigue
               sirviendo para encontrar a alguien; no tener baliza, no. */
            if (codigo == ADVERTISE_FAILED_DATA_TOO_LARGE && !sinNombre) {
                sinNombre = true
                Log.w(TAG, "baliza: el anuncio no cabe con el nombre, reintento sin él")
                motivo = "sin nombre: el anuncio no cabía"
                try { emitirYa() } catch (_: Exception) {}
                return
            }
            motivo = when (codigo) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "el anuncio no cabe"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "demasiadas balizas en el sistema"
                ADVERTISE_FAILED_ALREADY_STARTED -> "ya estaba emitiendo"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "error interno del bluetooth"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "este móvil no puede emitir balizas"
                else -> "fallo $codigo"
            }
            Log.e(TAG, "baliza BLE falló: $motivo")
        }
    }

    /**
     * Empieza (o reemite con datos nuevos) el anuncio.
     *
     * `sangre` y `edad` solo se pasan con la alarma o el rescate activos —
     * quien decide eso es el servicio, no esta clase. Fuera de eso van a cero y
     * el anuncio no lleva ni un dato personal.
     */
    fun emitir(estado: Int, salto: Int, sangre: Int, nombre: String): Boolean {
        ultimoEstado = estado; ultimoSalto = salto; ultimaSangre = sangre
        ultimoNombre = nombre
        sinNombre = false
        return emitirYa()
    }

    /* Lo último que se pidió emitir, para poder reintentarlo sin el nombre si el
       anuncio no cabe. Si se pierde el reintento se pierde la baliza entera, y
       quedarse sin baliza por un nombre largo sería el peor cambio posible. */
    private var ultimoEstado = 0
    private var ultimoSalto = 0
    private var ultimaSangre = 0
    private var ultimoNombre = ""
    @Volatile private var sinNombre = false

    private fun emitirYa(): Boolean {
        val estado = ultimoEstado
        val salto = ultimoSalto
        val sangre = ultimaSangre
        // el orden importa: cada fallo tiene un arreglo distinto y hay que
        // poder decirle a alguien cuál le toca
        if (!hayHardware()) { motivo = "este móvil no tiene BLE"; return false }
        val ad = adaptador
        if (ad == null) { motivo = "sin adaptador bluetooth"; return false }
        // en panico no se le puede pedir a nadie que vaya a los ajustes
        if (!ad.isEnabled && !encenderSiHaceFalta()) {
            motivo = "ENCIENDE EL BLUETOOTH"
            return false
        }
        if (!hayPermiso()) { motivo = "falta el permiso de bluetooth"; return false }

        parar()
        val e = try { ad.bluetoothLeAdvertiser } catch (ex: SecurityException) { null }
        if (e == null) { motivo = "el sistema no da el emisor"; return false }
        emisor = e

        /* Cuatro bytes fijos —versión, estado, salto, grupo— y detrás el nombre de
           pila en UTF-8, hasta trece. Ver la cuenta de los 31 bytes arriba. */
        val np = if (sinNombre) ByteArray(0) else nombreCorto(ultimoNombre)
        val datos = byteArrayOf(
            VERSION.toByte(),
            estado.coerceIn(0, 3).toByte(),
            salto.coerceIn(0, MallaAcustica.MAX_HOP).toByte(),
            sangre.coerceIn(0, 8).toByte()
        ) + np

        val ajustes = AdvertiseSettings.Builder()
            /* Potencia máxima y frecuencia alta: esto es exactamente lo contrario
               de una baliza comercial, que ahorra. Aquí el gasto extra es lo que
               hace que la señal cruce una losa más, y aun así consume mucho menos
               que cualquier alternativa. */
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)          // no hay nada a lo que conectarse: solo se escucha
            .setTimeout(0)                  // hasta que se pare a mano
            .build()

        val anuncio = AdvertiseData.Builder()
            .setIncludeDeviceName(false)    // el nombre del móvil es un dato personal
            .setIncludeTxPowerLevel(true)   // el rescatista lo necesita para estimar distancia
            .addServiceUuid(SERVICIO)
            .addServiceData(SERVICIO, datos)
            .build()

        return try {
            e.startAdvertising(ajustes, anuncio, callback)
            true
        } catch (ex: Exception) {
            motivo = "no se pudo arrancar: ${ex.javaClass.simpleName}"
            Log.e(TAG, "baliza BLE", ex)
            false
        }
    }

    fun parar() {
        try { emisor?.stopAdvertising(callback) } catch (_: Exception) {}
        emisor = null
        if (emitiendo) { emitiendo = false; motivo = "parada" }
    }
}
