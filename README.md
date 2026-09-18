# SismoRed

Una app de terremotos que funciona **sin internet, sin cuenta y sin servidor**. Hace
sonar una sirena con la pantalla bloqueada y el móvil en silencio, propaga la alerta de
móvil a móvil por sonido, emite una baliza de radio para que te encuentren bajo los
escombros, y guarda una ficha médica que quien te atienda pueda leer sin desbloquear el
teléfono.

Está pensada para el momento en que no hay cobertura, no hay luz y nadie va a mirar la
pantalla.

**Bienvenido, y gracias por asomarte.** Esto se abre en canal a propósito: es una app
que puede equivocarse cuando alguien la necesita, así que cualquiera tiene que poder
mirar qué hace y con qué números lo decide. Aquí no se esconde lo que no funciona —hay
una sección entera dedicada a ello— y la mejor forma de ayudar no es escribir código.
Está contada en [`CONTRIBUIR.md`](CONTRIBUIR.md).

El sitio del proyecto está en <https://sismored.pages.dev> (el dominio
`sismored.app` todavía no está conectado).

---

## Qué hace

| Pieza | Qué resuelve |
|---|---|
| **PÁNICO** | Sirena maximizada por el canal de alarma —suena aunque el móvil esté en silencio—, linterna en SOS, vibración y aviso a los móviles cercanos. |
| **Atajo de volumen** | Pide ayuda pulsando tres veces el botón de subir volumen, con la pantalla bloqueada y sin sacar el móvil del bolsillo. |
| **Detector sísmico** | El acelerómetro dispara la alarma solo. Distingue un terremoto de correr o de que el móvil se caiga al suelo. |
| **Malla acústica** | La alerta salta de un móvil a otro en tonos de 16-18 kHz, hasta cuatro saltos. Sin internet, sin datos y sin emparejar nada. |
| **Baliza de radio** | Un anuncio BLE con el estado, el grupo sanguíneo y el nombre de pila. Es lo que permite localizar a alguien: la señal sube al acercarse. |
| **Buscar supervivientes** | El otro lado de la baliza. Guía por tendencia de señal, avisa en silencio al acercarse y ofrece pasar al trabajo con sonido al llegar encima. |
| **Sonda** | Cuatro herramientas de audio: ecolocalización por chasquidos, detección de movimiento por doppler, búsqueda de respiración y un barrido que atraviesa escombros. |
| **Interfono** | Habla hacia abajo a todo volumen y abre el canal esperando respuesta. La voz viaja por el escombro, no por radio. |
| **Ficha médica** | Nombre, grupo, alergias y contacto, a pantalla completa y con el brillo al máximo. Se guarda solo en el móvil. |
| **Modo rescate** | Un pulso cada 12 segundos en vez de sirena continua: suena mucho menos y la batería aguanta horas o días pidiendo ayuda. |

Todo corre en un servicio en primer plano con wake lock parcial, que es lo que permite
que siga vivo con la pantalla apagada y la app cerrada desde recientes.

---

