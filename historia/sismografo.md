# Historia y Decisiones — Sismógrafo y Detección Inercial

Registro histórico de problemas, experimentos, decisiones y física del detector sismológico en SismoRed Android (`Sismografo.kt`, `Postura.kt`).

---

## STA/LTA sismológico recursivo en Sismografo.kt (4 de septiembre de 2026)

Reemplazo de la estimación de calma por ordenación de arrays por un filtro recursivo de largo plazo (LTA):
1. **Eliminación de pausas de GC**: Antes se hacía `calma.copyOf(cn).sortedArray()` cada 32 muestras (~640 ms) directamente en el hilo del sensor del acelerómetro. Eso creaba y ordenaba un array de 256 `Double` en la memoria dinámica continuamente, provocando micro-congelamientos de la recolección de basura de Android.
2. **Estimador adaptativo LTA con congelación**: Ahora `ltaH` se actualiza mediante suavizado exponencial con constante de tiempo lenta (~15 segundos): `alphaLta = (1.0 / (maxOf(20.0, srMedido) * 15.0))`. Si hay movimiento transitorio (`sta > ltaH * 2.0` o `sta > umbral * 0.4`) o si el móvil está siendo manipulado (`hayMano`), la adaptación **se congela inmediatamente**, evitando que la energía de un terremoto o de un golpe eleve el piso de ruido.
3. **Relación STA/LTA en sueloDeFiar**: Se calcula `ratioStaLta = sta / ltaPiso`. En modo fino (`umbral <= umbralFinoMax`), `sueloDeFiar` exige que la relación de energía instantánea contra el piso de ruido supere al menos 1.8 (`ratioStaLta >= 1.8`), blindando aún más al detector contra vibraciones constantes de mesa.

---

## El sismógrafo medía justo al revés de lo que tenía que oír

Salió de una queja de campo —«en reposo, si lo levanto de golpe se activa, y
reacciona a que mueva un poco la mesa con el teclado»— y detrás había un fallo
de física que llevaba desde el port de la web.

El detector medía `| |a| − media |`: el **módulo** del vector aceleración menos
su media lenta. Y el módulo casi no cambia con la aceleración horizontal, porque
entra en cuadratura con la gravedad:

| empujón de 1 m/s² | desviación del módulo |
|---|---|
| horizontal | **0,05** |
| vertical | **1,00** |

Veinte veces más lo vertical. Y resulta que **la mano levanta en vertical**, el
portazo y el teclado llegan por la mesa en vertical, y **lo que tira los
edificios es horizontal** — son ondas S. Medido en `fx sounds/sismo.py`:

| señal | pico de la media rápida |
|---|---|
| levantar el móvil de golpe | **2,39** |
| terremoto MMI V | **0,21** |

Once veces más el falso que el bueno. Y con el umbral en 0,8 **el terremoto no
disparaba nunca, ni bajándolo**: la app tenía a la vez un falso positivo que
molestaba y un falso negativo que no se podía ver.

### Lo que se cambió

**1. Se mide la componente horizontal.** Se resta el vector gravedad —el mismo
que ya se filtraba para el giro, así que no cuesta nada— y se toma solo lo
perpendicular. Con eso todo lo que llega por la mesa da CERO:

| señal | ciclo de trabajo (8 semillas) |
|---|---|
| teclear, portazo, martillazos, camión | 0,00 |
| lavadora centrifugando a 11 Hz | 0,00-0,01 |
| **terremoto MMI V** | **0,16-0,37** |
| terremoto MMI VI | 0,58-0,93 |
| terremoto MMI VII | 0,88-1,00 |

**2. Ciclo de trabajo en vez de muestras seguidas.** El contador viejo subía de
uno en uno y bajaba de cuatro en cuatro, y pedía 36 seguidas. Se diseñó contra el
correr —picos con calma en medio—, pero **un terremoto oscila y también baja del
umbral en cada semiciclo**: un MMI V no daba más de trece muestras seguidas, así
que con el castigo de cuatro por hueco el contador no llegaba nunca. La regla que
evitaba un falso garantizaba un mudo.

**3. Los umbrales cambian de escala, y por eso cambian de clave.** 0,25 en reposo
y 2,5 encima, contra 0,8 y 6,0. No son comparables: están medidos sobre otra
magnitud. Las claves pasan a `op_umbral_h` y `op_umbral_reposo_h` porque heredar
un valor de la escala vieja dejaría el detector sordo **en silencio**.

