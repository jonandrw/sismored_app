# Historia y Decisiones — Audio y Detección Acústica

Registro histórico de problemas, experimentos, decisiones y calibraciones en el subsistema de audio de SismoRed Android (`Escucha.kt`, `Interfono.kt`, banco de pruebas `fx sounds/`).

---

## Watchdog de sucesos y desacoplamiento del micrófono (4 de septiembre de 2026)

Arreglado el error del registro de campo del 3 de septiembre («la app preguntó cuatro veces en una hora sin que hubiera pasado nada»):

1. **El suceso atrapado**: `pruebaNueva()` ponía `sucesoDesde = System.currentTimeMillis()`. Cuando `Cascada` devolvía `NADA`, `cerrarSuceso()` solo se llamaba si `(haContestado || preguntaVencida)`. En un falso positivo nadie contesta y la pregunta nunca venció (porque no se llegó a preguntar), así que `sucesoDesde` **se quedaba abierto para siempre**, congelando `sucesoRegimen` y anclando `sucesoEstruendo`. Cualquier movimiento posterior se sumaba a ese estado residual y disparaba la pregunta.
   - **Solución**: `SUCESO_TIMEOUT_MS = 15_000L` y `reprogramarWatchdogSuceso()` en `ServicioSos.kt`. Si un suceso se abre y en 15 segundos no escala a pregunta o alarma, el watchdog llama automáticamente a `cerrarSuceso()`.
2. **El micrófono ya no abre un terremoto**: El micrófono oye la habitación (roces, portazos, tráfico), no las ondas S del suelo. Antes, `onEstruendo` llamaba a `pruebaNueva("estruendo por micrófono")`. Ahora, si no hay sacudida en curso (`sucesoDesde == 0L`), el estruendo solo se anota en el registro sin abrir ningún suceso. Solo si el suelo ya se estaba moviendo (`sucesoDesde > 0L`), el estruendo se suma como evidencia del temblor.

---

## Filtro Acústico Anti-Maquinaria y Detección de Golpes SOS en Escucha.kt (4 de septiembre de 2026)

Calibración de los detectores de sonido contra la biblioteca real (`fx sounds/`), reduciendo los falsos estruendos de **32,7 a 5,4 por hora** (-84%) y eliminando por completo falsos disparos en fuego y ruido ambiente:
1. **Filtro de Catástrofe y Aperiodicidad para Derrumbe**:
   - `NOV_ESTRUENDO = 20.0 dB`: Exige un cataclismo acústico sobre el fondo adaptativo (los derrumbes reales dan de 46 a 63 dB de novedad). Descarta motores y ruidos que antes pasaban con 6–12 dB.
   - `MOD_ESTRUENDO_MAX = 0.25`: Exige que la modulación de envolvente sea caótica y aperiódica (los derrumbes reales dan `<= 0.17`). Descarta maquinaria rotativa, camiones y generadores diésel con régimen de giro fijo (`mod > 0.30`).
   - `SOSTENIDO_ESTRUENDO_MAX = 35 ticks`: Limita la duración máxima de la fase violenta de colapso a ~3,7 s. Si un sonido fuerte persiste de forma continua, es un motor o compresor en marcha, no un derrumbe.
   - `rumble > 0.60`: Exige masa física sónica en frecuencias graves, eliminando falsos positivos en voces de fondo (que daban 0,51).
   - **Resultado en banco**: `Derrumbe` mantiene **8 de 8 (100%) aciertos en 0,9 s de mediana**; `Rescatistas` baja a **0,0 falsos/h** (antes 59,5); `Humanos` baja a **0,0 falsos/h** (antes 12,5).
2. **Compuerta de Cadencia Biológica para Golpes SOS**:
   - `INTERVALO_GOLPE_MIN = 250 ms` y `INTERVALO_GOLPE_MAX = 1250 ms`: La serie de impactos de auxilio contra tuberías o losas (`tap-tap-tap`) debe pertenecer a la cadencia manual humana (50 a 240 golpes por minuto).
   - `rumble > 0.10`: Exige cuerpo de impacto resonante estructural en los ataques bruscos.
   - **Resultado en banco**: En la carpeta `Ambiente`, las crepitaciones de fuego (`zehendrew-fire-sound-ambience`) quedaron completamente silenciadas (**0 falsos/hora** en Ambiente, frente a 408,5/h anteriores).

