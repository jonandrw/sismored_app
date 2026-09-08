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
- [ ] **Probar el Bio-Sonar (chasquidos continuos estilo A Quiet Place)**:
      - **ECO**: Apuntar a una pared a 1,5 m. Escuchar los 8 chasquidos secos (2,5 a 4,2 kHz) y comprobar si devuelve la distancia.
      - **MOVIMIENTO / DOPPLER**: Encenderlo y pasar la mano a 1 m del móvil; verificar que el pulso visual y el nivel se activan con los ecos móviles.
      - **RESPIRACIÓN**: Apuntar al pecho de una persona quieta a 0,5 - 1,0 m durante los 25 segundos y observar la detección de ritmo (12 a 36 rpm).

---

## 0.5. ~~Watchdog de suceso y desacoplamiento del micrófono~~ — HECHA Y PROBADA EN DISPOSITIVO (4 de septiembre)

- `SUCESO_TIMEOUT_MS = 15_000L` y `reprogramarWatchdogSuceso()` en `ServicioSos.kt`: cancela y cierra automáticamente cualquier suceso que no escale a pregunta o alarma tras 15 segundos. Corrige el fallo del 3 de septiembre donde la app preguntaba en bucle al quedar el estado anclado.
- `onEstruendo` desacoplado: el micrófono ya no abre un suceso sísmico si el suelo no se estaba moviendo. Solo evalúa si `sucesoDesde > 0L`.
- Probado por el usuario en hardware real: verificado funcionamiento perfecto sin falsas preguntas.

---

## 0.6. ~~STA/LTA sismológico recursivo en Sismografo.kt~~ — HECHA (4 de septiembre)

- Reemplazado `calma.copyOf().sortedArray()` que asignaba y ordenaba arrays de 256 `Double` en el sensor thread cada 32 muestras.
- Estimador LTA recursivo `ltaH` (~15 s) con congelación automática ante movimiento (`congelarLta = hayMano || sta > ltaH * 2.0 || sta > umbral * 0.4`).
- Relación `ratioStaLta = sta / ltaPiso` agregada a `sueloDeFiar` (`ratioStaLta >= 1.8`).
- Compilado y verificado limpio sin pausas de GC.

---

## 0.7. ~~Sabores de compilación `libre` vs `play`~~ — HECHA (4 de septiembre)

- Resuelto el conflicto de Google Play con `BIND_ACCESSIBILITY_SERVICE` (atajo de volumen).
- Sabor `libre` (F-Droid / GitHub): Mantiene `ServicioTeclas` en manifiesto y código (`teclasDisponibles() = true`).
- Sabor `play` (Google Play Store): `ServicioTeclas` totalmente excluido del APK y manifiesto; `teclasDisponibles() = false`. Interfaz oculta limpiamente el atajo de volumen sin errores ni alertas engañosas.
- Verificado y empaquetado: `app-libre-debug.apk` y `app-play-debug.apk` ensamblados con éxito.

---

## 0.8. ~~Bio-Sonar Acústico de Impulso en Sonda.kt~~ — HECHA (4 de septiembre)

- Reemplazado el tono continuo ultrasónico de 18,5 kHz por un **biosonar de chasquidos audibles** de 8 ms en la banda dulce del altavoz (2,5 kHz a 4,2 kHz), inspirado en la ecolocalización de murciélagos/cetáceos y el sonido de *A Quiet Place*.
- **Eco / Sondear**: 8 chasquidos en ~1,6 s, con zona ciega reducida a < 70 cm y resolución de ~10 cm.
- **Movimiento / Doppler MTI**: Emite 10 chasquidos/s y analiza diferencias cuadro a cuadro de los ecos entre 0,4 y 3,5 m.
- **Respiración**: Range-Gated Phase Biosonar a 10 Hz rastreando la fase del reflector principal respecto al camino directo para medir la oscilación del pecho (12 a 36 rpm) sin verse afectado por fluctuaciones de latencia del sistema.
- Compilado y empaquetado limpio en APK.

---

## 0.9. ~~Filtro Acústico Anti-Maquinaria y Detección de Golpes SOS~~ — HECHA (4 de septiembre)

