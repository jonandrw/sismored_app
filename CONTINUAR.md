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