---

## Sonido: por qué el chasquido no se oía

Tres causas a la vez, y hacían falta las tres:

1. El camino de audio de Android tarda 50-150 ms en arrancar y el chirp era más corto
   que eso: unas veces no salía y otras salía tarde. Lo arregla el pre-rollo de
   silencio (`PRE_MS`) y que `Altavoz` no suelte el `AudioTrack` hasta que la cabeza de
   reproducción haya pasado por el último marco. Antes `colaMs = 0` lo soltaba antes de
   sonar.
2. Sonaba al volumen de ALARMA del usuario. `USAGE_ALARM` salta el modo silencio pero
   **no sube el volumen** — eso solo lo hacía la sirena. Ahora todo lo que emite va
   dentro de `Altavoz.aTodoVolumen`, con cuenta de anidamiento para que dos
   herramientas no se pisen al restaurarlo.
3. **20 ms, y luego 40, seguían siendo inaudibles**: el oído integra energía en unos
   200 ms. `DUR_CHIRP` son ahora **100 ms**, y confirmado a oído. Eso obligó a
   re-sintonizar la captura —ventana de 250 a 400 ms y espera a 280— porque 100 ms de
   chirp más 120 de incertidumbre de latencia no caben en 250. Y 400 ms siguen siendo
   menos que los ~660 entre disparos, así que no se cuela el chasquido anterior.
   **La medida mejoró: 1,20 m ±0 cm, 8/8** — en un filtro adaptado la resolución la
   manda el ancho de banda (6 kHz ≈ 3 cm), no la duración.

El tono del movimiento y de la respiración va a 18,5 kHz, donde el altavoz rinde poco y
el oído casi no llega: **que apenas se oiga es el objetivo**, porque un tono audible
tapa los golpes que intentas escuchar. El rango del mando baja hasta 10 kHz para poder
comprobarlo, con el precio escrito en la propia pantalla.

---

## Interfono: la trampa del micrófono

`mic.cerrar()` **no cierra el micrófono**: `Microfono` cuenta usuarios y la malla lo
tiene abierto por su cuenta. La primera versión reprodujo la voz maximizada con el
micrófono abierto y su propio detector la anotó dos veces como GRITO DE AUXILIO — con
el estruendo armado, eso es una alarma falsa disparada por uno mismo. Se arregló con
`ensordecer(ms)` en `MallaAcustica` y en `Escucha`, que el interfono levanta antes de
cada reproducción por el callback `onAltavoz`.

Y lo de «detectar voz humana» es una **puerta de energía en 300-3400 Hz sobre el fondo
medido**, no un clasificador: el detector de voz de `Escucha` no se enciende nunca
contra las 21 grabaciones humanas, y colgar de él la decisión sería construir sobre lo
que se sabe roto. Ciclo completo probado: «TE HAN CONTESTADO · pico 13 dB sobre un
fondo de −72 dB · 1,6 s».

---

## El problema abierto de verdad: los detectores

La biblioteca de sonidos reales (`fx sounds/`, 63 MP3 en diez carpetas, tres de
ellas todavía vacías) demostró que **el detector no está listo para el campo**. Las
pruebas sintéticas dan 5/5 y la realidad no. La estructura de carpetas y qué se
espera de cada una está en `fx sounds/README.md`; el sitio del archivo **es** su
etiqueta, y el banco la lee de ahí.

Banco de medida, corre en once segundos y sin móvil:

```bash
cd "fx sounds" && ./convertir.sh && python banco.py wav
```

Las perillas van por variable de entorno: `UMB_EST_DB=-30 python banco.py wav`. Los
valores por defecto **son los de `Escucha.kt`**: correr el banco sin tocar nada mide
la app que se instala, y eso ahora es cierto —no lo era.

