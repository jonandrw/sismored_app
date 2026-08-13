# SismoRed Android — dónde retomar

Estado al 12 de agosto de 2026, y **este es el punto de partida**: aquí empieza el
control de versiones. Todo lo descrito está **compilando e instalado limpio** en tres
móviles —Huawei STK-LX3 (Android 10), Samsung A10s (Android 11) y Redmi 24094RAD4G
(Android 15)— y ninguno da errores al arrancar.

El repositorio es <https://github.com/jonandrw/sismored_app>. Quien llegue nuevo entra
por `CONTRIBUIR.md`; este archivo es el estado real de cada pieza, y **hay que
actualizarlo cuando algo cambie**: es lo que evita volver a investigar lo ya
investigado.

Compilar sin `java` en el PATH:

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug
```

---

## El camino hasta que esto sea fiable, en orden

No es una lista de deseos: es el orden en que hay que hacerlo, y cada punto desbloquea
al siguiente. Está escrito porque la tentación siempre es añadir funciones nuevas, y lo
que falta no son funciones.

**Primero, lo que ya puede dañar.** El detector de estruendo lanza PÁNICO solo, y el
banco con audio real dice: derrumbe 3 de 8, escombros 0 de 4, nueve animales leídos como
«grito», la voz que no se enciende nunca, tres falsos derrumbes con maquinaria. Una
alarma falsa en una red de emergencia no cuesta cero: quema la confianza de quien la
recibe, y la próxima vez ya no corre. **Decisión pendiente del autor**: dejarlo
desarmado de fábrica hasta que el banco dé números defendibles, o aceptar los falsos
positivos a cambio de no perder derrumbes. No es una decisión técnica.

**Segundo, las tres medidas físicas.** Ninguna necesita programar nada, y las tres
convierten una suposición razonada en un dato:

1. La sonda de eco **contra una pared a distancia conocida**. Si se va, el número es
   `ESPERA_MS`, y la propia salida da la dispersión y los disparos buenos.
2. La respiración **con una persona quieta a un metro**. El umbral de 0,45 está
   calibrado contra series que generó el propio código, y una vibración mecánica
   rítmica da 0,44: eso es un pelo, no un margen.
3. El interfono **con dos personas y algo sólido en medio**. Hoy los 8 dB sobre el fondo
   disparan con el aire de una habitación, o sea que dice «te han contestado» demasiadas
   veces. Ese falso positivo puede mandar a cavar donde no hay nadie, y por eso es el
   más urgente de los tres.

**Tercero, la malla entre dos móviles de verdad**, a través de una pared. El autotest
sintético pasa y la corroboración funciona, pero nadie ha visto un salto real.

**Cuarto, los detectores con el audio real de `fx sounds/`.** La pista sin explorar
sigue siendo la misma: la modulación de la envolvente a baja frecuencia — un motor tiene
una periodicidad marcada a su régimen de giro que un derrumbe no tiene. Y faltan
grabaciones de golpes, que es justo como pide ayuda alguien atrapado.

**Quinto, y es el que más vale**: que esto lo use media hora alguien que rescate para
vivir. Las decisiones de flujo —que el buscador no grite, hablar y callarse, mirar la
tendencia y no los metros, el silencio automático al llegar encima— están razonadas
desde la física y probablemente son correctas. Pero nadie que se dedique a esto las ha
tocado, y va a encontrar en media hora cosas que no se pueden imaginar sentado.

Lo que ya se puede firmar, mientras tanto: la ficha a pantalla completa, el PÁNICO con
sirena y atajo de volumen, el modo rescate, y la baliza con tendencia de señal. Eso
funciona sin red, sin otro móvil y sin nada que calibrar.

---

## Lo arreglado y añadido, con el porqué

Todo esto salió de usar la app en los tres móviles. Está compilando e instalado en los
tres, y lo verificado se dice como verificado.

### Sonido: por qué el chasquido no se oía

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

### El pitido que sobrevivía a DETENER

El hilo del tono se apagaba con una variable **local** a cada medida: si el análisis
moría por una excepción nadie la bajaba y sonaba para siempre, y DETENER no sabía nada
de la sonda. Ahora la bandera es de la clase, se baja en el `finally` pase lo que pase,
y `parar()` calla barrido, tono e interfono.

### La sonda, cuatro herramientas con interruptor

Eran tres botones que disparaban una medida de segundos y **las tres escribían en la
misma caja de texto**, así que la última borraba a las otras. Ahora cada una tiene su
rótulo, su interruptor, su animación en vivo y su propia consola: eco (chasquidos
continuos), movimiento, respiración y barrido. El tono es uno y el altavoz es uno, así
que encender movimiento apaga respiración y al revés — y eso se decide en el servicio,
no en la pantalla, para que valga igual venga la orden de donde venga.

**Cinco intentos automáticos y resultado estático** en las tres de medida: al quinto se
imprime y la herramienta se apaga sola, así el resultado se queda quieto en vez de
borrarse con el intento siguiente. La respiración se queda con el intento de más ritmo,
no con el último, porque una respiración débil aparece en uno de cinco. Si se corta a
mitad lo dice («Detenido tras 3 de 5»), no vende un resultado completo. Cada resultado
va al registro.

Verificado en el Samsung: cinco ráfagas a las 20:59:32, :42, :52, 21:00:03 y :13, y
«Eco continuo terminado».

### Las animaciones que no arrancaban

`activo` era un campo normal, y las vistas solo se reprograman **mientras están
activas**: al encenderlas no había nadie que las despertara. Asignar un campo no
repinta nada. Ahora el setter llama a `invalidate()`. El síntoma que lo delató fue que
el barrido sonaba fuerte y no dibujaba nada.

### Búsqueda

- **Es una pestaña**, la cuarta de cinco, no una vista escondida detrás de un botón.
- **El buscador ya no dispara su propia alarma.** No era un fallo del rastreo: era la
  malla haciendo su trabajo, oyendo la baliza de la víctima y lanzando el pánico local.
  Un rescatista con su sirena encendida no oye los escombros, que es lo único que
  tiene. `ServicioSos.buscando` hace que la alerta se anote y se siga
  **retransmitiendo**, pero este móvil no suene.
- **La barra estaba mal escalada**: recta de −100 a −40 dBm, así que a un metro o dos
  (≈−70 dBm) daba 50 % y el interfono, que pide 70 %, no aparecía nunca. Ahora dos
  tramos con anclajes medidos: −50 dBm o más → 100 %, −78 → 70 %, −95 → 0 %. El umbral
  vive en `Rastreador.PCT_CERCA` y lo comparten barra, aviso e interfono.
- **El aviso al acercarse se acelera** (1,5 s al 70 %, 280 ms encima) y es silencioso:
  vibración, destello de pantalla y flash.
- **Y se puede apagar, y se calla solo.** Interruptor «Avisarme al acercarme»,
  persistente. Y tras ocho segundos con la señal clavada arriba se pausa por su cuenta
  («EN PAUSA · YA ESTÁS ENCIMA»), con histéresis 95/85 para que la lectura temblorosa
  no lo haga parpadear. En ese momento aparece el bloque **«Ya estás encima»** con el
  botón que apaga el rastreo y baja a las herramientas de sonido: el rastreo ya cumplió
  y a partir de ahí lo que informa es el oído.
- **La tarjeta trae nombre de pila y grupo sanguíneo.** La cuenta de los 31 bytes:
  banderas 3 + potencia 3 + UUID 4 + cabecera 4 = 14, quedan 17; versión, estado, salto
  y grupo son cuatro, y el nombre se lleva los trece que sobran. La edad se cayó para
  hacerle sitio. Si no cupiera, `onStartFailure` reintenta **sin** nombre: una baliza
  sin nombre sirve, no tener baliza no. Probado entre dos móviles: llegan «Guillermina ·
  Grupo O+» y «Juan · Grupo O+».

### La ficha completa por Wi-Fi (implementada)

`FichaLan.kt`. Difusión UDP al puerto 51789 cada 3 s, **solo con alarma o rescate
activos**, escuchando siempre. Se manda a la difusión genérica y a la de cada interfaz,
porque hay Android que descarta una y routers que descartan la otra. Las recibidas se
indexan por IP, caducan al minuto y aparecen en Buscar bajo «FICHA COMPLETA POR WI-FI».

> Privacidad: esto cambia la promesa de que la ficha no sale del móvil. Por Wi-Fi va la
> ficha ENTERA a cualquiera en esa red, no trece bytes como en la radio. Está escrito
> donde el usuario lo ve, en el paso 3 de CÓMO FUNCIONA ESTA FICHA. **No sustituye al
> GATT**: la Wi-Fi necesita que exista una red, y en un edificio caído normalmente no
> hay. El GATT funciona móvil a móvil sin infraestructura, que es el caso que importa.

### La malla se cree la alerta a la primera si está temblando

`Sismografo` publica `ultimoTemblor` (media rápida por encima de MEDIO umbral: el suelo
se mueve aunque no sea para disparar), el servicio lo convierte en
`ServicioSos.temblando` con un minuto de validez —las réplicas vienen detrás— y
`corroborada()` se salta la doble escucha. La cadencia de ráfagas se sigue exigiendo:
eso descarta un ruido, no a un impostor. Y el impostor tendría que estar dentro del
mismo terremoto.

### Bluetooth automático al saltar la alarma

Hay gente que no lo quiere abierto todo el día. Hasta **Android 12** la app lo enciende
sola con `BLUETOOTH_ADMIN`. Desde **Android 13** `enable()` está retirado y no hay forma
de encenderlo en silencio: solo queda el diálogo del sistema, que lanza la pantalla una
vez por alarma y únicamente si la baliza no puede emitir.

### Interfono: la trampa del micrófono

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

### Textos e interfaz

- **Jerarquía de verdad**: el ladrillo `paso` tenía un solo TextView, así que la
  jerarquía se intentaba con MAYÚSCULAS y puntos medios, que no es jerarquía. Ahora cada
  paso tiene **título en negrita** y **cuerpo** en gris, y están reescritos los **27
  pasos** de la app. Los rótulos de sección llevan su subtítulo aparte en vez de
  «RÓTULO · media frase».
- **Los muros de ayuda son tarjetas de pasos**: CÓMO VIAJA LA ALERTA, CÓMO FUNCIONA
  ESTA FICHA, CÓMO SE USA, el interfono en tres pasos y la caja de internet en tres.
- **Las palabras cortadas** tenían causa: Android hifena por defecto y en columna
  estrecha deja trozos de palabra. `hyphenationFrequency="none"` y
  `breakStrategy="simple"`.
- **Consolas que escriben** (`VistaConsola`): el texto se revela carácter a carácter con
  un cursor de bloque parpadeando, y si el texto nuevo empieza por el viejo sigue desde
  donde estaba. Está en las siete consolas de herramientas y en el registro. La del
  interfono además **apila** con la hora delante, como un terminal.
- **Nada se parte ni se aprieta**: los ghost pasaron de altura fija a `match_parent` con
  mínimo y rótulo autoescalado; los rótulos de una línea (MARCAR EVENTO, DETENER) usan
  `Boton.UnaLinea`; el rótulo del detector cabe en una línea —«DERRUMBE» se partía
  dejando una «E» sola y estiraba la casilla—; el logotipo cede ancho para que los
  iconos no se salgan; las cinco pestañas se autoescalan y Búsqueda se llama «Buscar».
- **El título ya no cambia de tamaño solo**: la etiqueta «ESCUCHANDO 00:12» crecía cada
  segundo y el título se reflowaba con ella. Ahora la etiqueta dice OYENDO y el
  cronómetro vive en la fila de estado.
- **La fila clave/valor** reparte por peso: con el valor en `wrap_content`, «Escuchando
  16-18 kHz» se comía el ancho y tapaba el rótulo. Sin autoescalado a propósito: con
  `wrap_content` en el alto, Android calcula tamaño cero y **el texto desaparece** — se
  vio en «Estado de la malla».
- **La ficha a pantalla completa** es un layout con pesos que se reparte la pantalla
  entera: nombre (2), grupo en rojo a 64 sp junto a la edad (3), alergias y medicación
  (4, el más alto porque es el que no se puede leer a medias) y contacto (2). Los campos
  vacíos se esconden y sueltan su peso. Y es un `Dialog` pelado, no un `AlertDialog`:
  ése envuelve la vista en un contenedor `wrap_content` y los pesos colapsaban — la
  ficha salía a media pantalla. El toque para cerrar va en la vista, porque
  `setCanceledOnTouchOutside` cierra al tocar **fuera** y una ventana a pantalla completa
  no tiene fuera.
- **«Si aparece internet» ya hace algo.** El endpoint existe (contesta 400 validando, no
  404); el problema era que `vaciar()` se rendía en silencio en cuatro casos y ninguno
  decía nada. Ahora cada salida tiene su frase en la consola, y **PROBAR AHORA** encola
  un parte marcado como prueba y fuerza el intento.
- **Detección en vivo**: la escucha es un interruptor con su estado escrito, y las nueve
  filas van en dos grupos con rótulo (LO QUE OYE AHORA / LO ÚLTIMO QUE RECONOCIÓ).

### Autotest

Los seis casos del detector de respiración corren en el propio móvil dentro de
COMPROBAR QUE TODO FUNCIONA, y encontraron un fallo que iba a producción: el veredicto
filtraba otra vez por respiraciones por minuto además de por la banda, y 0,15 Hz son 67
muestras de retardo, o sea **8,96/min** — el ritmo más lento que se busca quedaba
descartado siempre.

---

## Lo que quedó SIN verificar en pantalla

Compilado e instalado, pero no lo vi funcionando con mis propios ojos. Son un vistazo
de un minuto cada uno:

- La caja de ajustes de la señal, al final de Entorno.
- La caja de internet, al final de Red.
- La ficha a pantalla completa (Ficha → MOSTRAR FICHA).
- Las consolas escribiendo con el cursor.

> Y una lección de método, porque me costó dos diagnósticos falsos: el puente temporal
> que se mete en `MainActivity.onCreate` para poder disparar acciones por `adb`
> **hay que comprobar que se ha insertado** (`grep` al archivo) antes de creerse el
> resultado. Dos veces di por roto algo que funcionaba, porque el ancla del script no
> coincidía y la acción no se disparaba nunca. El eco «que no corría» corría
> perfectamente.

---

## Diseño de referencia de la ficha por Wi-Fi (ya implementada)

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

## Lo siguiente: la ficha completa por GATT

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

## Detalle de la calibración del detector de respiración

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
