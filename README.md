# SismoRed Android — Fases 1 y 2

La fase 1 era la que decidía si el proyecto es viable: un servicio en primer plano que hace sonar la
sirena **con la pantalla bloqueada y el móvil en silencio**, y que se dispara **sin tocar la pantalla**.
Las pruebas de campo de la versión web fallaron justo en esos dos puntos, y ninguno tiene arreglo en un
navegador. Aquí sí.

La fase 2 es la que convierte un móvil que grita en una red que avisa: la **malla acústica**.

## Qué hace y por qué

| Pieza | Archivo | Qué resuelve |
|---|---|---|
| `Sirena` | `Sirena.kt` | Escribe al canal **ALARMA**, que suena aunque el móvil esté en silencio, y sube ese canal al máximo desde código. Barrido 2–4 kHz, donde el oído es más sensible |
| `ServicioSos` | `ServicioSos.kt` | Servicio en primer plano + wake lock parcial: sigue vivo con la pantalla apagada, indefinidamente |
| `ServicioTeclas` | `ServicioTeclas.kt` | **Tres pulsaciones de volumen = SOS**, a través del bolsillo, sin desbloquear ni mirar. Es un `AccessibilityService`: el único que recibe las teclas con la pantalla apagada |
| `Sismografo` | `Sismografo.kt` | Acelerómetro a 50 Hz con la pantalla apagada. Umbral **3,0 m/s²** |
| `Microfono` | `Microfono.kt` | **Un solo micrófono para toda la app.** No es comodidad: dos `AudioRecord` sobre la misma fuente no conviven, el segundo devuelve silencio o le quita la captura al primero |
| `MallaAcustica` | `MallaAcustica.kt` | La alerta salta de móvil a móvil por el aire, sin red: MARK 16 kHz + tono de salto |
| `Escucha` | `Escucha.kt` | Los cinco detectores: derrumbe, grito, voz, animal y golpes rítmicos. Más el «te oigo» de respuesta |
| `Sonda` | `Sonda.kt` | Eco (qué hay alrededor y a qué distancia), doppler (¿se mueve algo?) y barrido de penetración |
| `Altavoz` | `Altavoz.kt` | Sacar PCM por el canal de ALARMA, que es el que suena en silencio |

## Compilar

Todo está ya instalado en esta máquina. `java` no está en el PATH, así que hay que apuntarlo:

```bash
cd SismoRedAndroid && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug
```

El APK sale en `app/build/outputs/apk/debug/app-debug.apk` (5,7 MB).

## Instalar en el móvil

Activa **Opciones de desarrollador → Depuración USB**, conecta por cable y:

```bash
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" install -r app/build/outputs/apk/debug/app-debug.apk
```

Sin cable: copia el APK al móvil y ábrelo, permitiendo instalar de orígenes desconocidos.

## La prueba que decide todo

1. Abre la app y concede la notificación.
2. Pulsa **PERMITIR QUE SIGA ACTIVA SIN BATERÍA** y acepta. Sin esto, Doze puede matar el servicio
   a las horas, justo cuando más falta hace.
3. Pon el móvil **en silencio**.
4. **Bloquea la pantalla** y déjalo en el bolsillo.
5. Pulsa el botón de volumen **tres veces seguidas** en menos de 3 segundos.

Debe sonar la sirena a todo volumen. Si suena, el proyecto es viable y se sigue con la fase 2. Si no
suena, hay que investigar qué capa del fabricante lo bloquea antes de escribir una línea más.

Prueba también dejarlo sonando un rato con la pantalla apagada, para confirmar que el sistema no lo
mata a los pocos minutos.

## Estado tras la prueba en un Redmi (Android 15, HyperOS)

| | |
|---|---|
| Sirena con la pantalla bloqueada | **FUNCIONA**, confirmado a oído |
| Servicio en primer plano tras el bloqueo | **FUNCIONA** (`isForeground=true` en `dumpsys`) |
| Disparo por 3 pulsaciones de volumen | **FUNCIONA**, con `AccessibilityService` |
| Malla: micrófono a 48 kHz en primer plano | **FUNCIONA**. Este Redmi **no** ofrece `UNPROCESSED`; cae a `VOICE_RECOGNITION` |
| Malla: decodificador, los cuatro saltos | **FUNCIONA**, `AUTOTEST OK` en el móvil, ~18 ms por salto |
| Malla: recepción entre dos móviles | **SIN PROBAR** — hace falta un segundo móvil |
| Micrófono único compartido por malla y escucha | **FUNCIONA**, 48 kHz, un solo `AudioRecord` |
| Detector de tono (NSDF) | **FUNCIONA**: 110→109, 220→219, 700→700, 1400→1400 Hz, y el ruido no da tono |
| Los cinco detectores contra sonidos reales | **SIN PROBAR** — falta calibrar en campo, sobre todo el derrumbe |
| Filtro adaptado de la sonda | **FUNCIONA**: eco simulado a 1,20 m, medido 1,17 m |
| Sonda, doppler y barrido contra paredes reales | **SIN PROBAR** — hay que medir contra distancias conocidas |

