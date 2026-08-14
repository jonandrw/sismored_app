# La cascada de decisión

Qué hace SismoRed con lo que ven sus sensores, y por qué. Este documento es la
base: `Cascada.kt` es su traducción a código, línea por línea, y los doce
escenarios de aquí abajo son casos de `Cascada.autotest()` que corren en el móvil.

Si cambias una regla, cámbiala en los dos sitios y en la misma sesión.

---

## El error que había debajo de todo

Cada detector decidía por su cuenta y todos terminaban en el mismo sitio,
`panico()`. Eso arrastraba dos problemas que no se arreglan tocando umbrales:

**Un solo listón para acciones de coste muy distinto.** Encender la baliza es
silencioso, barato y puede ser lo único que quede de alguien. Encender la sirena
es caro: una alarma falsa en una red de emergencia quema la confianza de quien la
recibe, y a la tercera vez ya nadie corre. Con un umbral único hay que elegir
entre perder víctimas o gritar de más, y las dos opciones son malas.

**Se intentaba clasificar lo inclasificable.** Ningún juego de sensores distingue
con certeza «enterrada e inconsciente» de «el móvil se cayó detrás del sofá». Se
puede afinar el clasificador para siempre y no llegar.

---

## Lo que hace Google, que es el precedente que hay

El sistema de alerta sísmica de Android lleva tres años funcionando: detecta unos
312 terremotos al mes, de M1,9 a M7,8, y en todo ese tiempo ha dado **tres**
alertas falsas —dos por tormentas eléctricas y una por una notificación masiva—.
No lo consigue con un umbral mejor. Lo consigue con tres decisiones:

1. **Solo se fía del acelerómetro cuando el móvil está quieto y enchufado.** Un
   móvil en un bolsillo no puede distinguir un terremoto de andar, así que ni lo
   intenta.
2. **Ningún móvil decide.** El móvil dice «he visto algo» y quien decide es el
   servidor, cruzando muchos móviles.
3. **Dos niveles, no uno.** *BeAware* (MMI 3-4) es una notificación; *TakeAction*
   (MMI 5+) toma la pantalla y suena. Acción cara, listón alto.

Las tres se copian aquí. La segunda con un cambio importante: **nuestra malla es
el servidor**. Cuando se cae la red, el de Google desaparece y lo único que queda
es lo que ya tenemos — y `corroborada()` ya existe.

---

## Paso 0 — De quién me fío

Antes de creerse ningún sensor, en qué estado está el móvil (`Postura.kt`):

| régimen | qué significa | qué vale |
|---|---|---|
| `EN_REPOSO` | quieto sobre algo, quizá cargando | el acelerómetro **es** un sismógrafo |
| `ENCIMA` | lo lleva una persona | el acelerómetro mide a la persona, no al suelo |
| `DESCONOCIDO` | todavía no se sabe | se trata como el peor caso |

Se calcula con lo que ya está corriendo y no cuesta nada:

- **Los pasos**, que Android cuenta en el coprocesador de sensores con la pantalla
  apagada. No es una estimación de nada: es un contador en hardware. Tres pasos en
  el último minuto ya son `ENCIMA`.
- **`TYPE_STATIONARY_DETECT` / `TYPE_MOTION_DETECT`**, de un solo disparo y
  gratis... **cuando existen**. Medido: **ni el Redmi (Android 15) ni el
  Samsung A10s (Android 11) los tienen**. Están en AOSP, no en estos móviles.
- **El acelerómetro**, que es el respaldo y en la práctica el camino principal.
  Verificado en el A10s: con el móvil sobre una mesa pasa a `EN_REPOSO` a los
  30 s y a los 84 s seguía sin un solo falso movimiento.

Y de paso recoge las señales de vida que estaban ahí sin usar: **la pantalla
desbloqueada** —el mejor «estoy bien» que existe, y cuesta cero— y **la luz**,
que bajo escombros es de noche a las tres de la tarde.

---

## Paso 1 — La puerta: sin suceso no hay cascada

Sin sacudida propia ni corroboración de la malla, nada dispara. Esto solo es un
`if`, y apaga los dos falsos que hoy podían encender la sirena sin que se hubiera
movido nada:

- **el móvil que se cae de la mesa** (caída libre + impacto, que llamaba a
  `panico()` directamente desde `Sismografo`), y
- **el generador diésel**, que el banco cuenta a 52 estruendos falsos por hora.

