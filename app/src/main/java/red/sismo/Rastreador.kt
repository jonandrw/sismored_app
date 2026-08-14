package red.sismo

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * El otro lado de la baliza: el móvil de quien busca.
 *
 * Escanea anuncios de SismoRed y convierte la potencia de cada uno en algo que
 * sirva caminando sobre un montón de escombros.
 *
 * La decisión importante de esta clase es **lo que NO hace**: no traduce la
 * potencia a metros. La conversión de dBm a distancia asume espacio libre, y
 * aquí entre el emisor y el receptor hay hormigón, hierro y agua — dos metros
 * de escombro atenúan más que veinte de aire. Un número en metros sería una
 * mentira redonda y mandaría a cavar al sitio equivocado.
 *
 * Lo que sí sirve, y es lo que se da, es **la tendencia**: si al dar tres pasos
 * la señal sube, vas bien. Eso no depende de calibrar nada y funciona igual bajo
 * cualquier escombro. Buscar así es de libro: se camina, se mira si sube, y se
 * gira cuando baja.
 */
class Rastreador(private val ctx: Context) {

    companion object {
        private const val TAG = "SismoRed"
        /** Sin verse en 20 s se da por perdido: al otro lado puede haberse
         *  quedado sin batería, y decir que sigue ahí sería mentir. */
        private const val CADUCA_MS = 20000L
        /** Cada cuánto se guarda una foto de la señal para medir la tendencia. */
        private const val HISTORIA_MS = 2500L
        /* ---------- la barra ----------
           La escala era lineal de −100 a −40 dBm, y eso la hacía inútil justo
           donde importa: a un metro o dos llega del orden de −70 dBm, que en una
           recta así son un 50 %, y a esa distancia ya estás encima de la persona.
           El interfono, que pide un 70 %, no aparecía nunca.

           Ahora la escala está partida en dos tramos, y los anclajes salen de lo
           que se mide en la mano, no de la teoría:

             −50 dBm o más fuerte  → 100 %  (a un palmo, sobre el escombro)
             −78 dBm               →  70 %  (a un par de metros: ya es «cerca»)
             −95 dBm               →   0 %  (al límite de oírlo)

           Con esto, el tramo de arriba es amplio y el de abajo comprime lo que de
           todas formas no sirve para orientarse. Sigue sin ser distancia: dos
           metros de escombro atenúan más que veinte de aire, y por eso lo que
           guía es la tendencia. */
        private const val DBM_ENCIMA = -50.0
        private const val DBM_CERCA = -78.0
        private const val DBM_LIMITE = -95.0
        /** El porcentaje que marca `DBM_CERCA`, y el mismo que abre el interfono y
         *  dispara el aviso silencioso. Los tres tienen que ser el mismo número o
         *  la barra dice una cosa y la app hace otra. */
        const val PCT_CERCA = 70
    }

    /** Un móvil oído, con lo que se sabe de él. */
    class Hallazgo(val id: String) {
        @Volatile var rssi = -127
        /** Media exponencial: la potencia cruda salta 10 dB de una lectura a
         *  otra y sin suavizar la barra es inservible para orientarse. */
        @Volatile var suave = -127.0
        @Volatile var mejor = -127
        @Volatile var visto = 0L
        @Volatile var estado = Baliza.REPOSO
        @Volatile var salto = 0
        @Volatile var sangre = 0
        /** El nombre de pila, si el otro móvil lo emite. Vacío si no. */
        @Volatile var nombre = ""
        /** Potencia hace unos segundos, para saber si nos acercamos. */
        @Volatile var antes = -127.0
        @Volatile var marcaHistoria = 0L
        /** Potencia con la que el otro dice que emite, si la anuncia. */
        @Volatile var txPower = 127

        /* ---------- la ficha, que llega a trozos ----------
           En 31 bytes no cabe, así que el otro móvil la manda en tramas y aquí se
           juntan. Lo que se pierde llega en la vuelta siguiente, porque la baliza
           repite sin parar mientras dure la emergencia.

           `tramas` no es un detalle técnico: «7 de 9» es una medida honesta de la
           calidad del enlace, y es justo lo que necesita saber quien busca —si
           sube, se está acercando; si se queda en 3 de 9, hay demasiado escombro
           en medio. */
        val trozos = java.util.concurrent.ConcurrentHashMap<Int, ByteArray>()
        @Volatile var totalTramas = 0

