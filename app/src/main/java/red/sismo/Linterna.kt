package red.sismo

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * La linterna parpadeando en SOS.
 *
 * Es lo que hace que te encuentren de noche o entre el polvo, donde la sirena
 * se pierde entre otras sirenas pero un destello periódico no se confunde con
 * nada. En la PWA había que pedir la cámara entera con `getUserMedia` y luego
 * rezar para que el `track` expusiera `torch`; aquí `setTorchMode` **no exige
 * el permiso CAMERA** desde API 23, así que la app puede encender el flash sin
 * pedir un permiso que asusta y sin abrir la cámara.
 *
 * No lanza nunca: en algunos móviles el flash lo tiene ocupado otra app y el
 * sistema responde con `CameraAccessException`. Que falle la linterna no puede
 * llevarse por delante la sirena.
 */
class Linterna(ctx: Context) {

    companion object {
        private const val TAG = "SismoRed"
        /** · · · — — — · · ·, en milisegundos: los pares encienden. El mismo de
         *  la vibración, pero sin el 0 inicial que allí es una espera. */
        val SOS = longArrayOf(
            200, 200, 200, 200, 200, 500,
            600, 200, 600, 200, 600, 500,
            200, 200, 200, 200, 200, 1400
        )
    }

    private val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    /** El primer id con flash. Se busca una vez: en un móvil no cambia. */
    private val id: String? = try {
        cm?.cameraIdList?.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    } catch (e: Exception) {
        Log.e(TAG, "linterna: no se puede enumerar", e); null
    }

    fun hay(): Boolean = id != null

    private val h = Handler(Looper.getMainLooper())
    private var paso: Runnable? = null
    @Volatile private var encendida = false

    fun encender(on: Boolean) {
        val c = cm ?: return
        val i = id ?: return
        try { c.setTorchMode(i, on); encendida = on } catch (e: Exception) {
            Log.e(TAG, "linterna: setTorchMode($on)", e)
        }
    }

    /** Arranca o para el parpadeo en SOS. Idempotente en los dos sentidos. */
    fun sos(activar: Boolean) {
        parar()
        if (!activar || id == null) return
        var i = 0
        val tarea = object : Runnable {
            override fun run() {
                encender(i % 2 == 0)
                val espera = SOS[i]
                i = (i + 1) % SOS.size
                h.postDelayed(this, espera)
            }
        }
        paso = tarea
        h.post(tarea)
    }

    /** Un destello suelto, que es lo que gasta el modo rescate. */
    fun destello(ms: Long = 120) {
        if (id == null) return
        encender(true)
        h.postDelayed({ if (paso == null) encender(false) }, ms)
    }

    fun parar() {
        paso?.let { h.removeCallbacks(it) }
        paso = null
        if (encendida) encender(false)
    }
}