Lo que se pierde a cambio: un accidente doméstico aislado, sin terremoto, ya no
dispara nada. Es una decisión, no un descuido — sin el terremoto delante, los
falsos se comen el sistema.

## Paso 2 — ¿Me creo la sacudida?

En `EN_REPOSO`, sí. En `ENCIMA` no basta: andar pasa de 3 m/s² sin esfuerzo. Se
pide algo más — que se oiga el derrumbe, o que otro móvil lo confirme.

## Paso 3 — Preguntar, que es lo que resuelve el 99 %

No se intenta adivinar si está enterrada: **se le pregunta, y el silencio es la
respuesta.** Es el mismo mecanismo que la detección de caídas de los relojes, y
convierte un problema imposible en uno fiable.

- **`ENCIMA`** → pantalla ESTOY BIEN con 60 s de cuenta atrás, sin ruido: está
  despierta.
- **`EN_REPOSO`** → lo mismo **más la sirena**. Puede estar dormida en un quinto
  piso y no haberse enterado. Esa es la única razón por la que existe una sirena
  que se enciende sola.

Se puede contestar desde la notificación sin desbloquear: si hay que desbloquear
para decir «estoy bien», mucha gente no llega a tiempo.

Los 60 s son un compromiso y hay que medirlos con gente: cortos, alguien que está
sacando a su hijo de debajo de una mesa no llega a contestar; largos, una persona
inconsciente pierde ese minuto antes de que su baliza empiece.

## Paso 4 — La escalera de acciones

| acción | qué hace | qué pide |
|---|---|---|
| `NADA` | anota y sigue mirando | — |
| `PREGUNTAR` | ESTOY BIEN, silencioso | suceso confirmado |
| `AVISAR` | sirena + la pregunta | suceso + `EN_REPOSO` |
| `BALIZA` | baliza, ficha, malla y modo rescate, **sin ruido** | nadie contestó |
| `AUXILIO` | todo, incluida la sirena | nadie contestó **y se oye a alguien** |

Aquí está la respuesta a «99 % de certeza»: **no todas las acciones necesitan la
misma**. La baliza con un 60 % ya compensa; la sirena no.

## Paso 5 — Qué se puede afirmar

Lo que lee el rescatista no es lo que se sospecha, es lo que se sabe:

| rótulo | cuándo |
|---|---|
| `SE OYE A ALGUIEN` | voz o golpes junto al móvil |
| `MÓVIL DE ALGUIEN QUE NO CONTESTÓ` | el caso normal, y el más honesto |
| `SOLO UN MÓVIL · SALIÓ DESPEDIDO` | caída libre + impacto + inmóvil |

El tercero importa tanto como los otros dos: mandar a cavar donde solo hay un
teléfono cuesta minutos que alguien no tiene.

---

## Los doce escenarios

Corren en el móvil dentro de COMPROBAR QUE TODO FUNCIONA y tardan microsegundos.
**Los doce pasan** en el Redmi 24094RAD4G (Android 15) y en el Samsung A10s
(Android 11).

| escenario | decisión |
|---|---|
| el móvil se cae de la mesa | `NADA` |
| generador diésel al lado | `NADA` |
| andando con el móvil en el bolsillo | `NADA` |
| terremoto y está dormida en un 5º | `AVISAR` |
| terremoto y lo lleva encima | `PREGUNTAR` |
| alerta de otro móvil de la malla | `PREGUNTAR` |
| huye corriendo y no pulsa nada | `NADA` (40 pasos = está viva) |
| contesta que está bien | `NADA` |
| dormida, no contesta y no anda | `BALIZA` |
| derrumbe y el móvil sale despedido | `BALIZA` + `SOLO UN MÓVIL` |
| derrumbe y se la oye junto al móvil | `AUXILIO` |
| sin contador de pasos y sin contestar | `BALIZA` |

El último es el que evita el fallo silencioso: si no hay contador de pasos o falta
el permiso, «no sé si anda» **nunca** puede leerse como «ha andado». Un dato que
no existe no es un dato que valga cero.

---

## La pantalla de la pregunta

`PreguntaActivity` + `pregunta.xml`. Sale por **dos caminos a la vez y a
propósito**: lanzando la actividad, y con una notificación con
`fullScreenIntent` —que es la que funciona con el móvil bloqueado en la mesilla,
que es el caso que importa—. El segundo puede estar cerrado (desde Android 14 el
permiso solo se concede solo a apps de llamada o de alarma), y entonces la
pregunta llega como aviso de máxima prioridad, que se ve y se puede contestar.
Lo que no puede pasar nunca es que no se pregunte, y por eso hay dos caminos y
ninguno depende del otro. Diagnóstico lo dice en «Preguntar con el móvil
bloqueado».

