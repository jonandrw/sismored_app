package red.sismo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
 * No descarga ni instala: enseña una notificación y, al tocarla, abre la
 * descarga en el navegador. Instalar un APK a espaldas de nadie es justo lo que
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
     * El servidor propio. **Vacío mientras no haya dominio**, y entonces se
     * pregunta solo a GitHub. El día que lo haya, se rellena aquí y ya está.
     */
    private const val SITIO = ""

    private const val GITHUB =
        "https://api.github.com/repos/jonandrw/sismored_app/releases/latest"

    private const val CANAL = "sismored_actualizacion"
    private const val ID_NOTIF = 16
    private const val CADA_MS = 24 * 60 * 60 * 1000L
    private const val TAG = "SismoRed"

    /** Lo que se le enseña a quien lo tiene instalado. */
    private data class Version(val nombre: String, val codigo: Int, val url: String)

    /**
     * Mira si hay algo más nuevo, como mucho una vez al día.
     *
     * Se llama al abrir la app, no desde el servicio: comprobar
     * actualizaciones no tiene nada que ver con vigilar un terremoto, y el
     * servicio no debe gastar red en esto.
     */
    fun comprobar(ctx: Context) {
        val p = ctx.getSharedPreferences("sismored", Context.MODE_PRIVATE)
        val ahora = System.currentTimeMillis()
        if (ahora - p.getLong("ultimaComprobacionUpdate", 0L) < CADA_MS) return
        p.edit().putLong("ultimaComprobacionUpdate", ahora).apply()

        thread(name = "actualizacion", isDaemon = true) {
            val v = try { delSitio() } catch (e: Exception) {
                Log.d(TAG, "actualizacion: el sitio no contesta (${e.message})"); null
            } ?: try { deGithub() } catch (e: Exception) {
                Log.d(TAG, "actualizacion: GitHub no contesta (${e.message})"); null
            } ?: return@thread

            val mia = try {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).let { pi ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode.toInt()
                    else @Suppress("DEPRECATION") pi.versionCode
                }
            } catch (_: Exception) { return@thread }

            /* Con `codigo` a 0 el origen no sabía el versionCode —GitHub solo da
               la etiqueta—, así que se compara el nombre. Peor, pero es lo que
               hay, y como mucho avisa de una versión que ya tienes. */
            val hayNueva = if (v.codigo > 0) v.codigo > mia
                           else v.nombre.isNotBlank() && v.nombre != nombreInstalado(ctx)
            if (hayNueva) avisar(ctx, v)
        }
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
            if (url.startsWith("http")) url else SITIO + url
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
        return Version(etiqueta, 0, url)
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
