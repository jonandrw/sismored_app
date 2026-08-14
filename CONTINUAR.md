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

> **Al día de hoy esto ya no lo decide un detector, lo decide la cascada**
> (`CASCADA.md` y `Cascada.kt`): el estruendo y la caída libre son pruebas, no
> alarmas, y quien elige entre callar, preguntar, encender la baliza o gritar es
> una sola función con doce escenarios comprobados en dos móviles. Lo de abajo
> sigue siendo cierto sobre el detector; lo que ha cambiado es que ya no manda él.

**Primero, lo que ya puede dañar.** El detector de estruendo ya no lanza PÁNICO solo
—pide que el acelerómetro corrobore—, pero por el lado del micrófono el banco con
audio real dice **32,7 estruendos por hora** sobre el material que tendría que callar.
Reconoce los ocho derrumbes de ocho, y ese es justo el problema: oye de todo. Una
alarma falsa en una red de emergencia no cuesta cero: quema la confianza de quien la
recibe, y la próxima vez ya no corre. **Decisión pendiente del autor**: dejarlo
desarmado de fábrica hasta que el banco dé números defendibles, o aceptar los falsos
positivos a cambio de no perder derrumbes. No es una decisión técnica.

Y hay un fallo peor que los falsos, porque va en la dirección que mata: **ocho de once
animales se leen como «grito»**, y un llanto ahogado de persona se lee como «animal».
El código dice, con estas palabras, que ante la duda hay que decir grito porque
equivocarse hacia «es un perro» cuesta una vida. Hoy se equivoca hacia los dos lados.

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
una periodicidad marcada a su régimen de giro que un derrumbe no tiene. Y lo que falta
no es sobre todo código: faltan **horas de `Ambiente/`** para poder calcular los falsos
por hora de un día normal —hoy hay 44 segundos— y faltan **grabaciones de golpes**, que
es justo como pide ayuda alguien atrapado. Las dos carpetas llevan dentro un `LEEME.md`
diciendo qué grabar, y no hace falta programar para llenarlas.

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

## Cuatro ideas que sí aplican (y lo que se descarta)

Extraído de una revisión externa sobre radio y detección de audio. Casi todo lo que
traía no aplica a un proyecto de software para móviles convencionales; esto sí:

**1. Confirmar el estruendo con el acelerómetro.** Es lo más valioso y lo más barato.
Hoy el detector de audio lanza PÁNICO solo, y falla justo en lo que dice el banco: un
generador diésel da un falso derrumbe. Un derrumbe de verdad **sacude el móvil**; un
motor a diez metros no. Y `ServicioSos.temblando` ya existe (lo puso la malla
instantánea). Exigir corroboración del acelerómetro para la alarma disparada por audio
mata la mayoría de los falsos positivos sin bajar la sensibilidad del micrófono. Es un
`if`, y resuelve el problema número uno de la hoja de ruta.

~~**2. Medir falsas alarmas por hora, no aciertos.**~~ **hecho en el banco.**
`banco.py` da los falsos por hora de las carpetas que tienen que callar, con el
estruendo aparte porque es el único que puede levantar la sirena él solo. Lo que
falta ya no es código: son **horas de `Ambiente/`**, porque una tasa por hora medida
sobre once minutos de efectos de sonido no es la tasa de un día normal. El estado
**NO CONCLUYENTE** resultó estar ya en la app —cuando dos clases empatan no acumula
ninguna—, así que solo hacía falta contarlo: y casi nunca pasa. El motor no duda
entre dos clases; o ve una, o no ve nada.

**3. Guardar los falsos positivos como material de entrenamiento.** La carpeta
`fx sounds/Falsos/` ya existe, con su `LEEME.md` y contada como carpeta de silencio:
cada archivo que entre ahí empeora la cifra de falsos por hora hasta que alguien la
arregle, que es exactamente lo que tiene que pasar. Está vacía porque la app todavía
no se ha llevado a campo. Lo que sigue pendiente son los **«átomos»**: mezclar cada
golpe con ruido, reverberación y atenuación para generar miles de escenarios en vez
de tener 63 archivos.

**4. La baliza BLE no tiene saltos, y la acústica sí.** La malla acústica reenvía la
alerta hasta cuatro móviles de distancia; la baliza de radio no reenvía nada. La idea de
convertir un salto de diez metros en cinco de dos —cada móvil intermedio repitiendo lo
que oye— ya está implementada para el sonido y **no** para la radio. Un móvil que ve una
baliza podría reemitirla con salto+1, igual que hace `MallaAcustica`. Eso extiende el
alcance sin tocar la potencia de nada.

Y una cosa gratis, si el hardware la soporta: **BLE Coded PHY (S=8)**, de Bluetooth 5.
Cambia velocidad por sensibilidad, que es exactamente el intercambio que conviene aquí
—el anuncio son 17 bytes—. Va por `startAdvertisingSet()` con `PHY_LE_CODED` y el
escáner con `setLegacy(false)`, comprobando antes `isLeCodedPhySupported()` y cayendo al
anuncio de siempre si no está. Hay que medirlo, no darlo por bueno.