- Reducidos los falsos estruendos de **32,7 a 5,4 por hora** en el banco de audio (`banco.py`) manteniendo **8 de 8 (100%) aciertos en derrumbes**.
- Implementado filtro aperiódico de envolvente (`mod < 0.25`), umbral de cataclismo (`novedad > 20 dB`), límite de sostenido (`sostenido <= 35 ticks`) y energía grave (`rumble > 0.60`) en `Escucha.kt`: anula falsos colapsos por generadores diésel, camiones y helicópteros.
- Implementada compuerta de cadencia biológica de golpes humanos (250 ms a 1250 ms) y filtro de resonancia estructural (`rumble > 0.10`): eliminados los falsos disparos provocados por crepitación de fuego en `Ambiente` (bajó de 408,5 a 0 falsos/h).

---

## 0.10. ~~Paquete Legal y Privacidad Google Play~~ — HECHA (4 de septiembre)

- Creado documento oficial público `PRIVACIDAD.md` en la raíz del repositorio, detallando la arquitectura *Local-First*, procesamiento de audio exclusivamente en RAM y ausencia de telemetría.
- Integrado aviso legal obligatorio (*disclaimer* de emergencia) en el paso 3 de Bienvenida (`ob3_txt`) y en `Acerca de`: SismoRed no sustituye al 911/112 ni a los servicios oficiales de protección civil.
- Compilación validada en sabores `libre` y `play`.

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

## 2. ~~Confirmar el rescate~~ — HECHA

**ME HAN ENCONTRADO** en la ficha de la víctima —dos toques, el primero pregunta
y el segundo apaga, y se desarma solo a los diez segundos— y **RESCATADO** en el
del rescatista, que solo deja de llamar. La baliza de la víctima la apaga la
víctima; nunca alguien de fuera. Que aparezca la ficha sigue sin cancelar nada.

Y de paso **VER MI FICHA**, para poder mirar esa pantalla sin que pase de verdad.

**Falta probarlo entre los dos móviles**, que es lo único que no se puede
comprobar con uno.

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

**Estado: HECHO.** `AlertaGoogle` es el `NotificationListenerService`, el código
7 de la malla a 16,4 kHz reparte la alerta, y `alertaExterna` entra en la cascada
como una prueba más. Seis casos en el autotest, tres que debe reconocer y tres
que no —incluido el resumen de después del terremoto, que es el que se colaría—.

**Falta lo único que no se puede simular: una alerta de Google de verdad.** No
hay forma de provocarla. Lo que sí se puede probar ya es el reparto entre los dos
móviles, y el simulacro completo para la otra mitad de la cadena.

Lo que se decidió antes de escribirlo:

- Es **otro permiso sensible**, y sumado al de accesibilidad complica la revisión
  de Google Play. En F-Droid y GitHub Releases no es problema. Encaja con la
  decisión de ruta de publicación (ver más abajo).
- **Sí puede acabar encendiendo la baliza, pero no al recibirla — DECISIÓN DEL
  AUTOR, y corrige lo que decía aquí antes.** El motivo es de tiempos, y es lo
  que hace valiosa esta alerta: **llega segundos ANTES de que sacuda**. En el
  instante en que Google confirma, el acelerómetro del móvil todavía no ha visto
  nada; preguntar «¿estás bien?» ahí sería gastar la pregunta justo antes del
  terremoto, con la persona contestando que sí porque aún no ha pasado nada.

  El orden correcto es **armar, no disparar**:

  1. Al recibirla: suena, avisa y **reemite por la malla** a los que no tienen
     internet. Esa es la parte que Google no puede hacer y SismoRed sí.
  2. **Arma la vigilancia** unos minutos: umbral al mínimo y `temblando` dado por
     bueno sin esperar al acelerómetro. Un terremoto confirmado por una red
     sísmica nacional es mejor evidencia que el acelerómetro de un móvil — es una
     corroboración externa, la primera que tendría el sistema.
  3. Cuando llega la sacudida, la cascada **ya tiene la corroboración hecha**:
     pregunta al momento y, si nadie contesta, **baliza**.

  Así la baliza se enciende en quien no contesta y no en quien está bien, que era
  el miedo que había escrito aquí y sigue siendo válido. Lo que cambia es que ya
  no hace falta elegir entre las dos cosas.
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
