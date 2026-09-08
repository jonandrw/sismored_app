package red.sismo

import android.content.Context

/**
 * Distribución Google Play:
 * Las políticas de Google Play prohíben estrictamente el uso del permiso
 * `BIND_ACCESSIBILITY_SERVICE` para capturar botones físicos de volumen en segundo plano
 * a menos que la app sea una herramienta diseñada exclusivamente para personas con discapacidad.
 *
 * Para garantizar la aprobación en Google Play y evitar suspensiones de cuenta, en este sabor
 * (`play`) no existe ninguna declaración de servicio de accesibilidad ni en el código ni en el
 * AndroidManifest.xml.
 *
 * El usuario en Google Play puede disparar pánico desde el botón en pantalla, la sirena, la malla
 * acústica y el detector sísmico/estruendo con normalidad.
 */
fun teclasDisponibles(): Boolean = false

@Suppress("UNUSED_PARAMETER")
fun teclasActivas(ctx: Context): Boolean = false