**Lo que se descarta, y por qué:** amplificadores y antenas externas (no se le conecta
una antena RF a un móvil), adaptadores USB (no es un PC), LoRa y nodos dedicados (es
otro producto, no software), GPS (no llega bajo hormigón), Wi-Fi Aware (casi ningún
móvil), el barómetro para estimar profundidad (demasiado ruidoso y pocos móviles lo
llevan), y el sensor de proximidad como detector de movimiento (es un infrarrojo de
cerca/lejos, no mide nada). YAMNet queda en «quizá»: son megas de modelo y una
dependencia nueva, y no resuelve por sí solo el caso que falla.

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

## La cascada de decisión (nueva, y es la base de lo que viene)

Está entera en `CASCADA.md`. Lo que hay que saber sin abrirlo:

- **`Postura.kt`** contesta a «de quién me fío»: `EN_REPOSO` / `ENCIMA` /
  `DESCONOCIDO`. En reposo el acelerómetro es un sismógrafo; encima de una
  persona mide a la persona. Es la misma decisión con la que el sistema de alerta
  sísmica de Android lleva tres años y **tres** alertas falsas: solo vigila con el
  móvil quieto y enchufado, y quien decide de verdad es la agregación de muchos
  móviles. Nuestra malla es esa agregación.
- **Medido, y desmonta una suposición**: ni el Redmi (Android 15) ni el A10s
  (Android 11) tienen `TYPE_STATIONARY_DETECT` ni `TYPE_MOTION_DETECT`. Los
  detectores de un disparo de AOSP no están en estos móviles, así que el respaldo
  por acelerómetro no es un plan B: es el plan A. Verificado en el A10s — sobre
  una mesa pasa a `EN_REPOSO` a los 30 s y a los 84 s seguía sin un solo falso.
- **El contador de pasos sí está** en los dos, y pide `ACTIVITY_RECOGNITION`
  (séptimo permiso, ya en el manifiesto y en la bienvenida). `pm grant` funciona
  en el Samsung y **falla en HyperOS**, como todo lo demás.
- **Nadie adivina si estás enterrada: se pregunta.** ESTOY BIEN con 60 s de cuenta
  atrás, contestable desde la notificación sin desbloquear, y el silencio es la
  respuesta. Cubre de una vez a la que huye, la que duerme, la inconsciente y la
  que tiene el móvil a tres metros.
- **La escalera de acciones** separa lo barato de lo caro: baliza silenciosa con
  evidencia floja, sirena solo con mucha. Ahí está la respuesta al «99 % de
  certeza» — no todas las acciones necesitan la misma.
- **Dos falsos apagados con un `if`**: el móvil que se cae de la mesa y el
  generador diésel ya no pueden encender nada sin que el suelo se haya movido.
- `Cascada.autotest()` corre los **doce escenarios** dentro de COMPROBAR QUE TODO
  FUNCIONA. Pasan los doce en los dos móviles.

- **La pantalla «¿ESTÁS BIEN?» está hecha** (`PreguntaActivity`): sale por dos
  caminos —la actividad y una notificación con `fullScreenIntent`— para que
  ninguno dependa del otro, con un tic por segundo por el canal de alarma y una
  onda que sale del número. Verificada en el A10s, incluido que sobrevive a
  apagar y encender la pantalla y que la cuenta atrás sigue corriendo.
- **Hay un simulacro** en Diagnóstico para poder verla sin un terremoto. No puede
  escalar: sin sacudida la cascada se queda en NADA, comprobado dejándola vencer.
- **El repetidor está hecho**, y al hacerlo apareció un agujero que ya existía:
  **la malla solo reenviaba estando en alarma**, así que el que buscaba oía la
  alerta y la cadena se cortaba ahí — justo lo contrario de lo que decía su
  propio comentario. Ahora `MallaAcustica.reenviar()` lo usan los tres que oyen
  sin sonar: el silenciado, el buscador y el repetidor.

- **La última posición conocida está hecha** (`Ubicacion.kt`), y con una regla que
  no se puede relajar sin volver a discutirla: **la app no enciende el GPS ni una
  vez**. Solo lee `getLastKnownLocation` —un arreglo que ya hizo otra app—, lo
  guarda en disco, caduca a las 24 h y sale por Wi-Fi con la ficha, solo con
  alarma o rescate. Eso cambió una promesa que estaba escrita en tres sitios, así
  que cambiaron los tres: el manifiesto, Diagnóstico («GPS encendido por la app:
  NUNCA» + «Última posición conocida» con su antigüedad) y el paso 3 de la ficha.
  Verificado en el A10s: posición de hace 5 min, ±100 m, sin encender nada.

Lo que falta está al final de `CASCADA.md`: medir los 60 s con gente, ver un
salto real entre dos móviles y el detector de golpes contra audio real.

## La ficha por Wi-Fi: por qué no funcionaba entre dos móviles

Síntoma: en la misma red, uno se veía y el otro no. Eran **tres fallos
distintos**, y ninguno daba el menor error — de ahí que pareciera «que el móvil
no accede a la red».

1. **Faltaba el `MulticastLock`.** Sin él, el chip de Wi-Fi descarta los paquetes
   de difusión antes de que Android los vea. Y depende del fabricante: el Redmi
   los dejaba pasar y el A10s no, así que probándolo en un móvil parecía
   funcionar. Arreglado — se coge al empezar a escuchar y se suelta al parar.
   Necesita `CHANGE_WIFI_MULTICAST_STATE`, que no se le pide al usuario.