**Fase 1 completa.** El escenario que da sentido al proyecto está demostrado en hardware
real: alguien atrapado, sin poder mirar ni desbloquear el móvil, aprieta el botón de
volumen tres veces a través de la tela del bolsillo y suena la sirena a todo volumen
aunque el teléfono esté bloqueado y en silencio.

### El único mecanismo que funciona, y dos que no (no repetir)

Lo que funciona: **`AccessibilityService` con `canRequestFilterKeyEvents`** y
`flagRequestFilterKeyEvents`. Es el único que recibe `onKeyEvent` con la pantalla
apagada. Devuelve `false` para no consumir el evento, así el volumen sigue normal.
El precio: el usuario lo activa a mano en Ajustes → Accesibilidad, así que hay que
explicarle antes por qué se lo pides. El servicio solo declara `typeWindowStateChanged`
y filtrado de teclas: **no lee la pantalla ni otras apps**.

Ojo con Xiaomi: HyperOS desactiva servicios de accesibilidad al reiniciar o tras un
tiempo sin uso. Conviene comprobarlo al arrancar y avisar — la app ya lo muestra en
el estado (`teclasActivas()`).

### Dos mecanismos descartados para los botones (no repetir)

1. **`ContentObserver` sobre `Settings.System`.** Inútil: desde hace varias versiones
   Android ya no guarda ahí los volúmenes, los lleva `AudioService` por dentro. En
   Android 15 no llega ni un aviso.

2. **`MediaSession` con `VolumeProvider` remoto.** Parecía funcionar —`dumpsys media_session`
   llegó a mostrar `Media button session is red.sismo/SismoRed`— pero tras reinstalar
   pasó a `null` y no volvió. El motivo: esa prioridad la gana la última app que
   **reprodujo audio de verdad**, y una sesión que nunca ha sonado no se la gana sola.
   Funcionaba por herencia de la sesión anterior, no por mérito propio. No es fiable.

## Fase 2 — la malla acústica

Dos tonos simultáneos: la portadora **MARK a 16 kHz**, presente en toda baliza, y un **tono de salto**
(16,8 / 17,2 / 17,6 / 18 kHz para saltos 1 a 4). Quien la oye la reemite con salto+1, hasta 4. Es el
mismo protocolo que la PWA, así que **una app nativa y un navegador se entienden entre sí**.

Por qué por el aire y no por BLE: iOS no da Bluetooth al navegador, y en nativo restringe el
advertising en segundo plano al área de overflow, que casi solo ven otros iPhone. El infrarrojo ya no
existe. Micrófono y altavoz están en todas partes y no piden emparejamiento.

### Las cuatro decisiones que no son obvias

1. **La fuente de audio tiene que ser cruda.** Se pide `AudioSource.UNPROCESSED` y, si el móvil no lo
   soporta, `VOICE_RECOGNITION`. Además se crean el cancelador de eco, el supresor de ruido y el
   control automático de ganancia **solo para apagarlos**. Cualquiera de los tres borra los tonos de
   17 kHz — no los atenúa, los borra, y la malla se queda sorda sin dar ningún síntoma.

2. **Goertzel, no FFT.** Solo hay cinco frecuencias que mirar. Sale el mismo número que daría la
   transformada entera calculando una fracción, y aquí lo que se ahorra es batería de alguien atrapado.

3. **La ventana es corta a propósito** (2048 muestras, 42 ms). No es por precisión: es para poder
   contar los cortes de la ráfaga. Con la ventana de 170 ms de la PWA los silencios de 150 ms se
   emborronan y la cadencia deja de verse.

4. **El salto ganador tiene que despegarse 6 dB del segundo.** Con la sirena local a todo volumen el
   altavoz satura y la intermodulación deja algo de energía en los otros tonos. Leer un salto de más
   solo corta la cadena antes de tiempo; leerlo de menos reinyecta la alerta y la deja dando vueltas.
   Ante la duda se descarta el marco.

### Que nadie dispare alarmas ajenas con un generador de tonos

