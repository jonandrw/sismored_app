package red.sismo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Avisa de que hay una versión nueva. Nada más.
 *
 * No descarga ni instala: enseña una notificación y un aviso en Inicio, y al
 * tocarlos abre la descarga en el navegador. Instalar un APK a espaldas de nadie es justo lo que
 * esta app no puede permitirse hacer.
 *
 * Pregunta a dos sitios, en este orden:
 *
 *  1. **El servidor del proyecto**, si [SITIO] tiene algo. Es el que sabe el
 *     sha256 y el tamaño exactos porque los calcula del fichero.
 *  2. **GitHub Releases**, que no necesita dominio ni alojamiento y es la ruta
 *     de publicación que el proyecto ya tenía decidida.
 *
 * Con los dos caídos no pasa nada: esto es un extra, y una app de emergencia no
 * puede depender de que un servidor conteste.
 */
object Actualizacion {

    /**
     * El servidor propio. Es un fichero estático servido por Cloudflare
     * Pages, no un proceso: no hay nada que se pueda caer. Si aun así no
     * contesta, se pregunta a GitHub.
     */
    private const val SITIO = "https://sismored.app"

    private const val GITHUB =
        "https://api.github.com/repos/jonandrw/sismored_app/releases/latest"

    private const val CANAL = "sismored_actualizacion"
    private const val ID_NOTIF = 16
    private const val CADA_MS = 24 * 60 * 60 * 1000L
    private const val TAG = "SismoRed"

    /** Lo que se le enseña a quien lo tiene instalado. */
    data class Version(val nombre: String, val codigo: Int, val url: String, val notas: String = "")

    /**
     * La versión nueva que se encontró y aún no está instalada, o null.
     *
     * La lee Inicio para enseñar el aviso dentro de la app: la notificación
     * se quita con un dedo y no vuelve hasta el día siguiente.
     */
    fun pendiente(ctx: Context): Version? {
        if (BuildConfig.DEBUG || BuildConfig.FLAVOR == "play") return null
        val p = ctx.getSharedPreferences("sismored", Context.MODE_PRIVATE)
        val v = Version(
            p.getString("nueva_nombre", "") ?: "", p.getInt("nueva_codigo", 0),
            p.getString("nueva_url", "") ?: "", p.getString("nueva_notas", "") ?: ""
        )
        if (v.url.isBlank() || !esNueva(ctx, v)) return null
        return v
    }

    private fun esNueva(ctx: Context, v: Version): Boolean {
        val mia = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).let { pi ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode.toInt()
                else @Suppress("DEPRECATION") pi.versionCode
            }
        } catch (_: Exception) { return false }
        /* Con `codigo` a 0 el origen no sabía el versionCode —GitHub solo da
           la etiqueta—, así que se compara el nombre. Peor, pero es lo que
           hay, y como mucho avisa de una versión que ya tienes. */
        return if (v.codigo > 0) v.codigo > mia
               else v.nombre.isNotBlank() && v.nombre != nombreInstalado(ctx)
    }

    /**
     * Mira si hay algo más nuevo, como mucho una vez al día.
     *
     * La llaman dos sitios: [MainActivity] al abrir, y el [WatchdogReceiver]
     * en su pulso de 15 minutos. Lo segundo es lo que hace que se entere quien
     * instala la app y no vuelve a abrirla en meses, que es el caso normal de
     * una app de emergencia. Los dos pasan por la misma puerta de 24 h, así que
     * los pulsos de más no cuestan nada.
     */
    fun comprobar(ctx: Context, alEncontrar: (() -> Unit)? = null) {
        /* En Play actualiza la tienda, y sus normas no dejan que una app se
           actualice por otra vía: mandar a descargar un APK desde aquí es
           motivo de retirada. */
        if (BuildConfig.FLAVOR == "play") return
        /* Ni en depuración: esos móviles se actualizan por adb, y el APK que
           se ofrece va firmado con otra clave y no se instala encima. */
        if (BuildConfig.DEBUG) return
        val p = ctx.getSharedPreferences("sismored", Context.MODE_PRIVATE)
        val ahora = System.currentTimeMillis()
        if (ahora - p.getLong("ultimaComprobacionUpdate", 0L) < CADA_MS) return

        /* Sin red no se gasta el turno del día. Importa desde que esto lo
           despierta el watchdog: un pulso de madrugada con el wifi apagado
           marcaría la comprobación como hecha y no volvería a mirar hasta el
           día siguiente. */
        if (!hayRed(ctx)) return

        p.edit().putLong("ultimaComprobacionUpdate", ahora).apply()

        thread(name = "actualizacion", isDaemon = true) {
            val v = try { delSitio() } catch (e: Exception) {
                Log.d(TAG, "actualizacion: el sitio no contesta (${e.message})"); null
            } ?: try { deGithub() } catch (e: Exception) {
                Log.d(TAG, "actualizacion: GitHub no contesta (${e.message})"); null
            } ?: return@thread

            if (!esNueva(ctx, v)) return@thread
            p.edit()
                .putString("nueva_nombre", v.nombre).putInt("nueva_codigo", v.codigo)
                .putString("nueva_url", v.url).putString("nueva_notas", v.notas)
                .apply()
            avisar(ctx, v)
            alEncontrar?.invoke()
        }
    }

    private fun hayRed(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val cap = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun nombreInstalado(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (_: Exception) { "" }

    private fun delSitio(): Version? {
        if (SITIO.isBlank()) return null
        val o = JSONObject(leer("$SITIO/api/version.json"))
        if (!o.optBoolean("publicada", false)) return null
        val url = o.optString("url", "")
        return Version(
            o.optString("versionName", ""),
            o.optInt("versionCode", 0),
            if (url.startsWith("http")) url else SITIO + url,
            o.optString("notas", "")
        )
    }

    private fun deGithub(): Version? {
        val o = JSONObject(leer(GITHUB))
        val etiqueta = o.optString("tag_name", "").removePrefix("v")
        if (etiqueta.isBlank()) return null
        /* El APK del release si lo hay; si no, la página del release, que
           siempre existe y desde ahí se descarga a mano. */
        val assets = o.optJSONArray("assets")
        var url = o.optString("html_url", "")
        if (assets != null) for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name", "").endsWith(".apk")) {
                url = a.optString("browser_download_url", url); break
            }
        }
        return Version(etiqueta, 0, url, o.optString("body", ""))
    }

    private fun leer(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("Accept", "application/json")
            // GitHub rechaza las peticiones sin User-Agent
            c.setRequestProperty("User-Agent", "SismoRed")
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun avisar(ctx: Context, v: Version) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                nm.getNotificationChannel(CANAL) == null) {
                nm.createNotificationChannel(NotificationChannel(
                    CANAL, "Actualizaciones", NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) })
            }
            val abrir = PendingIntent.getActivity(
                ctx, 16, Intent(Intent.ACTION_VIEW, Uri.parse(v.url)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            nm.notify(ID_NOTIF, Notification.Builder(ctx, CANAL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Hay una versión nueva: ${v.nombre}")
                .setContentText("Toca para descargarla")
                .setAutoCancel(true)
                .setContentIntent(abrir)
                .build())
        } catch (e: Exception) {
            Log.e(TAG, "actualizacion: no se pudo avisar", e)
        }
    }
}
