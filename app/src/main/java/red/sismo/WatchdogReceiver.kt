package red.sismo

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Watchdog de supervivencia para ServicioSos contra OEM killers (Xiaomi HyperOS OneKeyClean, Samsung Sleeping Apps, etc.).
 *
 * Se programa periódicamente con AlarmManager.setExactAndAllowWhileIdle() (o setAndAllowWhileIdle()).
 * Las alarmas de AlarmManager residen en system_server y sobreviven al cierre de la aplicación
 * desde la pantalla de recientes ("swipe away").
 *
 * Si el usuario configuró que la app debe vigilar (!opciones.apagada && opciones.deberiaVigilar)
 * y el servicio no está activo o su latido expiró (> 60s), el receptor lo resucita con startForegroundService.
 */
class WatchdogReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SismoRed-Watchdog"
        private const val REQUEST_CODE = 9911
        const val INTERVALO_MS = 15 * 60_000L // 15 minutos

        fun programar(context: Context, delayMs: Long = INTERVALO_MS) {
            val op = Opciones(context)
            if (op.apagada || !op.deberiaVigilar) return

            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, WatchdogReceiver::class.java).apply {
                action = "red.sismo.WATCHDOG_CHECK"
            }
            val pi = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val triggerAt = SystemClock.elapsedRealtime() + delayMs
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (am.canScheduleExactAlarms()) {
                        am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                    } else {
                        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                } else {
                    am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                }
                Log.d(TAG, "Watchdog programado para dentro de ${delayMs / 1000}s")
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo programar alarma exacta para watchdog: ${e.message}")
                try {
                    am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                } catch (_: Exception) {}
            }
        }

        /** ¿Tiene el sistema alguna razón para dejarnos levantar el servicio? */
        fun puedeResucitar(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                ?: return false
            return pm.isIgnoringBatteryOptimizations(context.packageName)
        }

        fun cancelar(context: Context) {
            try {
                val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                val intent = Intent(context, WatchdogReceiver::class.java).apply {
                    action = "red.sismo.WATCHDOG_CHECK"
                }
                val pi = PendingIntent.getBroadcast(
                    context,
                    REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                if (pi != null) {
                    am.cancel(pi)
                    pi.cancel()
                    Log.d(TAG, "Watchdog cancelado")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error cancelando watchdog", e)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val op = Opciones(context)
        if (op.apagada || !op.deberiaVigilar) {
            cancelar(context)
            return
        }

        val ahora = System.currentTimeMillis()
        val latido = op.latido
        val hueco = ahora - latido
        val servicioMuerto = !ServicioSos.vivo || (latido > 0L && hueco > 60_000L)

        if (servicioMuerto) {
            /* Desde Android 12 no se puede arrancar un servicio en primer plano
               desde segundo plano salvo por una exención. Aquí solo vale una: que
               el usuario haya quitado la app de la optimización de batería —el
               permiso de alarmas exactas Google Play únicamente lo da a
               despertadores y calendarios, así que por ahí no hay camino—. Sin la
               exención esto lanza ForegroundServiceStartNotAllowedException, y
               tragársela sería decir que se vigila cuando no se vigila. */
            if (!puedeResucitar(context)) {
                Log.w(TAG, "ServicioSos caido (${hueco / 1000}s) y NO se puede resucitar: " +
                    "falta la exencion de optimizacion de bateria")
                op.watchdogImpotente = true
                programar(context, INTERVALO_MS)
                return
            }
            op.watchdogImpotente = false
            Log.w(TAG, "ServicioSos inactivo o latido retrasado (${hueco / 1000}s). Resucitando servicio...")
            try {
                val serviceIntent = Intent(context, ServicioSos::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Error resucitando ServicioSos desde Watchdog", e)
                op.watchdogImpotente = true
            }
        } else {
            Log.d(TAG, "ServicioSos verificado vivo (latido hace ${hueco / 1000}s)")
        }

        // Siempre re-programar el siguiente pulso mientras deba vigilar
        programar(context, INTERVALO_MS)

        /* Lo último, y después de reprogramar: mirar si hay versión nueva no
           puede estorbar a lo que este receptor existe para hacer. Se entra
           cada 15 minutos pero la propia comprobación solo sale a la red una
           vez al día, así que esto es lo que hace que se entere de una versión
           nueva quien instaló la app y no ha vuelto a abrirla. */
        Actualizacion.comprobar(context)
    }
}