2. **La difusión podía salir por los datos móviles.** Con datos encendidos la
   ruta por defecto es la del operador, y una difusión que sale por ahí no la ve
   nadie de la red local. Ahora el socket se ata a la red Wi-Fi
   (`Network.bindSocket`).
3. **Fallaba callado en tres sitios más**: la ficha vacía no se emitía sin
   decirlo, «no hay Wi-Fi» tampoco, y ninguna interfaz aceptando la difusión
   tampoco. Los tres se anotan ahora en el registro y se resumen en Diagnóstico
   («Ficha por Wi-Fi»).

### Lo que NO se puede arreglar desde la app, y hay que saberlo

**El A10s no recibe difusiones con la pantalla apagada.** Ni con el candado de
difusión ni con el de rendimiento (`WIFI_MODE_FULL_HIGH_PERF`) cogidos — se
comprobó con los dos puestos y la pantalla apagada, y no llega nada; se enciende
la pantalla y llega al instante. El mismo Redmi, con la pantalla apagada, **sí**
recibe. Es el firmware de ese Wi-Fi y no hay API que lo cambie.

Mitigación puesta: además de difundir, se manda **por unicast a los móviles que
ya han contestado alguna vez**, porque un paquete dirigido sí pasa el filtro
dormido. No arregla el primer contacto —para eso hace falta que el que recibe
tenga la pantalla encendida—, pero una vez que dos móviles se han visto ya no se
pierden. En el escenario real esto encaja: quien busca lleva la app abierta y
mirando, y quien está atrapado es el que emite.

Y el candado de rendimiento **solo se coge cuando hay motivo** —buscando, en
alarma, en rescate o de repetidor—, porque mantiene la radio despierta y eso
cuesta batería. Va en el latido del servicio y no en el bucle que pinta la
pantalla: ese se para justo cuando hace falta.

### Cómo se prueba, ahora sin adivinar

Diagnóstico → **PROBAR LA FICHA POR WI-FI CON OTRO MÓVIL**. Manda un datagrama
con un nombre falso —no enseña la ficha real ni enciende ninguna alarma—, deja la
escucha a tope dos minutos, y en el otro móvil tiene que aparecer «PRUEBA» en
Buscar. Comprobado en los dos sentidos entre el Redmi (192.168.101.22) y el A10s
(192.168.101.27).

## SILENCIO EN LA ZONA (nuevo, y a medio verificar)

Un sexto código en la malla, `CODIGO_SILENCIO` a **18,8 kHz**. Lo emite quien
busca —botón PEDIR SILENCIO EN LA ZONA, debajo de LLAMAR HACIA ABAJO— y todo
móvil que lo oiga se calla cinco minutos: sirena, vibración y sonda. **La baliza
sigue, por radio Y por sonido.**

> **Corregido tras una prueba de campo, y era el error más caro posible.** Aquí
> `onSilencio` llamaba también a `malla.pararEmision()`, o sea que la orden
> callaba la baliza acústica del que está debajo. No cuadra con el propio motivo
> de la función: se pide silencio porque el equipo escucha con geófonos y
> micrófonos de contacto **en la banda baja**, y ahí es donde estorba la sirena
> (2-4 kHz). La malla emite a **16-18,8 kHz**: no le tapa nada a un geófono.
> Pararla no le daba silencio a nadie y dejaba mudo el único canal acústico de la
> víctima justo mientras la buscan. Ahora no se toca.

Existe porque los equipos de rescate escuchan con micrófonos de contacto y
geófonos en la banda baja y piden silencio absoluto en el sitio. Una sirena tapa
exactamente lo que buscan. Si SismoRed no puede callarse cuando se lo piden, deja
de ser una ayuda.

Y sabiendo lo que NO alcanza: a 17-18 kHz esto no atraviesa una losa, así que no
va a llegar al móvil enterrado —ese ya está en modo rescate, un pulso cada 12 s—.
Va dirigido a los móviles de la superficie y de alrededor, que son muchos, están
al aire y son los que de verdad hacen ruido.

**Al hacerlo apareció un fallo viejo**: el autotest de la malla recortaba a
`MAX_HOP`, así que probaba cuatro veces los cuatro saltos y **la llamada (18,4
kHz) no se había probado nunca** — ni ahora el silencio. Corregido: se prueban
los seis códigos. Con eso, los seis decodifican OK en el A10s.

### Lo que falta de esto, y es lo importante

**No está confirmado que 18,8 kHz sobreviva el viaje por el aire.** El decodificador
lo lee perfecto con señal sintética, y el Samsung emite el tono (`salto=6 tx=3` en
el registro), pero el Redmi a 40 cm no lo anotó. No se pudo distinguir entre «el
altavoz no llega a 18,8 kHz» y «la malla del Redmi no estaba escuchando». Es la
misma prueba pendiente que la malla entera: **dos móviles, de verdad**. Hasta
entonces el silencio es código que compila y decodifica, no una función que se
pueda prometer.

## La sonda, la respiración y el doppler: por qué no aportaban nada

