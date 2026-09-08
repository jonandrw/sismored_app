package red.sismo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FichaLanTest {

    @Test
    fun testCalcularVentanaSubredExcluyePropiaYExtremos() {
        val miIp = "192.168.1.10"
        val cursor = 5
        val ventana = 10
        val (ips, nuevoCursor) = FichaLan.calcularVentanaSubred(miIp, cursor, ventana)

        assertEquals("Debe generar 10 IPs menos la propia si cae dentro", 9, ips.size)
        assertFalse("No debe contener la IP propia", ips.contains(miIp))
        assertFalse("No debe contener la IP de broadcast .255", ips.contains("192.168.1.255"))
        assertFalse("No debe contener la IP de red .0", ips.contains("192.168.1.0"))
        assertTrue("Debe contener 192.168.1.5", ips.contains("192.168.1.5"))
        assertTrue("Debe contener 192.168.1.9", ips.contains("192.168.1.9"))
        assertTrue("Debe contener 192.168.1.11", ips.contains("192.168.1.11"))
        assertEquals("Nuevo cursor debe ser cursor + ventana", 15, nuevoCursor)
    }

    @Test
    fun testCalcularVentanaSubredRotacionCompleta254() {
        val miIp = "10.0.0.50"
        var cursor = 1
        val todasIps = HashSet<String>()

        // 16 iteraciones de 16 hosts = 256 intentos, cubriendo los 254 hosts
        for (paso in 0 until 16) {
            val (ips, sigCursor) = FichaLan.calcularVentanaSubred(miIp, cursor, 16)
            todasIps.addAll(ips)
            cursor = sigCursor
        }

        // Se deben haber cubierto 253 IPs (254 hosts menos miIp 10.0.0.50)
        assertEquals("Debe barrer los 253 hosts restantes de la subred /24", 253, todasIps.size)
        assertFalse("La propia IP nunca debe estar en el conjunto barrido", todasIps.contains(miIp))
        for (h in 1..254) {
            if (h != 50) {
                assertTrue("Debe incluir 10.0.0.$h", todasIps.contains("10.0.0.$h"))
            }
        }
    }

    @Test
    fun testCalcularVentanaSubredEntradasInvalidas() {
        val (ipsNull, cNull) = FichaLan.calcularVentanaSubred(null, 1, 16)
        assertTrue("Con IP nula debe retornar lista vacía", ipsNull.isEmpty())
        assertEquals("El cursor no debe modificarse con IP nula", 1, cNull)

        val (ipsInvalida, cInv) = FichaLan.calcularVentanaSubred("invalida", 1, 16)
        assertTrue("Con IP no IPv4 debe retornar lista vacía", ipsInvalida.isEmpty())
        assertEquals("El cursor no debe modificarse con IP inválida", 1, cInv)
    }

    @Test
    fun testCalcularVentanaSubredWrapAround() {
        val miIp = "192.168.0.2"
        val cursor = 250
        val ventana = 8
        val (ips, nuevoCursor) = FichaLan.calcularVentanaSubred(miIp, cursor, ventana)

        assertEquals("Debe generar 7 IPs ya que la propia (host 2) se excluye de la ventana de 8", 7, ips.size)
        assertTrue("Debe contener .250", ips.contains("192.168.0.250"))
        assertTrue("Debe contener .254", ips.contains("192.168.0.254"))
        assertTrue("Debe contener .1 tras dar la vuelta", ips.contains("192.168.0.1"))
        assertFalse("No debe contener .2 porque es la IP propia", ips.contains("192.168.0.2"))
        assertTrue("Debe contener .3", ips.contains("192.168.0.3"))
        assertEquals("Nuevo cursor debe ser 4", 4, nuevoCursor)
    }
}