Tres decisiones de la pantalla:

- **No se cierra con Atrás.** Cerrarla sin querer sería contestar sin contestar.
- **La cuenta atrás no vive ahí.** Manda `ServicioSos.preguntaHasta`; la pantalla
  solo lo pinta. Si el sistema la mata, el temporizador sigue y la baliza se
  enciende igual.
- **Un tic por segundo y una onda que sale del número** (`VistaOndaCuenta`, por
  debajo de todo). Quien mira esta pantalla puede estar mirándola sin leerla: las
  dos cosas dicen «hay un reloj corriendo» sin pedir que se lea nada. El tic va
  por el canal de ALARMA, el único que suena con el móvil en silencio, y los
  últimos diez segundos suenan distinto.

Los dos botones son la pareja del logotipo invertida: **ESTOY BIEN** en blanco
con letra roja, **NECESITO AYUDA** en rojo con letra blanca. Nada de verde: aquí
no hay ningún estado que celebrar, hay dos caminos y hay que distinguirlos de un
vistazo.

Y hay un **simulacro** en Diagnóstico —VER LA PREGUNTA QUE SALE TRAS UN
TERREMOTO—, porque es la única pantalla de la app que nadie ha visto nunca antes
de necesitarla. No puede escalar a nada: sin sacudida no hay suceso, así que si
no se contesta, la cascada se queda en `NADA`. Comprobado.

## El repetidor

Quien contesta «estoy bien» deja de ser cliente de la red y pasa a ser un nodo:
escucha, no suena y **reenvía**. En un terremoto la mayoría de la gente está
bien, y cada uno de esos móviles tiene batería, altavoz y a alguien mirándolo.

Al implementarlo salió un agujero que ya estaba ahí: **la malla solo reenviaba si
este móvil estaba en alarma**. O sea que el que buscaba oía la alerta, la anotaba
—«no sueno porque estás buscando»— y **ahí se acababa el viaje**, aunque el
comentario del código dijera lo contrario. Ahora `MallaAcustica.reenviar()` es
una función aparte y la usan los tres que oyen sin gritar: el silenciado, el que
busca y el repetidor.

## Lo que falta, en orden

1. **Medir los 60 s con gente**, que es el único número de la cascada que no está
   medido.
2. **Ver un salto real entre dos móviles**, que es lo que confirma que el
   repetidor sirve de algo. El cambio de estado está verificado; el relevo no, y
   es el mismo pendiente de campo que ya tenía la malla.
3. **El detector de golpes contra audio real**, que sigue sin una sola medida y es
   la mitad de `vozOGolpesCerca`, o sea de lo que separa `BALIZA` de `AUXILIO`.

## La última posición conocida

`Ubicacion.kt`. Y lo primero, lo que NO hace: **la app no enciende el GPS ni una
vez**. No hay una sola llamada a `requestLocationUpdates` en el proyecto. Lo
único que se lee es `getLastKnownLocation`, o sea un arreglo que ya hizo otro
programa: coste de batería cero, y de aquí no sale ningún histórico porque no
hay ninguno — solo dónde estaba el móvil la última vez que alguien miró.

Vale la pena porque convierte un barrio en un portal para quien te busca, y
porque antes de quedar atrapado casi siempre hay un dato reciente.

Decisiones, para no volver a pensarlas:

- **Se guarda en disco.** Si el móvil se reinicia bajo los escombros, el sistema
  pierde su última posición conocida y esta se queda.
- **Caduca a las 24 h y entonces no viaja.** Un dato de ayer es peor que ninguno,
  porque el de ayer se cree y manda a cavar donde estuviste ayer. La antigüedad
  se enseña siempre, en palabras.
- **Sale por Wi-Fi con la ficha** (`FichaLan`), con la misma regla que ella: solo
  con la alarma o el rescate activos. En los 31 bytes de la baliza de radio no
  cabe.
- **Cambia una promesa, así que cambia el texto.** Diagnóstico decía «GPS usado:
  NINGUNO»; ahora son dos filas —«GPS encendido por la app: NUNCA» y «Última
  posición conocida», con su antigüedad— y el paso 3 de la ficha lo dice también.
  Es un permiso más en la pantalla de bienvenida, y sin él todo lo demás sigue
  funcionando igual.

Verificado en el A10s: lee una posición de hace 5 minutos con ±100 m sin haber
encendido el GPS.
