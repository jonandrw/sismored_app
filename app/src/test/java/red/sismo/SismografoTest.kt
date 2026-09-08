package red.sismo

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class SismografoTest {

    @Test
    fun testNotchFilterAtenuacion2Hz() {
        val notch = Sismografo.Notch(2.0, 3.5)
        val sr = 50.0
        notch.ajustar(sr)

        // 1. Probar tono a 2.0 Hz (frecuencia notch)
        // 200 muestras = 4 segundos a 50 Hz
        var maxSalidaNotch = 0.0
        for (i in 0 until 200) {
            val t = i / sr
            val x = sin(2.0 * PI * 2.0 * t)
            val y = notch.filtrar(x)
            // Medir régimen estacionario (últimas 100 muestras)
            if (i >= 100 && abs(y) > maxSalidaNotch) {
                maxSalidaNotch = abs(y)
            }
        }

        // Atenuación esperada a 2.0 Hz: > 18 dB (amplitud < 0.125 respecto a 1.0)
        assertTrue(
            "Filtro Notch a 2.0 Hz debe atenuar fuertemente (obtenido: $maxSalidaNotch)",
            maxSalidaNotch < 0.125
        )

        // 2. Probar tono fuera del notch: a 0.8 Hz (sismo típico)
        notch.reiniciar()
        var maxSalida08 = 0.0
        for (i in 0 until 200) {
            val t = i / sr
            val x = sin(2.0 * PI * 0.8 * t)
            val y = notch.filtrar(x)
            if (i >= 100 && abs(y) > maxSalida08) {
                maxSalida08 = abs(y)
            }
        }
        assertTrue(
            "Filtro Notch debe dejar pasar 0.8 Hz con mínima atenuación (obtenido: $maxSalida08)",
            maxSalida08 > 0.88
        )

        // 3. Probar tono fuera del notch: a 4.0 Hz
        notch.reiniciar()
        var maxSalida40 = 0.0
        for (i in 0 until 200) {
            val t = i / sr
            val x = sin(2.0 * PI * 4.0 * t)
            val y = notch.filtrar(x)
            if (i >= 100 && abs(y) > maxSalida40) {
                maxSalida40 = abs(y)
            }
        }
        assertTrue(
            "Filtro Notch debe dejar pasar 4.0 Hz con mínima atenuación (obtenido: $maxSalida40)",
            maxSalida40 > 0.88
        )
    }

    @Test
    fun testBandaOndaP9a18Hz() {
        val bandaP = Sismografo.Banda(9.0, 18.0)
        val sr = 50.0
        bandaP.ajustar(sr)

        // 1. Probar frecuencia central de Onda P (13.0 Hz)
        var maxSalida13 = 0.0
        for (i in 0 until 200) {
            val t = i / sr
            val x = sin(2.0 * PI * 13.0 * t)
            val y = bandaP.filtrar(x)
            if (i >= 100 && abs(y) > maxSalida13) {
                maxSalida13 = abs(y)
            }
        }
        assertTrue(
            "Canal P debe transmitir 13 Hz dentro de la banda de paso (obtenido: $maxSalida13)",
            maxSalida13 > 0.65
        )

        // 2. Probar rechazo a 2.0 Hz (banda de onda S)
        bandaP.reiniciar()
        var maxSalida2 = 0.0
        for (i in 0 until 200) {
            val t = i / sr
            val x = sin(2.0 * PI * 2.0 * t)
            val y = bandaP.filtrar(x)
            if (i >= 100 && abs(y) > maxSalida2) {
                maxSalida2 = abs(y)
            }
        }
        assertTrue(
            "Canal P debe rechazar fuertemente 2 Hz (obtenido: $maxSalida2)",
            maxSalida2 < 0.10
        )
    }

    @Test
    fun testKurtosisSaltoEnOndaP() {
        // Generar ruido de fondo estacionario (kurtosis ~3.0) y luego un frente de onda impulsivo
        val rnd = java.util.Random(1234)
        val n = 40
        val muestras = DoubleArray(n)

        // Ruido de fondo gaussiano sigma = 0.015 m/s2
        for (i in 0 until n) {
            muestras[i] = rnd.nextGaussian() * 0.015
        }

        fun calcularKurtosis(arr: DoubleArray): Pair<Double, Double> {
            var sum = 0.0
            for (v in arr) sum += v
            val mean = sum / arr.size
            var m2 = 0.0
            var m4 = 0.0
            for (v in arr) {
                val d = v - mean
                val d2 = d * d
                m2 += d2
                m4 += d2 * d2
            }
            m2 /= arr.size
            m4 /= arr.size
            val kurt = if (m2 > 1e-8) m4 / (m2 * m2) else 3.0
            return kurt to m2
        }

        val (kurtFondo, varFondo) = calcularKurtosis(muestras)
        assertTrue("Kurtosis de ruido gaussiano debe estar cerca de 3.0 (fue $kurtFondo)", kurtFondo in 1.8..4.5)

        // Ahora inyectar frente abrupto de Onda P (impacto no gaussiano concentrado)
        muestras[n - 1] = 0.16
        muestras[n - 2] = -0.14
        muestras[n - 3] = 0.12

        val (kurtP, varP) = calcularKurtosis(muestras)
        assertTrue(
            "Salto de frente de Onda P debe elevar Kurtosis por encima de 5.2 (fue $kurtP)",
            kurtP > Sismografo.KURTOSIS_P_MIN
        )
        assertTrue(
            "Varianza con Onda P debe superar VAR_P_MIN (fue $varP)",
            varP > Sismografo.VAR_P_MIN
        )
    }

    @Test
    fun testCascadaBiFaseDecision() {
        // Caso 1: sacudida en bolsillo sin confirmación externa -> NADA
        val d1 = Cascada.decidir(
            Cascada.Pruebas(
                regimen = Postura.Regimen.ENCIMA,
                sacudida = true,
                ondaP = false
            )
        )
        assertEquals(Cascada.Accion.NADA, d1.accion)

        // Caso 2: sacudida en bolsillo con Onda P previa bi-fase confirmada -> PREGUNTAR
        val d2 = Cascada.decidir(
            Cascada.Pruebas(
                regimen = Postura.Regimen.ENCIMA,
                sacudida = true,
                ondaP = true
            )
        )
        assertEquals(Cascada.Accion.PREGUNTAR, d2.accion)
    }

    @Test
    fun testCascadaAutotestCompleto() {
        val (ok, txt) = Cascada.autotest()
        assertTrue("Cascada.autotest() falló: $txt", ok)
    }

    @Test
    fun testSismografoAutotestCompleto() {
        val dummyCtx = ContextWrapper(null)
        val sismo = Sismografo(dummyCtx) { }
        val (ok, txt) = sismo.autotest()
        assertTrue("Sismografo.autotest() falló: $txt", ok)
    }
}
