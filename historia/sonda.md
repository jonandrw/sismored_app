# Historia y Decisiones — Sonda Acústica y Detección de Vida

Registro histórico de la evolución física y algorítmica del subsistema de sonda bio-acústica de SismoRed Android (`Sonda.kt`).

---

## Bio-Sonar Acústico de Impulso en Sonda.kt (4 de septiembre de 2026)

Reemplazo del tono ultrasónico continuo (18,5 kHz) por un **biosonar de impulsos/chasquidos secos** en la banda de máxima resonancia del altavoz (2,5 kHz a 4,2 kHz), inspirado en la ecolocalización animal (cetáceos, murciélagos) y las criaturas de *A Quiet Place*:
1. **La limitación física de 18,5 kHz**: En smartphones (especialmente gama media/baja como el Samsung A10s), los transductores piezoeléctricos/dinámicos caen entre 25 dB y 40 dB en ultrasonido. Además, 18,5 kHz sufre atenuación extrema contra escombros, mantas y polvo. Por el contrario, a 2,5–4,2 kHz el altavoz alcanza su máxima presión acústica (SPL) y penetra huecos difractando en aristas.
2. **El chasquido biológico (`DUR_BIO_CHASQUIDO = 8 ms`, Tukey 15%)**: Un paquete ultracorto con ventana suave que no produce pops de DC ni distorsión por recorte. Su duración de 8 ms reduce la zona ciega de ecolocalización a menos de 70 cm y su ancho de banda de 1,7 kHz proporciona una resolución de ~10 cm.
3. **Movimiento / Doppler MTI por tren de chasquidos**: Emite 10 chasquidos/segundo (`CADENCIA_RESP_HZ = 10`). Calcula la diferencia marco a marco del eco en ventanas de 2,5 a 21 ms (0,4 m a 3,5 m). Si un objeto se mueve en el hueco, la correlación de su eco fluctúa drásticamente respecto al fondo estático, encendiendo el nivel Doppler y detectando actividad biológica sin cegar la malla acústica (16–18 kHz).
4. **Respiración por biosonar de impulsos con compuerta de distancia (Range-Gated Phase Tracking)**:
   - Emite 10 chasquidos/segundo durante 25 segundos.
   - En cada disparo identifica la llegada del reflector principal entre 0,35 m y 3,0 m y extrae la fase de la portadora referenciada contra la llegada directa $d_0$ (eliminando por completo el jitter de latencia del AudioTrack de Android).
   - La serie temporal de fase se analiza a 10 Hz buscando oscilaciones rítmicas de 12 a 36 respiraciones por minuto (0,20 a 0,60 Hz).

---

## El pitido que sobrevivía a DETENER

El hilo del tono se apagaba con una variable **local** a cada medida: si el análisis
moría por una excepción nadie la bajaba y sonaba para siempre, y DETENER no sabía nada
de la sonda. Ahora la bandera es de la clase, se baja en el `finally` pase lo que pase,
y `parar()` calla barrido, tono e interfono.

---

## La sonda, cuatro herramientas con interruptor

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

---

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


### Autotest y caso de respiración en campo

Los seis casos del detector de respiración corren en el propio móvil dentro de
COMPROBAR QUE TODO FUNCIONA, y encontraron un fallo que iba a producción: el veredicto
filtraba otra vez por respiraciones por minuto además de por la banda, y 0,15 Hz son 67
muestras de retardo, o sea **8,96/min** — el ritmo más lento que se busca quedaba
descartado siempre.


### Fallos del propio banco de pruebas en la sonda

- `return todo && autotestRespiracion()` — el `&&` corta, así que **un caso de la
  sonda en rojo se saltaba la batería entera de respiración** sin decir nada.
- Los autotests corrían **en el hilo principal**, y al engordar los de la sonda
  colgaban la app al arrancar: «SismoRed no responde» si tocabas la pantalla en
  esos segundos. Era el «a veces falla» que se veía en el Samsung. Ahora van en su
  hilo, y comprobado aporreando la pantalla nada más arrancar: cero ANR.