La malla no autentica a nadie, y no hay forma de firmar criptográficamente un canal de cuatro tonos.
Lo que sí se puede exigir es constancia, y son tres filtros encadenados:

- **Dos detecciones separadas al menos 3 s**, dentro de una ventana de 30 s.
- **Cadencia**: la señal tiene que venir troceada en ráfagas (250 ms de tono, 150 de silencio). Un
  tono continuo desde un generador no la tiene, y se queda fuera.
- **Freno de amplificación**: 30 emisiones por minuto como máximo, para que ni un bucle ni un atacante
  puedan hacer emitir sin fin.

El precio son unos segundos de retraso en confirmar. A cambio, el ataque trivial deja de existir.

### Silenciar no desconecta a nadie

Pulsar DETENER calla **este** móvil un minuto. La malla sigue escuchando y sigue retransmitiendo lo
que oiga. Quien se harta del ruido no debería poder cortar la cadena para todos los que vienen detrás.

### Se comprueba a sí misma en cada arranque

Antes de empezar a grabar, el decodificador se inyecta una baliza sintética de cada salto y comprueba
que la lee. Cuesta unos milisegundos y evita el peor escenario: la malla aparentando estar viva y
sorda de verdad, sin que nadie se entere hasta que haga falta. Sale en logcat como `AUTOTEST OK`.

### El permiso de micrófono se explica antes de pedirlo

Es el permiso que más asusta y el que menos se parece a lo que la gente teme. La malla mira si hay
tonos de 16-18 kHz en marcos de 42 ms y los tira: no graba, no guarda y no sube nada — la app entera
funciona sin red. Se ofrece una sola vez; si se deniega, queda el aviso «MALLA APAGADA», que es
pulsable y lleva a Ajustes.

### Diagnóstico

La app ya registra en logcat cada paso del camino:

```bash
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" logcat -s SismoRed:*
```

- `pulsacion N/3` → las teclas llegan
- `DISPARO por botones` → se alcanzó el umbral
- `sirena fallo` → el audio falló, con su excepción
- `malla: AUTOTEST OK` → el decodificador se valida en este móvil
- `malla: malla activa · escuchando…` → el micrófono arrancó, con la tasa real y si la fuente es cruda
- `malla: baliza sin confirmar` → se oyó, pero aún no cumple la corroboración
- `malla: BALIZA CONFIRMADA` → alerta aceptada; dispara la alarma o se retransmite
- `malla: emitida baliza salto=N` → la baliza salió por el altavoz

## Fase 2 — la escucha forense

Cinco detectores sobre el mismo micrófono, todo heurísticas de señal, sin modelos ni red. Cada
detección lleva su confianza precisamente porque se equivocan: un falso positivo cuesta batería y un
falso negativo cuesta una vida, así que el sesgo es hacia oír de más.

| Detector | Qué lo define |
|---|---|
| Derrumbe | Muy fuerte, energía abajo y **sin tono**. Lo del tono no es un adorno: una voz masculina también da 100 % de energía grave, y lo único que la separa de un derrumbe es que tiene tono y el derrumbe es ruido |
| Grito | Fuerte, tónico, fundamental entre 300 y 1200 Hz |
| Voz | Fundamental de habla **y que oscile**. Un motor, un generador o un transformador sostienen la nota clavada; una persona nunca mantiene el tono exacto |
| Animal | Tónico pero fuera del rango de habla, o muy agudo y de ataque brusco |
| Golpes | Tres ataques bruscos en 5 s. Es el modo en que un atrapado pide ayuda |

Las dos decisiones que no son obvias: **la voz se separa por el tono, no por la banda** (a una voz
masculina no se le puede exigir energía en 400-3000 Hz, se le concentra por debajo de 150), y **de una
máquina se separa porque la voz oscila**.

El acumulador de cada detector suma +2 por marco que cumple y resta 1 por marco que no. Con +1/−1 el
contador se quedaba clavado a la mitad y no disparaba nunca, porque el habla tiene pausas y
consonantes sordas.

Si alguien grita, golpea o habla cerca mientras estamos pidiendo ayuda, **se le contesta** con dos
pitidos de 1 kHz. Saber que al otro lado hay algo que responde cambia lo que hace una persona
atrapada.

El tono fundamental va por NSDF (McLeod) diezmando x2. Se elige el **primer** pico que llega al 85 %
del máximo, no el máximo: ese es el paso que evita confundir 700 Hz con 100 Hz.

