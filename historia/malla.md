# Historia y Decisiones — Malla Acústica Ad-Hoc

Registro histórico del protocolo acústico inaudible entre móviles sin red (`MallaAcustica.kt`).

---

## La malla se cree la alerta a la primera si está temblando

`Sismografo` publica `ultimoTemblor` (media rápida por encima de MEDIO umbral: el suelo
se mueve aunque no sea para disparar), el servicio lo convierte en
`ServicioSos.temblando` con un minuto de validez —las réplicas vienen detrás— y
`corroborada()` se salta la doble escucha. La cadencia de ráfagas se sigue exigiendo:
eso descarta un ruido, no a un impostor. Y el impostor tendría que estar dentro del
mismo terremoto.

---

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

---

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

---

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
