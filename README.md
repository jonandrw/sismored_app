# SismoRed

Una app de terremotos que funciona **sin internet, sin cuenta y sin servidor**. Hace
sonar una sirena con la pantalla bloqueada y el móvil en silencio, propaga la alerta de
móvil a móvil por sonido, emite una baliza de radio para que te encuentren bajo los
escombros, y guarda una ficha médica que quien te atienda pueda leer sin desbloquear el
teléfono.

Está pensada para el momento en que no hay cobertura, no hay luz y nadie va a mirar la
pantalla.

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

## Estado, en una línea honesta

Funciona y está instalado en tres móviles (Android 10, 11 y 15). Lo que ya se puede dar
por bueno es la parte que no necesita calibrar nada: la ficha, el PÁNICO, el atajo de
volumen, el modo rescate y la baliza con tendencia de señal. Lo que aún **no** se puede
dar por bueno son las medidas finas —la distancia de la sonda, el umbral de respiración
y el del interfono—, que están calibradas contra señales sintéticas y les faltan tres
pruebas físicas. Están detalladas, con la constante que hay que mover en cada caso, en
`CONTINUAR.md`.

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
