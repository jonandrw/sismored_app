# Lo siguiente, en orden

Relevo para abrir una ventana nueva. `CONTINUAR.md` sigue siendo la fuente de
verdad —el porqué de cada decisión y las trampas ya pisadas—; esto es solo la
lista de lo que falta, ordenada por lo que más decide.

---

## 0. Cinco minutos con el móvil en la mano

No lo puedo hacer yo: el Redmi no acepta `input tap` por adb (falta
`INJECT_EVENTS`, se activa en Opciones de desarrollador → Depuración USB
(ajustes de seguridad)). Cada uno cierra algo que hoy está a medias.

- [ ] **APRENDER ESTE MÓVIL**, en la tarjeta de la sonda, con el brazo estirado y
      lejos de todo. Sin la firma restada, el eco sigue midiendo el teléfono.
      Luego repetir la ecolocalización tapando el móvil: si el resultado **cambia**,
      la sonda mide la sala por primera vez.
- [ ] **COMPROBAR QUE FUNCIONA**. Lanza regresiones escritas y nunca ejecutadas en
      un móvil: la cascada («preguntó, nadie contestó» → BALIZA), la firma del
      móvil y el ventilador a 9/min.
- [ ] **El giro con el móvil en la mano y andando.** En la mesa mide 0,0–0,2° y el
      corte está en 10°. Se lee en Inicio, en la línea de estado.
- [ ] **Sacudir el móvil en la mesa y no tocarlo en 60 s.** Es la prueba de que la
      baliza sale, y se arregló a ciegas.
- [ ] **Respiración a centímetros**, con el ventilador apagado. Ahora mide fase, no
      bandas laterales, y nadie la ha probado con una persona.

---

## 1. ~~La firma temporal de la malla~~ — HECHA

`verCadencia` ya exige el patrón entero: cuatro ráfagas seguidas con su ON, su
OFF y sobre todo su **periodo**, que es lo que no se mueve. Las ventanas están
medidas contra la trama sintetizada, no estimadas —la aritmética obvia era falsa,
los marcos se solapan al 50 %—, y hay un autotest que exige las dos mitades: que
la baliza pase y que un tono continuo no. El porqué de cada número está en
`CONTINUAR.md`.

Verificado en el A10s: `la baliza da 6 ráfagas de 4 y un tono continuo 0`.

**Y la regla sigue en pie**: cada vez que se toque un umbral de la malla, correr
el autotest. Subir de dos a tres marcos la dejó **sorda en los cuatro saltos**, y
eso no se nota usándola — solo lo cazó el autotest.

---

## 2. Confirmar el rescate, en los dos móviles

Hoy la ficha se abre sola cuando se oye la llamada del que busca, pero **no hay
forma de decir que ya te encontraron**. Falta:

- En el móvil de la víctima: **ME HAN ENCONTRADO** en la propia pantalla de la
  ficha.
- En el del rescatista: **RESCATADO**, que además deje de llamar.
- Solo eso apaga la baliza. Que aparezca la ficha **no** puede cancelar nada: si
  el rescatista se equivoca de hueco y el móvil deja de emitir, se pierde a la
  persona.

---

## 3. La ficha fragmentada por radio — HECHA, falta probarla entre dos móviles

El anuncio BLE son 31 bytes y hoy solo caben el nombre de pila y el grupo. Con
tramas numeradas cabe la ficha entera:

- Unos 20 bytes útiles por anuncio; se reservan tres: identificador, número de
  trama y total.
- El que busca va recogiendo y arma; lo que falte llega en la vuelta siguiente,
  porque la baliza repite sin parar.
- Checksum, para no montar una ficha corrupta.
- Y un efecto secundario valioso: «7 de 9 tramas» es una medida honesta de la
  calidad del enlace, que es justo lo que el rescatista necesita saber.

Por la malla acústica **no**: ahí el canal son bits por segundo.

**Estado**: implementado y compilando. La trama 0 es byte por byte la de antes
—nombre y grupo, legible ella sola— y las de continuación llevan edad, avisos y
contacto en trozos de 11 bytes, marcadas con un `0x01` que no puede confundirse
con texto. El emisor rota de trama cada 1,2 s; el que busca las junta y enseña
«7 de 9» mientras falten. **Falta la prueba entre dos móviles**: con uno solo no
se puede comprobar ni el reparto ni el armado.

---

## 4. Repetir la alerta sísmica de Google

