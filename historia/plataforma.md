# Historia y Decisiones — Plataforma Android, Hardware y Red

Registro histórico de integración con el sistema operativo Android, resiliencia ante fabricantes (Xiaomi HyperOS, Samsung OneUI), radio BLE, Wi-Fi LAN y ciclo de vida de procesos (`ServicioSos.kt`, `FichaLan.kt`, `Baliza.kt`, `Cascada.kt`).

---

## Sabores de compilación `libre` vs `play` (4 de septiembre de 2026)

Separación arquitectónica para cumplir con las políticas de Google Play sin sacrificar las funciones de la versión de código abierto:
1. **El conflicto de política**: Google Play rechaza sistemáticamente apps que usen `BIND_ACCESSIBILITY_SERVICE` para capturar teclas físicas de volumen con pantalla bloqueada si la app no está catalogada exclusivamente como herramienta de asistencia para discapacitados.
2. **Dimensión de sabor `distribucion`**:
   - **`libre` (F-Droid / GitHub)**: Contiene `ServicioTeclas : AccessibilityService` en `src/libre/java/red/sismo/` y su declaración en `src/libre/AndroidManifest.xml`. `teclasDisponibles()` devuelve `true`. El atajo de 3 toques de volumen con pantalla apagada funciona al 100%.
   - **`play` (Google Play Store)**: Contiene un stub sin dependencias en `src/play/java/red/sismo/` donde `teclasDisponibles()` y `teclasActivas()` devuelven `false`. El manifiesto resultante tiene **cero** menciones a accesibilidad. La interfaz (`MainActivity.kt`) oculta automáticamente la tarjeta en onboarding y en ajustes sin arrojar advertencias ni inconsistencias al usuario ni a los revisores de Google Play.
3. **Compilación**:
   - `.\gradlew.bat assembleLibreDebug` -> genera `app-libre-debug.apk`
   - `.\gradlew.bat assemblePlayDebug` -> genera `app-play-debug.apk`

---

## Paquete Legal y Política de Privacidad para Google Play (4 de septiembre de 2026)

1. **`PRIVACIDAD.md` pública**: Creado documento maestro en el repositorio raíz conforme a las directrices de datos de Google Play, GDPR y F-Droid. Documenta la arquitectura *Local-First*, la ausencia de servidores/telemetría y el procesamiento de audio exclusivamente en RAM sin grabación a disco.
2. **Descargos Legales Explícitos (*Disclaimers*)**:
   - Integrado aviso legal obligatorio en el paso 3 de Bienvenida (`ob3_txt`) y visible en `Acerca de`: SismoRed no sustituye a los servicios oficiales de emergencia (911/112/Protección Civil).
3. **Flujo de Publicación**:
   - Sabores `libre` y `play` probados y compilando con R8 y minificación listos.
   - Manifiesto limpio de accesibilidad en sabor `play`.

---

## Búsqueda

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

---

## La ficha completa por Wi-Fi (implementada)

`FichaLan.kt`. Difusión UDP al puerto 51789 cada 3 s, **solo con alarma o rescate
activos**, escuchando siempre. Se manda a la difusión genérica y a la de cada interfaz,
porque hay Android que descarta una y routers que descartan la otra. Las recibidas se
indexan por IP, caducan al minuto y aparecen en Buscar bajo «FICHA COMPLETA POR WI-FI».

> Privacidad: esto cambia la promesa de que la ficha no sale del móvil. Por Wi-Fi va la
> ficha ENTERA a cualquiera en esa red, no trece bytes como en la radio. Está escrito
> donde el usuario lo ve, en el paso 3 de CÓMO FUNCIONA ESTA FICHA. **No sustituye al
> GATT**: la Wi-Fi necesita que exista una red, y en un edificio caído normalmente no
> hay. El GATT funciona móvil a móvil sin infraestructura, que es el caso que importa.

---

## Bluetooth automático al saltar la alarma

Hay gente que no lo quiere abierto todo el día. Hasta **Android 12** la app lo enciende
sola con `BLUETOOTH_ADMIN`. Desde **Android 13** `enable()` está retirado y no hay forma
de encenderlo en silencio: solo queda el diálogo del sistema, que lanza la pantalla una
vez por alarma y únicamente si la baliza no puede emitir.

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

> **Y esto hay que decirlo donde el usuario lo ve, sin adornos**: por wifi va la
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

---

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

---

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

---

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

---

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

---

## Y ahora hay un apagado de verdad

Y se ha añadido lo que faltaba: **APAGAR SISMORED DEL TODO**, en Diagnóstico, con
confirmación. Para la vigilancia, el micrófono, la radio y el propio servicio, y
lo **recuerda** (`Opciones.apagada`) para no encenderse sola al siguiente arranque.
Una app con micrófono y un servicio que sobrevive a cerrarla necesita una puerta
de salida clara; sin ella deja de ser una herramienta y pasa a ser algo de lo que
defenderse. Volver a abrir la app y usarla limpia la marca.

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


### Ubicacion.refrescar() fuera del hilo de la interfaz

- De paso: `Ubicacion.refrescar()` se me había colado en el repintado del
  diagnóstico, o sea llamadas al servicio de ubicación **en el hilo de la
  interfaz** varias veces por segundo. Quien refresca es el servicio; la pantalla
  solo lee.



## Lo arreglado y añadido, con el porqué

Todo esto salió de usar la app en los tres móviles. Está compilando e instalado en los
tres, y lo verificado se dice como verificado.

### Watchdog de sucesos y desacoplamiento del micrófono (4 de septiembre de 2026)
### STA/LTA sismológico recursivo en Sismografo.kt (4 de septiembre de 2026)
### Sabores de compilación `libre` vs `play` (4 de septiembre de 2026)
### Bio-Sonar Acústico de Impulso en Sonda.kt (4 de septiembre de 2026)
### Filtro Acústico Anti-Maquinaria y Detección de Golpes SOS en Escucha.kt (4 de septiembre de 2026)
### Paquete Legal y Política de Privacidad para Google Play (4 de septiembre de 2026)
### Sonido: por qué el chasquido no se oía
### El pitido que sobrevivía a DETENER
### La sonda, cuatro herramientas con interruptor
### Las animaciones que no arrancaban
### La ficha completa por Wi-Fi (implementada)
### La malla se cree la alerta a la primera si está temblando
### Bluetooth automático al saltar la alarma
### Interfono: la trampa del micrófono

