# SismoRed Android — dónde retomar

Estado al **22 de septiembre de 2026**. Todo lo descrito está **compilando e
instalado limpio** en tres móviles —Huawei STK-LX3 (Android 10), Samsung A10s
(Android 11) y Redmi 24094RAD4G (Android 15)— y ninguno da errores al arrancar.

El repositorio es <https://github.com/jonandrw/sismored_app>. Quien llegue nuevo entra
por `CONTRIBUIR.md`; este archivo es el estado real de cada pieza, y **hay que
actualizarlo cuando algo cambie**: es lo que evita volver a investigar lo ya
investigado.

Compilar sin `java` en el PATH:

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug
```

---

## Revisión del 22 de septiembre (tarde): cerrado y pendiente

Commit `0cd0ecc`. Dos revisores (fallos y calidad) sobre `43e7462..HEAD` y
pruebas por adb en Huawei + Redmi. **Cerrado y verificado en el aparato:**

- **Sismógrafo ciego tras emitir por la malla** (regresión de `c6be550`): el
  emisor abierto entre tramas contaba como alarma ajena. Con el emisor abierto,
  el Redmi dispara la vigilia a 3,1 s.
- **Eco de la víctima**: el Huawei en pánico ya no confirma ni reenvía su propia
  baliza devuelta, y el Redmi que ayuda marca `sac=false` (antes: «terremoto
  confirmado» ×3 por la sirena a través de la mesa).
- **Atajo de volumen**: dispara con tres de SUBIR; BAJAR con la pantalla apagada
  lo roba la cámara de EMUI. Verificado en el Huawei.

Cerrado sin prueba de campo: veto de vibración a 500 ms y en `sueloDeFiar`,
`saltoEntrante` fijado al entrar en alarma y con tope, no preguntar a la
víctima, franja que cruza medianoche, repintado de la notificación, resto del
micrófono.

**Trampa nueva:** EMUI oculta los `Log.i` de las apps; en el Huawei el único
registro fiable es la base de datos de eventos.

**Segunda tanda, cerrada el mismo día** (compila y pasa los tests; en el móvil
solo se ha comprobado la consola):
- El periodo de la baliza va con cada bucle (`emitirEnBucle(hop, periodoMs)`),
  no en `relayMs`; tras la respuesta a una llamada vuelve al ritmo del modo.
- Reenvío/acuse alternan solo con el salto 1; los relevos siempre se reenvían
  y no reciben OIDO.
- Una sola regla de umbral (`umbralQueToca`).
- El margen del atajo cuenta desde `ServicioSos.alarmaDesde` (4 s), no desde
  el disparo propio: una segunda pulsación ya no calla la ayuda recién pedida.
- Aviso de vecino con «YA LO HE VISTO» (y quitarlo cuenta igual); se rearma
  tras 5 min sin balizas.
- Consola: ya no se reescribe entera con cada evento, no recorta el
  histórico de 200 a 50 y enseña la hora real (antes mm:ss con «.000» inventado).

**Pendiente:**
1. **Decisión del autor:** el micrófono se cede sin límite a cualquier app que
   grabe (salvo en emergencia). Una transcripción en directo deja la malla
   sorda toda la noche; la alternativa es recuperarlo dentro de la franja de
   vigilia a costa de estorbar a esa app.
2. **Decidido y aplicado (`4003f8d`):** los detectores de sonido solo en alarma
   o rescate (cierra la decisión abierta 2). Precio asumido: se pierde el veto
   de motor de la vigilia (`motorCerca`, nunca calibrado); si un camión dispara
   de noche, la vía es distinguirlo por la frecuencia en el acelerómetro, no
   volver a encender el micrófono. Y quien oye la LLAMADA sin ser víctima
   contesta solo 3 minutos.
3. Estructura (revisor de calidad), por partes: `Vigilia.kt`, estado de la
   notificación, rol ante una baliza, una sola puerta `movimientoAjeno`.
4. **Decisión abierta 1 cerrada (`aca8640`):** `Cascada.sismoConfirmado` —fuente
   de fuera, o fuerte + 3 s seguidos en reposo— decide si se pregunta y si el
   silencio escala; sin ello se cierra en silencio. Falta verla en campo: un
   golpe en la mesa de día no debe preguntar.
5. Siguiente: aviso del «Inicio automático» de HyperOS (`appops` 10008), publicar
   la 0.3 (`versionCode`, `api/version.json`, APK) y que la web avise de la
   versión nueva.

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

> [!IMPORTANT]
> **Prueba de campo real — Enjambre sísmico en Colombia (14 de septiembre de 2026)**:
> El usuario sintió 5 sismos reales (Chocó M4.9, M4.4). El acelerómetro midió ondas de 0,60 m/s²
> con STA/LTA de 40x y 100% de ciclo a las 15:04 y 15:05, pero la Cascada descartó todo como
> «sacudida floja» por exigir opinión ajena (malla o Google). En la BD hay 838 descartes idénticos.
> Documentado con detalle exhaustivo, queries y propuesta de solución no invasiva de dos niveles en
> [`INFORME-SISMICO-CAMPO-2026-09-14.md`](INFORME-SISMICO-CAMPO-2026-09-14.md) e [`historia/sismografo.md`](historia/sismografo.md).

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

**Tercero, la malla entre dos móviles de verdad.** ~~Nadie ha visto un salto
real.~~ **Hecho el 16 y 17 de septiembre**, ver más abajo.

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

## Del 18 al 22 de septiembre de 2026 — lo que dicen 11 días de uso real

**Esta es la sección que hay que leer primero.** 20.057 eventos del Redmi
(11→22 sep) y el registro de un Galaxy A21s de otro usuario. Son cifras
medidas, no impresiones.

### Las cifras que ordenan las prioridades

| | 11 días |
|---|---|
| Preguntas «¿estás bien?» por el sismógrafo | **35** |
| Alarmas de vigilia nocturna | 18 |
| Entradas en modo rescate solas | 9 |
| Terremotos reales detectados por el acelerómetro | **0** |
| Detecciones del detector de sonido | **9.107** |
| Alarmas disparadas por el detector de sonido | **0** |

Dos conclusiones duras:

1. **El detector de sonido produce 828 detecciones al día y no ha decidido nada
   en 11 días.** No es un detector de sismos: es la herramienta de rescate
   —respiración, golpes, voz bajo escombros—. Corriéndolo como vigilante de la
   vida diaria solo genera ruido y filas en la base de datos.
2. **Todas las alarmas vienen del sismógrafo o de la malla.** Los falsos
   positivos hay que atacarlos ahí, no en el audio.

Las 35 preguntas diurnas se reparten por hora así: **26 entre las 6 y la 1 del
mediodía**. Eso no es sismicidad, es alguien levantándose y usando el móvil. La
amplitud (0,6–0,7 m/s², STA/LTA 40–48x) no separa un portazo de un terremoto;
**la duración sí**, y es lo único que ha separado algo en todo el proyecto.

### Falsos positivos identificados y cerrados

Los tres eran lo mismo: **el móvil midiéndose a sí mismo.**

- **Vibración de llamada** (18 sep). Racha continua de 26 s → AUXILIO.
- **Sirena propia** (18 sep). 0,67 m/s² durante 3,4 s leídos como «terremoto
  confirmado» por el propio móvil que estaba sonando.
- **Despertador** (20 sep, 06:20). Ocho segundos de vibración dentro de la
  franja de vigilia → alarma completa sin preguntar. Medido el 22 con el
  arreglo puesto: **5,00 m/s²**, o sea 35 veces el umbral de vigilia y 12 veces
  más fuerte que zarandear la mesa a mano.

La invariante que cierra los tres está en `ServicioSos.altavozPropio()`: **el
acelerómetro calla siempre que suene el altavoz propio** —llamada, despertador
o notificación ajena, sirena de alarma o rescate, y baliza o reenvío de la
malla—. Y no basta con callar la decisión: `Sismografo.vibracionPropia` **rompe
la racha**, porque vetar sin romperla solo aplaza el disparo al momento de colgar.

### La calibración del detector nocturno, que es la que vale

Reproducible entre dos móviles y entre días: **dispara siempre en la primera
muestra pasados los 3.000 ms**, y el exceso es solo el paso de muestreo.

| | paso | disparo | calma de fondo |
|---|---|---|---|
| Redmi 24094RAD4G | 356 ms | 3203–3209 ms | 0,003 |
| Huawei STK-LX3 | 417 ms | 3335–3336 ms | 0,009 |

Control negativo limpio: no dispara a 2827–2919 ms. **El Huawei es unas 3 veces
más ruidoso** en reposo y cae en postura `ENCIMA` con facilidad.

### El catálogo de sismos NO es el problema

51 alertas en 6 días, sin un solo duplicado. 23 son un **enjambre real en
Chaparral (Tolima)** a 155–167 km, M3,7–4,4, uno cada 12–95 minutos durante dos
días. **Decisión del autor (22 sep): se queda como está.** Saber dónde tembló es
útil aunque no se sienta; el problema es sentirlos, y eso lo resuelve el modo
nocturno, no el radio del catálogo.

Queda medido por si algún día se quiere tocar: con `radioAviso` actual pasan 51
de 51; con `15·10^(0,38(M−2))` pasarían 10 de 51, conservando el M4,5 a 55 km
que el autor sí sintió y descartando los 23 de Chaparral que no sintió.

### El detector de grito no puede distinguir una sirena de una persona

Medido con `banco.py` contra `fx sounds/`: **4 de 7 sirenas salen como GRITO**.
Y no se arregla con un umbral — la modulación silábica de un grito infantil real
es 0,11 y la de una sirena de policía 0,13; los rangos se solapan enteros, igual
que el vibrato, la tonalidad y la frecuencia. Las características que mide no
llevan la información que los separa.

Por eso `gritoCerca` da `PERSONA_PROBABLE` y solo `golpesCerca` da `PERSONA`.
**Ojo**: el registro del A21s trae `GOLPES RÍTMICOS · 92%` en una casa normal,
así que los golpes tampoco están limpios, y `fx sounds/Golpes/` sigue vacía.

### Cambios de comportamiento que conviene conocer

- **Oír una baliza de socorro ya no convierte en víctima.** Antes el que oía
  llamaba a `panico()` directo: en un salón con N móviles, una pulsación los
  dejaba a todos gritando y emitiendo, lo que tapa a la víctima y corrompe el
  radar de saltos. Ahora decide la cascada (`socorroVecino`) y lo máximo es
  despertar a quien duerma (`AVISAR_VECINO`), nunca encender baliza propia.
- **Acuse ultrasónico** (`CODIGO_OIDO`, 14.400 Hz): quien recibe una baliza y no
  es víctima contesta «te he oído», y la víctima lo ve. **Solo funciona en modo
  rescate**: con la sirena puesta, el propio estruendo satura el micrófono y la
  cadencia sale 0/4.
- **En modo rescate la víctima desaparecía de la malla.** `rescate()` llamaba a
  `parar()` y nadie rearrancaba la baliza. Ahora sigue, cada 24 s.
- **`RELAY_MS` de 8 a 12 s**, para que la víctima calle lo suficiente para que le
  puedan contestar.
- **El micrófono se cede**: solo a apps que lo piden, y a mano con el botón
  «DEJAR DE ESCUCHAR» de la notificación (5 min) para las que se niegan antes de
  pedirlo, como la grabadora de MIUI.
- **La vigilia es configurable**: franja horaria y minutos de reposo. Los tres
  segundos de suelo moviéndose NO se configuran.
- **El interruptor del detector de sonido ya no existe**: no podía encender
  —`ajustarEscucha` lo apagaba a los 500 ms— y un control que miente es peor que
  no tenerlo. Queda el estado.
- **`DetectorPanicoVoz` eliminado.** Su documentación prometía reconocer frases
  («¡temblor!», «¡socorro!»); no reconocía ninguna, su lista `PATRONES` no la
  usaba nadie y la «confianza» era el volumen por 2,5.
- **`AlertaGoogle` concedido por fin** en los dos móviles de prueba. Llevaba
  escrito desde agosto sin haber corrido nunca: le faltaba el permiso de acceso
  a notificaciones, que hay que dar a mano.

### Decisión abierta 1 · las 35 preguntas diurnas, y es la que más decide

Tres salidas, sin implementar:

1. **Exigir duración y no amplitud** también de día, como en la vigilia. Es lo
   coherente con lo único que ha demostrado separar. Se perdería un terremoto
   corto y brusco.
2. **Que «nadie contesta» no escale sola** a modo rescate sin una segunda señal.
   Hoy el silencio se lee como inconsciencia, cuando la causa más probable es que
   no haya nadie cerca del móvil — que es justo por lo que estaba quieto.
3. Subir el umbral diurno y aceptar perder sensibilidad.

### Decisión abierta 2 · el detector de sonido durante la vigilia

**Propuesta, medida pero NO aplicada (decisión del autor: la deja abierta).**

Que `Escucha` solo corra en emergencia, rescate o búsqueda, y no durante la
vigilia nocturna. Argumento: 9.107 detecciones en 11 días y cero decisiones.

Lo que se perdería, y es el precio real:

- **`motorCerca`**, el veto de camión de la vigilia nocturna. Nunca calibrado:
  `MOTOR_DB`, `MOTOR_GRAVE` y `MOTOR_PLANITUD` siguen escritos a ojo.
- **`estruendo`**: 4 detecciones en 11 días.

**Lo que NO se pierde, y conviene tenerlo claro antes de descartar la idea: la
malla.** `MallaAcustica` y `Escucha` son dos oyentes independientes sobre el
mismo micrófono —`USA_MALLA` y `USA_FORENSE`—, así que apagar los
clasificadores no toca la propagación. Está comprobado en campo: **todas las
pruebas de malla del 18 y el 22 de septiembre** —el salto real en el edificio
atravesando plantas, el pánico entre los dos móviles, el reenvío y el acuse
ultrasónico— **se hicieron con `Escucha` apagado**, porque era de día y no
había vigilia armada. El registro del micrófono lo confirma:

```
microfono: solo la malla, leo 73728 muestras por siesta de 450 ms
microfono: hay prisa (usuarios=3), leo de 1024 en 1024
```

`usuarios=1` es la malla sola; `usuarios=3` es malla + forense.

### Trampas al probar (ahorran tandas enteras)

Cuatro cosas silencian la vigilia sin decirlo: postura `ENCIMA`, `quietoAntes`
por debajo del reposo exigido —**no se puede sacudir dos veces seguidas**—,
haber pulsado ESTOY BIEN en los últimos 5 minutos, y el modo `repetidor`.

**Nunca lanzar la app con `monkey`**: llama a `thawRotation()` y le enciende al
usuario la rotación automática. Usar `am start -n red.sismo/.MainActivity`.

Modo exprés de vigilia (solo en compilaciones de depuración), reposo 10 s y
franja 0–24 h:

```bash
adb shell am start -n red.sismo/.MainActivity --es probar vigilia-express
adb shell am start -n red.sismo/.MainActivity --es probar vigilia-normal
```

---

## Del 15 al 17 de septiembre de 2026

Diez días de pruebas con dos móviles a la vez, un Huawei STK-LX3 y un Redmi
24094RAD4G. Lo que se midió, no lo que se esperaba.

**La malla salta de verdad.** Primer salto real medido entre 2,5 y 11,7 s. Con
la vía rápida nueva —si la vigilia está armada y llega un código de alerta con
la cadencia correcta, se corrobora sin esperar al resto de la trama— los dos
móviles reaccionaron con 120 ms de diferencia. Esa última medida es la que vale.

**La vigilia nocturna existe y funciona.** Se arma tras 30 minutos de quietud,
entre la 1 y las 7, y por debajo baja el umbral del sismógrafo en vez del de
audio: de noche interesa el movimiento, no el ruido. Si se levanta el móvil, se
desarma. Al parar una alarma a mano, el ciclo vuelve a empezar.

**Cuatro fallos encadenados que la hacían imposible de disparar**, todos
corregidos: la calma previa se medía un fotograma atrás en vez de antes de todo
el disturbio; una racha sostenida moría en cualquier bache de ciclo; el
`sueloDeFiar` bloqueaba la regla nocturna; y el ESTOY BIEN se cancelaba a sí
mismo. Detalle en `historia/sismografo.md`.

**El bucle de realimentación de la malla**, en el que los dos móviles se
respondían entre ellos sin parar: 20 emisiones en 94 s antes, 3 emisiones y 3,5
minutos de silencio después.

**El micrófono se queda mudo sin avisar.** MIUI se lo quitó a la app 14 s
enteros durante una llamada de WhatsApp. Ahora se detecta y se anota.

**Panel de sismos dentro de la app**, solo con lo que confirma un servicio
sismológico —EMSC y SGC—, 7 días, agrupado por fecha.

**Publicada la 0.2-fase1** en <https://sismored.app>, firmada con la misma
clave que la 0.1 —comprobado comparando el certificado de los dos APK, porque
si no coincidiera nadie podría actualizar sin desinstalar y perder su ficha—.
La app avisa de que hay versión nueva sin necesidad de abrirla: la
comprobación cuelga del pulso de 15 minutos del watchdog.

**Fuera el envío de partes por internet.** Existía un interruptor, apagado de
fábrica, que mandaba hora, motivo y saltos a un servidor. Se llegó a montar el
receptor y funcionaba, pero la web promete con estas palabras que no hay
servidor nuestro y que solo salen tres cosas del móvil, y eso está escrito en
absoluto. Entre cambiar el texto y quitar la función, se quitó la función. Se
pierde la única vía que había para saber si la app saltó en un terremoto real;
es un precio conocido.

### Lo que estas pruebas dejan en evidencia

- **El sismógrafo no ha detectado nunca un terremoto real.** Cruzando el catálogo
  con el acelerómetro salen 10 coincidencias frente a las 8,4 que daría el azar.
  Eso no es una señal.
- **69 episodios nocturnos en 10 noches**, mediana de 0,3 s y máximo 6,6 s. Son
  cortos, pero nadie ha dejado la vigilia puesta una noche entera con los valores
  de producción.
- **El discriminador de motor está sin calibrar.** `MOTOR_DB`, `MOTOR_GRAVE` y
  `MOTOR_PLANITUD` son una primera aproximación escrita a ojo. Hace falta un
  camión de verdad delante.
- **El detector de pánico por voz saltó 5 veces en 2 minutos** con seguridades
  del 79 al 95 %. O sobra sensibilidad o sobra el detector.

---


> **La lista de lo que falta está en `SIGUIENTE.md`**, ordenada por lo que más
> decide: las cinco pruebas de cinco minutos, la firma temporal de la malla, la
> confirmación de rescate, la ficha fragmentada por radio, el relé de la alerta
> sísmica de Google, las pruebas de campo y lo que bloquea publicar.

---

## Índice temático de la historia técnica (`historia/`)

Toda la documentación técnica histórica, el detalle exhaustivo de lo investigado, los
motivos físicos de cada decisión y los problemas resueltos en cada móvil se han organizado
en documentos temáticos dentro de la carpeta `historia/`, sin recortar ni resumir nada
del registro original:

- [**historia/audio.md**](historia/audio.md):
  Detectores acústicos (estruendo, grito/animal, pánico por voz, cascada de audio, calibración de micrófono, desacoplamiento del suelo y banco de pruebas `fx sounds/`).
- [**historia/sismografo.md**](historia/sismografo.md):
  Acelerometría sísmica, filtros recursivos STA/LTA, compensación de gravedad, detección de manipulación en mano (`hayMano`), umbrales adaptativos y calibración de campo.
- [**historia/malla.md**](historia/malla.md):
  Malla acústica por ultrasonidos (15.2–18.8 kHz), codificación FSK de 10 tonos, protocolo de saltos, topología descentralizada sin radiofrecuencia y corroboración entre terminales.
- [**historia/sonda.md**](historia/sonda.md):
  Sonda activa de eco, biosonar acústico de impulsos (2.5–4.2 kHz), detección Doppler MTI de movimiento, seguimiento de respiración por correlación de fase y calibración de distancias.
- [**historia/interfaz.md**](historia/interfaz.md):
  Vistas personalizadas, radar sin invención de contactos, espectrograma de malla ordenado, ficha médica de emergencia, accesibilidad táctil, paleta de alto contraste y modos operativos.
- [**historia/plataforma.md**](historia/plataforma.md):
  Arquitectura del sistema en Android (versiones 10 a 15), sabores de compilación `libre` y `play`, servicios en primer plano, ciclo de vida, permisos, optimizaciones de batería y comportamiento específico por fabricante (Huawei, Samsung, Xiaomi).
