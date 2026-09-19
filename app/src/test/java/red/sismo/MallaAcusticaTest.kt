package red.sismo

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class MallaAcusticaTest {

    private fun crearMicStub(sr: Int = 48000): Microfono {
        val dummyCtx = ContextWrapper(null)
        return Microfono(dummyCtx)
    }

    @Test
    fun testConstantesFrecuenciasRobustas() {
        assertEquals("SILENCIO_ROBUSTO debe ser 15600.0 Hz", 15600.0, MallaAcustica.SILENCIO_ROBUSTO, 0.001)
        assertEquals("LLAMADA_ROBUSTA debe ser 15200.0 Hz", 15200.0, MallaAcustica.LLAMADA_ROBUSTA, 0.001)
        assertEquals("ALERTA_ROBUSTA debe ser 14800.0 Hz", 14800.0, MallaAcustica.ALERTA_ROBUSTA, 0.001)
        assertEquals("OIDO debe ser 14400.0 Hz", 14400.0, MallaAcustica.OIDO, 0.001)
        assertEquals("TONOS debe tener 11 tonos", 11, MallaAcustica.TONOS.size)
        assertEquals("Índice 8 en TONOS debe ser SILENCIO_ROBUSTO", 15600.0, MallaAcustica.TONOS[7], 0.001)
        assertEquals("Índice 9 en TONOS debe ser LLAMADA_ROBUSTA", 15200.0, MallaAcustica.TONOS[8], 0.001)
        assertEquals("Índice 10 en TONOS debe ser ALERTA_ROBUSTA", 14800.0, MallaAcustica.TONOS[9], 0.001)
        /* El código lo da el orden del array, no la frecuencia: si alguien
           inserta un tono en medio, los códigos de todos los de atrás cambian
           y dos versiones de la app dejan de entenderse. */
        assertEquals("Índice 11 en TONOS debe ser OIDO", 14400.0, MallaAcustica.TONOS[10], 0.001)
        assertEquals("CODIGO_OIDO debe ser el índice 11", 11, MallaAcustica.CODIGO_OIDO)
        /* Ninguno a menos de 400 Hz de otro: por debajo de eso el Goertzel de
           uno se cuela en el bin del vecino. */
        val orden = MallaAcustica.TONOS.sorted() + MallaAcustica.MARK
        for (a in orden) for (b in orden) if (a != b)
            assertTrue("$a y $b estan a menos de 400 Hz", Math.abs(a - b) >= 399.0)
    }

    @Test
    fun testAutotestTonosIndividuales() {
        val mic = crearMicStub(48000)
        val malla = MallaAcustica(mic, onConfirmada = {})

        // Probar los 10 códigos (1..4 saltos, 5 llamada legacy, 6 silencio legacy, 7 alerta, 8 silencio robusto, 9 llamada robusta)
        for (hop in 1..MallaAcustica.TONOS.size) {
            val ok = malla.autotest(hop)
            assertTrue("autotest para código $hop (${MallaAcustica.TONOS[hop - 1]} Hz) debe ser true", ok)
        }
    }

    @Test
    fun testAutotestCadenciaFirmaTemporal() {
        val mic = crearMicStub(48000)
        val malla = MallaAcustica(mic, onConfirmada = {})
        val (ok, mensaje) = malla.autotestCadencia()
        assertTrue("autotestCadencia debe dar true: $mensaje", ok)
    }

    @Test
    fun testSintesisChirpCss() {
        val sr = 48000
        val durS = 0.20
        val chirp = MallaAcustica.sintetizarChirp(sr, durS, 2200.0, 3200.0, 0.95)
        val esperadoN = (sr * durS).toInt()

        assertEquals("Longitud de muestras de Chirp debe ser durS * sr", esperadoN, chirp.size)
        assertNotNull("Chirp sintetizado no debe ser nulo", chirp)

        // Verificar que las muestras estén dentro del rango PCM 16-bit sin saturación explosiva
        var maxMuestra = 0
        for (s in chirp) {
            val absVal = kotlin.math.abs(s.toInt())
            if (absVal > maxMuestra) maxMuestra = absVal
        }
        assertTrue("Chirp debe tener amplitud acústica audible", maxMuestra > 10000)
        assertTrue("Chirp no debe exceder Short.MAX_VALUE", maxMuestra <= Short.MAX_VALUE)

        // Verificar envolvente suave en los extremos (5 ms = 240 muestras a 48 kHz)
        val rampa = (0.005 * sr).toInt()
        val muestraInicio = kotlin.math.abs(chirp[0].toInt())
        val muestraFin = kotlin.math.abs(chirp[chirp.size - 1].toInt())
        assertTrue("Muestra de inicio debe ser muy suave (< 1000)", muestraInicio < 1000)
        assertTrue("Muestra de fin debe ser muy suave (< 1000)", muestraFin < 1000)
    }
}