Nació de un dato de campo: en un salón de clases, la alerta de Google **no le
llegó al 80 % de los móviles Android**. Necesita internet, servicios de Google y
la función activada; el que la recibe la tiene segundos antes de que llegue la
sacudida.

La idea: **el móvil que sí la recibe la reparte por la malla acústica** a los que
no tienen internet. Es exactamente el caso que SismoRed puede resolver y Google
no.

Cómo se detecta: la alerta llega como **notificación** de los servicios de
Google. No hay API pública, así que hace falta un `NotificationListenerService`
con el permiso de **acceso a notificaciones**.

Lo que hay que decidir antes de escribirlo:

- Es **otro permiso sensible**, y sumado al de accesibilidad complica la revisión
  de Google Play. En F-Droid y GitHub Releases no es problema. Encaja con la
  decisión de ruta de publicación (ver más abajo).
- **Nunca puede encender la baliza de víctima.** Es un aviso que se propaga, no
  una emergencia propia: suena, avisa y reemite por la malla. Confundir las dos
  cosas llenaría la red de balizas de gente que está perfectamente.
- Hay que reconocer la notificación por paquete y contenido, y **no leer ninguna
  otra**. Eso se escribe en Acerca de con las mismas palabras.

---

## 5. Pruebas de campo, por lo que más decide

1. **Dos móviles.** Sigue sin verse **un solo salto real de la malla** — es la
   pieza central del proyecto. Con eso caen tres cosas: el salto, el tono de
   SILENCIO a 18,8 kHz (emite, pero no se ha confirmado que viaje por aire) y el
   repetidor.
2. **La sonda contra una pared a distancia conocida**, ya con la firma restada.
3. **Una noche entera con el móvil en la mesilla a 0,8 m/s²**: los falsos por
   hora reales, con portazos, lavadora y camión de la basura.
4. **Grabar sonidos.** `Golpes`, `Alarmas` y `Falsos` están **vacías**;
   `Ambiente` tiene 44 segundos. No hace falta programar y es lo que más falta:
   sin eso, los detectores no se pueden calibrar.

---

## 6. Antes de publicar

Técnico:

- [ ] **No hay configuración de firma**: hoy solo se puede generar un APK de
      depuración.
- [ ] La versión sigue en `0.1-fase1` (versionCode 1).
- [ ] `minify` apagado; decidir R8.

De tienda:

- [ ] La política de privacidad también en una **URL pública** (Play la exige en
      la ficha, no solo dentro de la app; el texto ya está escrito en Acerca de).
- [ ] Formulario de seguridad de datos.
- [ ] **El permiso de accesibilidad es el mayor riesgo de rechazo**: Play exige
      que esas APIs se usen para accesibilidad.
- [ ] Comprobar qué `targetSdk` exige Play este año; está en 34.
- [ ] Capturas, descripción e icono.

**La decisión de ruta**: GitHub Releases y F-Droid esquivan la política de
accesibilidad —y la de acceso a notificaciones, si se hace lo de Google—, y ya
estaban planeados en `PLAN-NATIVO.md`. Lo sensato es publicar ahí mientras se
miden los detectores, y llevarla a Play cuando los falsos por hora estén medidos.
Publicar una app de emergencia cuyo detector de derrumbe da 32 falsos/hora en el
banco no es un riesgo de rechazo: es el riesgo de que alguien deje de creerse la
alarma el día que sea de verdad.

---

## 7. Interfaz, lo que queda suelto

- [ ] La cabecera de las vistas de segundo nivel: **«Opciones │ Centro de
      control»** — título, barra roja y subtítulo. Afecta a las cuatro
      (Respuesta, Diagnóstico, Registro, Acerca de), por eso no se hizo.
- [x] Revisar Acerca de en pantalla. Visto en el A10s: el documento se lee, va
      justificado y la jerarquía funciona. Lo que sí chirría es que el subtítulo
      gris queda **pegado al título** y separado del cuerpo, o sea al revés de lo
      que debería.
- [ ] **La cuadrícula de respuesta sigue sin verse.** Es lo que queda de la
      pasada con el Samsung.
- [ ] Dos textos cortados por la derecha que ya están localizados: la fila
      «Ficha por Wi-Fi» de Diagnóstico («192.168.101.27 (con») y las dos marcas
      «--» sueltas encima de «Estado de la malla».

> Con el Samsung se puede **tocar y medir** (`input tap` funciona, al contrario
> que en el Redmi). `uiautomator dump` no: las consolas parpadean y la ventana
> nunca queda en reposo. Se mide sobre los píxeles de la captura, 2 px por dp.
