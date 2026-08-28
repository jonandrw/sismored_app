package red.sismo

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.widget.ProgressBar
import android.os.VibrationEffect
import android.view.View
import android.view.animation.AnimationUtils
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

private const val PIDE_NOTIF = 1
private const val PIDE_MICRO = 2
private const val PIDE_RADIO = 3
private const val PIDE_PASOS = 4
private const val PIDE_UBI = 5

/** Cuánto hay que mantener pulsado PÁNICO. Ver [MainActivity.montarPanico]. */
private const val PANICO_MANTENER_MS = 2000L

class MainActivity : AppCompatActivity() {

    private lateinit var op: Opciones

    /* Las cinco pestañas y las tres vistas de segundo nivel. Se muestran y se
       ocultan vistas en vez de usar fragmentos: son ocho secciones estáticas y
       un fragmento aquí solo añadiría ciclo de vida que vigilar.

       Búsqueda subió a pestaña: es el lado de quien busca, o sea media app, y
       estaba escondida detrás de un botón en mitad de Inicio. */
    private val pestanas by lazy {
        listOf(
            R.id.t_inicio to R.id.v_inicio,
            R.id.t_red to R.id.v_red,
            R.id.t_entorno to R.id.v_entorno,
            R.id.t_busqueda to R.id.v_busqueda,
            R.id.t_ficha to R.id.v_ficha
        )
    }
    private val subtitulos = mapOf(
        R.id.v_respuesta to R.string.v_respuesta,
        R.id.v_diag to R.string.v_diagnostico,
        R.id.v_registro to R.string.v_registro,
        R.id.v_acerca to R.string.v_acerca
    )
    private val todasLasVistas by lazy {
        pestanas.map { it.second } + subtitulos.keys
    }
    private var vista = R.id.v_inicio

    /** Refresco de lo que cambia solo. */
    private val reloj = Handler(Looper.getMainLooper())
    private val tic = object : Runnable {
        override fun run() { pintar(); reloj.postDelayed(this, 500) }
    }

    /** Últimas líneas de la malla. Es lo único que hace visible la prueba entre
     *  dos móviles: sin esto no se distingue «no llegó» de «llegó y se descartó». */
    private val lineas = ArrayDeque<String>()