        /** Las que han llegado de las que hay. */
        fun tramas(): String =
            if (totalTramas == 0) "" else "${trozos.size} de $totalTramas"

        /** La ficha armada, o vacío mientras falte alguna. */
        fun fichaCompleta(): String {
            if (totalTramas == 0 || trozos.size < totalTramas) return ""
            var b = ByteArray(0)
            for (k in 1..totalTramas) b += (trozos[k] ?: return "")
            val campos = String(b, Charsets.UTF_8).split("")
            val et = listOf("Edad", "Avisos", "Contacto")
            return campos.mapIndexedNotNull { i, v ->
                val t = v.trim()
                if (t.isEmpty() || i >= et.size) null else "${et[i]}: $t"
            }.joinToString(System.lineSeparator())
        }
        /** Pérdida de trayecto en dB: lo que emitió menos lo que llega. Es más
         *  comparable entre modelos que el dBm crudo, porque descuenta que cada
         *  móvil emite con una potencia distinta. Sigue SIN ser distancia. */
        val perdida: Int get() = if (txPower == 127) 0 else txPower - suave.toInt()

        /** 0 a 100. No es distancia y no se presenta como tal. Dos tramos: ver los
         *  anclajes en el `companion object`. */
        val proximidad: Int
            get() {
                val s = suave
                return when {
                    s >= DBM_ENCIMA -> 100
                    s >= DBM_CERCA -> {
                        // de 70 a 100 entre −78 y −50 dBm
                        val t = (s - DBM_CERCA) / (DBM_ENCIMA - DBM_CERCA)
                        (PCT_CERCA + t * (100 - PCT_CERCA)).toInt()
                    }
                    s >= DBM_LIMITE -> {
                        // de 0 a 70 entre −95 y −78 dBm
                        val t = (s - DBM_LIMITE) / (DBM_CERCA - DBM_LIMITE)
                        (t * PCT_CERCA).toInt()
                    }
                    else -> 0
                }.coerceIn(0, 100)
            }

        /** +1 acercándose, −1 alejándose, 0 igual. El umbral de 3 dB es el
         *  ruido normal de una lectura: por debajo no significa nada. */
        val tendencia: Int
            get() = when {
                antes <= -127.0 -> 0
                suave - antes > 3 -> 1
                antes - suave > 3 -> -1
                else -> 0
            }
    }

    private val adaptador: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val vistos = java.util.concurrent.ConcurrentHashMap<String, Hallazgo>()
    @Volatile var rastreando = false; private set
    @Volatile var motivo = "Sin arrancar"; private set
    private var alCambiar: (() -> Unit)? = null