## Compilar

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug
```

`local.properties` no viaja en el repositorio porque lleva rutas de tu máquina. Android
Studio la crea sola; si compilas a mano, escribe `sdk.dir=` con la ruta de tu SDK.

La app se autocomprueba al arrancar —malla, tonos, detectores, sonda y respiración— y
deja el resultado en el registro y en logcat. Si eso falla, no hace falta salir a probar
nada.

---

## Los dos documentos que importan

- **[`CONTRIBUIR.md`](CONTRIBUIR.md)** — por dónde entra alguien nuevo. Empieza por lo
  que más falta y no necesita programar: grabaciones de golpes reales sobre hormigón,
  hierro y tubería, porque ese detector nunca se ha comprobado contra audio real.
- **[`CONTINUAR.md`](CONTINUAR.md)** — el estado real de cada pieza: qué está verificado
  y qué no, las trampas ya pisadas para no volver a pisarlas, y el orden de lo que
  falta. Es la fuente de verdad del proyecto; hay que actualizarlo cuando algo cambie.

---

## Estado, a 17 de septiembre de 2026

Funciona y está instalado en tres móviles (Android 10, 11 y 15).

**Comprobado en campo, con dos móviles de verdad:** la ficha, el PÁNICO, el atajo de
volumen, el modo rescate, la baliza con tendencia de señal, y —desde esta semana— la
malla acústica, que salta de un móvil a otro en 2,5–11,7 s y, cuando la vigilia
nocturna ya está armada, en 120 ms. También la propia vigilia nocturna, que se arma tras
media hora de quietud y se desarma si levantas el móvil.

**Lo que no se puede dar por bueno todavía**, y hay que decirlo: el sismógrafo **nunca
ha detectado un terremoto real** —cruzando el catálogo salen 10 coincidencias frente a
las 8,4 del azar—; el filtro que debería distinguir un camión de un sismo está escrito
a ojo y sin calibrar; y la sonda, la respiración y el interfono siguen medidos contra
señales sintéticas. Cada caso, con la constante exacta que hay que mover, está en
[`CONTINUAR.md`](CONTINUAR.md).

---

## Las dos reglas del proyecto

**Ningún indicador se inventa nada.** Si un dato no se puede medir, se dice que no se
puede medir. La barra de búsqueda no se traduce a metros porque dos metros de escombro
atenúan más que veinte de aire, y un número en metros mandaría a cavar donde no es.
Preferimos «no lo sé» a un número bonito.

**Nada sale del móvil sin que se vea.** No hay cuenta, no hay servidor y no hay
telemetría. Lo único que se emite es la baliza de radio y la ficha, y solo con la alarma
o el modo rescate activos: está escrito con esas palabras en la pantalla de la ficha. Si
una propuesta cambia eso, tiene que cambiar también lo que el usuario lee.

---

## Gracias

A los **catálogos sísmicos públicos** que convierten una lectura del acelerómetro en un
hecho: el [Servicio Geológico Colombiano](https://www.sgc.gov.co) y el
[EMSC](https://www.seismicportal.eu). Sin su dato abierto esta app no podría confirmar
nada.

A los autores de las **cuatro tipografías** que lleva dentro —Barlow, JetBrains Mono,
Montserrat y Saira Condensed—, que las publicaron con una licencia que permite esto.

A quienes subieron a Pixabay los **63 sonidos** con los que se midieron los detectores.
No viajan en el repositorio, pero la auditoría de audio existe gracias a ellos: la
lista está en [`fx sounds/fuentes.md`](fx%20sounds/fuentes.md).

Y a quien grabe el primer golpe sobre una tubería y lo mande. Eso es lo que más falta.

---

## Licencia y autoría

Copyright (C) 2026 Juan Andrés Torres Orozco

SismoRed es software libre: puedes redistribuirlo y modificarlo bajo los términos
de la Licencia Pública General de GNU, versión 3, tal como la publica la Free
Software Foundation. El texto completo está en [LICENSE](LICENSE).

Se distribuye con la esperanza de que sea útil, pero **SIN NINGUNA GARANTÍA**, ni
siquiera la garantía implícita de comerciabilidad o idoneidad para un fin concreto.

La licencia es copyleft a propósito. En una app donde un fallo puede costar una
vida, quien la modifique y la reparta tiene que publicar sus cambios: nadie debería
poder cerrar esto y venderlo sin que se pueda auditar qué le hizo.

### Lo que hay dentro y no es nuestro

- **Las tipografías** (`app/src/main/res/font/`) son de sus autores y van bajo la SIL
  Open Font License 1.1, no bajo la GPL. Los cuatro avisos de copyright y el texto
  completo están en [`licencias/SIL-OFL-1.1.txt`](licencias/SIL-OFL-1.1.txt).
- **Los sonidos de prueba** no se reparten aquí. Son de Pixabay, y su licencia deja
  usarlos pero no volver a distribuirlos sueltos. En
  [`fx sounds/fuentes.md`](fx%20sounds/fuentes.md) está la lista con el identificador
  de cada uno para rehacer el banco igual.