    private val receptor = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            i?.getStringExtra("texto")?.let { anotar(it) }
            pintar()
        }
    }

    /* El destello de pantalla lo pide el servicio, que no puede pintar. */
    private val receptorDestello = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.getStringExtra("modo")) {
                "sos" -> destelloSos(true)
                "uno" -> destelloUno()
                else -> destelloSos(false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        op = Opciones(this)

        montarCabeceras()
        montarInicio()
        montarRed()
        montarEntorno()
        montarFicha()
        montarRespuesta()
        montarBusqueda()
        montarDiagnostico()

        for ((tab, _) in pestanas) findViewById<View>(tab).setOnClickListener { ir(tab) }
        ir(R.id.t_inicio)


        // La campana lleva al registro; el engranaje, al diagnóstico.
        findViewById<View>(R.id.go_log).setOnClickListener { ir(R.id.v_registro) }
        findViewById<View>(R.id.go_diag).setOnClickListener { ir(R.id.v_diag) }
        findViewById<View>(R.id.go_back).setOnClickListener { ir(R.id.t_inicio) }

        /* La primera vez manda la pantalla de bienvenida: encadenar diálogos de
           permiso deja fuera a unas versiones de Android u otras — en Android 11
           solo llegaba a saltar el del micrófono. Enseñarlos todos a la vez, con
           su estado y para qué sirve cada uno, es lo único que funciona igual en
           todas y además deja al usuario decidir en su orden. */
        if (prefs().getBoolean("bienvenida_hecha", false)) {
            pedirPermisos()
            reactivarSiEstabaApagada()
            avisarSiLoMataron()
        } else {
            enBienvenida = true
            montarBienvenida()
            findViewById<View>(R.id.v_onboard).visibility = View.VISIBLE
        }
        arrancarServicio(null)   // deja la vigilancia corriendo desde el principio


    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this, receptor, IntentFilter(ServicioSos.ACCION_REGISTRO),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, receptorDestello, IntentFilter(ServicioSos.ACCION_DESTELLO),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ServicioSos.mirando = vista == R.id.v_inicio || vista == R.id.v_entorno
        pintarBienvenida()
        reloj.post(tic)
    }

    override fun onPause() {
        super.onPause()
        // el refresco solo corre con la pantalla delante: en segundo plano
        // gastaría batería sin que nadie lo mire
        reloj.removeCallbacks(tic)
        ServicioSos.mirando = false
        rastreador.parar()
        ServicioSos.buscando = false
        destelloSos(false)
        try { unregisterReceiver(receptor) } catch (_: IllegalArgumentException) {}
        try { unregisterReceiver(receptorDestello) } catch (_: IllegalArgumentException) {}
    }

    /** Desde una vista de segundo nivel, atrás vuelve a Inicio en vez de cerrar
     *  la app: cerrarla desde Respuesta con la alarma sonando sería lo último
     *  que quiere nadie. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (vista in subtitulos) ir(R.id.t_inicio) else @Suppress("DEPRECATION") super.onBackPressed()
    }

    /* ===================== navegación ===================== */

    /** Acepta el id de una pestaña o el de una vista de segundo nivel. */
    private fun ir(cual: Int) {
        val destino = pestanas.firstOrNull { it.first == cual }?.second ?: cual
        vista = destino
        for (v in todasLasVistas) {
            findViewById<View>(v).visibility = if (v == destino) View.VISIBLE else View.GONE
        }
        val sub = destino in subtitulos
        findViewById<View>(R.id.barra).visibility = if (sub) View.GONE else View.VISIBLE
        findViewById<View>(R.id.subbarra).visibility = if (sub) View.VISIBLE else View.GONE
        if (sub) findViewById<TextView>(R.id.subtitulo).setText(subtitulos.getValue(destino))

        for ((tab, v) in pestanas) {
            val activa = v == destino
            val chip = findViewById<View>(tab)
            chip.isSelected = activa
            // rojo en la activa, como la PWA; el resto en gris
            val c = getColor(if (activa) R.color.rd else R.color.dim)
            (chip as? ViewGroup)?.let { g ->
                for (i in 0 until g.childCount) {
                    val hijo = g.getChildAt(i)
                    if (hijo is TextView) hijo.setTextColor(c) else if (hijo is ImageView) hijo.setColorFilter(c)
                }
            }
        }
        findViewById<ScrollView>(R.id.scroll).scrollTo(0, 0)
        // Solo Inicio y Entorno tienen lienzo que se mueva. En las demás, el
        // servicio no tiene por qué estar calculando ondas a 16 Hz.
        ServicioSos.mirando = destino == R.id.v_inicio || destino == R.id.v_entorno
        if (destino == R.id.v_diag) pintarDiagnostico(forzar = true)
        pintar()
    }

    /* ===================== antes de empezar ===================== */

    /** Un permiso tal y como lo ve el usuario: qué es, PARA QUÉ sirve, si está
     *  concedido y cómo se concede. El «para qué» no es adorno — «micrófono»
     *  asusta, «para oír avisos de otros móviles» se entiende. */
    private class Permiso(
        val icono: Int, val nombre: Int, val para: Int,
        val concedido: () -> Boolean, val pedir: () -> Unit
    )

    private var enBienvenida = false

    private fun permisosNecesarios(): List<Permiso> {
        val l = ArrayList<Permiso>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) l.add(Permiso(
            R.drawable.ic_registro, R.string.ob_notif, R.string.ob_notif_para,
            { checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED },
            { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PIDE_NOTIF) }
        ))
        l.add(Permiso(R.drawable.ic_voz, R.string.ob_micro, R.string.ob_micro_para,
            { hayMicro() },
            { if (prefs().getBoolean("micro_denegado_firme", false)) abrirAjustesDeLaApp() else pedirMicrofono() }))
        l.add(Permiso(R.drawable.ic_baliza, R.string.ob_radio, R.string.ob_radio_para,
            { faltanPermisosDeRadio().isEmpty() }, { explicarRadio() }))
        l.add(Permiso(R.drawable.ic_bateria, R.string.ob_bateria, R.string.ob_bateria_para,
            { (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName) },
            { pedirExencionBateria() }))
        l.add(Permiso(R.drawable.ic_volumen, R.string.ob_teclas, R.string.ob_teclas_para,
            { teclasActivas(this) },
            { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }))
        /* La última posición conocida. El texto dice lo que hace y lo que NO:
           la app no enciende el GPS, solo mira lo que el móvil ya sabía. Si
           alguien lo deja sin conceder, todo lo demás sigue funcionando. */
        l.add(Permiso(R.drawable.ic_baliza, R.string.ob_ubi, R.string.ob_ubi_para,
            { Ubicacion(this).hayPermiso() },
            { requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), PIDE_UBI) }))
        /* El contador de pasos. Va el último a propósito: es el único de los seis
           sin el que la app sigue haciendo su trabajo — si falta, la cascada
           trata «no sé si anda» como «no ha andado», que es el lado seguro. */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) l.add(Permiso(
            R.drawable.ic_nodo, R.string.ob_pasos, R.string.ob_pasos_para,
            { checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED },
            { requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), PIDE_PASOS) }
        ))
        return l
    }

    private fun montarBienvenida() {
        val lista = findViewById<LinearLayout>(R.id.ob_lista)
        lista.removeAllViews()
        val d = resources.displayMetrics.density
        for ((i, per) in permisosNecesarios().withIndex()) {
            val f = LayoutInflater.from(this).inflate(R.layout.permiso, lista, false)
            (f.layoutParams as LinearLayout.LayoutParams).topMargin = if (i == 0) 0 else (10 * d).toInt()
            f.findViewById<ImageView>(R.id.pm_icono).setImageResource(per.icono)
            f.findViewById<TextView>(R.id.pm_nombre).setText(per.nombre)
            f.findViewById<TextView>(R.id.pm_para).setText(per.para)
            f.tag = per
            f.setOnClickListener { if (!per.concedido()) per.pedir() }
            lista.addView(f)
        }
        findViewById<Button>(R.id.ob_listo).let {
            it.setText(if (Ficha(this).vacia()) R.string.ob_listo_ficha else R.string.ob_listo)
            it.setOnClickListener { cerrarBienvenida() }
        }
        findViewById<Button>(R.id.ob_saltar).setOnClickListener { cerrarBienvenida() }
        pintarBienvenida()
    }

    /**
     * Volver a abrir la app es querer que vigile otra vez.
     *
     * APAGAR DEL TODO para el servicio y lo recuerda, para no encenderse sola por
     * la espalda. Pero cuando la persona vuelve a abrirla, el sentido cambia: ya
     * no es una app que se resucita sola, es alguien que la está abriendo. Así
     * que se reactiva todo — y se le recuerda lo único que la app no puede
     * reactivar por su cuenta, que es el atajo de volumen: activar un servicio de
     * accesibilidad lo tiene que hacer la persona en Ajustes, no hay API.
     */
    private fun reactivarSiEstabaApagada() {
        if (!op.apagada) return
        op.apagada = false
        arrancarServicio(ServicioSos.ACCION_MALLA)
        anotar("SismoRed vuelve a vigilar.")
        if (!teclasActivas(this)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.reactivar_titulo)
                .setMessage(R.string.reactivar_texto)
                .setNegativeButton(R.string.matada_luego, null)
                .setPositiveButton(R.string.reactivar_ir) { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .show()
        }
    }

    /** La misma lista de la bienvenida, reabierta para revisarla cuando quieras.
     *  Es la única pantalla que dice qué le falta a la app y por qué le hace
     *  falta, así que no puede verse una sola vez y no volver nunca. */
    private fun abrirListaPermisos() {
        enBienvenida = true
        montarBienvenida()
        findViewById<View>(R.id.v_onboard).visibility = View.VISIBLE
    }

    /** Se repinta en cada vuelta a la app: muchos de estos se conceden en
     *  Ajustes, fuera de aquí, y al volver hay que ver el cambio. */
    private fun pintarBienvenida() {
        if (!enBienvenida) return
        val lista = findViewById<LinearLayout>(R.id.ob_lista)
        for (i in 0 until lista.childCount) {
            val f = lista.getChildAt(i)
            val per = f.tag as? Permiso ?: continue
            val ok = per.concedido()
            f.findViewById<TextView>(R.id.pm_estado).let {
                it.setText(if (ok) R.string.ob_hecho else R.string.ob_conceder)
                it.setTextColor(getColor(if (ok) R.color.gr else R.color.rd))
            }
            f.findViewById<ImageView>(R.id.pm_icono)
                .setColorFilter(getColor(if (ok) R.color.gr else R.color.ctl))
        }
    }

    private fun cerrarBienvenida() {
        prefs().edit().putBoolean("bienvenida_hecha", true).apply()
        enBienvenida = false
        findViewById<View>(R.id.v_onboard).visibility = View.GONE
        // el servicio puede necesitar volver a declararse ahora que hay micrófono
        if (hayMicro()) arrancarServicio(ServicioSos.ACCION_MALLA)
        /* Y lo primero después de los permisos es la ficha. Es el único dato que
           la app no puede conseguir sola y el único que sirve cuando ya no
           puedes hablar: quien te encuentre inconsciente necesita saber tu grupo
           y qué no puede darte. Rellenarla luego significa no rellenarla. */
        if (Ficha(this).vacia()) {
            ir(R.id.t_ficha)
            anotar("Rellena tu ficha médica: es lo único que la app no puede saber sola.")
        }
    }

    /* ===================== ladrillos reutilizables ===================== */

    /* `findViewById` recorre el árbol cada vez, y aquí se llama varias decenas
       de veces por tick — algunas anidadas. Con la pantalla entera colgando de
       un solo ScrollView eso se nota. Se resuelve una vez y se guarda. */
    private val cache = HashMap<Int, View>()
    private fun vista(id: Int): View = cache.getOrPut(id) { findViewById(id) }

    /** El valor de una fila `kv`, ya resuelto. Son quince por tick en Entorno y
     *  cada uno colgaba de otra búsqueda anidada. */
    private val cacheValor = HashMap<Int, TextView>()

    /** Crearlo cuesta bastante más de lo que parece: parsea el patrón y consulta
     *  los símbolos del idioma. No puede estar dentro del bucle de pintado. */
    private val hora = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())

    /** Devuelve true solo si el valor cambió desde la última vez. Sirve para no
     *  repintar fondos y tintes que llevan medio minuto siendo los mismos. */
    private val ultimo = HashMap<Int, Any?>()
    private fun cambio(id: Int, valor: Any?): Boolean {
        if (ultimo[id] == valor) return false
        ultimo[id] = valor
        return true
    }

    private fun cabecera(id: Int, icono: Int, titulo: Int) {
        val c = findViewById<View>(id)
        c.findViewById<ImageView>(R.id.ch_icono).setImageResource(icono)
        c.findViewById<TextView>(R.id.ch_titulo).setText(titulo)
    }

    /** Etiqueta de estado de una cabecera. `texto` null la esconde. */
    private fun pill(id: Int, texto: String?, fondo: Int, color: Int) {
        val p = vista(id).findViewById<TextView>(R.id.ch_pill)
        if (texto == null) { p.visibility = View.GONE; return }
        p.visibility = View.VISIBLE
        // el texto sí cambia cada segundo (el cronómetro); el estilo, casi nunca
        p.text = texto
        if (!cambio(id, fondo)) return
        p.setBackgroundResource(fondo)
        p.setTextColor(getColor(color))
    }

    private fun kv(id: Int, clave: Int) {
        vista(id).findViewById<TextView>(R.id.kv_clave).setText(clave)
    }

    private fun kvValor(id: Int, valor: String, color: Int = R.color.tx) {
        val v = cacheValor.getOrPut(id) { vista(id).findViewById(R.id.kv_valor) }
        v.text = valor
        if (cambio(-id, color)) v.setTextColor(getColor(color))
    }

    private fun ghost(id: Int, icono: Int, texto: Int, alPulsar: () -> Unit) {
        val b = findViewById<View>(id)
        b.findViewById<ImageView>(R.id.g_icono).setImageResource(icono)
        b.findViewById<TextView>(R.id.g_texto).setText(texto)
        b.setOnClickListener { alPulsar() }
    }

    /**
     * La segunda línea de un `ghost`: qué clase de botón es.
     *
     * Tres de estos encienden micrófono o radio —la malla, los detectores del
     * entorno y el rastreo de búsqueda— y los tres se veían exactamente iguales.
     * Con la misma pinta, nada distinguía el que hay que dejar encendido para
     * siempre del que gasta batería a propósito y se apaga al terminar. La
     * diferencia importa: si alguien apaga la vigilancia creyendo que apaga una
     * herramienta, se queda sin la alerta de los demás móviles.
     */
    private fun ghostSub(id: Int, texto: Int) {
        vista(id).findViewById<TextView>(R.id.g_sub).let {
            it.visibility = View.VISIBLE
            it.setText(texto)
        }
    }

    /** Un `ghost` con estado: encendido se pone verde entero. */
    private fun ghostEstado(id: Int, activo: Boolean) {
        if (!cambio(id, activo)) return
        val b = vista(id)
        b.setBackgroundResource(if (activo) R.drawable.ghost_on else R.drawable.campo_fondo)
        val c = getColor(if (activo) R.color.gr else R.color.dim)
        b.findViewById<TextView>(R.id.g_texto).setTextColor(c)
        b.findViewById<ImageView>(R.id.g_icono)
            .setColorFilter(getColor(if (activo) R.color.gr else R.color.ctl))
    }

    private fun conmutador(id: Int, texto: Int, alPulsar: () -> Unit) {
        val c = findViewById<View>(id)
        c.findViewById<TextView>(R.id.cm_texto).setText(texto)
        c.setOnClickListener { alPulsar() }
    }

    private fun conmutadorEstado(id: Int, activo: Boolean) {
        if (!cambio(id, activo)) return
        val c = vista(id)
        c.findViewById<View>(R.id.cm_pista)
            .setBackgroundResource(if (activo) R.drawable.sw_pista_on else R.drawable.sw_pista)
        val pomo = c.findViewById<View>(R.id.cm_pomo)
        pomo.setBackgroundColor(getColor(if (activo) R.color.gr else R.color.dim))
        val lp = pomo.layoutParams as FrameLayout.LayoutParams
        val d = resources.displayMetrics.density
        lp.marginStart = ((if (activo) 25 else 4) * d).toInt()
        pomo.layoutParams = lp
    }

    /** Interruptor ancho de una herramienta de la sonda. */
    private fun conmutadorAncho(id: Int, texto: Int, alPulsar: () -> Unit) {
        val c = findViewById<View>(id)
        c.findViewById<TextView>(R.id.cm_texto).setText(texto)
        c.setOnClickListener { alPulsar() }
    }

    /** [textoOn] deja cambiar lo que dice la linea de estado al estar encendido:
     *  el rastreo de busqueda avisa ahi de que gasta bateria, que es el unico
     *  momento en que ese aviso sirve para algo. */
    private fun conmutadorAnchoEstado(id: Int, activo: Boolean, textoOn: Int = R.string.cm_on) {
        if (!cambio(id, "$activo/$textoOn")) return
        val c = vista(id)
        c.findViewById<View>(R.id.cm_pista)
            .setBackgroundResource(if (activo) R.drawable.sw_pista_on else R.drawable.sw_pista)
        val col = getColor(if (activo) R.color.gr else R.color.dim)
        val pomo = c.findViewById<View>(R.id.cm_pomo)
        pomo.setBackgroundColor(col)
        val lp = pomo.layoutParams as FrameLayout.LayoutParams
        val d = resources.displayMetrics.density
        lp.marginStart = ((if (activo) 26 else 4) * d).toInt()
        pomo.layoutParams = lp
        c.findViewById<TextView>(R.id.cm_estado).let {
            it.setText(if (activo) textoOn else R.string.cm_off)
            it.setTextColor(col)
        }
    }

    /** Una salida de consola. Solo se toca si el texto cambio: son cuatro cajas y
     *  reasignar el texto de un TextView remide y repinta su rama del arbol. */
    private fun consola(id: Int, texto: String) {
        if (!cambio(-id * 31, texto)) return
        (vista(id) as VistaConsola).escribir(texto)
    }

    /**
     * Una fila del centro de opciones.
     *
     * Solo hay dos aspectos, y es a propósito: todas iguales menos la que apaga
     * la vigilancia entera. Si destacan dos, ninguna destaca — y la única que de
     * verdad tiene que separarse del resto es la que deja el móvil sin hacer
     * nada. Comprobar que funciona es una acción normal, no una decisión.
     */
    private fun opcion(id: Int, icono: Int, titulo: Int, desc: Int, apaga: Boolean = false,
                       alPulsar: () -> Unit) {
        val f = findViewById<View>(id)
        f.findViewById<ImageView>(R.id.of_icono).setImageResource(icono)
        f.findViewById<TextView>(R.id.of_titulo).setText(titulo)
        f.findViewById<TextView>(R.id.of_desc).setText(desc)
        f.setOnClickListener { alPulsar() }

        f.setBackgroundResource(if (apaga) R.drawable.fila_op_rd else R.drawable.fila_op)
        f.findViewById<View>(R.id.of_azulejo).setBackgroundResource(
            if (apaga) R.drawable.azulejo_rd_claro else R.drawable.azulejo)
        f.findViewById<ImageView>(R.id.of_icono).setColorFilter(getColor(R.color.tx))
        f.findViewById<TextView>(R.id.of_titulo).setTextColor(getColor(R.color.tx))
        f.findViewById<TextView>(R.id.of_desc).setTextColor(
            getColor(if (apaga) R.color.tx else R.color.dim))
        f.findViewById<ImageView>(R.id.of_flecha).setColorFilter(
            getColor(if (apaga) R.color.tx else R.color.ctl))
    }

    /** ¿Nos ha dado Android el acceso a notificaciones? Se lee del ajuste del
     *  sistema, que es la única fuente de verdad: el usuario puede quitarlo
     *  desde Ajustes sin que la app se entere. */
    private fun alertaGoogleActiva(): Boolean = try {
        android.provider.Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        )?.contains(packageName) == true
    } catch (_: Exception) { false }

    private fun casilla(id: Int, icono: Int, nombre: Int, que: Int, alPulsar: () -> Unit) {
        val c = findViewById<View>(id)
        c.findViewById<ImageView>(R.id.cs_icono).setImageResource(icono)
        c.findViewById<TextView>(R.id.cs_nombre).setText(nombre)
        c.findViewById<TextView>(R.id.cs_que).setText(que)
        c.setOnClickListener { alPulsar() }
    }

    /** Las que van en rojo al encenderse: no son un interruptor más, cambian
     *  cuánto dura el móvil. El color aquí informa, no decora. */
    private val casillasCaras = setOf(R.id.cs_rescate, R.id.cs_mantener)

    private fun casillaEstado(id: Int, activo: Boolean) {
        if (!cambio(id, activo)) return
        val c = vista(id)
        val cara = id in casillasCaras
        c.setBackgroundResource(
            if (!activo) R.drawable.casilla
            else if (cara) R.drawable.casilla_on_rd else R.drawable.casilla_on
        )
        val col = getColor(if (!activo) R.color.ctl else if (cara) R.color.rd else R.color.gr)
        c.findViewById<ImageView>(R.id.cs_icono).setColorFilter(col)
        c.findViewById<TextView>(R.id.cs_estado).let {
            it.setText(if (activo) R.string.on else R.string.off)
            it.setTextColor(col)
        }
    }

    /**
     * Paso numérico. `leer` y `poner` van contra las opciones de disco, no
     * contra una variable de aquí: el servicio tiene que ver el cambio aunque
     * la pantalla se destruya justo después.
     */
    private fun paso(
        id: Int, min: Double, max: Double, salto: Double,
        leer: () -> Double, poner: (Double) -> Unit, formato: (Double) -> String
    ) {
        val s = findViewById<View>(id)
        val v = s.findViewById<TextView>(R.id.st_valor)
        fun mover(d: Double) {
            // redondeo a un decimal: sin esto, restar 0,1 doce veces deja 2,8999999
            val nuevo = (Math.round((leer() + d) * 10.0) / 10.0).coerceIn(min, max)
            poner(nuevo)
            v.text = formato(nuevo)
            arrancarServicio(ServicioSos.ACCION_OPCIONES)
        }
        s.findViewById<View>(R.id.st_menos).setOnClickListener { mover(-salto) }
        s.findViewById<View>(R.id.st_mas).setOnClickListener { mover(+salto) }
        v.text = formato(leer())
    }

    private fun detector(id: Int, icono: Int, rotulo: Int) {
        val d = findViewById<View>(id)
        d.findViewById<ImageView>(R.id.det_icono).setImageResource(icono)
        d.findViewById<TextView>(R.id.det_rotulo).setText(rotulo)
    }

    private fun detectorEstado(id: Int, pct: Int, caliente: Boolean) {
        val d = vista(id)
        d.findViewById<TextView>(R.id.det_pct).text = "$pct%"
        d.findViewById<VistaBarra>(R.id.det_barra).pintar(pct / 100f, caliente)
        /* Cambiar el fondo y los tintes cuesta bastante más que cambiar un texto,
           y solo cambian cuando el detector pasa de frío a caliente. Repetirlo
           dos veces por segundo era gasto puro. */
        if (!cambio(id, caliente)) return
        d.setBackgroundResource(if (caliente) R.drawable.det_fondo_hot else R.drawable.det_fondo)
        d.findViewById<TextView>(R.id.det_pct)
            .setTextColor(getColor(if (caliente) R.color.rd else R.color.tx))
        d.findViewById<ImageView>(R.id.det_icono)
            .setColorFilter(getColor(if (caliente) R.color.rd else R.color.ctl))
    }

    /* ===================== montaje ===================== */

    /**
     * Un paso de instrucciones: icono, número, TÍTULO y cuerpo.
     *
     * El título va en negrita y el cuerpo en gris. La jerarquía está en el peso y
     * el color, no en escribir en mayúsculas ni en separar con puntos medios: eso
     * no se lee como un título, se lee como una frase larga y a gritos.
     */
    private fun paso(id: Int, n: Int, icono: Int, titulo: Int, texto: Int) {
        val v = vista(id)
        v.findViewById<ImageView>(R.id.ps_icono).setImageResource(icono)
        // n = 0 para los que van solos: un «1.» sin un «2.» detrás desconcierta
        v.findViewById<TextView>(R.id.ps_num).text = if (n > 0) "$n." else ""
        v.findViewById<TextView>(R.id.ps_titulo).setText(titulo)
        v.findViewById<TextView>(R.id.ps_texto).setText(texto)
    }

    private fun montarCabeceras() {
        cabecera(R.id.ch_propagacion, R.drawable.ic_red, R.string.rot_propagacion)
        cabecera(R.id.ch_sismico, R.drawable.ic_nodo, R.string.rot_sismico)
        cabecera(R.id.ch_como, R.drawable.ic_onda, R.string.rot_como)
        cabecera(R.id.ch_malla_estado, R.drawable.ic_red, R.string.rot_estado_malla)
        cabecera(R.id.ch_info_red, R.drawable.ic_nodo, R.string.rot_info_red)
        cabecera(R.id.ch_internet, R.drawable.ic_nodo, R.string.rot_internet)
        cabecera(R.id.ch_deteccion, R.drawable.ic_entorno, R.string.rot_deteccion)
        cabecera(R.id.ch_sonda, R.drawable.ic_sonar, R.string.rot_sonda)
        cabecera(R.id.ch_personal, R.drawable.ic_ficha, R.string.rot_personal)
        cabecera(R.id.ch_busqueda, R.drawable.ic_baliza, R.string.rot_busqueda)
        cabecera(R.id.ch_registro, R.drawable.ic_registro, R.string.rot_registro)
        cabecera(R.id.ch_como_malla, R.drawable.ic_onda, R.string.rot_como_malla)
        cabecera(R.id.ch_como_ficha, R.drawable.ic_candado, R.string.rot_como_ficha)
        cabecera(R.id.ch_como_resp, R.drawable.ic_casco, R.string.rot_como_resp)
    }

    /**
     * PÁNICO: mantener pulsado dos segundos.
     *
     * **No es una florituras de la maqueta, es seguridad.** Este control
     * despierta a un barrio entero —sirena, linterna, baliza de radio y malla— y
     * hasta ahora estaba a un toque de distancia, en la primera pantalla, con el
     * móvil en el bolsillo. Dos segundos no le cuestan nada a quien de verdad lo
     * necesita y quitan de en medio el roce accidental.
     *
     * Tres cosas hacen que se entienda sin leer nada:
     *
     *  - La barra se llena mientras se mantiene. «Mantén pulsado» sin barra es
     *    una instrucción que no dice cuánto falta, y quien no ve avance suelta.
     *  - El fondo sube de rojo mientras carga, así que la mano nota que está
     *    pasando algo aunque no mire la barra.
     *  - Al soltar antes de tiempo se vuelve atrás **de golpe**, no con una
     *    animación bonita: soltar es cancelar, y tiene que verse como tal.
     *
     * Y si ya hay una alarma en marcha, un toque basta para nada: el botón que
     * importa entonces es DETENER, que está justo debajo.
     */
    private fun montarPanico() {
        val bloque = findViewById<View>(R.id.panico)
        val carga = findViewById<ProgressBar>(R.id.panico_carga)
        val sub = findViewById<TextView>(R.id.panico_sub)
        var tarea: Runnable? = null
        var desde = 0L

        fun soltar(cancelado: Boolean) {
            tarea?.let { bloque.removeCallbacks(it) }
            tarea = null
            desde = 0L
            carga.progress = 0
            carga.visibility = View.INVISIBLE
            bloque.setBackgroundResource(R.drawable.panico_fondo)
            if (cancelado) sub.setText(R.string.panico_mantener)
        }

        bloque.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    desde = System.currentTimeMillis()
                    carga.visibility = View.VISIBLE
                    bloque.setBackgroundResource(R.drawable.panico_armando)
                    sub.setText(R.string.panico_soltando)
                    val t = object : Runnable {
                        override fun run() {
                            val ido = System.currentTimeMillis() - desde
                            carga.progress = ((ido * 100) / PANICO_MANTENER_MS).toInt().coerceIn(0, 100)
                            if (ido >= PANICO_MANTENER_MS) {
                                soltar(false)
                                /* Un golpe corto al llegar: quien lo tiene en la
                                   mano sabe que ha entrado sin mirar la pantalla. */
                                try {
                                    val vib = getSystemService(Vibrator::class.java)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                        vib?.vibrate(VibrationEffect.createOneShot(60, 255))
                                    else @Suppress("DEPRECATION") vib?.vibrate(60)
                                } catch (_: Exception) {}
                                arrancarServicio(ServicioSos.ACCION_PANICO)
                                pintar()
                            } else {
                                bloque.postDelayed(this, 40)
                            }
                        }
                    }
                    tarea = t
                    bloque.post(t)
                    v.isPressed = true
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    soltar(true)
                    v.isPressed = false
                    true
                }
                else -> false
            }
        }
    }

    private fun montarInicio() {
        montarPanico()
        findViewById<Button>(R.id.parar).setOnClickListener {
            arrancarServicio(ServicioSos.ACCION_PARAR); pintar()
        }

        // ---- MALLA DE PROPAGACIÓN ----
        val s3 = findViewById<View>(R.id.s3_malla)
        s3.findViewById<TextView>(R.id.s3_r1).setText(R.string.k_salto)
        s3.findViewById<TextView>(R.id.s3_r2).setText(R.string.k_balizas)
        s3.findViewById<TextView>(R.id.s3_r3).setText(R.string.k_retx)
        kv(R.id.kv_malla_estado, R.string.k_estado_malla)

        /* ESCUCHAR MALLA no es un cartel: es la vía para arreglarlo, incluso si
           el permiso ya se denegó del todo y el sistema ya no vuelve a preguntar. */
        ghost(R.id.g_malla, R.drawable.ic_entorno, R.string.b_escuchar_malla) {
            when {
                hayMicro() -> arrancarServicio(ServicioSos.ACCION_MALLA_CONMUTAR)
                prefs().getBoolean("micro_denegado_firme", false) -> abrirAjustesDeLaApp()
                else -> pedirMicrofono()
            }
            pintar()
        }
        ghostSub(R.id.g_malla, R.string.sub_siempre)
        ghost(R.id.g_senal, R.drawable.ic_onda, R.string.b_comprobar) {
            arrancarServicio(ServicioSos.ACCION_DIAGNOSTICO)
            ir(R.id.v_registro)
        }

        // ---- DETECTOR SÍSMICO ----
        kv(R.id.kv_sacudida, R.string.k_sacudida)
        /* Un solo mando, y edita el umbral del régimen en el que esté el móvil.
           Son dos números —quieto en una mesa y encima de una persona— y no
           tienen nada que ver entre sí, pero poner dos mandos obliga a explicar
           cuál es cuál y a que alguien elija en abstracto. Así se toca el que
           está pasando ahora, que es el único que se puede juzgar: se deja el
           móvil donde vaya a dormir, se mira lo que se mueve esa mesa y se sube o
           se baja hasta que convenza. La línea de estado dice cuál se está
           tocando. */
        paso(
            R.id.paso_umbral, Opciones.UMBRAL_MIN, Opciones.UMBRAL_MAX, Opciones.UMBRAL_PASO,
            { if (ServicioSos.enReposoAhora) op.umbralReposo else op.umbral },
            { if (ServicioSos.enReposoAhora) op.umbralReposo = it else op.umbral = it },
            { "%.1f".format(it) }
        )
        conmutador(R.id.cm_auto, R.string.sw_auto) {
            arrancarServicio(ServicioSos.ACCION_ARMAR); pintar()
        }
        conmutador(R.id.cm_sirena, R.string.sw_sirena) {
            op.sirena = !op.sirena
            arrancarServicio(ServicioSos.ACCION_OPCIONES); pintar()
        }

        paso(R.id.paso1, 1, R.drawable.ic_ficha, R.string.paso1_t, R.string.paso1)
        paso(R.id.paso2, 2, R.drawable.ic_red, R.string.paso2_t, R.string.paso2)
        paso(R.id.paso3, 3, R.drawable.ic_volumen, R.string.paso3_t, R.string.paso3)
        paso(R.id.paso4, 4, R.drawable.ic_alerta, R.string.paso4_t, R.string.paso4)
        paso(R.id.paso5, 5, R.drawable.ic_casco, R.string.paso5_t, R.string.paso5)

        findViewById<Button>(R.id.ir_respuesta).setOnClickListener { ir(R.id.v_respuesta) }
        findViewById<Button>(R.id.ir_busqueda).setOnClickListener { ir(R.id.t_busqueda) }
        findViewById<Button>(R.id.bateria).setOnClickListener { pedirExencionBateria() }
        findViewById<Button>(R.id.teclas).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun montarRed() {
        kv(R.id.kv_r_salto, R.string.k_salto)
        kv(R.id.kv_r_balizas, R.string.k_balizas)
        kv(R.id.kv_r_retx, R.string.k_retx)
        kv(R.id.kv_r_max, R.string.k_saltos_max)
        kv(R.id.kv_r_portadora, R.string.k_portadora)
        kv(R.id.kv_r_tonos, R.string.k_tonos)
        // Estos tres no cambian nunca: son el protocolo.
        kvValor(R.id.kv_r_max, getString(R.string.v_saltos_max))
        kvValor(R.id.kv_r_portadora, getString(R.string.v_portadora))
        kvValor(R.id.kv_r_tonos, getString(R.string.v_tonos))

        kv(R.id.kv_n_estado, R.string.k_estado)
        kv(R.id.kv_n_tipo, R.string.k_conexion)
        kv(R.id.kv_n_cola, R.string.k_cola)

        paso(R.id.paso_m1, 1, R.drawable.ic_red, R.string.paso_m1_t, R.string.paso_m1)
        paso(R.id.paso_m2, 2, R.drawable.ic_nodo, R.string.paso_m2_t, R.string.paso_m2)
        paso(R.id.paso_m3, 3, R.drawable.ic_parar, R.string.paso_m3_t, R.string.paso_m3)

        ghost(R.id.g_enviar, R.drawable.ic_nodo, R.string.b_enviar) { conmutarEnvio() }
        ghostSub(R.id.g_enviar, R.string.b_probar_envio)
        paso(R.id.paso_ra1, 1, R.drawable.ic_entorno, R.string.paso_ra1_t, R.string.paso_ra1)
        paso(R.id.paso_ra2, 2, R.drawable.ic_red, R.string.paso_ra2_t, R.string.paso_ra2)
        paso(R.id.paso_i1, 1, R.drawable.ic_nodo, R.string.paso_i1_t, R.string.paso_i1)
        paso(R.id.paso_i2, 2, R.drawable.ic_red, R.string.paso_i2_t, R.string.paso_i2)
        paso(R.id.paso_i3, 3, R.drawable.ic_diag, R.string.paso_i3_t, R.string.paso_i3)
        ghost(R.id.g_purgar, R.drawable.ic_parar, R.string.b_purgar) {
            Partes(this).borrar()
            anotar("cola de partes borrada")
            pintar()
        }
    }

    private fun montarEntorno() {
        detector(R.id.det_derrumbe, R.drawable.ic_derrumbe, R.string.d_derrumbe)
        detector(R.id.det_grito, R.drawable.ic_grito, R.string.d_gritos)
        detector(R.id.det_voz, R.drawable.ic_voz, R.string.d_voz)
        detector(R.id.det_animal, R.drawable.ic_animal, R.string.d_animales)
        detector(R.id.det_golpes, R.drawable.ic_golpes, R.string.d_golpes)

        kv(R.id.kv_e_estado, R.string.k_estado)
        kv(R.id.kv_e_nivel, R.string.k_nivel)
        kv(R.id.kv_e_tono, R.string.k_tono)
        kv(R.id.kv_e_ataques, R.string.k_ataques)
        kv(R.id.kv_e_derrumbe, R.string.k_ult_derrumbe)
        kv(R.id.kv_e_grito, R.string.k_ult_grito)
        kv(R.id.kv_e_voz, R.string.k_ult_voz)
        kv(R.id.kv_e_animal, R.string.k_ult_animal)
        kv(R.id.kv_e_golpes, R.string.k_ult_golpes)

        conmutadorAncho(R.id.cm_escuchar, R.string.cm_escuchar) {
            if (hayMicro()) arrancarServicio(ServicioSos.ACCION_ESCUCHA_CONMUTAR)
            else pedirMicrofono()
            pintar()
        }
        findViewById<Button>(R.id.b_marcar).setOnClickListener {
            arrancarServicio(ServicioSos.ACCION_MARCAR)
        }

        paso(
            R.id.paso_doppler, Opciones.DOPPLER_MIN, Opciones.DOPPLER_MAX, Opciones.DOPPLER_PASO,
            { op.dopplerKhz }, { op.dopplerKhz = it }, { "%.1f".format(it) }
        )

        /* Las instrucciones de las dos tarjetas van con el mismo ladrillo que
           CÓMO USARLA de Inicio, y cada paso lleva el icono de lo que nombra:
           el del derrumbe es el perfil de escombros, el de los golpes son los
           impactos. Se reconoce el dibujo antes de leer la frase. */
        paso(R.id.paso_e1, 1, R.drawable.ic_voz, R.string.paso_e1_t, R.string.paso_e1)
        paso(R.id.paso_e2, 2, R.drawable.ic_derrumbe, R.string.paso_e2_t, R.string.paso_e2)
        paso(R.id.paso_e3, 3, R.drawable.ic_golpes, R.string.paso_e3_t, R.string.paso_e3)
        paso(R.id.paso_e4, 4, R.drawable.ic_onda, R.string.paso_e4_t, R.string.paso_e4)
        paso(R.id.paso_e5, 5, R.drawable.ic_registro, R.string.paso_e5_t, R.string.paso_e5)

        /* Las cuatro herramientas de la sonda son interruptores, y las cuatro van
           por el servicio: el microfono es unico, y si la actividad abriera el suyo
           dejaria sorda a la malla. */
        findViewById<Button>(R.id.b_aprender_movil).setOnClickListener {
            arrancarServicio(ServicioSos.ACCION_APRENDER_MOVIL)
        }
        conmutadorAncho(R.id.cm_eco, R.string.cm_eco) {
            arrancarServicio(ServicioSos.ACCION_SONDA); pintar()
        }
        conmutadorAncho(R.id.cm_movimiento, R.string.cm_movimiento) {
            arrancarServicio(ServicioSos.ACCION_DOPPLER); pintar()
        }
        conmutadorAncho(R.id.cm_respira, R.string.cm_respira) {
            arrancarServicio(ServicioSos.ACCION_RESPIRA); pintar()
        }
        conmutadorAncho(R.id.cm_barrido, R.string.cm_barrido) {
            arrancarServicio(ServicioSos.ACCION_BARRIDO); pintar()
        }

        paso(
            R.id.paso_volsenal, Opciones.VOL_MIN, Opciones.VOL_MAX, Opciones.VOL_PASO,
            { op.volSenal }, { op.volSenal = it }, { "%.0f".format(it) }
        )
    }

    private fun montarRespuesta() {
        paso(R.id.paso_r1, 1, R.drawable.ic_alerta, R.string.paso_r1_t, R.string.paso_r1)
        paso(R.id.paso_r2, 2, R.drawable.ic_casco, R.string.paso_r2_t, R.string.paso_r2)
        casilla(R.id.cs_linterna, R.drawable.ic_linterna, R.string.t_linterna, R.string.q_linterna) {
            op.linterna = !op.linterna; aplicar()
        }
        casilla(R.id.cs_vibracion, R.drawable.ic_vibracion, R.string.t_vibracion, R.string.q_vibracion) {
            op.vibracion = !op.vibracion; aplicar()
        }
        casilla(R.id.cs_pantalla, R.drawable.ic_pantalla, R.string.t_pantalla, R.string.q_pantalla) {
            op.pantalla = !op.pantalla; aplicar()
        }
        casilla(R.id.cs_baliza, R.drawable.ic_baliza, R.string.t_baliza, R.string.q_baliza) {
            op.baliza = !op.baliza; aplicar()
        }
        casilla(R.id.cs_mantener, R.drawable.ic_candado, R.string.t_mantener, R.string.q_mantener) {
            op.mantener = !op.mantener; aplicar()
        }
        /* El rescate no es una preferencia guardada: es una acción que se
           enciende y se apaga ahora mismo, y quien manda es el servicio. */
        casilla(R.id.cs_rescate, R.drawable.ic_casco, R.string.t_rescate, R.string.q_rescate) {
            arrancarServicio(ServicioSos.ACCION_RESCATE); pintar()
        }
    }

    /* ===================== búsqueda ===================== */

    private val rastreador by lazy { Rastreador(this) }
    /** A partir de aquí se considera que lo tienes encima. Lo define el
     *  `Rastreador` junto con los anclajes de la barra: si fueran dos números
     *  distintos, la barra diría una cosa y el aviso haría otra. */
    private val CERCA_PCT = Rastreador.PCT_CERCA
    private var ultimoAviso = 0L
    /** Desde cuando la senal esta al maximo. Ver el silencio automatico. */
    private var encimaDesde = 0L
    private val vibra: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private fun montarBusqueda() {
        (vista(R.id.sonar_busqueda) as VistaSonar).brujula = true
        kv(R.id.kv_bus_estado, R.string.k_estado)
        kv(R.id.kv_bus_hallados, R.string.k_hallados)
        conmutadorAncho(R.id.cm_buscar, R.string.cm_buscar) { conmutarBusqueda() }
        paso(R.id.paso_encima, 0, R.drawable.ic_sonar,
            R.string.paso_encima_t, R.string.paso_encima)
        findViewById<Button>(R.id.b_abajo).setOnClickListener {
            /* Se apaga el rastreo y se baja a las herramientas de sonido: el
               rastreo ya cumplio, y a partir de aqui lo que informa es el oido. */
            if (rastreador.rastreando) {
                rastreador.parar()
                ServicioSos.buscando = false
                anotar("Rastreo apagado: a partir de aqui, hacia abajo con sonido")
            }
            val sc = findViewById<ScrollView>(R.id.scroll)
            val destino = findViewById<View>(R.id.b_llamar)
            sc.post { sc.smoothScrollTo(0, destino.top) }
            pintar()
        }
        conmutadorAncho(R.id.cm_aviso, R.string.cm_aviso) {
            op.avisoBusqueda = !op.avisoBusqueda
            pintar()
        }
        findViewById<Button>(R.id.b_olvidar).setOnClickListener {
            rastreador.olvidar(); pintar()
        }
        findViewById<Button>(R.id.b_silencio_zona).setOnClickListener {
            arrancarServicio(ServicioSos.ACCION_SILENCIO_ZONA)
        }
        findViewById<Button>(R.id.b_rescatado).setOnClickListener {
            arrancarServicio(ServicioSos.ACCION_RESCATE_HECHO)
            anotar("Rescatado: dejo de llamar hacia abajo.")
        }
        findViewById<Button>(R.id.b_llamar).setOnClickListener {
            arrancarServicio(ServicioSos.ACCION_LLAMAR)
        }
        /* El interfono no es una llamada y hay que decirlo donde se pulsa: lo que
           cruza el escombro es el sonido, no la radio. */
        paso(R.id.paso_if1, 1, R.drawable.ic_grito, R.string.paso_if1_t, R.string.paso_if1)
        paso(R.id.paso_if2, 2, R.drawable.ic_voz, R.string.paso_if2_t, R.string.paso_if2)
        paso(R.id.paso_if3, 3, R.drawable.ic_onda, R.string.paso_if3_t, R.string.paso_if3)

        paso(R.id.paso_b1, 1, R.drawable.ic_baliza, R.string.paso_b1_t, R.string.paso_b1)
        paso(R.id.paso_b2, 2, R.drawable.ic_onda, R.string.paso_b2_t, R.string.paso_b2)
        paso(R.id.paso_b3, 3, R.drawable.ic_pantalla, R.string.paso_b3_t, R.string.paso_b3)
        paso(R.id.paso_b4, 4, R.drawable.ic_grito, R.string.paso_b4_t, R.string.paso_b4)
        paso(R.id.paso_b5, 5, R.drawable.ic_ficha, R.string.paso_b5_t, R.string.paso_b5)
        findViewById<Button>(R.id.b_interfono).setOnClickListener {
            arrancarServicio(ServicioSos.ACCION_INTERFONO)
        }
    }

    private fun conmutarBusqueda() {
        if (rastreador.rastreando) {
            rastreador.parar()
            ServicioSos.buscando = false
            pintar(); return
        }
        if (!pedirRadio()) return
        // el repintado lo dispara el propio tic: llegan anuncios a decenas por
        // segundo y repintar en cada uno no dejaría hacer nada más
        rastreador.arrancar { }
        /* Mientras se busca, el servicio no puede disparar la alarma de este
           móvil al oír la malla: el que busca necesita el oído libre. */
        ServicioSos.buscando = true
        pintar()
    }

    private fun pintarBusqueda() {
        val activo = rastreador.rastreando
        conmutadorAnchoEstado(R.id.cm_buscar, activo, R.string.cm_on_bateria)
        kvValor(R.id.kv_bus_estado, rastreador.motivo,
            if (activo) R.color.gr else R.color.dim)

        val lista = findViewById<LinearLayout>(R.id.lista_hallazgos)
        // los que están BUSCANDO no son víctimas: no se listan como hallazgo
        val hs = rastreador.hallazgos().filter { it.estado != Baliza.BUSCANDO }
        /* Las ondas laten con el más fuerte de los oídos: cuanto más cerca,
           más rápido salen y más rojas se ponen. */
        /* Aviso silencioso al acercarse. Sin sirena a propósito: quien busca
           necesita OÍR los escombros, y un pitido en la mano tapa justo lo que
           ha venido a escuchar. Pantalla y vibración, que se notan sin sonar. */
        val cerca = hs.firstOrNull()?.proximidad ?: 0
        /* El aviso se acelera conforme se acerca: al 70 % avisa cada segundo y
           medio, y encima de la persona casi cuatro veces por segundo. Un intervalo
           fijo no dice si vas bien; el ritmo, sí — es lo mismo que hace un detector
           de metales, y funciona sin mirar la pantalla. */
        val hueco = if (cerca >= 100) 280L
                    else 1500L - (cerca - CERCA_PCT).coerceAtLeast(0) * 40L

        /* Silencio automatico al llegar.
           El aviso existe para guiar hacia la senal. Cuando ya estas encima deja de
           informar y se convierte en un movil que destella y vibra sin parar
           mientras cavas: se calla solo tras ocho segundos clavado arriba, y vuelve
           si la senal baja. La histeresis (95 para callar, 85 para volver) evita que
           parpadee entre callado y hablando con el temblor normal de la lectura. */
        if (cerca >= 95) {
            if (encimaDesde == 0L) encimaDesde = System.currentTimeMillis()
        } else if (cerca < 85) encimaDesde = 0L
        val yaEncima = encimaDesde > 0L && System.currentTimeMillis() - encimaDesde > 8000

        conmutadorAnchoEstado(R.id.cm_aviso, op.avisoBusqueda && !yaEncima,
            if (yaEncima) R.string.cm_on_encima else R.string.cm_on)
        vista(R.id.caja_encima).visibility =
            if (yaEncima && rastreador.rastreando) View.VISIBLE else View.GONE

        if (rastreador.rastreando && op.avisoBusqueda && !yaEncima && cerca >= CERCA_PCT &&
            System.currentTimeMillis() - ultimoAviso > hueco) {
            ultimoAviso = System.currentTimeMillis()
            destelloUno()
            // el flash lo tiene la cámara, que la lleva el servicio
            arrancarServicio(ServicioSos.ACCION_PULSO)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibra.vibrate(android.os.VibrationEffect.createOneShot(80, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION") vibra.vibrate(80)
                }
            } catch (_: Exception) {}
        }

        /* El interfono solo tiene sentido con un móvil encima: por debajo de esa
           señal, quien contesta está demasiado lejos para que su voz llegue por el
           escombro, y ofrecerlo sería prometer algo que no va a pasar. Se queda
           visible mientras el ciclo corre, aunque la señal baile en medio. */
        val wifi = ServicioSos.fichasWifi
        vista(R.id.caja_wifi).visibility = if (wifi.isBlank()) View.GONE else View.VISIBLE
        if (wifi.isNotBlank()) consola(R.id.wifi_salida, wifi)

        val puedeHablar = cerca >= CERCA_PCT || ServicioSos.interfonoOcupado
        vista(R.id.caja_interfono).visibility = if (puedeHablar) View.VISIBLE else View.GONE
        if (puedeHablar) {
            (vista(R.id.interfono_salida) as TextView).text = ServicioSos.interfonoSalida
        }

        (vista(R.id.sonar_busqueda) as VistaSonar).apply {
            // el `this` es obligatorio: sin él, `activo` es el val local de arriba
            this.activo = rastreador.rastreando
            intensidad = (hs.firstOrNull()?.proximidad ?: 0) / 100f
        }
        kvValor(R.id.kv_bus_hallados, hs.size.toString(),
            if (hs.isEmpty()) R.color.dim else R.color.rd)

        if (hs.isEmpty()) {
            if (lista.childCount != 1 || lista.getChildAt(0).id != R.id.hz_titulo) {
                lista.removeAllViews()
                lista.addView(TextView(this).apply {
                    id = R.id.hz_titulo
                    setText(R.string.nadie)
                    setTextColor(getColor(R.color.dim))
                    textSize = 12.5f
                })
            }
            return
        }
        if (lista.childCount != hs.size || lista.getChildAt(0).id == R.id.hz_titulo) {
            lista.removeAllViews()
            repeat(hs.size) {
                val f = LayoutInflater.from(this).inflate(R.layout.hallazgo, lista, false)
                (f.layoutParams as LinearLayout.LayoutParams).topMargin =
                    if (it == 0) 0 else (10 * resources.displayMetrics.density).toInt()
                lista.addView(f)
            }
        }
        for (i in hs.indices) {
            val f = lista.getChildAt(i) ?: continue
            val h = hs[i]
            f.findViewById<TextView>(R.id.hz_tendencia).let {
                it.text = when (h.tendencia) { 1 -> "↑"; -1 -> "↓"; else -> "·" }
                it.setTextColor(getColor(if (h.tendencia > 0) R.color.gr else if (h.tendencia < 0) R.color.dim else R.color.ctl))
            }
            f.findViewById<TextView>(R.id.hz_titulo).text = when (h.estado) {
                Baliza.ALARMA -> "PIDIENDO AYUDA"
                Baliza.RESCATE -> "MODO RESCATE"
                else -> "Móvil con SismoRed"
            }
            f.findViewById<TextView>(R.id.hz_sub).text =
                (if (h.salto > 0) "Alerta a ${h.salto} saltos · " else "") +
                when (h.tendencia) {
                    1 -> "Te estás acercando"
                    -1 -> "Te estás alejando"
                    else -> "Camina y mira si sube"
                }
            f.findViewById<TextView>(R.id.hz_dbm).text = "${h.suave.toInt()} dBm"
            f.findViewById<VistaBarra>(R.id.hz_barra)
                .pintar(h.proximidad / 100f, h.estado != Baliza.REPOSO)
            f.setOnClickListener { detalleHallazgo(h) }
            /* Nombre y grupo, en ese orden: el nombre primero porque es lo que se
               usa —se le grita para que conteste— y el grupo detrás porque es lo
               que hace falta al llegar. */
            f.findViewById<TextView>(R.id.hz_ficha).let {
                val partes = ArrayList<String>(2)
                if (h.nombre.isNotBlank()) partes.add("de " + h.nombre)
                if (h.totalTramas > 0 && h.fichaCompleta().isBlank()) partes.add("ficha " + h.tramas())
                if (h.sangre > 0) partes.add("Grupo ${Baliza.SANGRE[h.sangre]}")
                it.visibility = if (partes.isEmpty()) View.GONE else View.VISIBLE
                it.text = partes.joinToString(" · ")
            }
        }
    }

    /**
     * Todo lo que se sabe de un móvil oído, al pulsarlo.
     *
     * Se enseña lo que llega y nada más. El anuncio de radio cabe en 31 bytes, de
     * los que quedan diecisiete para el contenido: versión, estado, saltos, grupo
     * sanguíneo y el nombre de pila en los trece que sobran. La edad se quitó para
     * hacerle sitio al nombre — llamar a alguien por su nombre sirve, saber que
     * tiene 34 años no. Las alergias y el contacto **no viajan**: se leen en la
     * pantalla del propio móvil cuando lo encuentras, y aquí se dice para que
     * nadie se quede esperando un dato que no va a llegar.
     */
    private fun detalleHallazgo(h: Rastreador.Hallazgo) {
        val hora = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        val estado = when (h.estado) {
            Baliza.ALARMA -> "PIDIENDO AYUDA"
            Baliza.RESCATE -> "MODO RESCATE · lleva tiempo sin moverse"
            Baliza.BUSCANDO -> "otro equipo buscando"
            else -> "en reposo"
        }
        /* Se arma como lista de líneas y se une al final. Escribir los saltos
           dentro de cada cadena invita a que un editor los convierta en saltos
           de verdad y rompa el archivo, que es justo lo que pasó aquí. */
        val lineas = ArrayList<String>()
        lineas.add("Estado: " + estado)
        if (h.salto > 0) lineas.add("Alerta recibida a " + h.salto + " móviles de distancia")
        lineas.add("")
        lineas.add("FICHA QUE EMITE")
        lineas.add("Nombre: " + (if (h.nombre.isNotBlank()) h.nombre else "no lo emite"))
        lineas.add("Grupo sanguíneo: " + (if (h.sangre > 0) Baliza.SANGRE[h.sangre] else "no lo ha rellenado"))
        lineas.add("")
        lineas.add("Llámalo por su nombre: saber que alguien de arriba te llama por tu nombre cambia lo que aguanta una persona.")
        lineas.add("")
        /* El resto de la ficha llega en tramas, y se arma sola conforme mejora el
           enlace. «7 de 9» no es un detalle técnico: es la medida honesta de la
           calidad de la señal, y si sube es que te estás acercando. */
        val completa = h.fichaCompleta()
        if (completa.isNotBlank()) {
            lineas.add("RESTO DE LA FICHA")
            lineas.add(completa)
        } else if (h.totalTramas > 0) {
            lineas.add("RESTO DE LA FICHA")
            lineas.add("Llegando por partes: " + h.tramas() + " tramas.")
            lineas.add("Quédate cerca o acércate: lo que falte llega en la siguiente vuelta.")
        } else {
            lineas.add("Este móvil no está mandando el resto de la ficha. Solo la manda con la alarma o el modo rescate activos.")
        }
        lineas.add("")
        lineas.add("SEÑAL")
        lineas.add("Ahora: " + h.suave.toInt() + " dBm")
        lineas.add("Mejor que has captado: " + h.mejor + " dBm")
        if (h.perdida > 0) lineas.add("Pérdida de trayecto: " + h.perdida + " dB")
        lineas.add("Tendencia: " + when (h.tendencia) { 1 -> "te acercas"; -1 -> "te alejas"; else -> "sin cambio" })
        lineas.add("Visto por última vez: " + hora.format(java.util.Date(h.visto)))
        lineas.add("")
        lineas.add("La señal NO es distancia. Camina y mira si sube.")

        AlertDialog.Builder(this)
            .setTitle(if (h.estado == Baliza.RESCATE) "MODO RESCATE" else "Móvil detectado")
            .setMessage(lineas.joinToString(System.lineSeparator()))
            .setPositiveButton("Cerrar", null)
            .show()
    }

    /** El escaneo BLE gasta, y buscar es una tarea de minutos con el móvil en la
     *  mano: no puede quedarse corriendo en el bolsillo. */
    private fun pedirRadio(): Boolean {
        if (faltanPermisosDeRadio().isEmpty()) return true
        explicarRadio()
        return false
    }

    private fun aplicar() {
        arrancarServicio(ServicioSos.ACCION_OPCIONES)
        pintar()
    }

    private fun montarDiagnostico() {
        /* REVISAR PERMISOS abre la LISTA de la app, no los ajustes del sistema.
           Los de Android enseñan permisos sueltos, sin decir para qué los quiere
           esta app, y la mitad de los que hacen falta aquí —accesibilidad,
           batería— ni siquiera salen ahí. */
        opcion(R.id.op_permisos, R.drawable.ic_candado, R.string.b_permisos,
            R.string.d_permisos) { abrirListaPermisos() }
        opcion(R.id.op_acerca, R.drawable.ic_registro, R.string.b_acerca,
            R.string.d_acerca) { ir(R.id.v_acerca) }
        opcion(R.id.op_ficha_lan, R.drawable.ic_red, R.string.b_probar_ficha_lan,
            R.string.d_ficha_lan) {
            arrancarServicio(ServicioSos.ACCION_PROBAR_FICHA_LAN)
            ir(R.id.v_registro)
        }
        opcion(R.id.op_simulacro, R.drawable.ic_alerta, R.string.b_probar_pregunta,
            R.string.d_simulacro) { arrancarServicio(ServicioSos.ACCION_PROBAR_PREGUNTA) }
        /* La alerta de Google. Va aquí y NO en la bienvenida a propósito: es el
           permiso más grande que pide la app —Android da el acceso a
           notificaciones entero, no por aplicación— y la app funciona completa
           sin él. Quien lo active tiene que hacerlo leyendo qué hace, no
           pulsando «siguiente» cinco veces. */
        opcion(R.id.op_alerta_google, R.drawable.ic_alerta, R.string.b_alerta_google,
            R.string.d_alerta_google) {
            AlertDialog.Builder(this)
                .setTitle(R.string.b_alerta_google)
                .setMessage(R.string.d_alerta_google_aviso)
                .setNegativeButton(android.R.string.cancel, null)
                /* Probarla simulada. Hace falta porque **no hay forma de
                   provocar una alerta de Google de verdad**: no se puede pedir
                   un terremoto para ver si el camino funciona. Esto mete la
                   alerta por el mismo sitio por el que entraría la real, así que
                   prueba todo menos la notificación: el reparto por la malla, el
                   armado del sismógrafo y el aviso. */
                .setNeutralButton(R.string.b_alerta_google_probar) { _, _ ->
                    arrancarServicio(ServicioSos.ACCION_ALERTA_EXTERNA)
                    ir(R.id.v_registro)
                }
                .setPositiveButton(R.string.b_alerta_google_activar) { _, _ ->
                    try {
                        startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                    } catch (_: Exception) {
                        anotar("Este móvil no deja abrir esos ajustes desde la app: " +
                               "Ajustes → Notificaciones → Acceso a notificaciones.")
                    }
                }
                .show()
        }
        /* Y la fila dice si el permiso está puesto o no. Sin esto, activarlo y
           no activarlo se ven exactamente igual — que es justo lo que pasó: la
           alerta no llegaba y no había forma de saber que el permiso nunca se
           había concedido. Android lo da desde sus Ajustes y puede quitarlo por
           su cuenta, así que se relee cada vez que se entra. */
        vista(R.id.op_alerta_google).findViewById<TextView>(R.id.of_desc).text =
            if (alertaGoogleActiva()) getString(R.string.d_alerta_google_on)
            else getString(R.string.d_alerta_google_off)
        /* Esa pantalla se abre sola, sobre el bloqueo y con el brillo al máximo,
           en el peor momento de la vida de alguien — y hasta ahora no había forma
           de verla sin que pasara de verdad. Un grupo sanguíneo mal escrito no se
           descubre en un terremoto. */
        opcion(R.id.op_ver_ficha, R.drawable.ic_ficha, R.string.b_ver_ficha,
            R.string.d_ver_ficha) { arrancarServicio(ServicioSos.ACCION_VER_FICHA) }
        /* El simulacro que llega hasta el final. Va con confirmación porque
           enciende la baliza y la malla de verdad: cualquier móvil con SismoRed
           al alcance va a oírlo y va a reaccionar. Y tiene que ser de verdad,
           porque el tramo que nunca se ha visto funcionar fuera del autotest es
           justo ese. */
        opcion(R.id.op_simulacro_total, R.drawable.ic_baliza, R.string.b_simulacro_total,
            R.string.d_simulacro_total) {
            AlertDialog.Builder(this)
                .setTitle(R.string.b_simulacro_total)
                .setMessage(R.string.d_simulacro_total_aviso)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.b_simulacro_empezar) { _, _ ->
                    arrancarServicio(ServicioSos.ACCION_SIMULACRO_TOTAL)
                    ir(R.id.v_registro)
                }
                .show()
        }
        /* Comprueba la app entera sin altavoz, sin micrófono y sin segundo móvil:
           se le inyectan señales conocidas a cada pieza. */
        opcion(R.id.op_autotest, R.drawable.ic_diag, R.string.autotest,
            R.string.d_autotest) {
            arrancarServicio(ServicioSos.ACCION_DIAGNOSTICO)
            ir(R.id.v_registro)
        }
        opcion(R.id.op_apagar, R.drawable.ic_parar, R.string.b_apagar_todo,
            R.string.d_apagar, apaga = true) {
            AlertDialog.Builder(this)
                .setTitle(R.string.b_apagar_todo)
                .setMessage(R.string.b_apagar_aviso)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.b_apagar_todo) { _, _ ->
                    arrancarServicio(ServicioSos.ACCION_APAGAR)
                    anotar("SismoRed apagada. Vuelve a abrir la app para encenderla.")
                }
                .show()
        }
    }

    /* ===================== ficha médica ===================== */

    private fun montarFicha() {
        paso(R.id.paso_f1, 1, R.drawable.ic_ficha, R.string.paso_f1_t, R.string.paso_f1)
        paso(R.id.paso_f2, 2, R.drawable.ic_candado, R.string.paso_f2_t, R.string.paso_f2)
        paso(R.id.paso_f3, 3, R.drawable.ic_baliza, R.string.paso_f3_t, R.string.paso_f3)
        paso(R.id.paso_f4, 4, R.drawable.ic_pantalla, R.string.paso_f4_t, R.string.paso_f4)
        val f = Ficha(this)
        val campos = listOf<Pair<EditText, (String) -> Unit>>(
            findViewById<EditText>(R.id.f_nombre).also { it.setText(f.nombre) } to { v: String -> f.nombre = v },
            findViewById<EditText>(R.id.f_sangre).also { it.setText(f.sangre) } to { v: String -> f.sangre = v },
            findViewById<EditText>(R.id.f_edad).also { it.setText(f.edad) } to { v: String -> f.edad = v },
            findViewById<EditText>(R.id.f_med).also { it.setText(f.medicacion) } to { v: String -> f.medicacion = v },
            findViewById<EditText>(R.id.f_contacto).also { it.setText(f.contacto) } to { v: String -> f.contacto = v }
        )
        /* Se guarda al escribir, sin botón de guardar: si alguien rellena esto y
           se va sin pulsar nada, la ficha tiene que estar ahí igualmente. */
        for ((campo, guardar) in campos) {
            campo.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { guardar(s?.toString() ?: "") }
            })
        }

        findViewById<Button>(R.id.f_mostrar).setOnClickListener {
            if (f.vacia()) { anotar("la ficha está vacía"); pintar(); return@setOnClickListener }
            mostrarFicha(f)
        }
        findViewById<Button>(R.id.f_borrar).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.f_borrar)
                .setMessage("Se borran de este móvil. No hay copia en ningún otro sitio.")
                .setPositiveButton("Borrar") { _, _ ->
                    f.borrar()
                    for ((campo, _) in campos) campo.setText("")
                    anotar(getString(R.string.f_borrada)); pintar()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    /**
     * La ficha a pantalla completa y con el brillo al máximo: se enseña a quien
     * te atiende sin que tenga que desbloquear ni buscar nada.
     */
    /**
     * La ficha a pantalla completa, que es lo que lee quien te encuentra.
     *
     * Era un TextView con todo el texto seguido pegado arriba a la izquierda, y
     * media pantalla en negro debajo. Ahora es un layout con pesos: la ficha se
     * reparte la pantalla ENTERA en cualquier tamano, el grupo sanguineo ocupa lo
     * que tiene que ocupar y los valores se autoescalan en vez de recortarse.
     *
     * Los campos vacios se esconden y sueltan su peso, para que tres datos llenen
     * la pantalla igual de bien que cinco.
     */
    private fun mostrarFicha(f: Ficha) {
        val v = LayoutInflater.from(this).inflate(R.layout.ficha_full, null)

        fun campo(id: Int, texto: String) {
            val t = v.findViewById<TextView>(id)
            t.text = texto
            // el padre del valor es el bloque entero: si no hay dato, fuera el bloque
            val bloque = t.parent as View
            if (texto.isBlank()) bloque.visibility = View.GONE
        }
        campo(R.id.ff_nombre, f.nombre.trim())
        campo(R.id.ff_sangre, f.sangre.trim().uppercase())
        campo(R.id.ff_edad, f.edad.trim().let { if (it.isBlank()) "" else "$it años" })
        campo(R.id.ff_med, f.medicacion.trim())
        campo(R.id.ff_contacto, f.contacto.trim())

        /* Un `Dialog` pelado, NO un `AlertDialog`.
           `AlertDialog.setView` mete la vista dentro de su propio contenedor, y ese
           contenedor mide `wrap_content` en alto. Mis bloques van con
           `layout_height="0dp"` y peso, y un peso contra un padre sin alto definido
           colapsa: por eso la ficha salía preciosa pero ocupando media pantalla.
           Con `setContentView` la vista ES la raíz, el alto es el de la ventana y
           los pesos reparten la pantalla entera. */
        val d = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        d.setContentView(v)
        d.window?.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT
        )
        d.setOnDismissListener { brillo(-1f) }
        /* Y el toque para cerrar va en la propia vista.
           `setCanceledOnTouchOutside` no podía funcionar nunca aquí: cierra al tocar
           FUERA de la ventana, y una ventana a pantalla completa no tiene fuera. */
        v.setOnClickListener { d.dismiss() }
        d.show()
        brillo(1f)
    }

    private fun brillo(v: Float) {
        window.attributes = window.attributes.apply { screenBrightness = v }
    }

    /* ===================== destello de pantalla ===================== */

    private var destelloPaso: Runnable? = null

    /** El mismo SOS que la linterna y el vibrador, sobre la vista blanca que
     *  tapa toda la pantalla. Solo mientras la app esté delante: para cuando no
     *  lo está están la sirena y la linterna, que no dependen de que nadie mire. */
    private fun destelloSos(activar: Boolean) {
        destelloPaso?.let { reloj.removeCallbacks(it) }
        destelloPaso = null
        val v = findViewById<View>(R.id.destello) ?: return
        if (!activar) { v.visibility = View.GONE; return }
        var i = 0
        val tarea = object : Runnable {
            override fun run() {
                v.visibility = if (i % 2 == 0) View.VISIBLE else View.GONE
                val espera = Linterna.SOS[i]
                i = (i + 1) % Linterna.SOS.size
                reloj.postDelayed(this, espera)
            }
        }
        destelloPaso = tarea
        reloj.post(tarea)
    }

    /** Un destello suelto, que es lo que gasta el modo rescate. */
    private fun destelloUno() {
        if (destelloPaso != null) return
        val v = findViewById<View>(R.id.destello) ?: return
        v.visibility = View.VISIBLE
        reloj.postDelayed({ if (destelloPaso == null) v.visibility = View.GONE }, 140)
    }

    /* ===================== pintado ===================== */

    private fun anotar(m: String) {
        lineas.addFirst(m)
        while (lineas.size > 40) lineas.removeLast()
    }

    private fun pintar() {
        if (enBienvenida) pintarBienvenida()
        val alarma = ServicioSos.enAlarma
        val rescate = ServicioSos.enRescate

        /* «Mantener activa»: en la web era un wake lock de pantalla; aquí es la
           pantalla de esta actividad, que es lo único que un servicio no puede
           mantener encendido. El wake lock parcial del servicio no es opcional
           ni se enseña: sin él no hay sirena. */
        if (op.mantener && (alarma || rescate)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        pintarPildora(alarma, rescate)

        when (vista) {
            R.id.v_inicio -> pintarInicio(alarma, rescate)
            R.id.v_red -> pintarRed()
            R.id.v_entorno -> pintarEntorno()
            R.id.v_respuesta -> pintarRespuesta(rescate)
            R.id.v_busqueda -> pintarBusqueda()
            R.id.v_diag -> pintarDiagnostico()
            R.id.v_registro -> {
                if (lineas.isEmpty() && ServicioSos.ultimoRegistro.isNotEmpty()) anotar(ServicioSos.ultimoRegistro)
                findViewById<TextView>(R.id.registro).text = lineas.joinToString("\n")
                pill(R.id.ch_registro, lineas.size.toString(), R.drawable.pill_off, R.color.dim)
            }
        }
    }

    /** Para no pedir el bluetooth dos veces en la misma alarma. */
    private var btPedido = false

    /**
     * Si hay alarma y la baliza no puede emitir porque el bluetooth esta apagado, se
     * pide con el dialogo del sistema. Desde Android 13 es la unica via: `enable()`
     * ya no existe, asi que o lo confirma una persona o no hay radio. Solo se pide
     * con la pantalla delante y una vez por alarma, que si no es acoso.
     */
    private fun pedirBluetoothSiHaceFalta(alarma: Boolean, rescate: Boolean) {
        if (!alarma && !rescate) { btPedido = false; return }
        if (btPedido || ServicioSos.radioEmitiendo) return
        if (!ServicioSos.radioMotivo.contains("BLUETOOTH", true)) return
        btPedido = true
        try {
            startActivity(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (_: Exception) {}
    }

    /**
     * La píldora de estado de la cabecera, visible desde cualquier pantalla.
     *
     * Cuatro estados y ni uno más, porque una píldora que dice cinco cosas ya no
     * se lee de un vistazo: **emitiendo** (rojo, algo está saliendo de este
     * móvil), **rescate** (rojo, sigue emitiendo pero a pulsos), **vigilando**
     * (verde) y **apagada** (gris). El rojo aquí obedece la regla de la paleta:
     * solo aparece cuando este teléfono está mandando algo.
     *
     * El punto respira mientras haya algo vivo, y se queda quieto cuando no.
     * Esa es la diferencia entre «vigilando» y «la app se ha colgado», que sin
     * animación no se puede ver.
     */
    private fun pintarPildora(alarma: Boolean, rescate: Boolean) {
        val punto = findViewById<View>(R.id.pe_punto) ?: return
        val texto = findViewById<TextView>(R.id.pe_texto) ?: return
        val (rotulo, color, vivo) = when {
            alarma -> Triple("emitiendo", R.color.rd, true)
            rescate -> Triple("rescate", R.color.rd, true)
            ServicioSos.armado || ServicioSos.mallaEscuchando -> Triple("activo", R.color.gr, true)
            else -> Triple("apagada", R.color.ctl, false)
        }
        if (!cambio(R.id.pe_texto, rotulo)) return
        texto.text = rotulo
        val c = getColor(color)
        texto.setTextColor(c)
        punto.background?.mutate()?.setTint(c)
        if (vivo) {
            if (punto.animation == null)
                punto.startAnimation(AnimationUtils.loadAnimation(this, R.anim.respirar))
        } else {
            punto.clearAnimation()
            punto.alpha = 1f
        }
    }

    private fun pintarInicio(alarma: Boolean, rescate: Boolean) {
        pedirBluetoothSiHaceFalta(alarma, rescate)
        val estado = findViewById<TextView>(R.id.estado)
        estado.text = when {
            alarma -> "ALARMA ACTIVA"
            rescate -> "MODO RESCATE"
            ServicioSos.armado -> getString(R.string.vigilando)
            else -> "EN REPOSO"
        }
        estado.setTextColor(getColor(if (alarma || rescate) R.color.rd else if (ServicioSos.armado) R.color.gr else R.color.dim))
        /* Y aquí se dice CÓMO está vigilando, que es lo que cambia solo según
           dónde esté el móvil. Un detector que se vuelve más fino cuando lo dejas
           en la mesilla tiene que decirlo, o parece que se ha vuelto loco. */
        val sub = findViewById<TextView>(R.id.estado_sub)
        when {
            alarma -> sub.setText(R.string.sub_alarma)
            rescate -> sub.setText(R.string.sub_rescate)
            /* Que se vea que el ESTOY BIEN llegó. Sin esto, pulsarlo y que la
               pantalla se cierre es exactamente igual a que el botón no funcione,
               y no hay forma de saber cuál de las dos cosas ha pasado. */
            ServicioSos.contestoBien > 0 &&
                System.currentTimeMillis() - ServicioSos.contestoBien < 300_000L -> {
                sub.text = "Has dicho que estás bien. Este móvil repite las alertas de otros."
            }
            ServicioSos.armado -> {
                val u = ServicioSos.umbralActivo
                val calma = ServicioSos.calmaMedida
                /* Los cuatro números que deciden, a la vista. Sin esto, cuando
                   la app dispara sola no hay forma de saber por qué, y calibrar
                   se convierte en adivinar — que es exactamente lo que pasó. */
                val ur = ServicioSos.umbralReal
                sub.text = if (u > 0)
                    "%s · dispara a %.2f%s · ahora %.0f%% · mano %.0f°%s".format(
                        if (ServicioSos.enReposoAhora) "En reposo, vigilancia fina" else "Lo llevas encima",
                        if (ur > 0) ur else u,
                        if (ur > u + 0.01) " (subido por el ruido de aquí)" else "",
                        ServicioSos.cicloTrabajo * 100,
                        ServicioSos.manoGrados,
                        if (calma > 0) " · aquí se mueve %.3f".format(calma) else ""
                    )
                else getString(R.string.sub_vigilando)
            }
            else -> sub.setText(R.string.sub_reposo)
        }
        // El borde rojo es lo que se ve de reojo sin llegar a leer nada.
        if (cambio(R.id.tarjeta_estado, alarma || rescate)) {
            vista(R.id.tarjeta_estado).setBackgroundResource(
                if (alarma || rescate) R.drawable.tarjeta_alarma else R.drawable.tarjeta
            )
        }
        (vista(R.id.anillo) as VistaAnillo).apply {
            this.alarma = alarma || rescate
            this.armado = ServicioSos.armado
            this.sacudida = ServicioSos.sacudida
        }

        // Si el atajo no está activo hay que decirlo: es la diferencia entre
        // poder pedir ayuda sin ver el móvil y no poder.
        val listo = teclasActivas(this)
        findViewById<TextView>(R.id.teclas_estado).let {
            it.text = if (listo) "Atajo de volumen listo" else "ATAJO DE VOLUMEN SIN ACTIVAR"
            it.setTextColor(getColor(if (listo) R.color.dim else R.color.rd))
        }

        // ---- malla de propagación ----
        val salto = ServicioSos.mallaSalto
        for ((i, id) in listOf(R.id.hop1, R.id.hop2, R.id.hop3, R.id.hop4).withIndex()) {
            val lit = i < salto
            if (!cambio(id, lit)) continue
            (vista(id) as TextView).let {
                it.setBackgroundResource(if (lit) R.drawable.salto_on else R.drawable.salto)
                it.setTextColor(getColor(if (lit) R.color.gr else R.color.ctl))
            }
        }
        val s3 = vista(R.id.s3_malla)
        s3.findViewById<TextView>(R.id.s3_v1).text = if (salto > 0) salto.toString() else getString(R.string.guion)
        s3.findViewById<TextView>(R.id.s3_v2).text = ServicioSos.mallaRx.toString()
        s3.findViewById<TextView>(R.id.s3_v3).text = ServicioSos.mallaTx.toString()

        val viva = ServicioSos.mallaEscuchando
        ghostEstado(R.id.g_malla, viva)
        kvValor(
            R.id.kv_malla_estado,
            when {
                viva -> "Escuchando"
                hayMicro() -> "Apagada"
                else -> "Sin micrófono"
            },
            if (viva) R.color.gr else R.color.dim
        )

        // ---- detector sísmico ----
        // La traza se pinta sola a 60 fps leyendo el servicio; de aquí solo
        // necesita el umbral, que lo cambia una persona y no cada marco.
        (vista(R.id.traza) as VistaTraza).umbral = op.umbral
        /* Corta: esta fila es estrecha y el texto se partía. Lo que hace falta
           para entender el número —a qué umbral vigila y cuánto se mueve este
           sitio— va en la línea de estado, que tiene sitio. */
        kvValor(R.id.kv_sacudida, "%.2f m/s²".format(ServicioSos.sacudida))
        conmutadorEstado(R.id.cm_auto, ServicioSos.armado)
        conmutadorEstado(R.id.cm_sirena, op.sirena)
    }

    private fun pintarRed() {
        val viva = ServicioSos.mallaEscuchando
        pill(
            R.id.ch_malla_estado,
            getString(if (viva) R.string.pill_activa else R.string.pill_apagada),
            if (viva) R.drawable.pill_on else R.drawable.pill_off,
            if (viva) R.color.gr else R.color.dim
        )
        findViewById<TextView>(R.id.malla_sub)
            .setText(if (viva) R.string.malla_sub_on else R.string.malla_sub_off)
        findViewById<VistaRadar>(R.id.radar)
            .pintar(viva, ServicioSos.mallaPorSalto, ServicioSos.mallaTx)

        val salto = ServicioSos.mallaSalto
        kvValor(R.id.kv_r_salto, if (salto > 0) salto.toString() else getString(R.string.guion))
        kvValor(R.id.kv_r_balizas, ServicioSos.mallaRx.toString())
        kvValor(R.id.kv_r_retx, ServicioSos.mallaTx.toString())

        val envio = op.envio
        ghostEstado(R.id.g_enviar, envio)
        kvValor(
            R.id.kv_n_estado,
            when {
                !envio -> "Desactivado"
                ServicioSos.tipoRed == "sin red" -> "Sin red · en cola"
                else -> "Red disponible"
            },
            if (envio) R.color.tx else R.color.dim
        )
        kvValor(R.id.kv_n_tipo, ServicioSos.tipoRed)
        kvValor(R.id.kv_n_cola, ServicioSos.enCola.toString())
        consola(R.id.red_salida, ServicioSos.redSalida)
    }

    private fun pintarEntorno() {
        val escuchando = ServicioSos.oyeEscuchando
        val ahora = System.currentTimeMillis()
        val cuando = ServicioSos.oyeCuando
        val prog = ServicioSos.oyeProgreso
        val ids = listOf(R.id.det_derrumbe, R.id.det_grito, R.id.det_voz, R.id.det_animal, R.id.det_golpes)
        for (i in ids.indices) {
            val reciente = cuando.getOrElse(i) { 0L }.let { it > 0 && ahora - it < Escucha.CALIENTE_MS }
            val pct = if (reciente) 100 else (prog.getOrElse(i) { 0.0 } * 100).toInt().coerceIn(0, 100)
            detectorEstado(ids[i], pct, reciente)
        }
        // el osciloscopio se pinta solo a 60 fps: aquí no hay que tocarlo

        val seg = if (escuchando && ServicioSos.oyeDesde > 0) (ahora - ServicioSos.oyeDesde) / 1000 else 0
        /* La etiqueta ya no lleva el cronometro. Con "ESCUCHANDO 00:12" crecia y
           encogia cada segundo, y el titulo de la tarjeta se reflowaba con ella:
           parecia que el titulo cambiaba de tamano solo. El tiempo se lee en la
           fila de estado, que es donde no molesta a nadie. */
        pill(
            R.id.ch_deteccion,
            if (escuchando) getString(R.string.pill_oyendo)
            else getString(R.string.pill_pausa),
            if (escuchando) R.drawable.pill_rec else R.drawable.pill_off,
            if (escuchando) R.color.rd else R.color.dim
        )
        conmutadorAnchoEstado(R.id.cm_escuchar, escuchando)

        kvValor(R.id.kv_e_estado,
            if (escuchando) "Escuchando · %02d:%02d".format(seg / 60, seg % 60) else "Apagada",
            if (escuchando) R.color.gr else R.color.dim)
        kvValor(R.id.kv_e_nivel, "${ServicioSos.oyeNivelDb.toInt()} dBFS")
        val hz = ServicioSos.oyeTonoHz
        kvValor(R.id.kv_e_tono, if (hz > 0) "${hz.toInt()} Hz" else getString(R.string.guion))
        kvValor(R.id.kv_e_ataques, ServicioSos.oyeImpactos.toString())

        /* Cada herramienta pinta lo suyo: su interruptor, su animacion y su
           consola. Las animaciones solo se mueven si su herramienta esta
           encendida, para no gastar bateria dibujando cuatro lienzos a 60 fps. */
        val ecoOn = ServicioSos.ecoActivo
        val movOn = ServicioSos.quienTono == "movimiento"
        val respOn = ServicioSos.quienTono == "respiracion"
        val barOn = ServicioSos.barridoActivo

        conmutadorAnchoEstado(R.id.cm_eco, ecoOn)
        conmutadorAnchoEstado(R.id.cm_movimiento, movOn)
        conmutadorAnchoEstado(R.id.cm_respira, respOn)
        conmutadorAnchoEstado(R.id.cm_barrido, barOn)

        (vista(R.id.sonar_sonda) as VistaSonar).apply {
            activo = ecoOn || ServicioSos.sondaOcupada
            intensidad = 1f
        }
        (vista(R.id.pulso_mov) as VistaPulso).activo = movOn
        (vista(R.id.pulso_resp) as VistaPulso).apply { activo = respOn; lento = true }
        (vista(R.id.graf_barrido) as VistaBarrido).activo = barOn

        consola(R.id.eco_salida, ServicioSos.ecoSalida)
        consola(R.id.mov_salida, ServicioSos.dopplerSalida)
        consola(R.id.resp_salida, ServicioSos.respiraSalida)
        consola(R.id.barrido_salida, ServicioSos.barridoSalida)

        val filas = listOf(R.id.kv_e_derrumbe, R.id.kv_e_grito, R.id.kv_e_voz, R.id.kv_e_animal, R.id.kv_e_golpes)
        for (i in filas.indices) {
            val t = cuando.getOrElse(i) { 0L }
            kvValor(filas[i], if (t > 0) hora.format(java.util.Date(t)) else getString(R.string.guion))
        }
    }

    private fun pintarRespuesta(rescate: Boolean) {
        casillaEstado(R.id.cs_linterna, op.linterna)
        casillaEstado(R.id.cs_vibracion, op.vibracion)
        casillaEstado(R.id.cs_pantalla, op.pantalla)
        casillaEstado(R.id.cs_baliza, op.baliza)
        casillaEstado(R.id.cs_mantener, op.mantener)
        casillaEstado(R.id.cs_rescate, rescate)
    }

    /* ===================== diagnóstico ===================== */

    /** Una fila de diagnóstico. Si un dato no se puede saber en este móvil se
     *  dice «no disponible», nunca se rellena con algo verosímil. */
    private fun fila(donde: LinearLayout, nombre: String, valor: String, color: Int) {
        val f = LayoutInflater.from(this).inflate(R.layout.fila_kv, donde, false)
        f.findViewById<TextView>(R.id.kv_clave).text = nombre
        f.findViewById<TextView>(R.id.kv_valor).let {
            /* La mayúscula va aquí y no en cada llamada: son veintidós filas y
               la siguiente que alguien añada saldría en minúscula otra vez. Solo
               toca la primera letra, así que las que ya van en mayúsculas —SIN
               CONCEDER, NINGUNA— y las que empiezan por un número se quedan
               como están. */
            it.text = valor.replaceFirstChar { c -> c.titlecase(java.util.Locale.getDefault()) }
            it.setTextColor(getColor(color))
        }
        donde.addView(f)
    }

    private fun si(b: Boolean) = if (b) R.color.gr else R.color.rd

    /** Las filas de diagnóstico se rehacen enteras, así que no pueden ir al
     *  ritmo del resto: inflar veinte layouts dos veces por segundo cuesta
     *  bastante más que cambiar veinte textos, y aquí nada cambia tan deprisa. */
    private var diagUltimo = 0L
    /** Se crea una vez, no en cada repintado. Solo se lee. */
    private var ubicacion: Ubicacion? = null

    private fun pintarDiagnostico(forzar: Boolean = false) {
        val ahora = System.currentTimeMillis()
        if (!forzar && ahora - diagUltimo < 2000) return
        diagUltimo = ahora

        val sis = findViewById<LinearLayout>(R.id.diag_sistema)
        sis.removeAllViews()
        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val bat = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        fila(sis, "Batería", if (bat in 0..100) "$bat%" else "no disponible",
            if (bat < 0) R.color.dim else si(bat > 25))
        val micOn = ServicioSos.oyeEscuchando || ServicioSos.mallaEscuchando
        fila(sis, "Micrófono", if (micOn) "activo" else "inactivo", if (micOn) R.color.gr else R.color.dim)
        fila(sis, "Acelerómetro", if (ServicioSos.armado) "leyendo · armado" else "leyendo · desarmado",
            if (ServicioSos.armado) R.color.gr else R.color.dim)
        fila(sis, "Malla acústica", if (ServicioSos.mallaEscuchando) "escuchando" else "apagada",
            if (ServicioSos.mallaEscuchando) R.color.gr else R.color.dim)
        fila(sis, "Frecuencia de muestreo",
            if (ServicioSos.micSr > 0) "${ServicioSos.micSr} Hz" else "al abrir el micrófono",
            if (ServicioSos.micSr > 0) R.color.gr else R.color.dim)
        fila(sis, "Fuente sin procesar", if (ServicioSos.micCrudo) "sí" else "no: voice_recognition",
            if (ServicioSos.micCrudo) R.color.gr else R.color.dim)
        val rt = Runtime.getRuntime()
        val librePct = (100 - (rt.totalMemory() - rt.freeMemory()) * 100 / rt.maxMemory()).toInt()
        fila(sis, "Memoria libre", "$librePct%", si(librePct > 20))
        val rok = ServicioSos.radioEmitiendo
        fila(sis, "Baliza de radio", if (rok) "emitiendo" else ServicioSos.radioMotivo,
            if (rok) R.color.gr else if (ServicioSos.radioMotivo.startsWith("ENCIENDE")) R.color.rd else R.color.dim)
        fila(sis, "Partes en espera", ServicioSos.enCola.toString(),
            if (ServicioSos.enCola > 0) R.color.dim else R.color.gr)
        fila(sis, "Red usada", if (op.envio) "solo partes, si la activas" else "NINGUNA",
            if (op.envio) R.color.dim else R.color.gr)
        /* Antes decía «GPS usado: NINGUNO» y era verdad. Sigue siéndolo en lo que
           importa —la app no enciende el GPS ni una vez—, pero ahora lee la
           última posición que dejó otra app, así que la fila tiene que decir eso
           y no una media verdad más cómoda. */
        /* SOLO LEER. `refrescar()` pregunta al servicio de ubicación del sistema,
           que es una llamada al otro lado de un binder y puede tardar; metida en
           el repintado del diagnóstico —que corre varias veces por segundo en el
           hilo de la interfaz— colgó la app entera: «Input dispatching timed out,
           MainActivity is not responding». Quien refresca es el servicio, al
           arrancar y en cada suceso. Aquí se pinta lo que ya hay. */
        val ubi = ubicacion ?: Ubicacion(this).also { ubicacion = it }
        /* El canal que fallaba en silencio: si el chip filtra la difusión o no
           hay Wi-Fi, aquí se lee en vez de quedarse esperando una ficha que no
           va a llegar nunca. */
        val fl = ServicioSos.fichaLanEstado
        fila(sis, "Ficha por Wi-Fi", fl,
            if (fl.startsWith("escuchando") || fl.startsWith("emitiendo")) R.color.gr else R.color.dim)
        fila(sis, "GPS encendido por la app", "NUNCA", R.color.gr)
        fila(sis, "Última posición conocida", ubi.resumen(),
            if (ubi.hay() && ubi.hayPermiso()) R.color.dim else R.color.ctl)

        val cap = findViewById<LinearLayout>(R.id.diag_capacidades)
        cap.removeAllViews()
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        fila(cap, "Acelerómetro", quiza(sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null),
            si(sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null))
        val hayLinterna = Linterna(this).hay()
        fila(cap, "Linterna", quiza(hayLinterna), si(hayLinterna))
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        fila(cap, "Vibración", quiza(vib.hasVibrator()), si(vib.hasVibrator()))
        fila(cap, "Permiso de micrófono", if (hayMicro()) "concedido" else "SIN CONCEDER", si(hayMicro()))
        val teclas = teclasActivas(this)
        fila(cap, "Atajo de volumen", if (teclas) "activo" else "SIN ACTIVAR", si(teclas))
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val exenta = pm.isIgnoringBatteryOptimizations(packageName)
        fila(cap, "Batería exenta", quiza(exenta), si(exenta))
        val notif = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        fila(cap, "Notificaciones", if (notif) "concedidas" else "SIN CONCEDER", si(notif))
        val radioOk = faltanPermisosDeRadio().isEmpty()
        fila(cap,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "Permiso de bluetooth" else "Permiso para buscar",
            if (radioOk) "concedido" else "SIN CONCEDER", si(radioOk))
        /* En Android 11 y anteriores no basta el permiso: si la ubicación del
           sistema está apagada, el escaneo de radio devuelve cero resultados sin
           dar ningún error. Es la trampa que deja una búsqueda muda sin motivo. */
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val ubi = try { androidx.core.location.LocationManagerCompat.isLocationEnabled(lm) } catch (_: Exception) { false }
            fila(cap, "Ubicación del sistema", if (ubi) "encendida" else "APÁGALA NO, ENCIÉNDELA", si(ubi))
        }
        val bt = try {
            (getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter?.isEnabled == true
        } catch (_: Exception) { false }
        fila(cap, "Bluetooth encendido", quiza(bt), si(bt))
        /* Lo que decide si esta app sirve de algo con la pantalla guardada. En
           HyperOS, deslizarla fuera de recientes la mata aunque esté vigilando
           (`OneKeyClean` en el registro del sistema) — y la app no puede
           impedirlo, solo enterarse. Aquí se ve sin tener que provocarlo. */
        val vivo = servicioVivo()
        /* Mismo criterio que el aviso: un hueco en el latido, no la foto de este
           instante. Si no, al abrir la app la fila salía en rojo un segundo
           aunque todo estuviera bien. */
        val hueco = System.currentTimeMillis() - op.latido
        val murio = op.deberiaVigilar && !op.apagada && !vivo && op.latido > 0 &&
            hueco in 120_000L..(6 * 3600_000L)
        fila(cap, "Sigue vigilando con la app cerrada",
            when {
                murio -> "NO: tu móvil la mató"
                vivo -> "sí"
                op.apagada -> "apagada por ti"
                else -> "sin comprobar todavía"
            },
            if (murio) R.color.rd else if (vivo) R.color.gr else R.color.dim)
        /* Si esto está en NO, «¿estás bien?» no puede salir a pantalla completa
           con el móvil bloqueado y llega como aviso normal. Se sigue pudiendo
           contestar, pero hay que verlo, así que tiene que estar dicho. */
        val pc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            try {
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .canUseFullScreenIntent()
            } catch (_: Exception) { false }
        else true
        fila(cap, "Preguntar con el móvil bloqueado",
            if (pc) "sí" else "NO: llegará como aviso", si(pc))
        fila(cap, "Funciona sin conexión", "sí, entera", R.color.gr)
    }

    private fun quiza(b: Boolean) = if (b) "sí" else "no"

    /* ===================== envío por internet ===================== */

    /** Nada sale del móvil sin que alguien lea qué sale y diga que sí. */
    private fun conmutarEnvio() {
        if (op.envio) {
            op.envio = false
            anotar("envío por internet desactivado")
            pintar()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rot_internet)
            .setMessage(R.string.confirmar_envio)
            .setPositiveButton("Activar") { _, _ ->
                op.envio = true
                anotar("envío por internet activado")
                arrancarServicio(ServicioSos.ACCION_ENVIAR)
                pintar()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /* ===================== permisos y servicio ===================== */

    private fun hayMicro() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun arrancarServicio(accion: String?) {
        /* Abrir la app y usarla es querer que funcione: se limpia la marca de
           apagado. Menos cuando lo que se pide es justamente apagarla. */
        if (accion != ServicioSos.ACCION_APAGAR && op.apagada) op.apagada = false
        val i = Intent(this, ServicioSos::class.java)
        if (accion != null) i.action = accion
        ContextCompat.startForegroundService(this, i)
    }

    /**
     * ¿Me mataron mientras no miraba?
     *
     * Si el usuario quería vigilancia, el servicio dio señales de vida hace poco
     * y ahora no está corriendo, no ha sido él: ha sido el sistema. En HyperOS
     * pasa al deslizar la app fuera de recientes —el registro del sistema lo
     * llama `OneKeyClean`— y ocurre 200 ms después de que la app haya hecho todo
     * lo que puede hacer: declararse `stopWithTask="false"` y volver a primer
     * plano en `onTaskRemoved`.
     *
     * Contra eso no hay código. Lo único honesto es enterarse y decirlo, porque
     * lo contrario es que alguien se vaya a dormir creyendo que está vigilado.
     */
    private fun avisarSiLoMataron() {
        if (op.apagada || !op.deberiaVigilar || op.latido == 0L) return
        val ahora = System.currentTimeMillis()
        val hueco = ahora - op.latido

        /* La pregunta NO es «¿está corriendo ahora?». Al abrir la app el servicio
           aún no ha arrancado, así que con esa pregunta el aviso salía en todos
           los arranques, incluso con la vigilancia funcionando perfectamente.

           La pregunta buena es si hubo un HUECO: el servicio deja un latido cada
           10 s, así que si el último es de hace un momento, estaba vivo hasta
           ahora mismo y no ha pasado nada. Si es de hace media hora, alguien lo
           mató mientras nadie miraba. */
        if (hueco < 2 * 60_000L) return
        // ni tan viejo que ya no signifique nada (el móvil apagado una semana)
        if (hueco > 6 * 3600_000L) return
        if (servicioVivo()) return
        // y como mucho una vez al día: un aviso que se repite deja de leerse
        if (ahora - op.ultimoAvisoMuerte < 24 * 3600_000L) return
        op.ultimoAvisoMuerte = ahora
        AlertDialog.Builder(this)
            .setTitle(R.string.matada_titulo)
            .setMessage(R.string.matada_texto)
            .setNegativeButton(R.string.matada_luego, null)
            .setPositiveButton(R.string.matada_ajustes) { _, _ -> abrirInicioAutomatico() }
            .show()
        anotar("El sistema paró SismoRed al cerrar la app. Sin arreglarlo, no vigila cuando la cierras.")
    }

    private fun servicioVivo(): Boolean = try {
        @Suppress("DEPRECATION")
        (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
            .getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == ServicioSos::class.java.name }
    } catch (_: Exception) { true }

    /** El ajuste de «inicio automático» de MIUI/HyperOS, y si no existe, la
     *  ficha de la app. No hay API estándar: cada fabricante se lo inventa. */
    private fun abrirInicioAutomatico() {
        val intentos = listOf(
            Intent().setClassName("com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            Intent().setClassName("com.samsung.android.lool",
                "com.samsung.android.sm.ui.battery.BatteryActivity"),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$packageName"))
        )
        for (i in intentos) {
            try { startActivity(i); return } catch (_: Exception) {}
        }
    }

    private fun pedirPermisos() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PIDE_NOTIF)
            return                      // el micrófono se pide después, no a la vez
        }
        pedirMicrofonoUnaVez()
    }

    /** Solo se ofrece solo la primera vez. Si dijo que no, no se le persigue:
     *  queda ESCUCHAR MALLA, que es pulsable. */
    private fun pedirMicrofonoUnaVez() {
        if (hayMicro()) { pedirRadioUnaVez(); return }
        if (prefs().getBoolean("micro_pedido", false)) return
        prefs().edit().putBoolean("micro_pedido", true).apply()
        pedirMicrofono()
    }

    /**
     * El permiso de bluetooth se pide al principio, con los demás.
     *
     * No puede esperar a que alguien entre en Búsqueda: el que lo necesita para
     * emitir es **el que está debajo**, y bajo una losa nadie va a poder aceptar
     * un diálogo. Si no está concedido de antes, la baliza de radio no existe
     * justo cuando es lo único que puede encontrarte.
     */
    /**
     * Qué permisos hacen falta para la baliza de radio, que **no son los mismos
     * según la versión de Android**:
     *
     *  - Android 12 y posteriores: `BLUETOOTH_ADVERTISE` para emitir y
     *    `BLUETOOTH_SCAN` para buscar.
     *  - Android 11 y anteriores: emitir no pide nada, pero **buscar exige el
     *    permiso de UBICACIÓN**, porque el sistema entiende que oír radios
     *    cercanas permite deducir dónde estás. SismoRed no lo usa para eso y hay
     *    que decirlo antes de pedirlo, o parece que la app quiere seguirte.
     */
    private fun permisosDeRadio(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_SCAN)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun faltanPermisosDeRadio(): List<String> =
        permisosDeRadio().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    private fun pedirRadioUnaVez() {
        if (faltanPermisosDeRadio().isEmpty()) return
        if (prefs().getBoolean("radio_pedida", false)) return
        prefs().edit().putBoolean("radio_pedida", true).apply()
        explicarRadio()
    }

    /** La explicación va SIEMPRE antes del diálogo del sistema. Es el permiso
     *  que peor pinta tiene de los tres, y el que menos se parece a lo que hace. */
    private fun explicarRadio() {
        val faltan = faltanPermisosDeRadio()
        if (faltan.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Que te encuentren desde arriba")
            .setMessage(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) getString(R.string.desc_radio)
                else getString(R.string.desc_radio) + "\n\n" + getString(R.string.desc_radio_ubicacion)
            )
            .setPositiveButton("Permitir") { _, _ -> requestPermissions(faltan.toTypedArray(), PIDE_RADIO) }
            .setNegativeButton("Ahora no", null)
            .show()
    }

    private fun prefs() = getSharedPreferences("sismored", Context.MODE_PRIVATE)

    private fun abrirAjustesDeLaApp() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {}
    }

    /**
     * El micrófono se explica ANTES de pedirlo. Es el permiso que más asusta y
     * el que menos se parece a lo que la gente teme: la malla mira si hay tonos
     * de 16-18 kHz en marcos de 42 ms y los tira. No graba, no guarda, no sube
     * nada — la app entera funciona sin red.
     */
    private fun pedirMicrofono() {
        AlertDialog.Builder(this)
            .setTitle("Escuchar la malla")
            .setMessage(R.string.desc_micro)
            .setPositiveButton("Permitir") { _, _ ->
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PIDE_MICRO)
            }
            .setNegativeButton("Ahora no", null)
            .show()
    }

    override fun onRequestPermissionsResult(codigo: Int, permisos: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(codigo, permisos, res)
        when (codigo) {
            PIDE_NOTIF -> pedirMicrofonoUnaVez()
            // si acaba de concederlo, se arranca sin obligarle a pulsar otra vez
            PIDE_RADIO -> if (res.isNotEmpty() && res.all { it == PackageManager.PERMISSION_GRANTED }) {
                rastreador.arrancar { }
            }
            // El servicio ya está en marcha sin micrófono: hay que avisarle de
            // que ahora sí puede escuchar, porque el tipo de servicio en primer
            // plano se fija al arrancar y hay que volver a declararlo.
            PIDE_MICRO -> {
                if (hayMicro()) arrancarServicio(ServicioSos.ACCION_MALLA)
                // Si dice que no y el sistema ya no ofrece explicación, es un no
                // definitivo: a partir de ahí solo se arregla desde Ajustes.
                else if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO))
                    prefs().edit().putBoolean("micro_denegado_firme", true).apply()
                pedirRadioUnaVez()
            }
        }
        pintarBienvenida()
        pintar()
    }

    /**
     * Sin esta exención, Doze puede matar el servicio a las horas — justo cuando
     * más falta hace. El sistema exige que lo pida el usuario, no se puede dar solo.
     */
    private fun pedirExencionBateria() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            anotar("la batería ya está exenta"); pintar(); return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
