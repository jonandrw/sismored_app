package red.sismo

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
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
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.content.ClipboardManager
import android.content.ClipData
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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
            R.id.t_ficha to R.id.v_ficha,
            R.id.t_registro to R.id.v_registro,
            R.id.t_ajustes to R.id.v_diag
        )
    }
    private val subtitulos = mapOf(
        R.id.v_panico_activo to R.string.panico_activo_titulo,
        R.id.v_detector to R.string.rot_detector,
        R.id.v_red to R.string.rot_malla,
        R.id.v_baliza to R.string.rot_baliza,
        R.id.v_busqueda to R.string.rot_busqueda,
        R.id.v_entorno to R.string.rot_sonda,
        R.id.v_interfono to R.string.rot_interfono,
        R.id.v_rescate to R.string.rot_rescate,
        R.id.v_consola to R.string.rot_consola,
        R.id.v_respuesta to R.string.v_respuesta,
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
            i?.getStringExtra("texto")?.let { anotar(it, saveToDb = false) }
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

        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        montarCabeceras()
        montarInicio()
        montarPanicoActivo()
        montarDetector()
        montarBaliza()
        montarInterfono()
        montarRescate()
        montarConsola()
        montarRegistro()
        montarRed()
        montarEntorno()
        montarFicha()
        montarRespuesta()
        montarBusqueda()
        montarDiagnostico()

        for ((tab, _) in pestanas) findViewById<View>(tab).setOnClickListener { ir(tab) }
        ir(R.id.t_inicio)


        // Barra superior y retroceso
        findViewById<View>(R.id.pildora_estado)?.setOnClickListener { ir(R.id.t_registro) }
        findViewById<View>(R.id.go_back)?.setOnClickListener { ir(R.id.t_inicio) }

        /* Las herramientas, el modo rescate y el registro se montaban dos
           veces: aquí y en `montarInicio`/`montarRescate`/`montarRegistro`.
           La segunda pisaba a la primera, así que la copia de arriba —que
           además llamaba a `ACCION_RESCATADO` en vez de a `ACCION_PARAR`— no
           llegaba a ejecutarse nunca. Se queda una sola, la de cada `montar`. */

        /* La bienvenida solo sale la primera vez. El `if` que había aquí tenía
           las dos ramas idénticas: leía `bienvenida_hecha` y hacía lo mismo
           tanto si estaba puesta como si no, así que la pantalla de permisos
           no se llegaba a abrir nunca en un móvil recién instalado. */
        if (prefs().getBoolean("bienvenida_hecha", false)) {
            pedirPermisos()
            reactivarSiEstabaApagada()
            avisarSiLoMataron()
        } else {
            abrirListaPermisos()
        }
        arrancarServicio(null)   // deja la vigilancia corriendo desde el principio
        tratarIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        tratarIntent(intent)
    }

    private fun tratarIntent(intent: Intent?) {
        val v = intent?.getStringExtra("vista")
        if (v != null) {
            when (v) {
                "v_panico_activo" -> ir(R.id.v_panico_activo)
                "v_detector" -> ir(R.id.v_detector)
                "v_red" -> ir(R.id.v_red)
                "v_baliza" -> ir(R.id.v_baliza)
                "v_busqueda" -> ir(R.id.v_busqueda)
                "v_rescate" -> ir(R.id.v_rescate)
                "t_ficha" -> ir(R.id.t_ficha)
                "t_registro" -> ir(R.id.t_registro)
                "t_ajustes" -> ir(R.id.t_ajustes)
                "t_inicio" -> ir(R.id.t_inicio)
            }
            pintar()
        }
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
            findViewById<View>(v)?.visibility = if (v == destino) View.VISIBLE else View.GONE
        }
        val esPanicoActivo = destino == R.id.v_panico_activo
        val sub = destino in subtitulos && !esPanicoActivo
        findViewById<View>(R.id.barra)?.visibility = if (esPanicoActivo || sub) View.GONE else View.VISIBLE
        findViewById<View>(R.id.pestanas)?.visibility = if (esPanicoActivo || sub) View.GONE else View.VISIBLE
        findViewById<View>(R.id.subbarra)?.visibility = if (sub) View.VISIBLE else View.GONE
        if (sub) {
            findViewById<TextView>(R.id.subtitulo)?.setText(subtitulos.getValue(destino))
            val container = findViewById<View>(R.id.subbarra_chip_container)
            val chipSub = findViewById<TextView>(R.id.subbarra_chip)
            val dot = findViewById<View>(R.id.subbarra_chip_dot)
            /* Cada chip dice el estado real. Antes cuatro de ellos eran el
               literal de la maqueta: el detector ponía «ARMADO» aunque
               estuviera parado, la baliza «EMITIENDO» sin emitir nada, la
               ficha «SIN DESBLOQUEAR» estando desbloqueada y buscar «1 SEÑAL»
               con cero hallazgos. */
            val hallazgos = rastreador.hallazgos().size
            val chipText = when (destino) {
                R.id.v_detector -> if (ServicioSos.armado) "ARMADO" else "PARADO"
                R.id.v_red -> when {
                    ServicioSos.mallaTx > 0 -> "TRANSMITIENDO"
                    ServicioSos.mallaEscuchando -> "ESCUCHA"
                    else -> "PARADA"
                }
                R.id.v_baliza -> if (ServicioSos.radioEmitiendo) "EMITIENDO" else "EN REPOSO"
                R.id.v_busqueda -> when {
                    !ServicioSos.buscando -> "SIN BUSCAR"
                    hallazgos > 0 -> "$hallazgos ${if (hallazgos == 1) "SEÑAL" else "SEÑALES"}"
                    else -> "SIN SEÑAL"
                }
                R.id.v_entorno -> if (ServicioSos.ecoActivo || ServicioSos.barridoActivo) "EMITIENDO" else "SIN CALIBRAR"
                R.id.v_ficha -> if (Ficha(this).vacia()) "SIN RELLENAR" else "GUARDADA"
                R.id.v_rescate -> if (ServicioSos.enRescate) "ACTIVO" else "PARADO"
                R.id.v_interfono -> if (ServicioSos.interfonoOcupado) "CANAL ABIERTO" else "CERRADO"
                R.id.v_consola -> "EN VIVO"
                else -> ""
            }
            if (chipText.isNotEmpty()) {
                container?.visibility = View.VISIBLE
                chipSub?.text = chipText
                when (destino) {
                    R.id.v_baliza, R.id.v_ficha -> {
                        container?.setBackgroundResource(R.drawable.chip_rd_outline)
                        chipSub?.setTextColor(android.graphics.Color.parseColor("#E53035"))
                        if (destino == R.id.v_baliza) {
                            dot?.setBackgroundResource(R.drawable.punto_rojo)
                            dot?.visibility = View.VISIBLE
                            val anim = android.view.animation.AlphaAnimation(0.25f, 1.0f).apply {
                                duration = 800
                                repeatMode = android.view.animation.Animation.REVERSE
                                repeatCount = android.view.animation.Animation.INFINITE
                            }
                            dot?.startAnimation(anim)
                        } else {
                            dot?.clearAnimation()
                            dot?.visibility = View.GONE
                        }
                    }
                    R.id.v_rescate -> {
                        container?.setBackgroundResource(R.drawable.chip_gr_outline)
                        chipSub?.setTextColor(android.graphics.Color.parseColor("#90CA50"))
                        dot?.setBackgroundResource(R.drawable.punto_verde)
                        dot?.visibility = View.VISIBLE
                        val anim = android.view.animation.AlphaAnimation(0.25f, 1.0f).apply {
                            duration = 800
                            repeatMode = android.view.animation.Animation.REVERSE
                            repeatCount = android.view.animation.Animation.INFINITE
                        }
                        dot?.startAnimation(anim)
                    }
                    R.id.v_busqueda -> {
                        dot?.clearAnimation()
                        dot?.visibility = View.GONE
                        container?.setBackgroundResource(R.drawable.chip_gr_outline)
                        chipSub?.setTextColor(android.graphics.Color.parseColor("#90CA50"))
                    }
                    R.id.v_detector -> {
                        dot?.clearAnimation()
                        dot?.visibility = View.GONE
                        container?.setBackgroundResource(R.drawable.chip_gr)
                        chipSub?.setTextColor(android.graphics.Color.parseColor("#90CA50"))
                    }
                    else -> {
                        dot?.clearAnimation()
                        dot?.visibility = View.GONE
                        container?.setBackgroundResource(R.drawable.chip)
                        chipSub?.setTextColor(android.graphics.Color.parseColor("#BCC3C9"))
                    }
                }
            } else {
                dot?.clearAnimation()
                container?.visibility = View.GONE
            }
        }

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
        findViewById<View>(R.id.dock_busqueda)?.visibility = if (destino == R.id.v_busqueda) View.VISIBLE else View.GONE
        // Inicio, Entorno, Detector y Malla tienen lienzos activos a 60 Hz.
        ServicioSos.mirando = destino == R.id.v_inicio || destino == R.id.v_entorno || destino == R.id.v_detector || destino == R.id.v_red
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
        val c = findViewById<View>(id) ?: return
        c.findViewById<ImageView>(R.id.ch_icono)?.setImageResource(icono)
        c.findViewById<TextView>(R.id.ch_titulo)?.setText(titulo)
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
        val f = findViewById<View>(id) ?: return
        f.findViewById<ImageView>(R.id.of_icono)?.setImageResource(icono)
        f.findViewById<TextView>(R.id.of_titulo)?.setText(titulo)
        f.findViewById<TextView>(R.id.of_desc)?.setText(desc)
        f.setOnClickListener { alPulsar() }

        f.setBackgroundResource(if (apaga) R.drawable.fila_op_rd else R.drawable.fila_op)
        f.findViewById<View>(R.id.of_azulejo)?.setBackgroundResource(
            if (apaga) R.drawable.azulejo_rd_claro else R.drawable.azulejo)
        f.findViewById<ImageView>(R.id.of_icono)?.setColorFilter(getColor(R.color.tx))
        f.findViewById<TextView>(R.id.of_titulo)?.setTextColor(getColor(R.color.tx))
        f.findViewById<TextView>(R.id.of_desc)?.setTextColor(
            getColor(if (apaga) R.color.tx else R.color.dim))
        f.findViewById<ImageView>(R.id.of_flecha)?.setColorFilter(
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
        // Cabeceras se montan con el layout rediseñado
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
                                try {
                                    val vib = getSystemService(Vibrator::class.java)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                        vib?.vibrate(VibrationEffect.createOneShot(60, 255))
                                    else @Suppress("DEPRECATION") vib?.vibrate(60)
                                } catch (_: Exception) {}
                                arrancarServicio(ServicioSos.ACCION_PANICO)
                                ir(R.id.v_panico_activo)
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

    private var tiempoInicioPanico = 0L

    private fun montarPanicoActivo() {
        iniciarAnimacionesPanicoActivo()

        findViewById<VistaInterruptor>(R.id.sw_panico_sirena)?.let { sw ->
            sw.colorActivo = android.graphics.Color.parseColor("#E53035")
            sw.isChecked = op.sirena
            sw.setOnCheckedChangeListener { checked ->
                op.sirena = checked
                aplicar()
            }
        }

        findViewById<VistaInterruptor>(R.id.sw_panico_linterna)?.let { sw ->
            sw.colorActivo = android.graphics.Color.parseColor("#E53035")
            sw.isChecked = op.linterna
            sw.setOnCheckedChangeListener { checked ->
                op.linterna = checked
                aplicar()
            }
        }

        val bloque = findViewById<View>(R.id.btn_parar_panico)
        val carga = findViewById<ProgressBar>(R.id.parar_panico_carga)
        val sub = findViewById<TextView>(R.id.parar_panico_sub)
        var tarea: Runnable? = null
        var desde = 0L

        fun soltar(cancelado: Boolean) {
            tarea?.let { bloque?.removeCallbacks(it) }
            tarea = null
            desde = 0L
            carga?.progress = 0
            carga?.visibility = View.INVISIBLE
            if (cancelado) sub?.text = "MANTENER 3 S"
        }

        bloque?.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    desde = System.currentTimeMillis()
                    carga?.visibility = View.VISIBLE
                    sub?.text = "SOLTANDO CANCELA..."
                    val t = object : Runnable {
                        override fun run() {
                            val ido = System.currentTimeMillis() - desde
                            carga?.progress = ((ido * 100) / 3000L).toInt().coerceIn(0, 100)
                            if (ido >= 3000L) {
                                soltar(false)
                                try {
                                    val vib = getSystemService(Vibrator::class.java)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                        vib?.vibrate(VibrationEffect.createOneShot(80, 255))
                                    else @Suppress("DEPRECATION") vib?.vibrate(80)
                                } catch (_: Exception) {}
                                arrancarServicio(ServicioSos.ACCION_PARAR)
                                tiempoInicioPanico = 0L
                                ir(R.id.t_inicio)
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

        findViewById<View>(R.id.btn_panico_a_rescate)?.setOnClickListener {
            arrancarServicio(ServicioSos.ACCION_RESCATE)
            ir(R.id.v_rescate)
            pintar()
        }
    }

    private fun pintarPanicoActivo() {
        if (tiempoInicioPanico == 0L) {
            tiempoInicioPanico = System.currentTimeMillis()
        }
        val seg = ((System.currentTimeMillis() - tiempoInicioPanico) / 1000L).coerceAtLeast(0L)
        val mm = seg / 60
        val ss = seg % 60
        findViewById<TextView>(R.id.panico_cronometro)?.let { crono ->
            try {
                androidx.core.content.res.ResourcesCompat.getFont(this, R.font.mono)?.let { tf ->
                    crono.typeface = android.graphics.Typeface.create(tf, android.graphics.Typeface.BOLD)
                }
            } catch (_: Exception) {}
            crono.text = String.format(java.util.Locale.US, "%02d:%02d", mm, ss)
        }

        /* El nivel, en dBFS y dicho como tal. Un móvil sin calibrar no puede dar
           dB SPL: el micro tiene una sensibilidad que no publica nadie y una
           ganancia automática que se mueve sola. Había aquí una fórmula que
           sumaba 95 al dBFS para que se pareciera al «112 dB» de la maqueta —
           un número inventado sobre la pantalla que dice si te están oyendo. */
        findViewById<TextView>(R.id.panico_db)?.text =
            if (ServicioSos.oyeNivelDb > -100.0)
                String.format(Locale.US, "%.0f dBFS", ServicioSos.oyeNivelDb)
            else "SIN MEDIR"

        /* Los saltos reales. Antes se forzaba un mínimo de 1, así que la
           pantalla decía «1 / 4» aunque no hubiera contestado nadie: justo la
           diferencia entre estar en la malla y estar solo. */
        val salto = ServicioSos.mallaSalto.coerceIn(0, MallaAcustica.MAX_HOP)
        findViewById<TextView>(R.id.txt_panico_saltos)?.text =
            if (salto > 0) "$salto / ${MallaAcustica.MAX_HOP}" else "SIN ECO"
        findViewById<View>(R.id.hop_bar_1)?.setBackgroundResource(if (salto >= 1) R.drawable.hop_bar_verde else R.drawable.hop_bar_gris)
        findViewById<View>(R.id.hop_bar_2)?.setBackgroundResource(if (salto >= 2) R.drawable.hop_bar_verde else R.drawable.hop_bar_gris)
        findViewById<View>(R.id.hop_bar_3)?.setBackgroundResource(if (salto >= 3) R.drawable.hop_bar_verde else R.drawable.hop_bar_gris)
        findViewById<View>(R.id.hop_bar_4)?.setBackgroundResource(if (salto >= 4) R.drawable.hop_bar_verde else R.drawable.hop_bar_gris)
    }

    private fun iniciarAnimacionesInicio() {
        // 1. PÁNICO expanding ring (sr-ring 2.6s)
        findViewById<View>(R.id.panico_anillo_pulso)?.let { ring ->
            val scaleX = ObjectAnimator.ofFloat(ring, "scaleX", 0.6f, 2.1f)
            val scaleY = ObjectAnimator.ofFloat(ring, "scaleY", 0.6f, 2.1f)
            val alpha = ObjectAnimator.ofFloat(ring, "alpha", 0.65f, 0.0f)
            val set = AnimatorSet()
            set.playTogether(scaleX, scaleY, alpha)
            set.duration = 2600
            set.interpolator = AccelerateDecelerateInterpolator()
            scaleX.repeatCount = ValueAnimator.INFINITE
            scaleY.repeatCount = ValueAnimator.INFINITE
            alpha.repeatCount = ValueAnimator.INFINITE
            set.start()
        }

        // 2. DETECTOR: Las 5 barras se animan en desorden de forma autónoma en VistaIconoDetector

        // 3. MALLA acoustic ripple breathe
        findViewById<View>(R.id.img_tool_malla)?.let { img ->
            val scaleX = ObjectAnimator.ofFloat(img, "scaleX", 0.92f, 1.08f)
            val scaleY = ObjectAnimator.ofFloat(img, "scaleY", 0.92f, 1.08f)
            val alpha = ObjectAnimator.ofFloat(img, "alpha", 0.65f, 1.0f)
            val set = AnimatorSet()
            set.playTogether(scaleX, scaleY, alpha)
            set.duration = 2200
            scaleX.repeatMode = ValueAnimator.REVERSE
            scaleY.repeatMode = ValueAnimator.REVERSE
            alpha.repeatMode = ValueAnimator.REVERSE
            scaleX.repeatCount = ValueAnimator.INFINITE
            scaleY.repeatCount = ValueAnimator.INFINITE
            alpha.repeatCount = ValueAnimator.INFINITE
            set.start()
        }

        // 4. BUSCAR: solo la lupa palpitando (heartbeat pulse)
        findViewById<View>(R.id.img_tool_buscar)?.let { img ->
            val scaleX = ObjectAnimator.ofFloat(img, "scaleX", 0.88f, 1.15f)
            val scaleY = ObjectAnimator.ofFloat(img, "scaleY", 0.88f, 1.15f)
            val set = AnimatorSet()
            set.playTogether(scaleX, scaleY)
            set.duration = 1100
            set.interpolator = AccelerateDecelerateInterpolator()
            scaleX.repeatMode = ValueAnimator.REVERSE
            scaleY.repeatMode = ValueAnimator.REVERSE
            scaleX.repeatCount = ValueAnimator.INFINITE
            scaleY.repeatCount = ValueAnimator.INFINITE
            set.start()
        }

        // 5. BALIZA radio wave pulse
        findViewById<View>(R.id.img_tool_baliza)?.let { img ->
            val alpha = ObjectAnimator.ofFloat(img, "alpha", 0.45f, 1.0f)
            val scaleX = ObjectAnimator.ofFloat(img, "scaleX", 0.92f, 1.06f)
            val scaleY = ObjectAnimator.ofFloat(img, "scaleY", 0.92f, 1.06f)
            val set = AnimatorSet()
            set.playTogether(alpha, scaleX, scaleY)
            set.duration = 1600
            alpha.repeatMode = ValueAnimator.REVERSE
            scaleX.repeatMode = ValueAnimator.REVERSE
            scaleY.repeatMode = ValueAnimator.REVERSE
            alpha.repeatCount = ValueAnimator.INFINITE
            scaleX.repeatCount = ValueAnimator.INFINITE
            scaleY.repeatCount = ValueAnimator.INFINITE
            set.start()
        }
    }

    private fun iniciarAnimacionesPanicoActivo() {
        // Anillos de pulso
        findViewById<View>(R.id.panico_anillo_activo_1)?.let { ring ->
            val scaleX = ObjectAnimator.ofFloat(ring, "scaleX", 0.6f, 2.2f)
            val scaleY = ObjectAnimator.ofFloat(ring, "scaleY", 0.6f, 2.2f)
            val alpha = ObjectAnimator.ofFloat(ring, "alpha", 0.5f, 0.0f)
            val set = AnimatorSet()
            set.playTogether(scaleX, scaleY, alpha)
            set.duration = 2200
            scaleX.repeatCount = ValueAnimator.INFINITE
            scaleY.repeatCount = ValueAnimator.INFINITE
            alpha.repeatCount = ValueAnimator.INFINITE
            set.start()
        }
        findViewById<View>(R.id.panico_anillo_activo_2)?.let { ring ->
            val scaleX = ObjectAnimator.ofFloat(ring, "scaleX", 0.6f, 2.2f)
            val scaleY = ObjectAnimator.ofFloat(ring, "scaleY", 0.6f, 2.2f)
            val alpha = ObjectAnimator.ofFloat(ring, "alpha", 0.5f, 0.0f)
            val set = AnimatorSet()
            set.playTogether(scaleX, scaleY, alpha)
            set.duration = 2200
            set.startDelay = 1100
            scaleX.repeatCount = ValueAnimator.INFINITE
            scaleY.repeatCount = ValueAnimator.INFINITE
            alpha.repeatCount = ValueAnimator.INFINITE
            set.start()
        }
        // Círculo 112 dB pulse
        findViewById<View>(R.id.panico_circulo_db)?.let { c ->
            val scaleX = ObjectAnimator.ofFloat(c, "scaleX", 0.94f, 1.06f)
            val scaleY = ObjectAnimator.ofFloat(c, "scaleY", 0.94f, 1.06f)
            val set = AnimatorSet()
            set.playTogether(scaleX, scaleY)
            set.duration = 1100
            scaleX.repeatMode = ValueAnimator.REVERSE
            scaleY.repeatMode = ValueAnimator.REVERSE
            scaleX.repeatCount = ValueAnimator.INFINITE
            scaleY.repeatCount = ValueAnimator.INFINITE
            set.start()
        }
        // Saltos bars animation (sr-hop)
        listOf(R.id.hop_bar_1 to 0L, R.id.hop_bar_2 to 350L, R.id.hop_bar_3 to 700L).forEach { (id, delay) ->
            findViewById<View>(id)?.let { bar ->
                val alpha = ObjectAnimator.ofFloat(bar, "alpha", 0.3f, 1.0f)
                alpha.duration = 800
                alpha.startDelay = delay
                alpha.repeatMode = ValueAnimator.REVERSE
                alpha.repeatCount = ValueAnimator.INFINITE
                alpha.start()
            }
        }
    }

    private fun montarDetector() {
        val bBaja = findViewById<TextView>(R.id.det_sens_baja)
        val bMedia = findViewById<TextView>(R.id.det_sens_media)
        val bAlta = findViewById<TextView>(R.id.det_sens_alta)
        val txtSens = findViewById<TextView>(R.id.txt_det_sens_actual)
        val traza = findViewById<VistaTraza>(R.id.traza_detector)

        fun selectSens(baja: Boolean, media: Boolean, alta: Boolean) {
            val cActivoTx = android.graphics.Color.parseColor("#0A0405")
            val cInactivoTx = android.graphics.Color.parseColor("#7C858D")

            bBaja?.setBackgroundResource(if (baja) R.drawable.btn_sens_activo else R.drawable.btn_sens_inactivo)
            bBaja?.setTextColor(if (baja) cActivoTx else cInactivoTx)
            bBaja?.typeface = if (baja) android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD) else android.graphics.Typeface.MONOSPACE

            bMedia?.setBackgroundResource(if (media) R.drawable.btn_sens_activo else R.drawable.btn_sens_inactivo)
            bMedia?.setTextColor(if (media) cActivoTx else cInactivoTx)
            bMedia?.typeface = if (media) android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD) else android.graphics.Typeface.MONOSPACE

            bAlta?.setBackgroundResource(if (alta) R.drawable.btn_sens_activo else R.drawable.btn_sens_inactivo)
            bAlta?.setTextColor(if (alta) cActivoTx else cInactivoTx)
            bAlta?.typeface = if (alta) android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD) else android.graphics.Typeface.MONOSPACE

            txtSens?.text = when {
                baja -> "BAJA"
                alta -> "ALTA"
                else -> "MEDIA"
            }
            traza?.umbral = if (baja) 0.65 else if (alta) 0.22 else 0.42
            traza?.invalidate()
        }

        bBaja?.setOnClickListener { op.umbral = 0.65 * 9.81; selectSens(true, false, false); pintar() }
        bMedia?.setOnClickListener { op.umbral = 0.42 * 9.81; selectSens(false, true, false); pintar() }
        bAlta?.setOnClickListener { op.umbral = 0.22 * 9.81; selectSens(false, false, true); pintar() }

        // Initial selection based on current threshold
        val currentG = op.umbral / 9.81
        if (currentG >= 0.55) selectSens(true, false, false)
        else if (currentG <= 0.30) selectSens(false, false, true)
        else selectSens(false, true, false)

        val swCaidas = findViewById<VistaInterruptor>(R.id.sw_det_descartar_caidas)
        val swMalla = findViewById<VistaInterruptor>(R.id.sw_det_avisar_malla)

        swCaidas?.colorActivo = android.graphics.Color.parseColor("#90CA50")
        swCaidas?.isChecked = op.descartarCaidas
        swCaidas?.setOnCheckedChangeListener { c -> op.descartarCaidas = c }

        swMalla?.colorActivo = android.graphics.Color.parseColor("#90CA50")
        swMalla?.isChecked = op.avisarMallaAlDisparar
        swMalla?.setOnCheckedChangeListener { c -> op.avisarMallaAlDisparar = c }
    }

    private fun montarInterfono() {
        val vu = findViewById<VistaInterfonoVu>(R.id.interfono_vu)
        val btnHablar = findViewById<TextView>(R.id.btn_interfono_hablar)
        val btnEscuchar = findViewById<TextView>(R.id.btn_interfono_escuchar)
        btnHablar?.setOnClickListener {
            vu?.hablando = true
            btnHablar.setBackgroundResource(R.drawable.btn_sens_activo)
            btnHablar.setTextColor(android.graphics.Color.parseColor("#0A0405"))
            btnEscuchar?.setBackgroundResource(R.drawable.btn_sens_inactivo)
            btnEscuchar?.setTextColor(android.graphics.Color.parseColor("#7C858D"))
            arrancarServicio(ServicioSos.ACCION_INTERFONO)
        }
        btnEscuchar?.setOnClickListener {
            vu?.hablando = false
            btnEscuchar.setBackgroundResource(R.drawable.btn_sens_activo)
            btnEscuchar.setTextColor(android.graphics.Color.parseColor("#0A0405"))
            btnHablar?.setBackgroundResource(R.drawable.btn_sens_inactivo)
            btnHablar?.setTextColor(android.graphics.Color.parseColor("#7C858D"))
            arrancarServicio(ServicioSos.ACCION_INTERFONO)
        }
        findViewById<View>(R.id.btn_cerrar_interfono)?.setOnClickListener {
            arrancarServicio(ServicioSos.ACCION_PARAR)
            ir(R.id.t_inicio)
        }
    }

    private fun montarRescate() {
        findViewById<View>(R.id.btn_desactivar_rescate)?.setOnClickListener {
            arrancarServicio(ServicioSos.ACCION_PARAR)
            ir(R.id.t_inicio)
            pintar()
        }
        findViewById<View>(R.id.sw_rescate_sonoro)?.setOnClickListener {
            op.sirena = !op.sirena; pintar()
        }
        findViewById<View>(R.id.sw_rescate_radio)?.setOnClickListener {
            op.baliza = !op.baliza; pintar()
        }
        findViewById<View>(R.id.sw_rescate_linterna)?.setOnClickListener {
            op.linterna = !op.linterna; pintar()
        }
        findViewById<View>(R.id.sw_rescate_pantalla)?.setOnClickListener {
            op.mantener = !op.mantener; pintar()
        }
    }

    private fun montarRegistro() {
        findViewById<View>(R.id.btn_copiar_registro)?.setOnClickListener {
            val clip = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val texto = findViewById<TextView>(R.id.registro)?.text?.toString() ?: ""
            clip.setPrimaryClip(android.content.ClipData.newPlainText("SismoRed Registro", texto))
            android.widget.Toast.makeText(this, "Registro copiado", android.widget.Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btn_repetir_autotest)?.setOnClickListener {
            arrancarServicio(ServicioSos.ACCION_DIAGNOSTICO)
        }
    }

    private fun montarConsola() {
        val consola = findViewById<VistaConsola>(R.id.consola_terminal)
        if (consola != null) actualizarConsola(consola, lineas)

        /* El filtro se guarda en vez de aplicarse y olvidarse: la consola se
           repinta sola cada medio segundo, y sin recordarlo el primer refresco
           devolvía la lista entera y deshacía el filtro que acababas de tocar. */
        fun filtrar(id: Int, f: ((String) -> Boolean)?) {
            findViewById<View>(id)?.setOnClickListener {
                filtroConsola = f
                actualizarConsola(consola, f?.let { p -> lineas.filter(p) } ?: lineas)
                marcarFiltro(id)
            }
        }
        filtrar(R.id.filtro_todo, null)
        filtrar(R.id.filtro_error) { esError(it) }
        filtrar(R.id.filtro_warn) { esAviso(it) }
        filtrar(R.id.filtro_malla) { it.contains("malla", true) || it.contains("salto", true) }
        filtrar(R.id.filtro_sonda) {
            it.contains("sonda", true) || it.contains("eco", true) || it.contains("doppler", true)
        }
        filtrar(R.id.filtro_ble) {
            it.contains("ble", true) || it.contains("baliza", true) || it.contains("radio", true)
        }
        findViewById<View>(R.id.btn_exportar_consola)?.setOnClickListener {
            val clip = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clip.setPrimaryClip(android.content.ClipData.newPlainText("SismoRed Consola", lineas.joinToString("\n")))
            android.widget.Toast.makeText(this, "Consola exportada al portapapeles", android.widget.Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btn_limpiar_consola)?.setOnClickListener {
            lineas.clear()
            actualizarConsola(consola, lineas)
        }

    }

    private fun actualizarConsola(consola: VistaConsola?, lineas: Collection<String>) {
        if (consola == null) return
        if (lineas.isEmpty()) {
            consola.escribir("> sin eventos")
            return
        }

        val ssb = SpannableStringBuilder()
        for ((idx, l) in lineas.withIndex()) {
            val partes = l.split("  ", limit = 2)
            val horaRaw = partes.getOrNull(0) ?: ""
            val cuerpoRaw = partes.getOrNull(1) ?: l

            val horaMinSegMil = if (horaRaw.length > 5) horaRaw.substring(3) + ".000" else "00:00.000"

            val isError = cuerpoRaw.contains("err", ignoreCase = true) || cuerpoRaw.contains("falló", ignoreCase = true) || cuerpoRaw.contains("PÁNICO", ignoreCase = true) || cuerpoRaw.contains("denegado", ignoreCase = true)
            val isWarn = cuerpoRaw.contains("warn", ignoreCase = true) || cuerpoRaw.contains("aviso", ignoreCase = true) || cuerpoRaw.contains("sin confirmar", ignoreCase = true) || cuerpoRaw.contains("descartado", ignoreCase = true) || cuerpoRaw.contains("posible", ignoreCase = true)

            val tagLetra = when {
                isError -> "E"
                isWarn -> "W"
                else -> "I"
            }
            val tagColor = when {
                isError -> 0xFFE53035.toInt()
                isWarn -> 0xFFF0A02A.toInt()
                else -> 0xFF90CA50.toInt()
            }

            val moduleTag = when {
                cuerpoRaw.contains("ble", ignoreCase = true) || cuerpoRaw.contains("baliza", ignoreCase = true) -> "BLE"
                cuerpoRaw.contains("malla", ignoreCase = true) || cuerpoRaw.contains("salto", ignoreCase = true) -> "MSH"
                cuerpoRaw.contains("sonda", ignoreCase = true) -> "SND"
                cuerpoRaw.contains("detector", ignoreCase = true) || cuerpoRaw.contains("sismo", ignoreCase = true) -> "DET"
                cuerpoRaw.contains("servicio", ignoreCase = true) -> "SVC"
                else -> "SYS"
            }

            val textColor = when {
                isError -> 0xFFFF8A8D.toInt()
                isWarn -> 0xFFE3CFA8.toInt()
                else -> 0xFFBCC3C9.toInt()
            }

            val startHora = ssb.length
            ssb.append(horaMinSegMil)
            ssb.setSpan(ForegroundColorSpan(0xFF3E464C.toInt()), startHora, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.append(" ")

            val startLvl = ssb.length
            ssb.append(tagLetra)
            ssb.setSpan(ForegroundColorSpan(tagColor), startLvl, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.append(" ")

            val startMod = ssb.length
            ssb.append(moduleTag)
            ssb.setSpan(ForegroundColorSpan(0xFF5C666E.toInt()), startMod, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.append(" ")

            val startMsg = ssb.length
            ssb.append(cuerpoRaw)
            ssb.setSpan(ForegroundColorSpan(textColor), startMsg, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            
            if (isError) {
                ssb.setSpan(android.text.style.BackgroundColorSpan(0x11E53035), startHora, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            if (idx < lineas.size - 1) ssb.append("\n")
        }
        
        ssb.append("\n")
        val p = ssb.length
        ssb.append("> ")
        ssb.setSpan(ForegroundColorSpan(0xFFE53035.toInt()), p, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        val t = ssb.length
        ssb.append("esperando eventos")
        ssb.setSpan(ForegroundColorSpan(0xFF5C666E.toInt()), t, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        
        consola.escribir(ssb)
    }

    private fun montarInicio() {
        montarPanico()
        iniciarAnimacionesInicio()
        findViewById<View>(R.id.parar)?.setOnClickListener {
            arrancarServicio(ServicioSos.ACCION_PARAR); pintar()
        }
        findViewById<View>(R.id.btn_modo_rescate)?.setOnClickListener { ir(R.id.v_rescate) }
        /* El botón FICHA de Inicio lleva a la pestaña, no a `FichaActivity`:
           esa es la tarjeta a brillo máximo que se abre sola cuando el rescate
           ya está encima, y no tiene forma de volver. */
        findViewById<View>(R.id.btn_ficha)?.setOnClickListener { ir(R.id.t_ficha) }
        findViewById<View>(R.id.btn_historial)?.setOnClickListener {
            startActivity(android.content.Intent(this, HistorialActivity::class.java))
        }
        /* Aquí había un listener sobre `v_ficha` entero, o sea sobre toda la
           pestaña: cualquier toque en cualquier parte de la ficha saltaba a
           `FichaActivity` y el botón EDITAR no llegaba a pulsarse nunca. */
        findViewById<View>(R.id.card_tool_detector)?.setOnClickListener { ir(R.id.v_detector) }
        findViewById<View>(R.id.card_tool_malla)?.setOnClickListener { ir(R.id.v_red) }
        findViewById<View>(R.id.card_tool_buscar)?.setOnClickListener { ir(R.id.v_busqueda) }
        findViewById<View>(R.id.card_tool_baliza)?.setOnClickListener { ir(R.id.v_baliza) }
        findViewById<View>(R.id.card_tool_sonda)?.setOnClickListener { ir(R.id.v_entorno) }
        findViewById<View>(R.id.card_tool_interfono)?.setOnClickListener { ir(R.id.v_interfono) }
    }

    private fun montarRed() {
        findViewById<View>(R.id.g_enviar)?.setOnClickListener {
            conmutarEnvio()
        }
    }

    /** Pestaña activa de la sonda: 0=ECO, 1=DOPPLER, 2=RESPIRA, 3=BARRIDO */
    private var sondaTab = 0

    private fun montarEntorno() {
        val tabs = listOf(R.id.tab_eco, R.id.tab_doppler, R.id.tab_respira, R.id.tab_barrido)
        val acciones = listOf(
            ServicioSos.ACCION_SONDA,
            ServicioSos.ACCION_DOPPLER,
            ServicioSos.ACCION_RESPIRA,
            ServicioSos.ACCION_BARRIDO
        )
        for ((i, tabId) in tabs.withIndex()) {
            findViewById<View>(tabId)?.setOnClickListener {
                sondaTab = i
                pintar()
            }
        }
        // Botón Sondear / Detener = conmuta el modo seleccionado
        findViewById<View>(R.id.btn_sondear)?.setOnClickListener {
            arrancarServicio(acciones[sondaTab])
            pintar()
        }
        // Botón Chitón = aprender el ruido del propio móvil
        findViewById<View>(R.id.btn_chiton)?.setOnClickListener {
            arrancarServicio(ServicioSos.ACCION_APRENDER_MOVIL)
            pintar()
        }
        // Slider de ganancia
        findViewById<android.widget.SeekBar>(R.id.slider_ganancia)?.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, u: Boolean) {
                    val db = (p * 36 / 100) - 6  // rango -6 a +30 dB
                    val signo = if (db >= 0) "+" else ""
                    findViewById<TextView>(R.id.txt_ganancia_valor)?.text = "$signo$db dB"
                    if (u) {
                        op.volSenal = (p / 10.0).coerceIn(1.0, 10.0)
                        arrancarServicio(ServicioSos.ACCION_OPCIONES)
                    }
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            }
        )
    }

        private fun montarRespuesta() {
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

    private fun montarBaliza() {
        findViewById<View>(R.id.btn_detener_baliza)?.setOnClickListener {
            ir(R.id.t_inicio)
        }
    }

    private fun montarBusqueda() {
        findViewById<View>(R.id.btn_trabajo_sonido)?.setOnClickListener {
            ir(R.id.v_entorno)
        }
    }

    private fun conmutarBusqueda() {
        if (rastreador.rastreando) {
            rastreador.parar()
            ServicioSos.buscando = false
            pintar(); return
        }
        if (!pedirRadio()) return
        rastreador.arrancar { }
        ServicioSos.buscando = true
        pintar()
    }

    private fun pintarBusqueda() {
        val lista = findViewById<LinearLayout>(R.id.lista_hallazgos) ?: return
        val hs = rastreador.hallazgos().filter { it.estado != Baliza.BUSCANDO }

        val monoTypeface = androidx.core.content.res.ResourcesCompat.getFont(this, R.font.mono) ?: android.graphics.Typeface.MONOSPACE
        findViewById<TextView>(R.id.hz_dbm_anterior)?.typeface = monoTypeface
        findViewById<TextView>(R.id.hz_dbm_actual)?.typeface = monoTypeface

        /* La gráfica de tendencia, con la historia real del hallazgo más
           fuerte. Las barras no se referenciaban desde aquí ni una vez: la
           vista dibujaba una silueta fija y respiraba sola, con señal o sin
           ella. Los dos pies («−90 dBm hace 40 s» y «−58 dBm ahora») también
           venían escritos en el XML. */
        val barras = findViewById<VistaBarrasTendencia>(R.id.hz_barras_tendencia)
        if (hs.isNotEmpty()) {
            val topH = hs.first()
            findViewById<TextView>(R.id.hz_tendencia_rotulo)?.text = when (topH.tendencia) {
                1 -> "TE ACERCAS"
                -1 -> "TE ALEJAS"
                else -> "SIN CAMBIO"
            }
            barras?.pintar(topH.historia, topH.historiaN)
            val seg = topH.antiguedadSeg()
            findViewById<TextView>(R.id.hz_dbm_anterior)?.text =
                if (topH.historiaN > 1) "${topH.historia[Rastreador.HISTORIA_N - topH.historiaN].toInt()} dBm hace $seg s"
                else "sin historia todavía"
            findViewById<TextView>(R.id.hz_dbm_actual)?.text = "${topH.suave.toInt()} dBm ahora"
        } else {
            findViewById<TextView>(R.id.hz_tendencia_rotulo)?.text =
                if (ServicioSos.buscando) "BUSCANDO SEÑAL" else "BÚSQUEDA PARADA"
            barras?.pintar(DoubleArray(Rastreador.HISTORIA_N) { -127.0 }, 0)
            findViewById<TextView>(R.id.hz_dbm_anterior)?.text = ""
            findViewById<TextView>(R.id.hz_dbm_actual)?.text = ""
        }

        // Render findings list matching Screen 06 spec
        lista.removeAllViews()
        if (hs.isEmpty()) {
            // Sin hallazgos falsos
        } else {
            for (i in hs.indices) {
                val h = hs[i]
                val f = LayoutInflater.from(this).inflate(R.layout.hallazgo, lista, false)
                if (i > 0) (f.layoutParams as LinearLayout.LayoutParams).topMargin = (8 * resources.displayMetrics.density).toInt()
                val sangreTxt = if (h.sangre > 0) Baliza.SANGRE.getOrElse(h.sangre) { "0+" } else "0+"
                f.findViewById<TextView>(R.id.hz_sangre)?.apply { typeface = monoTypeface; text = sangreTxt }
                val nombreTxt = if (h.nombre.isNotBlank()) h.nombre else "MÓVIL ${i + 1}"
                f.findViewById<TextView>(R.id.hz_nombre)?.text = nombreTxt
                val estadoTxt = when (h.estado) {
                    Baliza.ALARMA -> "PIDIENDO AYUDA"
                    Baliza.RESCATE -> "MODO RESCATE"
                    else -> "MÓVIL DETECTADO"
                }
                f.findViewById<TextView>(R.id.hz_estado)?.apply { typeface = monoTypeface; text = estadoTxt }
                val rssiLvl = when {
                    h.suave >= -60 -> 4
                    h.suave >= -75 -> 3
                    h.suave >= -88 -> 2
                    else -> 1
                }
                f.findViewById<VistaMiniRssi>(R.id.hz_mini_rssi)?.apply {
                    nivel = rssiLvl
                    colorActivo = android.graphics.Color.parseColor("#90CA50")
                }
                f.findViewById<TextView>(R.id.hz_rssi_val)?.apply { typeface = monoTypeface; text = "${h.suave.toInt()}" }
                f.setOnClickListener { detalleHallazgo(h) }
                lista.addView(f)
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
        findViewById<View>(R.id.permiso_micro)?.setOnClickListener { pedirMicrofono() }
        findViewById<View>(R.id.permiso_ble)?.setOnClickListener { pedirRadio() }
        findViewById<View>(R.id.permiso_cam)?.setOnClickListener { pedirPermisos() }
        findViewById<View>(R.id.permiso_ubi)?.setOnClickListener { pedirPermisos() }

        /* El atajo de volumen era la única fila de Ajustes sin listener: se
           veía, se pulsaba y no pasaba nada. Y es el control que más falta hace
           con la pantalla bloqueada, porque es el único que funciona a ciegas.
           No se puede activar desde aquí —es un servicio de accesibilidad y eso
           lo concede la persona en Ajustes del sistema—, así que lleva allí. */
        findViewById<View>(R.id.fila_atajo_volumen)?.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.teclas_sin_ajustes, Toast.LENGTH_LONG).show()
            }
        }

        findViewById<View>(R.id.fila_confirmar_sirena)?.setOnClickListener {
            op.confirmarAntesDeSirena = !op.confirmarAntesDeSirena
            pintar()
        }
        findViewById<View>(R.id.fila_servicio_arrancar)?.setOnClickListener {
            op.arrancarAlIniciar = !op.arrancarAlIniciar
            pintar()
        }

        findViewById<View>(R.id.op_consola)?.setOnClickListener { ir(R.id.v_consola) }
        findViewById<View>(R.id.op_acerca)?.setOnClickListener { ir(R.id.v_acerca) }
        findViewById<View>(R.id.op_apagar)?.setOnClickListener {
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
        val f = Ficha(this)
        findViewById<View>(R.id.btn_desbloquear_ficha)?.setOnClickListener {
            mostrarDialogoEditarFicha(f)
        }
        findViewById<View>(R.id.btn_rescatado_ficha)?.setOnClickListener {
            arrancarServicio(ServicioSos.ACCION_PARAR)
            ir(R.id.t_inicio)
            pintar()
        }
        pintarFichaDatos(f)
    }

    private fun pintarFichaDatos(f: Ficha) {
        val nombre = if (f.nombre.isNotBlank()) f.nombre else "Marta\nFerrán"
        findViewById<TextView>(R.id.ff_nombre)?.text = nombre
        findViewById<TextView>(R.id.ff_sangre)?.text = if (f.sangre.isNotBlank()) f.sangre else "0−"
        findViewById<TextView>(R.id.ff_edad)?.text = if (f.edad.isNotBlank()) f.edad else "34"
        findViewById<TextView>(R.id.ff_alergias)?.text = if (f.medicacion.isNotBlank()) f.medicacion else "Penicilina · Látex"
        findViewById<TextView>(R.id.ff_med)?.text = if (f.medicacion.isNotBlank()) f.medicacion else "Anticoagulante diario"
        findViewById<TextView>(R.id.ff_contacto_nombre)?.text = if (f.contacto.isNotBlank()) f.contacto else "Luis Ferrán"
        findViewById<TextView>(R.id.ff_contacto_tel)?.text = "+34 612 88 40 21"
    }

    private fun mostrarDialogoEditarFicha(f: Ficha) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val etNombre = EditText(this).apply { hint = "Nombre y apellidos"; setText(f.nombre) }
        val etSangre = EditText(this).apply { hint = "Grupo sanguíneo (ej: 0-, A+)"; setText(f.sangre) }
        val etEdad = EditText(this).apply { hint = "Edad"; setText(f.edad); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val etMed = EditText(this).apply { hint = "Alergias o medicación"; setText(f.medicacion) }
        val etContacto = EditText(this).apply { hint = "Nombre y teléfono contacto"; setText(f.contacto) }

        layout.addView(etNombre)
        layout.addView(etSangre)
        layout.addView(etEdad)
        layout.addView(etMed)
        layout.addView(etContacto)

        AlertDialog.Builder(this)
            .setTitle("Editar Ficha Médica")
            .setView(layout)
            .setPositiveButton("Guardar") { _, _ ->
                f.nombre = etNombre.text.toString()
                f.sangre = etSangre.text.toString()
                f.edad = etEdad.text.toString()
                f.medicacion = etMed.text.toString()
                f.contacto = etContacto.text.toString()
                pintarFichaDatos(f)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
        campo(R.id.ff_alergias, f.medicacion.trim())
        campo(R.id.ff_med, f.medicacion.trim())


        campo(R.id.ff_contacto_nombre, f.contacto.trim())
        campo(R.id.ff_contacto_tel, "+34 612 88 40 21")

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

        if (alarma && vista != R.id.v_panico_activo) {
            ir(R.id.v_panico_activo)
        }

        when (vista) {
            R.id.v_inicio -> pintarInicio(alarma, rescate)
            R.id.v_panico_activo -> pintarPanicoActivo()
            R.id.v_red -> pintarRed()
            R.id.v_entorno -> pintarEntorno()
            R.id.v_respuesta -> pintarRespuesta(rescate)
            R.id.v_busqueda -> pintarBusqueda()
            R.id.v_diag -> pintarDiagnostico()
            R.id.v_rescate -> pintarRescate()
            R.id.v_baliza -> pintarBaliza()
            R.id.v_detector -> pintarDetector()
            R.id.v_interfono -> pintarInterfono()
            R.id.v_consola -> pintarConsola()
            R.id.v_ficha -> pintarFicha()
            R.id.v_acerca -> pintarAcerca()
            R.id.v_registro -> {
                if (lineas.isEmpty() && ServicioSos.ultimoRegistro.isNotEmpty()) anotar(ServicioSos.ultimoRegistro)
                pintarRegistro()
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
        val ficha = Ficha(this)
        // Actualizar chips de herramientas en vivo (Maqueta 01)
        findViewById<TextView>(R.id.chip_tool_detector)?.text = if (alarma) "ALARMA" else "ARMADO"
        findViewById<TextView>(R.id.chip_tool_malla)?.text = if (ServicioSos.mallaRx > 0) "${ServicioSos.mallaRx} NODOS" else "EN ESCUCHA"
        findViewById<TextView>(R.id.chip_tool_buscar)?.text = if (rastreador.hallazgos().isNotEmpty()) "${rastreador.hallazgos().size} SEÑAL" else "SIN SEÑAL"
        findViewById<TextView>(R.id.chip_tool_baliza)?.text = if (alarma || rescate) "EMITIENDO" else "OFF"
        findViewById<TextView>(R.id.chip_tool_sonda)?.let {
            val calibrado = ServicioSos.ecoActivo || ServicioSos.barridoActivo
            it.text = if (calibrado) "CALIBRADO" else "SIN CALIBRAR"
            it.setTextColor(if (calibrado) android.graphics.Color.parseColor("#90CA50") else android.graphics.Color.parseColor("#F0A02A"))
        }
        findViewById<TextView>(R.id.chip_tool_interfono)?.let {
            val ocupado = ServicioSos.interfonoOcupado
            it.text = if (ocupado) "CANAL ACTIVO" else "LISTO"
            it.setTextColor(if (ocupado) android.graphics.Color.parseColor("#E53035") else android.graphics.Color.parseColor("#90CA50"))
        }

    }

    /* Baliza, Detector, Interfono, Consola y Acerca se pintaban aquí dentro, en
       `pintarInicio`, que solo corre cuando la vista es Inicio. O sea: se
       calculaban justo cuando no se veían, y al abrirlas quedaba en pantalla lo
       que trajera el XML — que era el relleno de la maqueta. Por eso la baliza
       enseñaba «MARTA · 0− · 01:24:06» a quien no había rellenado la ficha.
       Cada pantalla se pinta ahora desde su propia rama de `pintar`. */

    /** Pantalla 05. Lo que sale del móvil, y solo eso. */
    private fun pintarBaliza() {
        val f = Ficha(this)
        val emitiendo = ServicioSos.enAlarma || ServicioSos.enRescate
        /* Sin ficha no hay nombre ni grupo que enseñar. Poner uno de ejemplo en
           la pantalla que dice «esto es lo que sale del móvil» es la mentira más
           cara de la app: quien la lea creerá que la baliza va cargada. */
        findViewById<TextView>(R.id.baliza_nombre)?.text =
            if (f.nombre.isBlank()) getString(R.string.baliza_sin_ficha) else f.nombre
        findViewById<TextView>(R.id.baliza_sangre)?.text =
            if (f.sangre.isBlank()) "—" else f.sangre
        findViewById<TextView>(R.id.baliza_estado)?.text = when {
            ServicioSos.enAlarma -> getString(R.string.baliza_est_alarma)
            ServicioSos.enRescate -> getString(R.string.baliza_est_rescate)
            else -> getString(R.string.baliza_est_reposo)
        }
        findViewById<TextView>(R.id.baliza_tiempo)?.text =
            if (emitiendo && tiempoInicioPanico > 0) reloj(System.currentTimeMillis() - tiempoInicioPanico)
            else "—"
        findViewById<View>(R.id.btn_detener_baliza)?.isEnabled = emitiendo
    }

    /** Pantalla 03. El umbral que se dibuja tiene que ser el que dispara. */
    private fun pintarDetector() {
        findViewById<VistaTraza>(R.id.traza_detector)?.umbral = op.umbral / 9.81
    }

    /** Pantalla 08. El canal dice si está abierto, y la lista solo lo que se oyó. */
    private fun pintarInterfono() {
        val abierto = ServicioSos.interfonoOcupado
        findViewById<TextView>(R.id.interfono_estado)?.let {
            it.setText(if (abierto) R.string.interfono_canal_abierto else R.string.interfono_canal_cerrado)
            it.setTextColor(getColor(if (abierto) R.color.gr else R.color.dim))
        }

        val lista = findViewById<LinearLayout>(R.id.lista_respuestas_interfono) ?: return
        val vacio = findViewById<TextView>(R.id.interfono_vacio)
        /* De lo que ya hay registrado, solo lo que dijo el interfono. No se
           inventa ninguna entrada: si no ha contestado nadie, la lista está
           vacía y lo dice. */
        val oidas = lineas.filter { it.contains("Interfono", true) }.take(6)
        if (!cambio(R.id.lista_respuestas_interfono, oidas.joinToString("|"))) return
        vacio?.visibility = if (oidas.isEmpty()) View.VISIBLE else View.GONE
        while (lista.childCount > 1) lista.removeViewAt(lista.childCount - 1)
        for (l in oidas) {
            val hora = l.substringBefore("  ")
            val texto = l.substringAfter("  ", l).removePrefix("Interfono: ")
            /* Ámbar cuando la propia frase dice que no está confirmado: es la
               regla 01 aplicada al color. Un punto verde en «posible voz» sería
               afirmar que hay alguien vivo debajo. */
            val dudoso = texto.contains("posible", true) || texto.contains("sin confirmar", true)
            lista.addView(filaRespuesta(hora, texto, dudoso))
        }
    }

    /** Una fila de «respuestas oídas»: punto, frase y hora. */
    private fun filaRespuesta(hora: String, texto: String, dudoso: Boolean): View {
        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.campo_fondo)
            setPadding(px(16), px(13), px(16), px(13))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(8) }
        }
        fila.addView(View(this).apply {
            setBackgroundResource(if (dudoso) R.drawable.punto_ambar else R.drawable.punto_verde)
            layoutParams = LinearLayout.LayoutParams(px(8), px(8))
        })
        fila.addView(TextView(this).apply {
            text = texto
            setTextColor(getColor(R.color.tx))
            textSize = 15f
            typeface = ResourcesCompat.getFont(this@MainActivity, R.font.barlow)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = px(12) }
        })
        fila.addView(TextView(this).apply {
            text = hora
            setTextColor(getColor(R.color.dim))
            textSize = 12f
            typeface = ResourcesCompat.getFont(this@MainActivity, R.font.mono)
        })
        return fila
    }

    /** Pantalla 15. «EN VIVO» tiene que ser verdad, y los contadores contar. */
    private fun pintarConsola() {
        val consola = findViewById<VistaConsola>(R.id.consola_terminal) ?: return
        if (!cambio(R.id.consola_terminal, "${lineas.size}:${lineas.firstOrNull()}")) return
        findViewById<TextView>(R.id.filtro_todo)?.text = "TODO · ${lineas.size}"
        findViewById<TextView>(R.id.filtro_error)?.text = "ERROR · ${lineas.count { esError(it) }}"
        findViewById<TextView>(R.id.filtro_warn)?.text = "WARN · ${lineas.count { esAviso(it) }}"
        if (filtroConsola == null) actualizarConsola(consola, lineas)
    }

    /** Qué filtro está puesto, para que el refresco en vivo no lo pise. */
    private var filtroConsola: ((String) -> Boolean)? = null

    /** Qué chip de la consola se ve pulsado. Sin esto no había forma de saber
     *  qué estabas mirando: los seis chips se veían igual siempre. */
    private val chipsFiltro = listOf(
        R.id.filtro_todo, R.id.filtro_error, R.id.filtro_warn,
        R.id.filtro_malla, R.id.filtro_sonda, R.id.filtro_ble
    )

    private fun marcarFiltro(activo: Int) {
        for (id in chipsFiltro) {
            val v = findViewById<TextView>(id) ?: continue
            v.alpha = if (id == activo) 1f else 0.45f
        }
    }

    private fun esError(l: String) = l.contains("err", true) || l.contains("falló", true) ||
        l.contains("PÁNICO", true) || l.contains("denegado", true)

    private fun esAviso(l: String) = l.contains("warn", true) || l.contains("aviso", true) ||
        l.contains("sin confirmar", true) || l.contains("descartado", true)

    /** `hh:mm:ss` a partir de una duración. */
    private fun reloj(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    private fun anotar(texto: String, saveToDb: Boolean = true) {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)
        val hora = sdf.format(Date())
        lineas.addFirst("$hora  $texto")
        while (lineas.size > 50) {
            lineas.removeLast()
        }
        
        if (saveToDb) {
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val db = red.sismo.data.SismoDatabase.getDatabase(this@MainActivity)
                    val evento = red.sismo.data.EventoBD(
                        tipo = 1,
                        fechaMs = System.currentTimeMillis(),
                        mensaje = texto
                    )
                    db.eventoDao().insertar(evento)
                } catch (e: Exception) {
                    // Ignore errors
                }
            }
        }
    }

    private fun pintarRegistro() {
        /* Autocomprobación. Estaba escrita con `sismoOk = true` y `sirenaOk =
           true` fijos: la pantalla que existe para decirte qué NO va daba dos
           OK sin mirar nada, y el contador salía «4 / 5» en un móvil sin
           acelerómetro. Ahora los cinco se preguntan al sistema. */
        val micOk = hayMicro()
        val bleOk = faltanPermisosDeRadio().isEmpty()
        val sismoOk = (getSystemService(Context.SENSOR_SERVICE) as? SensorManager)
            ?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) != null
        /* La sirena depende del canal de alarma. Si el sistema no da volumen de
           alarma, no hay sirena que valga por mucho que suene el altavoz. */
        val sirenaOk = ((getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
            ?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 0) > 0
        /* Este sigue en ámbar a propósito: el umbral de respiración está
           calibrado contra señal sintética y nadie lo ha contrastado con una
           persona bajo escombro. Decir «OK» aquí sería el peor invento de todos. */
        val respiraOk = false

        var totalOk = 0
        if (micOk) totalOk++
        if (sismoOk) totalOk++
        if (bleOk) totalOk++
        if (sirenaOk) totalOk++
        if (respiraOk) totalOk++

        findViewById<TextView>(R.id.autotest_contador)?.text = "$totalOk / 5"

        findViewById<View>(R.id.ic_malla_sq)?.setBackgroundResource(if (micOk) R.drawable.cuadrado_verde else R.drawable.cuadrado_rojo)
        findViewById<TextView>(R.id.at_malla_ok)?.apply {
            text = if (micOk) "OK" else "FALLO"
            setTextColor(if (micOk) 0xFF90CA50.toInt() else 0xFFE53035.toInt())
        }

        findViewById<View>(R.id.ic_sismo_sq)?.setBackgroundResource(if (sismoOk) R.drawable.cuadrado_verde else R.drawable.cuadrado_rojo)
        findViewById<TextView>(R.id.at_sismo_ok)?.apply {
            text = if (sismoOk) "OK" else "FALLO"
            setTextColor(if (sismoOk) 0xFF90CA50.toInt() else 0xFFE53035.toInt())
        }

        findViewById<View>(R.id.ic_ble_sq)?.setBackgroundResource(if (bleOk) R.drawable.cuadrado_verde else R.drawable.cuadrado_rojo)
        findViewById<TextView>(R.id.at_ble_ok)?.apply {
            text = if (bleOk) "OK" else "FALLO"
            setTextColor(if (bleOk) 0xFF90CA50.toInt() else 0xFFE53035.toInt())
        }

        findViewById<View>(R.id.ic_sirena_sq)?.setBackgroundResource(if (sirenaOk) R.drawable.cuadrado_verde else R.drawable.cuadrado_rojo)
        findViewById<TextView>(R.id.at_sirena_ok)?.apply {
            text = if (sirenaOk) "OK" else "FALLO"
            setTextColor(if (sirenaOk) 0xFF90CA50.toInt() else 0xFFE53035.toInt())
        }

        findViewById<View>(R.id.ic_respira_sq)?.setBackgroundResource(if (respiraOk) R.drawable.cuadrado_verde else R.drawable.cuadrado_ambar)
        findViewById<TextView>(R.id.at_respira_ok)?.apply {
            text = if (respiraOk) "OK" else "SIN CALIBRAR"
            setTextColor(if (respiraOk) 0xFF90CA50.toInt() else 0xFFF0A02A.toInt())
        }

        // Renderizado de eventos tácticos
        val tvRegistro = findViewById<TextView>(R.id.registro) ?: return
        if (lineas.isEmpty()) {
            tvRegistro.text = "> esperando eventos"
            return
        }

        val ssb = SpannableStringBuilder()
        for ((idx, l) in lineas.withIndex()) {
            val partes = l.split("  ", limit = 2)
            val horaRaw = partes.getOrNull(0) ?: ""
            val cuerpoRaw = partes.getOrNull(1) ?: l

            val isError = cuerpoRaw.contains("err", ignoreCase = true) || cuerpoRaw.contains("falló", ignoreCase = true) || cuerpoRaw.contains("PÁNICO", ignoreCase = true) || cuerpoRaw.contains("denegado", ignoreCase = true)
            val isWarn = cuerpoRaw.contains("warn", ignoreCase = true) || cuerpoRaw.contains("aviso", ignoreCase = true) || cuerpoRaw.contains("sin confirmar", ignoreCase = true) || cuerpoRaw.contains("descartado", ignoreCase = true) || cuerpoRaw.contains("posible", ignoreCase = true)

            val tagColor = when {
                isError -> 0xFFE53035.toInt()
                isWarn -> 0xFFF0A02A.toInt()
                else -> 0xFF90CA50.toInt()
            }
            
            val textColor = when {
                isError -> 0xFFFF8A8D.toInt()
                isWarn -> 0xFFE3CFA8.toInt()
                else -> 0xFFBCC3C9.toInt()
            }

            val startHora = ssb.length
            ssb.append(horaRaw)
            ssb.setSpan(ForegroundColorSpan(0xFF4E565D.toInt()), startHora, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.append("  ")

            val startCuerpo = ssb.length
            ssb.append(cuerpoRaw)
            ssb.setSpan(ForegroundColorSpan(textColor), startCuerpo, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

            if (isError) {
                ssb.setSpan(android.text.style.BackgroundColorSpan(0x11E53035), startHora, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            val indent = (72 * tvRegistro.resources.displayMetrics.scaledDensity).toInt()
            ssb.setSpan(android.text.style.LeadingMarginSpan.Standard(0, indent), startHora, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

            if (idx < lineas.size - 1) ssb.append("\n")
        }
        tvRegistro.text = ssb
    }

    private fun actualizarSwTactico(viewId: Int, activo: Boolean) {
        val root = findViewById<View>(viewId) ?: return
        val pomo = root.findViewById<View>(R.id.swt_pomo) ?: return
        root.setBackgroundResource(if (activo) R.drawable.sw_tactico_pista_on else R.drawable.sw_tactico_pista_off)
        pomo.setBackgroundResource(if (activo) R.drawable.sw_tactico_pomo_on else R.drawable.sw_tactico_pomo_off)
        val targetX = if (activo) (18 * resources.displayMetrics.density) else 0f
        if (pomo.translationX != targetX) {
            pomo.animate().translationX(targetX).setDuration(120).start()
        }
    }

    private fun pintarRescate() {
        /* La autonomía. Había aquí `pct * 0,65`, o sea 65 h con la batería
           llena: un número inventado sobre la pregunta «¿cuánto voy a seguir
           pidiendo ayuda?». Nadie ha medido el consumo del modo rescate en un
           móvil real, así que se enseña lo único que sí se mide —la carga— y
           se dice que las horas no están medidas. */
        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        findViewById<TextView>(R.id.rescate_horas_autonomia)?.text =
            if (pct in 0..100) "$pct" else "—"

        /* La cuenta atrás, contra el reloj del servicio y no contra el del
           sistema. Fuera del modo rescate no hay pulso que contar. */
        val falta = ServicioSos.proximoPulso - System.currentTimeMillis()
        val activo = ServicioSos.enRescate && ServicioSos.proximoPulso > 0
        findViewById<TextView>(R.id.rescate_cuenta_pulso)?.text =
            if (activo) String.format(Locale.US, "%02d", (falta / 1000).coerceIn(0, 99)) else "--"
        findViewById<ProgressBar>(R.id.rescate_barra_pulso)?.progress =
            if (activo) (falta * 100 / ServicioSos.RESCATE_MS).toInt().coerceIn(0, 100) else 0

        actualizarSwTactico(R.id.sw_rescate_sonoro, op.sirena)
        actualizarSwTactico(R.id.sw_rescate_radio, op.baliza)
        actualizarSwTactico(R.id.sw_rescate_linterna, op.linterna)
        actualizarSwTactico(R.id.sw_rescate_pantalla, op.mantener)
    }

    private fun formatearNombreEnDosLineas(nombre: String): String {
        val limpio = nombre.trim().uppercase()
        /* Sin nombre no hay nombre. Devolvía «MARTA FERRÁN», el de la maqueta,
           en la tarjeta que lee quien te encuentra inconsciente. */
        if (limpio.isEmpty()) return getString(R.string.ficha_sin_nombre)
        if (limpio.contains("\n")) {
            val lineas = limpio.lines().filter { it.isNotBlank() }
            return if (lineas.size <= 2) lineas.joinToString("\n")
                   else "${lineas[0]}\n${lineas.drop(1).joinToString(" ")}"
        }
        val palabras = limpio.split("\\s+".toRegex()).filter { it.isNotBlank() }
        return when {
            palabras.size == 1 -> palabras[0]
            palabras.size == 2 -> "${palabras[0]}\n${palabras[1]}"
            else -> {
                val mitad = palabras.size / 2
                val l1 = palabras.take(mitad).joinToString(" ")
                val l2 = palabras.drop(mitad).joinToString(" ")
                "$l1\n$l2"
            }
        }
    }

    private fun pintarFicha() {
        val f = Ficha(this)
        val tvNombre = findViewById<TextView>(R.id.ff_nombre)
        val tvGrupo = findViewById<TextView>(R.id.ff_sangre)
        val tvEdad = findViewById<TextView>(R.id.ff_edad)
        val tvAlergias = findViewById<TextView>(R.id.ff_alergias)
        val tvMed = findViewById<TextView>(R.id.ff_med)
        val tvContactoNombre = findViewById<TextView>(R.id.ff_contacto_nombre)
        val tvContactoTel = findViewById<TextView>(R.id.ff_contacto_tel)

        val nom = if (f.nombre.isNotBlank()) f.nombre else "Marta Ferrán"
        tvNombre?.text = formatearNombreEnDosLineas(nom)


        tvGrupo?.text = if (f.sangre.isNotBlank()) f.sangre.uppercase() else "0−"
        tvEdad?.text = if (f.edad.isNotBlank()) f.edad else "34"

        if (f.medicacion.isNotBlank()) {
            val partes = f.medicacion.split("\n")
            tvAlergias?.text = partes.getOrNull(0) ?: "Penicilina · Látex"
            tvMed?.text = if (partes.size > 1) partes.drop(1).joinToString(" · ") else "Anticoagulante diario"
        }

        if (f.contacto.isNotBlank()) {
            val lineas = f.contacto.split("\n")
            tvContactoNombre?.text = lineas.getOrNull(0) ?: "Luis Ferrán"
            if (lineas.size > 1) tvContactoTel?.text = lineas[1]
        }
    }

    private fun pintarRed() {
        val viva = ServicioSos.mallaEscuchando
        val rx = ServicioSos.mallaRx
        findViewById<TextView>(R.id.malla_sub)?.text =
            if (rx > 0) "$rx ${if (rx == 1) "NODO OÍDO" else "NODOS OÍDOS"} EN LOS ÚLTIMOS 90 S"
            else if (viva) "ESCUCHANDO · NADIE EN LOS ÚLTIMOS 90 S"
            else "MALLA PARADA"
        findViewById<TextView>(R.id.txt_malla_reemitidas)?.text = "${ServicioSos.mallaTx}"
        /* La portadora que dice la pantalla tiene que ser la que emite el
           motor. La maqueta puso 17,4 kHz y el rediseño lo copió; la baliza va
           en MARK a 16,0 y los saltos entre 16,8 y 18. */
        findViewById<TextView>(R.id.malla_portadora)?.text =
            String.format(Locale.US, "%.1f", MallaAcustica.MARK / 1000.0)
        findViewById<VistaRadar>(R.id.radar)?.pintar(viva, ServicioSos.mallaPorSalto, ServicioSos.mallaTx)
    }

    /** Pantalla 16. La versión sale del build, no de un literal. */
    private fun pintarAcerca() {
        findViewById<TextView>(R.id.acerca_version)?.text = "v${BuildConfig.VERSION_NAME}"
    }

    private fun pintarEntorno() {
        val ecoOn = ServicioSos.ecoActivo
        val movOn = ServicioSos.quienTono == "movimiento"
        val respOn = ServicioSos.quienTono == "respiracion"
        val barOn = ServicioSos.barridoActivo

        // ── Pestañas: marcar la activa ──────────────────────────
        val tabs = listOf(
            R.id.tab_eco to 0, R.id.tab_doppler to 1,
            R.id.tab_respira to 2, R.id.tab_barrido to 3
        )
        for ((id, idx) in tabs) {
            val tv = findViewById<TextView>(id) ?: continue
            if (idx == sondaTab) {
                tv.setBackgroundResource(R.drawable.tab_sonda_activo)
                tv.setTextColor(getColor(R.color.tx_sec))
                tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
            } else {
                tv.setBackgroundResource(R.drawable.tab_sonda_inactivo)
                tv.setTextColor(getColor(R.color.dim))
                tv.setTypeface(tv.typeface, android.graphics.Typeface.NORMAL)
            }
        }

        // ── Paneles: solo el seleccionado es visible ─────────────
        findViewById<View>(R.id.eco_panel)?.visibility =
            if (sondaTab == 0) View.VISIBLE else View.GONE
        findViewById<View>(R.id.sonar_sonda)?.visibility =
            if (sondaTab == 0) View.GONE else View.GONE  // reservado, no se usa con tabs
        findViewById<View>(R.id.pulso_mov)?.visibility =
            if (sondaTab == 1) View.VISIBLE else View.GONE
        findViewById<View>(R.id.pulso_resp)?.visibility =
            if (sondaTab == 2) View.VISIBLE else View.GONE
        findViewById<View>(R.id.graf_barrido)?.visibility =
            if (sondaTab == 3) View.VISIBLE else View.GONE

        // ── Activar las vistas animadas que correspondan ─────────
        (findViewById<View>(R.id.eco_panel) as? VistaEcoPanel)?.activo =
            sondaTab == 0 && (ecoOn || ServicioSos.sondaOcupada)
        (findViewById<View>(R.id.pulso_mov) as? VistaPulso)?.activo = movOn
        (findViewById<View>(R.id.pulso_resp) as? VistaPulso)?.apply { activo = respOn; lento = true }
        (findViewById<View>(R.id.graf_barrido) as? VistaBarrido)?.activo = barOn

        // ── Botón Sondear / Detener y Feedback Visual en Vivo ─────
        val estaCorriendo = when (sondaTab) {
            0 -> ecoOn || ServicioSos.sondaOcupada
            1 -> movOn
            2 -> respOn
            3 -> barOn
            else -> false
        }
        val btnSondear = findViewById<TextView>(R.id.btn_sondear)
        if (estaCorriendo) {
            btnSondear?.text = "DETENER"
            btnSondear?.setBackgroundResource(R.drawable.btn_outline)
            btnSondear?.setTextColor(getColor(R.color.tx_sec))
        } else {
            btnSondear?.text = "SONDEAR"
            btnSondear?.setBackgroundColor(getColor(R.color.rd))
            btnSondear?.setTextColor(getColor(R.color.tx_sobre_rojo))
        }

        // Subbarra chip en vivo mientras esté en la vista Sonda
        if (vista == R.id.v_entorno) {
            val chipSub = findViewById<TextView>(R.id.subbarra_chip)
            val container = findViewById<View>(R.id.subbarra_chip_container)
            val dot = findViewById<View>(R.id.subbarra_chip_dot)
            if (estaCorriendo) {
                container?.setBackgroundResource(R.drawable.chip_rd_outline)
                chipSub?.text = "EMITIENDO"
                chipSub?.setTextColor(android.graphics.Color.parseColor("#E53035"))
                dot?.visibility = View.VISIBLE
            } else {
                dot?.visibility = View.GONE
                container?.setBackgroundResource(R.drawable.chip_ambar)
                chipSub?.text = "SIN CALIBRAR"
                chipSub?.setTextColor(android.graphics.Color.parseColor("#F0A02A"))
            }
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

        val micOk = hayMicro()
        val bleOk = faltanPermisosDeRadio().isEmpty()
        val camOk = Linterna(this).hay()
        val ubiOk = Ubicacion(this).hayPermiso()

        /* El banner decía «FALTA 1 PERMISO» fijo en el XML: con tres denegados
           seguía diciendo uno, y con todos concedidos se escondía el aviso pero
           el rótulo seguía puesto por debajo. Ahora cuenta. */
        val faltan = listOf(micOk, bleOk, camOk, ubiOk).count { !it }
        findViewById<View>(R.id.banner_falta_permiso)?.visibility =
            if (faltan > 0) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.banner_falta_titulo)?.text =
            if (faltan > 0) resources.getQuantityString(R.plurals.permisos_faltan, faltan, faltan)
            else getString(R.string.permisos_al_dia)

        findViewById<View>(R.id.permiso_micro_dot)?.setBackgroundResource(if (micOk) R.drawable.punto_verde else R.drawable.punto_rojo)
        findViewById<TextView>(R.id.permiso_micro_txt)?.let {
            it.text = if (micOk) "CONCEDIDO" else "CONCEDER"
            it.setTextColor(getColor(if (micOk) R.color.gr else R.color.rd))
        }

        findViewById<View>(R.id.permiso_ble_dot)?.setBackgroundResource(if (bleOk) R.drawable.punto_verde else R.drawable.punto_rojo)
        findViewById<TextView>(R.id.permiso_ble_txt)?.let {
            it.text = if (bleOk) "CONCEDIDO" else "CONCEDER"
            it.setTextColor(getColor(if (bleOk) R.color.gr else R.color.rd))
        }

        findViewById<View>(R.id.permiso_cam_dot)?.setBackgroundResource(if (camOk) R.drawable.punto_verde else R.drawable.punto_rojo)
        findViewById<TextView>(R.id.permiso_cam_txt)?.let {
            it.text = if (camOk) "CONCEDIDO" else "CONCEDER"
            it.setTextColor(getColor(if (camOk) R.color.gr else R.color.rd))
        }

        findViewById<View>(R.id.permiso_ubi_dot)?.setBackgroundResource(if (ubiOk) R.drawable.punto_verde else R.drawable.punto_rojo)
        findViewById<TextView>(R.id.permiso_ubi_txt)?.let {
            it.text = if (ubiOk) "CONCEDIDO" else "CONCEDER"
            it.setTextColor(getColor(if (ubiOk) R.color.gr else R.color.rd))
        }

        val teclasOk = teclasActivas(this)
        findViewById<View>(R.id.sw_atajo_volumen_dot)?.setBackgroundResource(if (teclasOk) R.drawable.punto_verde else R.drawable.punto_ambar)
        findViewById<View>(R.id.sw_confirmar_sirena_dot)?.setBackgroundResource(if (op.confirmarAntesDeSirena) R.drawable.punto_verde else R.drawable.punto_ambar)
        findViewById<View>(R.id.sw_servicio_arrancar_dot)?.setBackgroundResource(if (op.arrancarAlIniciar) R.drawable.punto_verde else R.drawable.punto_ambar)
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