### Lo que el banco dice hoy

| carpeta | acierta | se equivoca |
|---|---|---|
| Derrumbe (8) | **8**, en 0,9 s de mediana | — |
| Escombros (4) | 0 | 2 leídos como grito o animal, 2 mudos y sin acercarse |
| Animales (11) | 1 | **8 leídos como «grito»**, o sea como una persona |
| Humanos (21) | — | 1 estruendo, y un llanto ahogado leído como «animal» |
| Maquinaria (11) | 8 en silencio | 3 falsos derrumbes |
| Rescatistas (7) | — | 2 falsos derrumbes (helicóptero y camión) |

Y una que sobrevivió a la corrección del banco, así que ya se puede firmar: **el
detector de voz no se enciende ni una sola vez** contra las 21 grabaciones humanas
—ni con el tope de oscilación bueno ni con rodaje—, mientras que «grito» salta en 13
de ellas. Era la sospecha sobre la que se decidió que el interfono no colgara de él,
y era correcta.

Y la tabla que de verdad decide, **falsos por hora** sobre las carpetas que tienen
que callar: **32,7 estruendos/hora** y 267/hora de los otros cuatro, sobre once
minutos de audio. Un detector que acierta el 99 % pero grita una vez por hora es
inservible, porque a la tercera vez nadie corre. Dos advertencias sobre ese número:

- Es la cota superior **por el lado del micrófono**. Desde que el estruendo pide
  corroboración del acelerómetro, un estruendo de esos solo levanta la sirena si el
  móvil se está moviendo además.
- Es material adverso a propósito —generadores, martillos, sirenas—, no una casa un
  martes por la tarde. La cifra de uso normal necesita **horas** de `Ambiente/`, y
  hoy hay 44 segundos. Hasta entonces esa cifra no existe; no es que sea buena.

### Lo que cambió en el banco, y por qué los números de antes eran falsos

Aquí decía «derrumbe 3 de 8». Eran 3 de 8 **del banco**, no del detector:

1. **El banco se había separado de `Escucha.kt`.** El tope de oscilación del tono
   seguía en 0,35 cuando en la app era 1,2 —de ahí salió el «la voz no se enciende
   NUNCA», que era un artefacto—, al grito y a la voz les faltaba la puerta de
   novedad, y el umbral del estruendo estaba en −40 en vez de −25. Si se toca un
   umbral, se toca en los dos sitios en la misma sesión.
2. **Cada clip arrancaba en frío.** Los efectos de sonido vienen recortados al
   ataque, así que el fondo adaptativo se enganchaba al propio derrumbe durante los
   doce ticks de calentamiento y la novedad salía cero: el detector se quedaba ciego
   justo en los archivos más brutales. La app no funciona así —el micrófono lleva
   horas abierto oyendo una habitación—, y su propio autotest ya metía silencio
   delante (`SILENCIO_N`). Ahora el banco pone delante 1,7 s de habitación a
   −72 dBFS, que es el fondo que midió el interfono en el A10s. Solo con eso, el
   derrumbe pasó de 3 de 8 a 8 de 8 sin tocar una línea de Kotlin.
3. **Menos `Ambiente/` y `Maquinaria/`**, que arrancan en frío a propósito: un
   generador lleva media hora encendido cuando el móvil lo oye, ya *es* el fondo, y
   ponerle delante una habitación en silencio le regalaría tres falsos que no tiene.
   Esa decisión sola mueve la maquinaria de 6 falsos derrumbes a 3.
4. **Ya no para en el primer disparo**, que es lo que hacía imposible contar falsos
   por hora: un archivo con un falso y otro con veinte contaban igual. Ahora corre
   hasta el final con el enfriamiento de cada detector, el mismo del móvil.

También cuenta lo que la app llama **no concluyente** —los ticks en que había dos
candidatos y ninguno se despegó del otro—, y sale un dato: casi nunca pasa. El motor
no duda entre dos clases; o ve una, o no ve nada. Y por archivo dice **cuánto llegó a
llenarse el acumulador**, que es lo que separa «no dispara por poco» de «no lo ve en
absoluto»: los dos escombros mudos están al 0 %, no al 90.