Las tres estaban calibradas contra señales sintéticas generosas. Al medirlas
contra su propia física, las tres tenían el mismo tipo de fallo — y ninguno era
de calibración de campo: **eran de aritmética, y se podían haber encontrado sin
salir de casa**.

### El eco pedía un eco cuatro veces mayor del que existe

La amplitud que devuelve una pared es `(camino_directo / 2d) · R`, con el camino
altavoz→micrófono ≈ 15 cm:

| | hormigón (R≈0,9) | tabique (R≈0,5) |
|---|---|---|
| 1,0 m | 6,8 % | 3,8 % |
| 2,0 m | 3,4 % | 1,9 % |

El umbral estaba en **12 %**. O sea que no podía ver una pared a más de medio
metro. Ahora hay un modelo físico en el código (`ecoFisico`), el umbral sale de
él (3 % en la pila) y hay cuatro casos de prueba con amplitudes reales.

### Y aun así medía el teléfono, no la sala

Lo dijo una prueba de campo que ninguna prueba sintética habría dado: **las
mismas cuatro superficies tapando el móvil con objetos encima**. Dos causas
encadenadas:

1. `apilar()` terminaba en `.take(4)`, así que **decía «4 superficies» siempre que
   hubiera cuatro o más**. Era un tope disfrazado de medida.
2. Esos cuatro eran rebotes de la propia carcasa y lóbulos del chirp: fijos,
   presentes en los ocho disparos, y con el umbral al 3 % pasaban de sobra. Los
   ecos de verdad quedaban por debajo y nunca entraban en la lista.

Arreglado como se hace en cualquier radar con su acoplo directo: **aprender la
firma del móvil y restarla**. Botón `APRENDER ESTE MÓVIL` en la tarjeta de la
sonda — se sujeta lejos de todo, una ráfaga, y a partir de ahí se resta. La
regresión reproduce el síntoma: *antes: pared=true y 3 fantasmas · después:
pared=true y 0 fantasmas*. Y si no se ha aprendido, el registro lo dice.

**Pendiente**: probarlo en el móvil. Sin aprender la firma, el eco sigue midiendo
el teléfono.

### La respiración medía donde la señal no está

`Microfono.N` = 2048 a 48 kHz → cada bin son **23,4 Hz**, y el análisis miraba
bandas laterales desde el bin 3, o sea **70 Hz**. Un tórax respirando desplaza
**0,5 Hz**: la cincuentava parte de UN bin. No era poca sensibilidad, era el
sitio equivocado — por eso no detectaba a nadie ni a diez centímetros.

A 18,5 kHz la longitud de onda son 1,85 cm, así que 5 mm de excursión de tórax
son **3,4 radianes de fase**. Ahora se demodula en I/Q con el índice absoluto de
muestra y la serie es la **fase desenrollada**; `periodicidad()` no se tocó.

Y el falso positivo de campo —**un ventilador oscilante leído como «PROBABLE
CUERPO HUMANO, 9 respiraciones por minuto»**— salía justo en el borde de abajo de
la banda. 9/min = 0,15 Hz = un ventilador que barre cada 6-7 s. La banda empieza
ahora en **0,20 Hz (12/min)**: un adulto en reposo respira de 12 a 20, y alguien
atrapado respira más deprisa, no más despacio. El caso de prueba que decía «9/min
es respiración» ahora es la regresión que dice que es un ventilador.

### El doppler no llegaba a ver a alguien andando

Misma aritmética: la banda empezaba en el bin 3 = 70 Hz = **0,65 m/s**, y por una
habitación se anda a 0,4-0,6 m/s. Por eso dio «0,2 veces el fondo» con gente
circulando. Ahora empieza en el bin 2 (0,43 m/s). Al bin 1 no se baja: ahí manda
la fuga de la portadora y la deriva de reloj entre altavoz y micrófono.

### Y dos fallos del propio banco de pruebas

- `return todo && autotestRespiracion()` — el `&&` corta, así que **un caso de la
  sonda en rojo se saltaba la batería entera de respiración** sin decir nada.
- Los autotests corrían **en el hilo principal**, y al engordar los de la sonda
  colgaban la app al arrancar: «SismoRed no responde» si tocabas la pantalla en
  esos segundos. Era el «a veces falla» que se veía en el Samsung. Ahora van en su
  hilo, y comprobado aporreando la pantalla nada más arrancar: cero ANR.
- De paso: `Ubicacion.refrescar()` se me había colado en el repintado del
  diagnóstico, o sea llamadas al servicio de ubicación **en el hilo de la
  interfaz** varias veces por segundo. Quien refresca es el servicio; la pantalla
  solo lee.

## Vigilancia de día y de noche, sin mirar el reloj

La idea era un «modo vigía nocturno» que se alternase solo. Se hizo, pero **por
estado y no por hora**: el reloj se equivoca con quien trabaja de noche, con la
siesta y con el móvil olvidado en la mesa toda la tarde. `Postura` ya distingue
`EN_REPOSO` de `ENCIMA` **midiendo**, y es el mismo criterio con el que el
sistema de alerta sísmica de Android lleva tres años: quieto y enchufado.

Lo que cambia: **el umbral sísmico ya no es un número fijo**, son dos.

- `ENCIMA` → 6,0 m/s². El conservador. Andar y correr pasan de 3 sin esfuerzo, y
  aquí no se puede afinar más sin llenar el bolsillo de falsas alarmas.