**4. Y ya tiene autotest**, que no tenía. Es el detector donde más caro sale no
tenerlo: no se puede comprobar usándolo —haría falta un terremoto— y sus dos
fallos son mudos. Corre en COMPROBAR TODO y se anota siempre. Salida real en el
A10s:

```
autotest sismógrafo · teclear en la mesa → no OK | martillazos en la mesa → no OK |
levantarlo de golpe → no OK | lavadora centrifugando → no OK | camión pasando → no OK |
TERREMOTO MMI V → dispara OK | MMI VI → dispara OK | MMI VII → dispara OK
```

### Lo que se probó y NO vale

**Contar los cruces por cero** para separar un tirón de un terremoto. Con una
realización del ruido parecía perfecto —5 cruces contra 8-12—; con ocho semillas,
levantar da 3-10 y los terremotos 5-16. **Se solapan enteros.** Estuve a punto de
fijar un umbral sobre una tirada de dados, y la única razón por la que no pasó es
que se comprobó con más de una semilla. Queda escrito para que nadie lo reintente.

### Lo que sigue sin estar medido

Todo esto es banco sintético. **Un móvil real en una mesa real con alguien
tecleando al lado sigue sin medirse**, y es la prueba de §5 que lleva pendiente
desde el principio: una noche entera contando los falsos por hora.

---

## «En reposo» y «dormida» no son lo mismo

Otra de campo, y de las buenas: «apenas sale el letrero de pregunta, se dispara
la baliza». No era la baliza — era la **sirena**, que en AVISAR suena a la vez
que la pregunta. El registro lo decía tal cual: `TERREMOTO · te despierto y te
pregunto si estás bien`.

Y la regla estaba mal puesta. La sirena automática existe para **una sola cosa**:
alguien dormido en un quinto piso que no ha sentido nada. Pero la condición era
«el móvil está en reposo», y eso mete en el mismo saco la mesilla de noche a las
tres de la mañana y la mesa del salón con su dueño delante mirando el teléfono.
En el segundo caso la sirena no despierta a nadie: sobresalta. Y una alarma que
sobresalta sin motivo es una alarma que se acaba apagando para siempre — que es
el riesgo que más veces se ha escrito en este documento.

El dato que los separa ya se medía y no se usaba aquí: **cuánto hace que alguien
tocó el móvil**. Menos de veinte minutos, estás despierto y basta con preguntar
sin ruido. Más, o no se sabe, sirena. Y «no se sabe» va al lado de la sirena a
propósito: no saber no puede costarle el aviso a quien duerme.

Tres casos nuevos en el autotest, y el de campo escrito con esas palabras:
«terremoto con el móvil en la mesa y tú delante → PREGUNTAR».

---

## La alarma con el móvil en la mano no venía del sismógrafo

El fallo que echó la app del Redmi, cazado con el registro delante y no con un
banco sintético. La secuencia, tal cual:

```
10:06:49  sismografo: 2.05 m/s2 pero hay una mano (8°), no disparo
10:06:49  sismografo: 3.06 m/s2 pero hay una mano (35°), no disparo
          ... nueve veces seguidas
10:07:12  cascada(estruendo por micrófono) -> PREGUNTAR · terremoto confirmado
10:08:12  cascada(nadie ha contestado) -> BALIZA/PERSONA_PROBABLE
```

**La puerta de la mano funcionaba.** Bloqueó nueve disparos seguidos. Lo que
disparó fue el micrófono, y la cascada lo dio por terremoto confirmado — cuando
un estruendo sin sacudida tiene que dar NADA, y está escrito así en el paso 1.

Para llegar ahí, `sacudida` tenía que ser cierta. Y lo era, por una puerta
trasera: `Sismografo.ultimoTemblor`. Es la bandera blanda de «aquí se mueve el
suelo», usa **medio umbral, sin ciclo de trabajo y sin mirar si hay una mano**, y
de ella sale `ServicioSos.temblando`, que la cascada lee como `sacudida`.

Con el móvil en la mano esa bandera está levantada de continuo. Así que **mano +
cualquier ruido por el micrófono = alarma completa**, sin que el sismógrafo
llegara a disparar ni una vez.

La lección, que ya ha salido tres veces esta sesión: **proteger el disparo no
basta si otra cosa cuenta la misma historia por otro camino**. La bandera decía
«se mueve el suelo» y una mano invalida esa frase exactamente igual que invalida
el disparo. Ahora lleva la misma puerta.

Y la regresión comprueba las dos direcciones, que es lo que faltó la primera vez:
con mano no puede levantarse, y en un terremoto de verdad **tiene que**
levantarse — si no, la malla perdería el atajo que le deja creerse una alerta
ajena a la primera.

---

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

---

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
