package red.sismo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.util.Log

/**
 * La última posición conocida, y **solo** eso.
 *
 * Antes de quedar atrapado el móvil suele saber dónde estaba: el mapa que abriste
 * al mediodía, la app del tiempo, cualquier cosa. Ese dato reduce el área de
 * búsqueda de un barrio a un portal, y no cuesta nada tenerlo.
 *
 * **Aquí no se enciende el GPS nunca.** No hay `requestLocationUpdates`, ni una
 * sola vez: se lee `getLastKnownLocation`, que devuelve un arreglo que ya hizo
 * otro programa. Coste de batería cero y ningún seguimiento — SismoRed no sabe
 * por dónde has pasado, solo dónde estaba el móvil la última vez que alguien
 * miró. Si nadie miró, no hay dato, y eso también se dice.
 *
 * Esto cambia una promesa de la app, así que va escrito donde el usuario lo lee
 * —Diagnóstico y el paso 3 de la ficha— y sale del móvil con la misma regla que
 * la ficha: solo con la alarma o el rescate activos.
 *
 * Se guarda en disco a propósito. Si el móvil se reinicia bajo los escombros, el
 * sistema pierde su última posición conocida y esta se queda.
 */
class Ubicacion(private val ctx: Context) {

    companion object {
        private const val TAG = "SismoRed"
        private const val P = "sismored_ubi"
        /** Más viejo que esto ya no acota nada: en un día se anda kilómetros. */
        const val CADUCA_MS = 24 * 3600_000L
    }

    private val prefs = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)

    fun hayPermiso(): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Mirar si el sistema tiene algo más fresco que lo guardado, y quedarse con
     * lo mejor. Se llama en los momentos en que importa —al arrancar y cuando
     * pasa algo—, no en un bucle: leer esto no cuesta nada, pero llamarlo cada
     * segundo tampoco aporta nada.
     */
    fun refrescar() {
        if (!hayPermiso()) return
        try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var mejor: android.location.Location? = null
            for (prov in lm.getProviders(true)) {
                val l = try { lm.getLastKnownLocation(prov) } catch (_: SecurityException) { null }
                    ?: continue
                if (mejor == null || l.time > mejor!!.time) mejor = l
            }
            val l = mejor ?: return
            if (l.time <= prefs.getLong("t", 0L)) return
            prefs.edit()
                .putFloat("lat", l.latitude.toFloat())
                .putFloat("lon", l.longitude.toFloat())
                .putFloat("err", if (l.hasAccuracy()) l.accuracy else -1f)
                .putLong("t", l.time)
                .apply()
            Log.i(TAG, "ubicación: última conocida de ${l.provider} hace ${(System.currentTimeMillis() - l.time) / 60000} min")
        } catch (e: Exception) {
            Log.w(TAG, "ubicación: ${e.message}")
        }
    }

    fun hay(): Boolean = prefs.getLong("t", 0L) > 0
    fun cuando(): Long = prefs.getLong("t", 0L)
    fun lat(): Double = prefs.getFloat("lat", 0f).toDouble()
    fun lon(): Double = prefs.getFloat("lon", 0f).toDouble()
    fun error(): Float = prefs.getFloat("err", -1f)

    /** Cuánto hace, en palabras. Sin la antigüedad el dato engaña: una posición
     *  de ayer manda a cavar donde estuviste ayer. */
    fun antiguedad(): String {
        if (!hay()) return "no hay ninguna"
        val m = (System.currentTimeMillis() - cuando()) / 60000
        return when {
            m < 1 -> "hace un momento"
            m < 60 -> "hace $m min"
            m < 48 * 60 -> "hace ${m / 60} h"
            else -> "hace ${m / 1440} días"
        }
    }

    /** Para la pantalla: dónde y de cuándo, o por qué no hay nada. */
    fun resumen(): String = when {
        !hayPermiso() -> "sin permiso"
        !hay() -> "ninguna todavía"
        System.currentTimeMillis() - cuando() > CADUCA_MS -> "demasiado vieja (${antiguedad()})"
        else -> "%.5f, %.5f · %s%s".format(
            lat(), lon(), antiguedad(),
            if (error() > 0) " · ±%.0f m".format(error()) else ""
        )
    }

    /** Lo que viaja, y solo con la alarma o el rescate activos. Caducada no
     *  viaja: es peor un dato viejo que ninguno, porque el de ayer se cree. */
    fun paraEnviar(): Triple<Double, Double, Long>? {
        if (!hay() || System.currentTimeMillis() - cuando() > CADUCA_MS) return null
        return Triple(lat(), lon(), cuando())
    }
}