**Callejones sin salida ya explorados** — no repetirlos: para separar un generador
diésel de un derrumbe no sirve la planitud espectral (se solapan, 0,00–0,18 contra
0,16), ni el flujo espectral marco a marco (0,50–0,66 contra 0,35–0,71), ni la
distancia al espectro medio de los últimos segundos (0,37–0,64 contra 0,30–0,66).
La pista que queda sin probar: la modulación de la envolvente a baja frecuencia —
un motor tiene una periodicidad marcada a su régimen de giro que un derrumbe no.

Y faltan grabaciones: **Alarmas y Golpes están vacías**. La de golpes es la que más
falta hace, porque golpear una tubería es como pide ayuda alguien atrapado, y ese
detector no se ha comprobado nunca contra audio real. Hoy un fuego crepitando lo
dispara.

---

## Interfono acústico (detalle de diseño)

`Interfono.kt`, botón HABLAR CON QUIEN ESTÁ DEBAJO en Búsqueda. No es una llamada y no
puede serlo: ni los 31 bytes del anuncio BLE ni la malla de tonos llevan voz. Es medio
dúplex en el móvil del rescatista: mide el fondo (0,5 s), graba 4 s, lo reproduce hacia
abajo maximizado a +12 dB, abre el canal 4 s esperando respuesta —y lo deja abierto
mientras dure, hasta 15 s— y devuelve amplificado lo que ha llegado. Cierra a los 700 ms
de silencio. Aparece solo con un hallazgo al 70 % de proximidad o más.

Ciclo completo probado en el A10s: «TE HAN CONTESTADO · pico 13 dB sobre un fondo
de −72 dB · 1,6 s de respuesta».

Dos cosas que costaron y no hay que volver a pisar:

- **`mic.cerrar()` no cierra el micrófono.** `Microfono` cuenta usuarios, y la
  malla y los detectores lo tienen abierto por su cuenta. La primera versión
  reprodujo la voz maximizada con el micrófono abierto y su propio detector la
  anotó dos veces como GRITO DE AUXILIO — con el estruendo armado, eso es una
  alarma falsa disparada por uno mismo. Se arregló con `ensordecer(ms)` en
  `MallaAcustica` y en `Escucha`, que el interfono levanta por el callback
  `onAltavoz` antes de cada reproducción.
- **Lo de «detectar voz humana» es una puerta de energía en 300–3400 Hz sobre el
  fondo medido, no un clasificador.** A propósito: el detector de voz de `Escucha`
  no se enciende NUNCA contra las 21 grabaciones humanas reales, y colgar de él la
  decisión de si alguien ha contestado sería construir sobre lo que se sabe roto.
  La puerta confunde un golpe metálico rítmico con una voz, pero no falla hacia el
  lado peligroso: no dice «no ha contestado nadie» cuando sí.

> **Falta probarlo con dos personas y un escombro de verdad**, y ahí se verá si los
> 8 dB sobre el fondo (`SOBRE_FONDO_DB`) y los 250 ms de sostenido son los buenos.
> En una habitación normal el fondo ya lo dispara: la prueba de arriba dio «te han
> contestado» con el ruido de la sala.


**Interfono acústico** (`Interfono.kt`, botón HABLAR CON QUIEN ESTÁ DEBAJO en
Búsqueda). No es una llamada y no puede serlo: ni los 31 bytes del anuncio BLE ni
la malla de tonos llevan voz. Es medio dúplex en el móvil del rescatista: mide el
fondo (0,5 s), graba 4 s, lo reproduce hacia abajo maximizado a +12 dB, abre el
canal 4 s esperando respuesta —y lo deja abierto mientras dure, hasta 15 s— y
devuelve amplificado lo que ha llegado. Cierra a los 700 ms de silencio. Aparece
solo con un hallazgo al 70 % de proximidad o más.

