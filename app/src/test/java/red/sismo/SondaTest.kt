package red.sismo

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SondaTest {

    private fun crearMicStub(sr: Int = 48000): Microfono {
        val dummyCtx = ContextWrapper(null)
        val mic = Microfono(dummyCtx)
        return mic
    }

    @Test
    fun testSondaAutotestCompleto() {
        val mic = crearMicStub(48000)
        val sonda = Sonda(mic, onRegistro = { System.err.println(it) })
        val ok = sonda.autotest()
        assertTrue("Sonda.autotest() con Auto-Zero NLMS debe retornar true", ok)
    }

    @Test
    fun testAutoZeroNLMSEliminaFantasmaCercano() {
        val mic = crearMicStub(48000)
        val sonda = Sonda(mic)
        val sr = 48000
        val tpl = sonda.chirp(sr)
        val maxLag = (sr * 2 * 6.0 / 343.0).toInt()

        // Simular 8 disparos con artefacto de chasis a 34 cm (2 ms) y pared a 2 m
        val dPared = 2.0
        val ampPared = sonda.ecoFisico(dPared, 0.9)
        val tramos = ArrayList<DoubleArray>()

        // Simular con artefacto = true (introduce artefacto a 2.0 ms = 34 cm, amplitud 0.20)
        for (r in 0 until 8) {
            val rec = FloatArray((sr * 0.25).toInt())
            val off = (sr * 0.02).toInt()
            val lagPared = (2 * dPared / 343.0 * sr).toInt()
            val lagChasis = (sr * 0.0020).toInt() // 34 cm

            // Sonido directo
            for (i in tpl.indices) {
                if (off + i < rec.size) rec[off + i] += tpl[i]
                if (off + lagChasis + i < rec.size) rec[off + lagChasis + i] += tpl[i] * 0.20f
                if (off + lagPared + i < rec.size) rec[off + lagPared + i] += tpl[i] * ampPared
            }

            // Correlación
            val n = rec.size - tpl.size
            val corr = DoubleArray(n)
            for (i in 0 until n) {
                var s = 0.0
                var j = 0
                while (j < tpl.size) { s += rec[i + j] * tpl[j]; j += 2 }
                corr[i] = abs(s)
            }

            // Tramo
            var d0 = 0
            var mx = 0.0
            for (i in corr.indices) if (corr[i] > mx) { mx = corr[i]; d0 = i }
            if (mx > 1e-12) {
                tramos.add(DoubleArray(maxLag + 1) { k ->
                    val idx = d0 + k
                    if (idx < corr.size) corr[idx] / mx else 0.0
                })
            }
        }

        // Sin firma previa aprendida (firma == null), Auto-Zero NLMS debe actuar
        sonda.firmaBorrar()
        val reflectores = sonda.apilar(tramos, sr)

        for (ref in reflectores) {
            System.err.println("TEST REFLECTOR: dist=${ref.distancia} m, amp=${ref.amplitud}, pres=${ref.presencia}/${ref.total}")
        }

        // Verificar que CERO reflectores caen a menos de 50 cm
        val fantasmasCerca = reflectores.count { it.distancia < 0.50 }
        assertEquals("Auto-Zero NLMS debe cancelar acoplo a < 50 cm sin calibración", 0, fantasmasCerca)
    }

    @Test
    fun testSerializacionFirmaBase64() {
        // Validar lógica de empaquetado FloatBuffer y Base64 utilizada por Opciones.sondaFirma
        val original = DoubleArray(256) { (it * 0.0123) }
        val bytes = ByteArray(original.size * 4)
        val buf = java.nio.ByteBuffer.wrap(bytes).asFloatBuffer()
        for (x in original) buf.put(x.toFloat())
        val b64 = java.util.Base64.getEncoder().encodeToString(bytes)

        assertNotNull(b64)
        assertTrue(b64.isNotBlank())

        val decBytes = java.util.Base64.getDecoder().decode(b64)
        val decBuf = java.nio.ByteBuffer.wrap(decBytes).asFloatBuffer()
        val recuperado = DoubleArray(decBuf.remaining()) { decBuf.get().toDouble() }

        assertEquals(original.size, recuperado.size)
        for (i in original.indices) {
            assertEquals("Diferencia en muestra $i", original[i].toFloat(), recuperado[i].toFloat(), 1e-5f)
        }
    }
}