- `EN_REPOSO` → 1,2 m/s². Un móvil quieto en una mesilla no anda ni corre, así
  que casi todo lo que obligaba a poner el listón alto no existe. Y es justo el
  caso en el que hay alguien durmiendo que no se va a enterar.

Lo elige el latido del servicio, que corre con la pantalla apagada. Verificado en
el A10s: dejado quieto, pasa de 6,0 a 1,2 él solo y lo dice en la línea de
estado.

**Y el móvil mide su propio sitio.** `Sismografo.calmaMedida` guarda el percentil
98 de la sacudida mientras nadie lo toca — sobre una mesa de verdad dio **0,04
m/s²**, o sea que 1,2 son treinta veces la calma y hay muchísimo margen. Eso
convierte «elige un umbral en m/s²», que nadie sabe hacer, en «déjalo donde vayas
a dormir y mira lo que se mueve esa mesa». Una mesa con la lavadora al lado no es
una mesilla de noche y no tienen por qué compartir número.

El mando de umbral **edita el del régimen en el que esté el móvil**, y la línea
de estado dice cuál. Un solo control, siempre sobre algo que se puede juzgar. El
mínimo baja de 0,5 a 0,2 porque en reposo el margen útil está más abajo.

Lo que falta medir: si 1,2 aguanta una noche entera sin falsos con portazos, la
lavadora y el camión de la basura. Es medida de campo, y ahora se puede hacer
porque el móvil enseña contra qué compite.

## Por qué la baliza no se activaba (y no era el umbral)

Una prueba de campo lo dejó escrito en el registro, con hora:

```
20:13:15  cascada(estruendo) -> PREGUNTAR · terremoto confirmado: pregunto si está bien
20:14:15  cascada(nadie ha contestado) -> NADA · «sacudida con el móvil encima y sin confirmar»
```

Se preguntó, nadie contestó —que es exactamente el caso que tiene que encender la
baliza— y decidió **NADA**. Dos fallos encadenados, y ninguno era de sensibilidad:

**1. La prueba caducaba antes que la pregunta.** `temblando` dura un minuto y la
cuenta atrás dura otro: cuando tocaba juzgar el silencio, la sacudida que había
justificado preguntar ya no existía. Ahora, **abierto un suceso, su evidencia no
caduca**: se acumula y solo se borra al cerrar el caso. Preguntar y luego olvidar
por qué se preguntaba era lo peor de los dos mundos.

**2. El propio terremoto descalificaba al sismógrafo.** Sacudir un móvil que está
en una mesa reinicia el reloj de quietud, así que el régimen dejaba de ser
`EN_REPOSO` en cuanto empezaba el suceso — y con ello se perdía el umbral fino
(1,2) y pasaba a exigir corroboración por audio. Corregido con
`Postura.regimenRecordado()`: si hace menos de dos minutos estaba en reposo **y no
ha dado un paso desde entonces**, sigue siendo un móvil en una mesa al que alguien
está sacudiendo. Los pasos son lo que separa los dos casos, y no hay forma de
sacudir un móvil dando cero pasos si lo llevas encima. Es el mismo principio que
ya usaba el sismógrafo congelando su media lenta durante el evento.

Con las dos cosas, la cadena que se esperaba funciona: móvil quieto + sacudida
inusual → AVISAR al momento → 60 s sin respuesta → BALIZA. Hay una regresión
nueva en `Cascada.autotest()` con ese escenario exacto («preguntó, nadie contestó,
lo llevaba encima» → BALIZA). **Falta lanzarla en un móvil**: el Redmi no acepta
toques por adb y el A10s se desconectó.

## El atajo de volumen, solo con la pantalla apagada o bloqueada

Viendo un vídeo se sube y se baja el volumen sin pensar, y tres toques en tres
segundos pasan todos los días. Ahí el atajo no aporta nada —si estás mirando el
móvil tienes el botón de PÁNICO en la pantalla— y en cambio lanza una alerta a
toda la red por nada. Ahora solo cuenta si la pantalla está apagada o bloqueada.

**Silenciar una alarma que ya suena sigue valiendo siempre**: quien la quiere
callar la está mirando.

Trampa de Kotlin que costó dos compilaciones: una línea que empieza por `!` se
pega al tipo de la línea anterior y se lee como `Tipo!`, el tipo de plataforma. El
error no menciona el signo.

## HyperOS SÍ mata el servicio al cerrar la app, y no hay código que lo impida

Medido deslizando la app fuera de recientes en el Redmi. El registro del sistema
lo dice con nombre y apellidos:

```
20:44:35.112  SismoRed: app cerrada desde recientes: la vigilancia sigue   ← la app hizo lo suyo
20:44:35.338  am_kill: [0,32587,red.sismo,50,OneKeyClean,191740]           ← MIUI la mata 226 ms después
20:44:35.562  am_proc_died
```

`onTaskRemoved` se llamó, `stopWithTask="false"` está declarado y el servicio
estaba en primer plano. Da igual: **`OneKeyClean`, el limpiador de tareas de
Xiaomi, lo mata de todos modos**, y con el inicio automático desactivado tampoco
vuelve. Contra eso no hay API.

