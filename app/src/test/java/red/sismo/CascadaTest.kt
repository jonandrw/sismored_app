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
        // Caso de campo del 16 de septiembre de 2026, que corrige al del 14.
        //
        // Un STA/LTA alto solo dice que el suelo se movió más que su propio ruido
        // de fondo, y eso pasa a todas horas. Ese día el Redmi dio CATORCE avisos
        // discretos en quince horas y ninguno coincidió con un sismo real; el
        // M5.0 de las 14:32 —59 km de profundidad, confirmado por USGS y EMSC—
        // no dejó ni una sola lectura en el registro.
        //
        // Asi que esto vuelve a ser NADA, y lo que enciende el aviso discreto es
        // un catálogo oficial. Ver el caso de abajo.
        val pruebas = Cascada.Pruebas(
            regimen = Postura.Regimen.EN_REPOSO,
            sacudida = true,
            sacudidaFuerte = false,
            ratioStaLta = 28.1,
            corroborada = false,
            alertaExterna = false
        )
        val decision = Cascada.decidir(pruebas)
        assertEquals(
            "Un STA/LTA alto, solo, no distingue un terremoto de un camión",
            Cascada.Accion.NADA,
            decision.accion
        )
    }

    @Test
    fun testCatalogoOficialSiEnciendeElAvisoDiscreto() {
        // Lo que sí acertó el 16 de septiembre: el catálogo tenía el M5.0 a las
        // 14:32 mientras el acelerómetro no sentía nada. La app no tenía que
        // sentirlo, tenía que preguntarlo.
        val pruebas = Cascada.Pruebas(
            regimen = Postura.Regimen.EN_REPOSO,
            alertaExterna = true,
            alertaCatalogo = true
        )
        val decision = Cascada.decidir(pruebas)
        assertEquals(
            "Un catálogo oficial que confirma un sismo cerca merece un aviso discreto",
            Cascada.Accion.PREGUNTAR_DISCRETA,
            decision.accion
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
            alertaExterna = false
        )
        val decision = Cascada.decidir(pruebas)
        assertEquals(
            "Vibración leve sin contraste suficiente debe dar NADA",
            Cascada.Accion.NADA,
            decision.accion
        )
    }

    @Test
    fun testVozSolaNoLevantaNada() {
        // La voz NO es una segunda opinión: la oye el micrófono de este mismo
        // móvil. Medido el 16 de septiembre de 2026 en el Redmi, diez detecciones
        // en quince horas de conversación corriente, y una de ellas sacó el
        // «¿estás bien?» a pantalla completa sin que hubiera temblado nada.
        //
        // El detector que las producía se quitó el 18 de septiembre —no
        // reconocía ninguna frase y su «confianza» era el volumen— pero el
        // invariante se queda: una sacudida con buen contraste y nadie que la
        // corrobore no levanta nada.
        val pruebas = Cascada.Pruebas(
            regimen = Postura.Regimen.EN_REPOSO,
            sacudida = true,
            sacudidaFuerte = false,
            ratioStaLta = 12.0,
            msDesdeInteraccion = 6 * 3600_000L
        )
        val decision = Cascada.decidir(pruebas)
        assertEquals(
            "Una sacudida sin corroborar no puede levantar nada",
            Cascada.Accion.NADA,
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
    fun testReceptorSismicoOnlineAutotest() {
        val receptor = ReceptorSismicoOnline(onAlertaSismica = { _, _, _, _, _ -> })
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