    fun hayHardware(): Boolean =
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    fun hayPermiso(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        else
        // Hasta Android 11 el sistema exige la ubicación para escanear, aunque
        // aquí no se use para nada: un anuncio BLE deja saber dónde estás.
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private val callback = object : ScanCallback() {
        override fun onScanResult(tipo: Int, r: ScanResult?) { procesar(r) }
        override fun onBatchScanResults(rs: MutableList<ScanResult>?) { rs?.forEach { procesar(it) } }
        override fun onScanFailed(codigo: Int) {
            rastreando = false
            motivo = "El escaneo falló ($codigo)"
            Log.e(TAG, "rastreador: $motivo")
        }
    }

    private fun procesar(r: ScanResult?) {
        val res = r ?: return
        val datos = res.scanRecord?.getServiceData(Baliza.SERVICIO) ?: return
        if (datos.isEmpty() || datos[0].toInt() != Baliza.VERSION) return

        val id = res.device?.address ?: return
        val h = vistos.getOrPut(id) { Hallazgo(id) }
        val ahora = System.currentTimeMillis()

        val tp = try { res.txPower } catch (_: Throwable) { 127 }
        if (tp != 127) h.txPower = tp
        else res.scanRecord?.txPowerLevel?.let { if (it != Int.MIN_VALUE) h.txPower = it }
        h.rssi = res.rssi
        /* Media más viva que antes (0,25 -> 0,4): con 0,25 la barra tardaba
           varios anuncios en reflejar un paso, y quien busca mueve el móvil. La
           lectura cruda salta ±6 dB por reflexiones, así que suavizar hace
           falta; el equilibrio es este. */
        h.suave = if (h.suave <= -127.0) res.rssi.toDouble() else h.suave + (res.rssi - h.suave) * 0.4
        if (res.rssi > h.mejor) h.mejor = res.rssi
        h.visto = ahora
        if (datos.size >= 4) {
            h.estado = datos[1].toInt()
            h.salto = datos[2].toInt()
            h.sangre = datos[3].toInt()
            /* Del quinto byte en adelante va el nombre de pila en UTF-8, si lo
               hay. Puede no venir: el anuncio de una versión anterior traía la
               edad ahí, y un móvil sin ficha no manda nada. Se descarta cualquier
               cosa que no sea texto imprimible para que un byte de otra versión no
               salga en pantalla como un jeroglífico. */
            /* ¿Es una trama de continuación? El 0x01 lo marca, y no puede
               confundirse con un nombre: UTF-8 no empieza ningún carácter con un
               byte de control. */
            if (datos.size > 5 && datos[4] == Baliza.TRAMA_MARCA) {
                val cab = datos[5].toInt() and 0xFF
                val k = (cab shr 4) and 0x0F
                val total = cab and 0x0F
                if (k in 1..total && total <= Baliza.MAX_TRAMAS) {
                    h.totalTramas = total
                    h.trozos[k] = datos.copyOfRange(6, datos.size)
                }
                return
            }
            h.nombre = if (datos.size > 4) {
                val s = String(datos, 4, datos.size - 4, Charsets.UTF_8).trim()
                if (s.length in 1..Baliza.MAX_NOMBRE && s.all { it.isLetter() || it == ' ' || it == '\'' || it == '-' }) s
                else ""
            } else ""
        }
        /* Se guarda una foto cada pocos segundos y la tendencia compara contra
           ella. Comparar contra la lectura anterior no diría nada: entre dos
           anuncios seguidos no ha dado tiempo a dar un paso. */
        if (ahora - h.marcaHistoria > HISTORIA_MS) {
            h.antes = h.suave
            h.marcaHistoria = ahora
        }
        alCambiar?.invoke()
    }

    fun arrancar(alCambiar: () -> Unit): Boolean {
        if (rastreando) return true
        if (!hayHardware()) { motivo = "Este móvil no tiene BLE"; return false }
        if (!hayPermiso()) { motivo = "Falta el permiso de bluetooth"; return false }
        val ad = adaptador ?: return false
        if (!ad.isEnabled) { motivo = "El bluetooth está apagado"; return false }
        val e = try { ad.bluetoothLeScanner } catch (ex: SecurityException) { null }
        if (e == null) { motivo = "El sistema no da el escáner"; return false }

        this.alCambiar = alCambiar
        val filtro = ScanFilter.Builder().setServiceUuid(Baliza.SERVICIO).build()
        val ajustes = ScanSettings.Builder()
            // buscar es una tarea de minutos con el móvil en la mano: aquí la
            // batería importa menos que no perderse un anuncio débil
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        return try {
            e.startScan(listOf(filtro), ajustes, callback)
            rastreando = true
            motivo = "Buscando"
            Log.i(TAG, "rastreador BLE activo")
            true
        } catch (ex: Exception) {
            motivo = "No se pudo arrancar: ${ex.javaClass.simpleName}"
            Log.e(TAG, "rastreador", ex); false
        }
    }

    fun parar() {
        try { adaptador?.bluetoothLeScanner?.stopScan(callback) } catch (_: Exception) {}
        rastreando = false
        motivo = "Parado"
        alCambiar = null
    }

    fun olvidar() = vistos.clear()

    /** Lo que se ha oído hace poco, lo más fuerte primero. */
    fun hallazgos(): List<Hallazgo> {
        val ahora = System.currentTimeMillis()
        vistos.entries.removeAll { ahora - it.value.visto > CADUCA_MS }
        return vistos.values.sortedByDescending { it.suave }
    }
}