La FFT está escrita a mano (radix-2, en `Escucha.kt`) porque Android no trae ninguna y no se va a
meter una dependencia de terceros en una app que debe funcionar sin red ni cuentas. Está contrastada
contra numpy: error relativo 1e-15, que es el redondeo de coma flotante.

**El umbral del derrumbe no está calibrado en campo.** Es el único detector que dispara la alarma
solo, y solo lo hace con la vigilancia armada. Vale la mismísima lección que el umbral sísmico: se
mide en un móvil real en uso diario, nunca contra ruido generado.

## Fase 2 — la sonda acústica

Tres herramientas manuales; ninguna corre sola gastando batería.

**Eco.** Un chirp de 20 ms entre 2 y 8 kHz, y un filtro adaptado que correlaciona lo grabado con lo
que se emitió. El primer pico es el sonido directo altavoz→micrófono y todo lo que viene después son
ecos; **se mide relativo a ese pico**, y por eso no hay que calibrar la latencia de audio, que es
distinta en cada modelo. Da las distancias a las superficies y el RT60: un hueco pequeño entre
escombros se apaga en milisegundos, una nave industrial tarda segundos.

**Doppler.** Un tono fijo de 18,5 kHz; si algo se mueve cerca, el eco vuelve corrido y aparecen
bandas laterales. Dice que **se mueve un cuerpo**, no cuántos ni dónde ni si respiran.

> Interacción que no es evidente: 18,5 kHz cae dentro de la banda donde la malla mide su ruido de
> fondo, así que **mientras el doppler suena la malla queda casi sorda**. Dura unos segundos y lo
> lanza una persona a mano; por eso no puede quedarse corriendo solo. Queda avisado en el registro.

**Barrido de penetración.** De 80 Hz a 4 kHz cada 4 s, en diente de sierra. Los escombros filtran
distinto según la frecuencia y nadie sabe cómo filtra ESTE montón: lo grave atraviesa masa, lo agudo
lo localiza mejor el oído. Barriendo entero, algo pasa sí o sí.

Las tres van **por el servicio, no por la pantalla**, porque el micrófono es único: si la actividad
abriera el suyo, dejaría sorda a la malla.

## La capa visual

La misma que la PWA, porque quien instale la app después de haber usado la web no debería notar que
ha cambiado de programa: paleta de `SismoRed/ui.css`, Montserrat auto-alojada, **bordes rectos en
todo** (ningún radio distinto de 0), barra superior con el logotipo y dos iconos, y cuatro pestañas
abajo — Inicio, Red, Entorno y Registro.

Tres cosas que costaron y conviene no volver a descubrir:

- **La Montserrat de la PWA es woff2, que Android no lee.** Es una fuente variable de un solo eje;
  se instancia a los pesos 400/600/700 y se convierte a TTF con `fonttools`. Los TTF ya están en
  `res/font/`, así que esto solo hace falta si se cambia la fuente.
- **El `logotipo.png` es 1536×1024 pero el contenido solo ocupa la franja central.** Recortado al
  vuelo se pintaba negro sobre negro y no se veía nada; ahora va recortado a su caja real.
- **Material redondea los botones por defecto.** Hay que declarar un `shapeAppearance` con
  `cornerSize` de 0, si no el diseño se va al traste sin que nadie toque el layout.

Falta la pestaña **Ficha** de la PWA (ficha médica de emergencia). No es diseño: es una función que
la app nativa todavía no tiene.

## La prueba de la malla, con dos móviles

Es la que queda pendiente y la que decide si el canal acústico sirve de verdad.

1. Los dos móviles con la app abierta, a unos metros.
2. Pulsa **PÁNICO** en uno.

El otro debe anotar `BALIZA CONFIRMADA · salto 1` en unos segundos y disparar su propia alarma. Tarda
a propósito: hacen falta dos balizas separadas 3 s. En la pantalla del segundo móvil se ve el registro
en vivo, que es lo que distingue «no llegó» de «llegó y se descartó».

Merece la pena medir hasta dónde llega: con puertas de por medio, con ruido, y con los móviles en el
bolsillo, que es como van a estar.

## Qué NO tiene todavía

Ni los cinco detectores de sonido, ni sonda, ni BLE, ni modo rescate. Todo eso está escrito y validado
en la versión web y se porta después — es lógica de señal, se traduce casi línea a línea.

La interfaz sigue siendo un banco de pruebas feo a propósito: la paleta, la tipografía Montserrat, los
bordes rectos y el logotipo están en `../SismoRed/ui.css` y `../SismoRed/faviconsismored_io/`.

Siguientes fases en `../SismoRed/PLAN-NATIVO.md`.