Lo único honesto es **enterarse y decirlo**, porque lo contrario es que alguien se
vaya a dormir creyendo que está vigilado:

- El servicio deja un **latido en disco** (`Opciones.latido`) cada 10 s mientras
  vigila, y marca `deberiaVigilar`.
- Al abrir la app, si quería vigilar, el latido es reciente y el servicio no está
  corriendo, no ha sido el usuario: **ha sido el sistema**. Sale un aviso que lo
  explica y lleva al ajuste de INICIO AUTOMÁTICO (la actividad de MIUI existe en
  este HyperOS, comprobado con `cmd package query-activities`; hay respaldo a la
  ficha de la app si no).
- Y una fila permanente en Diagnóstico: **«Sigue vigilando con la app cerrada»**,
  en rojo si el móvil la mató. Así no depende de haber visto el aviso.

Verificado en el Redmi simulando la muerte: el aviso sale.

**Lo que hay que decirle al usuario al publicar**: en Xiaomi/Redmi hay que darle
inicio automático y fijarla en recientes con el candado. Sin eso, SismoRed solo
vigila con la app abierta — y eso hay que decirlo antes, no después.

## Y ahora hay un apagado de verdad

Y se ha añadido lo que faltaba: **APAGAR SISMORED DEL TODO**, en Diagnóstico, con
confirmación. Para la vigilancia, el micrófono, la radio y el propio servicio, y
lo **recuerda** (`Opciones.apagada`) para no encenderse sola al siguiente arranque.
Una app con micrófono y un servicio que sobrevive a cerrarla necesita una puerta
de salida clara; sin ella deja de ser una herramienta y pasa a ser algo de lo que
defenderse. Volver a abrir la app y usarla limpia la marca.

## Cómo se distingue una mano de un terremoto: por el GIRO

El umbral fino en reposo (0,8 m/s²) es inservible si el móvil se cree «en reposo»
teniéndolo en la mano: una sacudida floja enciende la baliza. Y encontrar el
discriminador costó descartar tres que no valen:

- **Los pasos no bastan.** Alguien sentado en un sofá coge el móvil sin dar uno.
- **«Estaba quieto hace dos minutos» tampoco.** Lo estaba — y por eso el móvil en
  la mano acababa con el umbral fino puesto. Este fallo lo introduje yo al
  arreglar el anterior.
- **«Cuánto llevaba quieto justo antes de cruzar el umbral» tampoco.** En un
  terremoto la sacudida también empieza medio segundo antes del cruce, así que
  sale pequeño en los dos casos.

Lo que sí los separa es la **orientación**. Un móvil en una mesa apunta siempre al
mismo sitio: durante un terremoto se sacude, pero la gravedad le sigue entrando
por la misma cara — el suelo se mueve, la mesa no gira. Una mano no puede
sostener nada sin girarlo.

`Sismografo.giroGrados`: gravedad filtrada (α = 0,02) y ángulo máximo contra las
direcciones de los últimos 15 s. **Medido en el Redmi sobre una mesa: 0,0–0,2°.**
El corte está en 10°, así que el margen es de dos órdenes de magnitud. Y manda
sobre todo lo demás: si ha girado, hay una mano, y se acabó el umbral fino.

Se ve en la pantalla de inicio junto al umbral («giro X°»), para poder
comprobarlo sin herramientas.

**Falta medirlo con el móvil en la mano** y andando, que es la otra mitad de la
prueba. Si en la mano da menos de 10°, hay que bajar el corte; con 0,2° de mesa
hay sitio de sobra.

### Y el umbral en reposo baja a 0,8 m/s², con la escala de Mercalli detrás

| MMI | aceleración de pico | qué se siente |
|---|---|---|
| IV | 0,14–0,38 m/s² | se nota dentro de casa |
| V | 0,38–0,90 m/s² | **lo nota todo el mundo, se despierta la gente** |
| VI | 0,90–1,77 m/s² | los muebles se mueven, daño leve |

Con 1,2 solo saltaba ya metido en MMI VI. Con 0,8 salta dentro de MMI V, que es
donde alguien dormido tiene que enterarse — y sigue siendo veinte veces la calma
medida en una mesa real (0,04 m/s²). Lo que hace seguro bajarlo no es la
amplitud, es la **duración**: hay que aguantar 0,6 s seguidos por encima. Un
portazo es un pico; un terremoto sacude segundos.

## La pantalla ACERCA DE (hecha, y en parte es obligatoria)

Se llega desde Diagnóstico. No es cortesía:

- **La política de privacidad tiene que estar accesible dentro de la app.** Lo
  exige Google Play para cualquier app, y aquí hay ficha médica (dato de salud),
  micrófono y ubicación.
- **El permiso de accesibilidad hay que justificarlo donde se lea.** Es el mayor
  riesgo de rechazo que tiene este proyecto: Play exige que las APIs de
  accesibilidad se usen para accesibilidad, y aquí se usan para oír el botón de
  volumen con la pantalla apagada. La tarjeta lo dice con todas las letras: no
  lee la pantalla, ni lo que escribes, ni otras apps.
- **Una app que dice detectar terremotos tiene que decir lo que NO puede hacer.**
  Hay una tarjeta entera en rojo: no sustituye a los servicios de emergencia, no
  garantiza que te encuentren, no detecta personas —detecta móviles— y no
  predice nada.

