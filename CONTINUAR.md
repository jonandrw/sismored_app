# SismoRed Android — dónde retomar

Estado al 12 de agosto de 2026. Todo lo descrito aquí está **compilando e instalado
limpio** en tres móviles: Huawei STK-LX3 (Android 10), Samsung A10s (Android 11) y
Redmi 24094RAD4G (Android 15). Ninguno da errores al arrancar.

Compilar sin `java` en el PATH:

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug
```

---

## Lo arreglado tras probar en campo

Todo esto salió de usar la app en los tres móviles, y todo está compilando e
instalado en los tres.

**El pitido que sobrevivía a DETENER.** El hilo del tono continuo se apagaba con
una variable LOCAL a cada medida: si el análisis moría por una excepción nadie la
bajaba y el tono sonaba para siempre, y DETENER no sabía nada de la sonda. Ahora la
bandera (`tonoVivo`) es de la clase, se baja en el `finally` pase lo que pase, y
`parar()` calla barrido, tono e interfono. Además cada herramienta se apaga con su
propio botón: si ya suena, la segunda pulsación CALLA en vez de relanzar.

**El buscador disparaba su propia alarma.** No era un fallo del rastreo: era la
malla haciendo su trabajo — oía la baliza acústica de la víctima, la confirmaba y
lanzaba el pánico local. Un rescatista con su sirena encendida no oye los escombros,
que es lo único que tiene. Ahora `ServicioSos.buscando` (lo pone la pantalla, igual
que `mirando`) hace que la alerta se anote y se siga RETRANSMITIENDO a la red, pero
este móvil no suene. El aviso al que busca es vibración, destello de pantalla y
flash, y la cadencia se acelera al acercarse: 1,5 s al 70 % y 280 ms encima.

**La barra de proximidad estaba mal escalada.** Era una recta de −100 a −40 dBm, así
que a un metro o dos (≈−70 dBm) daba 50 % y el interfono, que pide 70 %, no aparecía
nunca. Ahora son dos tramos con anclajes medidos en la mano: −50 dBm o más → 100 %,
−78 → 70 %, −95 → 0 %. El umbral vive en `Rastreador.PCT_CERCA` y lo usan la barra,
el aviso silencioso y el interfono: si fueran números distintos, la barra diría una
cosa y la app haría otra.

**Los chasquidos no se oían por tres razones a la vez**, y hacían falta las tres:
el arranque del camino de audio se comía un sonido más corto que él (lo arregla el
pre-rollo y el vaciado real del `AudioTrack`); sonaba al volumen de ALARMA que
tuviera el usuario, porque `USAGE_ALARM` salta el silencio pero NO sube el volumen
—eso solo lo hacía la sirena— y ahora la ráfaga entera va dentro de
`Altavoz.aTodoVolumen`; y 20 ms es demasiado corto para que el oído lo integre, así
que `DUR_CHIRP` son 40 ms. La resolución no sufre: en un filtro adaptado la manda el
ancho de banda (6 kHz ≈ 3 cm), no la duración. Autotest tras el cambio: 1,20 m
±1 cm, 8/8.

**La malla ya se cree la alerta a la primera si está temblando.** `Sismografo`
publica `ultimoTemblor` (media rápida por encima de MEDIO umbral: el suelo se mueve
aunque no sea para disparar), el servicio lo convierte en `ServicioSos.temblando`
con un minuto de validez —las réplicas vienen justo detrás— y `corroborada()` se
salta la doble escucha. La cadencia de ráfagas se sigue exigiendo: eso descarta un
ruido cualquiera, no a un impostor. Y el impostor tendría que estar dentro del mismo
terremoto.

**Búsqueda es una pestaña**, la cuarta de cinco. Ya no es una vista de segundo nivel
detrás de un botón en mitad de Inicio (el botón sigue como atajo).

**La tarjeta del buscador trae nombre de pila y grupo sanguíneo.** La cuenta de los
31 bytes: banderas 3 + potencia 3 + UUID 4 + cabecera del service data 4 = 14, así
que quedan 17 para el contenido. Versión, estado, salto y grupo son cuatro, y el
nombre se lleva los trece que sobran — por eso va el nombre de pila y no el
completo. La edad se cayó para hacerle sitio: llamar a alguien por su nombre sirve,
saber que tiene 34 años no. Si el anuncio no cupiera, `onStartFailure` reintenta SIN
nombre: una baliza sin nombre sigue sirviendo, no tener baliza no. Probado entre dos
móviles: llegan «Guillermina · Grupo O+» y «Juan · Grupo O+».

> Privacidad: esto cambia la promesa de que la ficha no sale del móvil. Ahora el
> nombre de pila y el grupo se EMITEN mientras la alarma o el rescate estén activos,
> y cualquiera con un escáner cerca puede leerlos ese rato. Está escrito donde el
> usuario lo ve, en el paso 3 de CÓMO FUNCIONA ESTA FICHA. El resto de la ficha
> sigue sin salir, y el plan del GATT sigue en pie para ella.

**Los muros de ayuda son tarjetas de pasos.** Los párrafos de tres y cuatro frases
al pie de las tarjetas se convirtieron en pasos numerados con icono (el ladrillo
`paso`): CÓMO VIAJA LA ALERTA en Red, CÓMO FUNCIONA ESTA FICHA en Ficha, CÓMO SE USA
en Respuesta, y el interfono en tres pasos, porque su ciclo tiene un momento para
hablar y otro para callarse y eso no se lee del tirón mientras suena.

**Textos:** los ghost pasaron de altura fija a `match_parent` con mínimo de 100 dp y
el rótulo se autoescala — con 108 dp clavados, en pantalla estrecha una línea se
partía en dos y se salía de la caja. Y los valores de estado van capitalizados
(«Escuchando», «Apagada», «Buscando», «Te estás acercando»).

---

## Lo siguiente (1): la ficha completa por wifi

Pedido en campo. Si hay una wifi —el punto de acceso de un campamento de rescate, o
el compartido de cualquiera— se aprovecha para mandar la ficha **entera** a los
móviles que estén en ella. Resuelve lo mismo que el GATT y es mucho más simple,
porque en una red local no hay que descubrir a nadie.

Decisiones tomadas, para no volver a pensarlas:

1. **UDP a la difusión de la subred**, puerto 51789. Una ficha completa son unos
   cientos de bytes: cabe de sobra en un datagrama, así que no hace falta ni
   servidor ni conexión ni emparejar nada. Un `DatagramSocket` con
   `setBroadcast(true)` a `255.255.255.255` y a la difusión de la interfaz — hay que
   mandar a las dos, porque algunos Android descartan la genérica.
2. **Emite cada 3 s y SOLO con alarma o rescate activos.** Es la misma regla que la
   baliza de radio, y por el mismo motivo. Se enciende en `panico()` y `rescate()` y
   se apaga en `parar()`.
3. **Escucha cuando alguien está buscando** (`ServicioSos.buscando`), y también en
   reposo si la vigilancia está encendida: recibir no cuesta batería apreciable.
4. **JSON plano** con versión, nombre, sangre, edad, medicación y contacto, más un
   `id` estable (el que ya usa la baliza) para poder casarlo con el hallazgo BLE de
   la lista. Si no casa, se enseña igual en su propio bloque.
5. **En la interfaz**: dentro de la tarjeta de Búsqueda, debajo de QUÉ SE HA OÍDO, un
   bloque «FICHA COMPLETA RECIBIDA POR WIFI» con las que hayan llegado. Y en
   `detalleHallazgo()`, si hay ficha por wifi de ese id, se rellena con ella en vez
   de decir que la edad y las alergias no viajan.

> **Y esto hay que decirlo donde el usuario lo vea, sin adornos**: por wifi va la
> ficha COMPLETA —nombre y apellido, alergias, medicación y contacto— a cualquiera
> que esté en la misma red, no solo trece bytes de nombre como en la radio. Es una
> disclosure mucho mayor que la de la baliza. Va en el paso 3 de CÓMO FUNCIONA ESTA
> FICHA, junto a lo que ya dice de la radio, y con la misma condición: solo mientras
> la alarma o el rescate estén activos.

No hace falta permiso nuevo: `INTERNET` ya está en el manifiesto para la cola de
partes, y UDP en la red local no pide nada más.

---

## Lo siguiente (2): la ficha completa por GATT

Hoy el anuncio BLE lleva versión, estado, saltos, grupo sanguíneo y el nombre de
pila, y con eso se agotan los 17 bytes útiles de los 31 del anuncio. La edad, las
alergias, la medicación y el contacto **no viajan**, y el diálogo de la vista
Búsqueda lo dice para que nadie espere un dato que no va a llegar.

Las decisiones ya están tomadas; falta escribirlo:

1. **`Baliza.setConnectable(true)`** — hoy está en `false` a propósito. Cuesta
   batería, así que solo debe ser conectable mientras haya alarma o rescate.
2. **`BLUETOOTH_CONNECT`** es obligatorio desde Android 12 para abrir GATT, en los
   dos lados. Es el sexto permiso: va al manifiesto y a la pantalla de bienvenida
   (`permisosNecesarios()` en `MainActivity`), con su «para qué sirve».
3. **Servidor GATT en el móvil enterrado**: un servicio con una característica de
   lectura con la ficha completa. Caben 512 bytes; el problema de los 31 desaparece.
4. **Cliente en el del rescatista**: al pulsar la tarjeta, `connectGatt`, leer y
   rellenar `detalleHallazgo()`, que ya existe y ya tiene el sitio.
5. **La característica solo se rellena con alarma o rescate activos.** Fuera de eso
   devuelve vacío, o se rompe la promesa de que la ficha no sale del móvil.

Nota de privacidad que juega a favor: con GATT la ficha deja de emitirse y pasa a
**entregarse cuando alguien la pide**, así que no se puede capturar escuchando de
forma pasiva. Es más segura que meterla en el anuncio.

---

## El problema abierto de verdad: los detectores

La biblioteca de sonidos reales (`fx sounds/`, 63 MP3 en 9 categorías, ya
convertidos a WAV mono 48 kHz en `fx sounds/wav/`) demostró que **el detector no
está listo para el campo**. Las pruebas sintéticas dan 5/5 y la realidad no:

| carpeta | acierta | se equivoca |
|---|---|---|
| Derrumbe (8) | 3 | 5 pasan por alto |
| Escombros (4) | 0 | los 4 pasan por alto |
| Animales (11) | 0 | 9 leídos como «grito» |
| Humanos (21) | — | «voz» no se enciende NUNCA |
| Maquinaria (11) | 6 en silencio | 3 falsos derrumbes |

Banco de medida, corre en segundos y sin móvil:

```bash
cd "fx sounds" && python banco.py wav
```

Las perillas van por variable de entorno: `UMB_EST_DB=-25 python banco.py wav`.

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

## Trampas de esta sesión, para no volver a pisarlas

- **Los comentarios XML de Android no admiten `--` dentro.** Nada de separadores
  `<!-- ---- -->`.
- **No escribir `\n` dentro de cadenas Kotlin desde scripts de Python.** Se
  convierten en saltos de línea reales y parten el archivo. Arma listas de líneas y
  únelas con `System.lineSeparator()`.
- **`adb shell input tap/swipe` lanza `SecurityException`** (falta `INJECT_EVENTS`).
  Para ver una vista que no sea la inicial hay que meter un puente temporal en
  `MainActivity.onCreate` que lea un extra del intent, capturar de una tanda con
  `screencap` + `pull`, y **quitarlo antes de cerrar**. Los `adb shell` con rutas
  necesitan `MSYS_NO_PATHCONV=1` en Git Bash.
- **Nunca comprobar el clasificador sobre la instancia que está escuchando.**
  `tick` lleva estado acumulado y el hilo del micrófono la está usando a la vez;
  eso tiraba la app al arrancar. Se usa una instancia aparte (`comprobarDetectores`).
- **Samsung saca la barra de volumen cada vez que arranca un flujo de audio con uso
  de ALARMA.** Por eso el `AudioTrack` de la malla se mantiene abierto entre
  ráfagas en vez de crearse cada 4 s.
- **Los permisos no se piden igual en cada versión.** Encadenar diálogos dejaba
  fuera a Android 11, donde solo saltaba el del micrófono. Por eso existe la
  pantalla de bienvenida, que los enseña todos a la vez con su estado.
- **`pm grant` falla en HyperOS**: en el Redmi los permisos hay que darlos a mano.

---

## Lo que quedó hecho y verificado

Motor: maximizador con limitador de anticipación en la sirena (+12 dB de empuje sin
recortar), sonda por ráfaga de 8 chasquidos que da el error medido (1,20 m ±1 cm,
8/8 disparos), fondo adaptativo en los detectores, baliza BLE con búsqueda por
tendencia de señal, llamada hacia abajo (sirena + tono + anuncio de radio) con
respuesta reforzada, modo rescate automático a los 10 minutos, autonomía en pánico
cuando la alarma salta sola, umbral sísmico a 6,0.

Interfaz: paridad completa con la PWA, pantalla de bienvenida con permisos, ficha
médica como primer paso, guía de uso en cinco pasos con icono propio, vista de
búsqueda con brújula y ondas, aviso silencioso al acercarse, y el servicio con
`stopWithTask="false"` para que cerrar la app desde recientes no mate la vigilancia.

La pestaña Entorno va en orden de uso: los botones antes de los números, las nueve
filas partidas en LO QUE OYE AHORA y LO ÚLTIMO QUE RECONOCIÓ, y los bloques de
ayuda convertidos en pasos numerados con icono (el ladrillo `paso`, el mismo de
CÓMO USARLA). En QUÉ HAY ALREDEDOR cada herramienta lleva su explicación pegada a
su botón, y el paso del doppler está junto al botón que lo usa: antes las tres se
describían juntas al pie con nombres —SONDA, MOVIMIENTO, BARRIDO— que no aparecían
en ningún botón.

La vista Búsqueda, igual. Debajo de LLAMAR HACIA ABAJO iba todo seguido: el
interfono metido entre el botón y la lista, la lista sin rótulo —que vacía es una
frase gris suelta— y cinco párrafos de ayuda al pie, empezando por «camina
despacio», que es lo mismo que decía la frase de la lista, y acabando con la
explicación de LLAMAR HACIA ABAJO cinco elementos por debajo de su propio botón.
Ahora son tres bloques con rótulo y en orden de uso: QUÉ SE HA OÍDO, HABLAR CON
QUIEN HAS ENCONTRADO (el interfono, después de la lista porque se usa después de
encontrar a alguien) y CÓMO SE USA con los cinco párrafos convertidos en pasos.

Los tres ghost que encienden micrófono o radio llevan segunda línea diciendo qué
clase de botón son (`ghostSub`): ESCUCHAR MALLA «déjalo encendido siempre»,
ESCUCHAR ENTORNO «solo cuando haga falta», EMPEZAR A BUSCAR «herramienta de
rescate · gasta batería». Eran tres cajas idénticas para tres cosas distintas.

---

## Lo añadido en la última sesión, y qué le falta a cada cosa

**El chasquido ya suena en cada disparo.** `Altavoz.reproducir` mete un pre-rollo
de silencio (`preMs`) y no suelta el `AudioTrack` hasta que la cabeza de
reproducción ha pasado por el último marco. Antes escribía 20 ms y hacía
`stop()`+`release()` inmediatos con `colaMs = 0`: unas veces no salía nada y otras
salía tarde. Como ahora se sabe CUÁNDO suena, la espera de la sonda baja de 350 a
230 ms (`PRE_MS` / `ESPERA_MS` en `Sonda.kt`).

> **Falta comprobarlo con una pared a distancia conocida.** El autotest sintético
> sigue dando 1,20 m ±1 cm, 8/8, pero es simulado y no pasa por el altavoz: no
> puede ver un error de temporización. Si la distancia real se va, el número que
> hay que mover es `ESPERA_MS`, y la propia salida de la sonda dice la dispersión y
> los disparos buenos.

**Predicción de cuerpo humano por la respiración** (`Sonda.respiracion`, botón
¿HAY ALGUIEN RESPIRANDO?, 25 s). Mismo tono de 18,5 kHz que el doppler, pero
buscando el ritmo: quita la recta de mínimos cuadrados, pasa dos medias móviles en
cascada y autocorrelaciona en 0,15–0,6 Hz exigiendo que el pico sea un máximo
interior. Calibrado contra series sintéticas con `fx sounds/resp.py`, que corre en
un segundo:

| caso | periodicidad |
|---|---|
| respirando (limpio, con ruido, con ruido doble, 9/min, 30/min, acercándose) | 0,45 – 0,97 |
| ruido puro, golpe suelto, motor, deriva sola | 0,00 |
| vibración mecánica rítmica a 2 Hz | 0,44 |

Esos seis casos están ahora dentro de `COMPROBAR QUE TODO FUNCIONA`
(`autotestRespiracion` en `Sonda.kt`), así que se comprueban en el propio móvil y
no solo en el script. Salida real en el A10s:

```
autotest respiración · respira 15/min con ruido igual → 0,71 · 15/min OK |
respira 9/min, el más lento de la banda → 0,97 · 9/min OK |
acercándose andando → 0,94 · 15/min OK | solo ruido → 0,00 OK |
vibración de 2 Hz → 0,32 · 15/min OK | deriva sola → 0,01 · 14/min OK |
respira 15/min con ruido doble → 0,32 · 15/min (informativo)
```

Y encontró un fallo que iba a producción: el veredicto filtraba otra vez por
respiraciones por minuto (`bpm >= 9`) además de por la banda, y 0,15 Hz son 67
muestras de retardo, o sea **8,96/min** — el ritmo más lento que se busca quedaba
descartado siempre. Ya no filtra dos veces.

El último caso es informativo a propósito: con la señal a la mitad del ruido el
método no da garantías y el resultado depende del sorteo —0,50 con una realización
del ruido, 0,21 con otra—. Es el caso contra el que medir si algún día hay que
subir la sensibilidad.

Umbral en 0,45, justo encima de la vibración mecánica. Los tres pasos importan:
sin quitar la recta, andar hacia el escombro daba «cuerpo humano» solo (0,89); con
una media móvil en vez de dos, una lona a 2 Hz daba 0,95, porque la
autocorrelación normalizada no mira la amplitud y un 10 % de algo periódico sigue
correlacionando perfecto.

> **Falta medirlo con una persona debajo de un escombro.** El umbral no está
> validado con un cuerpo real y las unidades de «movimiento» son el logaritmo de un
> cociente de espectros, así que no se sabe cuánto valen en campo. Por eso la
> salida imprime siempre periodicidad, periodo y movimiento crudos, y el veredicto
> dice PROBABLE y advierte de que algo mecánico con ritmo lo imita.
> Y ojo: un cuerpo inconsciente respira muy poco y puede no salir.

**Interfono acústico** (`Interfono.kt`, botón HABLAR CON QUIEN ESTÁ DEBAJO en
Búsqueda). No es una llamada y no puede serlo: ni los 31 bytes del anuncio BLE ni
la malla de tonos llevan voz. Es medio dúplex en el móvil del rescatista: mide el
fondo (0,5 s), graba 4 s, lo reproduce hacia abajo maximizado a +12 dB, abre el
canal 4 s esperando respuesta —y lo deja abierto mientras dure, hasta 15 s— y
devuelve amplificado lo que ha llegado. Cierra a los 700 ms de silencio. Aparece
solo con un hallazgo al 70 % de proximidad o más.

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
