package red.sismo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CascadaTest {

    @Test
    fun testCascadaCasosAutotestIntegrados() {
        val (ok, log) = Cascada.autotest()
        assertTrue("Autotest de Cascada debe pasar al 100%: $log", ok)
    }

    @Test
    fun testCasoCampo14SeptiembreSismoEnReposoSinRed() {
        // Caso de campo real del 14 de septiembre de 2026:
        // Redmi en reposo en mesa (giro = 0°), sismo del Chocó (STA/LTA 28.1x, 0.45 m/s²).
        // Sin otros nodos en la malla y sin alerta de Google:
        // ANTES: emitía NADA (sismo desatendido y silenciado).
        // AHORA: emite PREGUNTAR_DISCRETA (aviso no invasivo que no activa sirena ni baliza si expira).
        val pruebas = Cascada.Pruebas(
            regimen = Postura.Regimen.EN_REPOSO,
            sacudida = true,
            sacudidaFuerte = false,
            ratioStaLta = 28.1,
            corroborada = false,
            alertaExterna = false,
            vozPanico = false
        )
        val decision = Cascada.decidir(pruebas)
        assertEquals(
            "Sismo en reposo con alto contraste STA/LTA debe preguntar de forma discreta",
            Cascada.Accion.PREGUNTAR_DISCRETA,
            decision.accion
        )
        assertTrue(
            "Motivo debe registrar el ratio STA/LTA",
            decision.motivo.contains("STA/LTA 28.1x")
        )
    }

    @Test
    fun testFalsoPositivoLeveEnReposoSigueDescartado() {
        // Un golpe leve en la mesa o vibración doméstica (STA/LTA 4.0x):
        // NO debe disparar PREGUNTAR_DISCRETA ni PREGUNTAR. Debe descartarse como NADA.
        val pruebas = Cascada.Pruebas(
            regimen = Postura.Regimen.EN_REPOSO,
            sacudida = true,
            sacudidaFuerte = false,
            ratioStaLta = 4.0,
            corroborada = false,
            alertaExterna = false,
            vozPanico = false
        )
        val decision = Cascada.decidir(pruebas)
        assertEquals(
            "Vibración leve sin contraste suficiente debe dar NADA",
            Cascada.Accion.NADA,
            decision.accion
        )
    }

    @Test
    fun testCorroboracionPorVozDePanico() {
        // Sismo con exclamación de auxilio o pánico por voz:
        // Actúa como segunda opinión local inmediata.
        val pruebas = Cascada.Pruebas(
            regimen = Postura.Regimen.EN_REPOSO,
            sacudida = true,
            sacudidaFuerte = false,
            ratioStaLta = 12.0,
            vozPanico = true,
            msDesdeInteraccion = 6 * 3600_000L
        )
        val decision = Cascada.decidir(pruebas)
        assertEquals(
            "Sismo corroborado por pánico acústico debe elevarse a AVISAR",
            Cascada.Accion.AVISAR,
            decision.accion
        )
    }

    @Test
    fun testCorroboracionPorRedSismicaOnline() {
        // Sismo corroborado por alerta de red abierta (EMSC / USGS):
        val pruebas = Cascada.Pruebas(
            regimen = Postura.Regimen.EN_REPOSO,
            sacudida = true,
            sacudidaFuerte = false,
            ratioStaLta = 15.0,
            alertaExterna = true,
            msDesdeInteraccion = 6 * 3600_000L
        )
        val decision = Cascada.decidir(pruebas)
        assertEquals(
            "Sismo corroborado por alerta sísmica online debe elevarse a AVISAR",
            Cascada.Accion.AVISAR,
            decision.accion
        )
    }

    @Test
    fun testDetectorPanicoVozAutotest() {
        val detector = DetectorPanicoVoz({ _, _ -> })
        val (ok, log) = detector.autotest()
        assertTrue("Autotest de DetectorPanicoVoz debe ser 100% OK: $log", ok)
    }

    @Test
    fun testReceptorSismicoOnlineAutotest() {
        val receptor = ReceptorSismicoOnline(onAlertaSismica = { _, _, _ -> })
        val (ok, log) = receptor.autotest()
        assertTrue("Autotest de ReceptorSismicoOnline debe ser 100% OK: $log", ok)
    }

    @Test
    fun testPerfilesEntornoParametros() {
        assertEquals(0.20, Opciones.PerfilEntorno.TRANQUILO.umbralReposo, 0.001)
        assertEquals(8.0, Opciones.PerfilEntorno.TRANQUILO.staLtaMin, 0.001)
        assertEquals(0.40, Opciones.PerfilEntorno.TRANQUILO.fuerteMin, 0.001)

        assertEquals(0.25, Opciones.PerfilEntorno.NORMAL.umbralReposo, 0.001)
        assertEquals(10.0, Opciones.PerfilEntorno.NORMAL.staLtaMin, 0.001)
        assertEquals(0.60, Opciones.PerfilEntorno.NORMAL.fuerteMin, 0.001)

        assertEquals(0.35, Opciones.PerfilEntorno.RUIDOSO.umbralReposo, 0.001)
        assertEquals(15.0, Opciones.PerfilEntorno.RUIDOSO.staLtaMin, 0.001)
        assertEquals(0.80, Opciones.PerfilEntorno.RUIDOSO.fuerteMin, 0.001)
    }
}
