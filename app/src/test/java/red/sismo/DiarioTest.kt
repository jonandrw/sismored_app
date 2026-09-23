package red.sismo

import org.junit.Assert.assertEquals
import org.junit.Test
import red.sismo.data.EventoBD

class DiarioTest {

    private fun ev(id: Long, minuto: Int, m: String) =
        EventoBD(id = id, tipo = 1, fechaMs = 1_000_000_000L + minuto * 60_000L, mensaje = m)

    private val unDia: (Long) -> Pair<String, String> = { "HOY" to "HOY" }

    @Test
    fun loImportanteDejaFueraElRuidoYPliegaLoRepetido() {
        // La más reciente primero, como sale de la base de datos.
        val eventos = listOf(
            ev(6, 20, "Móvil en reposo: vigilo a 0,25 m/s²"),
            ev(5, 12, "ALERTA RECIBIDA de otro móvil, justo al lado"),
            ev(4, 11, "ALERTA RECIBIDA de otro móvil, justo al lado"),
            ev(3, 10, "ALERTA RECIBIDA de otro móvil, justo al lado"),
            ev(2, 5, "VOZ HUMANA CERCA · 82%"),
            ev(1, 1, "ALARMA: botón de pánico"),
        )
        val filas = Diario.filas(eventos, todo = false, dia = unDia)
        // una cabecera, la racha de tres alertas plegada y la alarma
        assertEquals(3, filas.size)
        val racha = filas[1] as Diario.Fila.Suceso
        assertEquals(Diario.Tipo.MALLA, racha.tipo)
        assertEquals(3, racha.grupo.size)
        assertEquals(Diario.Tipo.ALARMA, (filas[2] as Diario.Fila.Suceso).tipo)

        // con TODO entra el ruido, y cada cosa distinta es su propia fila
        assertEquals(5, Diario.filas(eventos, todo = true, dia = unDia).size)
    }

    @Test
    fun dosIgualesMuySeparadosNoSonLaMismaRacha() {
        val eventos = listOf(
            ev(2, 100, "TERREMOTO · ¿estás bien? Tienes 60 s para contestar"),
            ev(1, 10, "TERREMOTO · ¿estás bien? Tienes 60 s para contestar"),
        )
        assertEquals(3, Diario.filas(eventos, todo = false, dia = unDia).size)
    }
}