Seis tarjetas: qué es · lo que no puede hacer · tus datos · por qué cada permiso ·
preguntas frecuentes · contacto y versión. La versión se lee del propio paquete,
que escribirla a mano es garantizar que algún día mienta. El contacto es el
repositorio, que ya estaba documentado; no se inventó ningún correo.

Jerarquía igual que la pantalla de «¿estás bien?»: **rótulo rojo pequeño →
titular blanco de una frase → cuerpo gris** (`TitularTarjeta` en themes.xml). Sin
el titular, una tarjeta de texto largo es un muro que nadie lee.

**Sin revisar en pantalla**: el Redmi no acepta toques por adb y el A10s está
desconectado, así que compila e instala pero nadie la ha visto todavía.

## La malla se inventaba balizas, y lanzaba la alarma entera

El fallo más grave encontrado hasta ahora, y salió de una queja vaga —«al revisar
permisos se disparó la baliza»— que resultó no tener nada que ver con los
permisos. El registro:

```
panico(malla acústica (salto 2))
panico(malla acústica (salto 4))
```

Medido con el móvil quieto en una mesa y **ningún otro emitiendo**:

| | antes | después |
|---|---|---|
| candidatos («puede ser una alerta») | **866** | **0** |
| balizas confirmadas | **27** | **0** |
| alarmas completas lanzadas | **2** | **0** |

El ruido ultrasónico de una habitación normal —cargadores, pantallas, focos LED—
tiene energía de sobra para pasar los 10 dB de margen sobre el suelo que pedía el
detector. Y una vez pasado el primer filtro, la corroboración no podía salvarlo:
con 866 candidatos, encontrar dos separados 3 s dentro de una ventana de **30 s**
es trivial.

Lo que se cambió:

- **`MARGEN_DB` de 10 a 20.** Veinte decibelios son cien veces la potencia del
  fondo; una baliza real la emite un altavoz a todo volumen a pocos metros y pasa
  de sobra, el ruido ambiente no.
- **`CORROB_VENTANA` de 30 s a 12 s.** Una baliza real se repite cada ~4 s, así
  que dos detecciones en doce segundos le sobran; con treinta se emparejaban dos
  ruidos sin ninguna relación entre sí.

### Y la contraprueba, que es la mitad del trabajo

Se probó también a exigir **tres marcos seguidos** en vez de dos, y el autotest lo
cazó al instante: `autotest malla FALLA` en los cuatro saltos. El decodificador
tiene un contrato de dos marcos con todo lo que lo usa. **Subir un umbral sin
comprobar que sigue oyendo lo de verdad es cambiar un fallo ruidoso por uno
mudo**, que es peor porque no se nota. Configuración final: 20 dB, dos marcos,
ventana de 12 s — cero falsos y los cuatro saltos OK.

### Lo que falta aquí, y es de diseño

El margen es un parche bueno, no la solución. El protocolo actual es «portadora
más uno de cuatro tonos»: cualquier ruido con energía en dos bins lo imita. Lo
que de verdad separa una baliza del ruido no es el nivel, es la **estructura
temporal** — la ráfaga de 0,25 s encendida y 0,15 apagada, seis veces. Exigir esa
firma (correlar contra el patrón, como hace cualquier enlace digital con su
palabra de sincronismo) rechaza el ruido sin necesidad de pedir volumen, y de
paso quita el ataque trivial de reproducir un tono. Está a medias: `verCadencia`
ya cuenta ráfagas, pero no se exige el patrón completo.

> **La lista de lo que falta está en `SIGUIENTE.md`**, ordenada por lo que más
> decide: las cinco pruebas de cinco minutos, la firma temporal de la malla, la
> confirmación de rescate, la ficha fragmentada por radio, el relé de la alerta
> sísmica de Google, las pruebas de campo y lo que bloquea publicar.

## La firma temporal de la malla (hecha, y medida antes de escribirla)

El margen de 20 dB quitó los 866 falsos candidatos, pero era un parche: el
protocolo es «portadora más uno de cuatro tonos» y cualquier ruido con energía en
dos bins lo imita. Ahora se exige la FORMA de la trama —250 ms de tono, 150 de
silencio— y no más volumen.

**Lo primero que hizo falta fue desmentir la aritmética obvia.** `Microfono.SALTO`
es N/2: los marcos se solapan al 50 %, así que cae uno cada **21,3 ms y no cada
42,7**, y la ventana de 42,7 ms desborda los bordes de la ráfaga. Sintetizando la
trama de `emitirUna()` y pasándola por el propio decodificador
(`fx sounds/cadencia.py`):

| señal | ON | OFF | periodo |
|---|---|---|---|
| al lado | 277-299 ms | 107-128 | 384-405 |
| lejos (1/800 de amplitud) | 235-256 | 149-171 | 384-405 |
| con reverberación fuerte | 213-363 | 43-64 | 341-406 |
| tono continuo | una racha de 2411 ms | — | — |

Una ráfaga de 250 ms se ve de 213 a 363. Haber puesto la ventana «en 250 ± algo»
habría dejado la malla muda, y muda no se nota usándola.

**El periodo es el discriminador, no el ciclo de trabajo.** La reverberación
cambia el reparto entre tono y silencio; no cambia cuándo empieza la ráfaga
siguiente. Por eso el corte fino va en el periodo (340-460 ms, y que no se mueva
más de 45 ms entre ráfagas consecutivas) y las ventanas de ON y OFF son anchas.

**Cuántas ráfagas.** Medido contra ruido que parpadea a todas las velocidades
posibles (`fx sounds/rechazo.py`), una hora por caso:

| regla | peor caso |
|---|---|
| dos flancos de subida (lo de antes) | SIEMPRE |
| tres ráfagas seguidas | 19/hora |
| **cuatro ráfagas seguidas** | **2/hora** |
| cinco ráfagas seguidas | 0/hora |

Cinco tienta y es justo lo que no se puede hacer: la trama trae seis, así que
pedir cinco es no dejar margen para perder una — y quien escucha se engancha a
mitad de trama constantemente. Con cuatro se pueden perder dos.

Lo que cuesta: la confirmación llega en la quinta ráfaga y no en la segunda,
**1,7 s de trama en vez de 0,5**. También en el atajo de «está temblando».

**Y hay un autotest nuevo que es el que de verdad protege**, porque el fallo a
cazar aquí es mudo: `autotestCadencia()` sintetiza la trama entera y la pasa por
el decodificador con el solape real, y exige las dos mitades — que la baliza pase
y que un tono continuo no. Verificado en el A10s:

```
autotest cadencia OK · firma temporal: la baliza da 6 ráfagas de 4 y un tono continuo 0
autotest malla OK · salto 1..6
```

Cuesta unas décimas y va dentro de COMPROBAR TODO, anotándose **salga bien o mal**:
el número es la prueba de que nadie ha endurecido el umbral hasta dejar de oír.

**Lo que NO cubre**: una sala con reverberación de 0,6 s y cola al 80 % funde las
ráfagas en una sola racha de 96 marcos y la firma no la ve. Ningún criterio de
forma la vería. La baliza repite cada 4 s, así que sigue intentándolo.

## La pasada de interfaz con el Samsung delante

El A10s **sí acepta `input tap`**, así que por primera vez se puede navegar,
tocar y medir. `uiautomator dump` no sirve en esta app —las consolas parpadean y
la ventana nunca queda en reposo: «could not get idle state»—, así que se mide
sobre los píxeles de la captura (`medir.py`, 2 px por dp a densidad 320).

Las tarjetas de instrucciones (`paso.xml`), medidas y arregladas:

- **El título no tenía jerarquía**: 13 sp contra 12,5 del cuerpo. Media décima no
  es jerarquía, es la misma línea escrita dos veces. Ahora 14 contra 12.
- **Los títulos se partían en dos líneas** y la segunda se leía como cuerpo. Los
  33 títulos están reescritos a **≤ 29 caracteres**, que es lo que cabe en una
  línea en la columna que dejan el icono y el número. No hay `maxLines`: recortar
  con puntos suspensivos es peor que partir, así que la regla la cumple el texto.
- **Los cuerpos llegaban a seis líneas.** Reescritos a **dos**, justificados con la
  misma receta de `DocCuerpo` (`high_quality` + `none` + `inter_word`).
- **Cada paso es ahora una tarjeta de verdad** (`drawable/tarjeta_paso.xml`), con
  borde y tono propios y 10 dp entre ellas; fuera las 23 líneas divisorias. El
  primer intento reusó `fila_op` y no se vio nada: es del mismo #14171A que la
  tarjeta madre, así que separar sin cambiar de tono es solo más espacio en
  blanco.
- **Márgenes medidos**: 46,5-48 dp entre pasos, 23,5 arriba y 22-23,5 abajo. La
  diferencia es tinta —una línea sin trazos descendentes mide 1,5 dp menos—; el
  relleno es exactamente 16 dp por lado.

Y la excepción, a propósito: **`paso_f3` sigue en cinco líneas**. Es la única
pantalla donde el usuario ve que por Wi-Fi sale la ficha ENTERA. Recortarla a dos
líneas sería romper una promesa escrita, no ahorrar una línea.

Dos rótulos más que se cortaban con puntos suspensivos en el centro de opciones:
«PROBAR LA FICHA POR WI-…» y «COMPROBAR QUE FUNCIO…», ahora **PROBAR LA WI-FI** y
**COMPROBAR TODO**.

**Y la malla que se montaba encima de sí misma.** En MALLA DE PROPAGACIÓN se leía
«Balizas detectadasRetransmisiones». No era un solape de dibujo: las tres columnas
de `stats3` iban a peso 1 y **sin un solo pixel entre ellas**, y «Balizas
detectadas» ocupaba 190 px de los 198 de su columna. Ahora hay 10 dp de hueco y
los rótulos son **Balizas oídas** y **Reenvíos**.

### Lo que queda visto pero sin arreglar

- En MALLA DE PROPAGACIÓN, dos marcas «--» sueltas flotando encima de «Estado de
  la malla». Es otro caso del alto calculado a cero que ya está documentado en
  esta misma tarjeta.
- En Diagnóstico, la fila «Ficha por Wi-Fi» corta el valor por la derecha:
  «192.168.101.27 (con».
- La cuadrícula de respuesta **sigue sin revisarse** en pantalla.

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
