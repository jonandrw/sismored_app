# Cómo entrar en SismoRed

SismoRed es una app de terremotos que funciona **sin internet**: hace sonar una sirena
con la pantalla bloqueada, propaga la alerta de móvil a móvil por sonido, emite una
baliza de radio para que te encuentren bajo los escombros, y guarda una ficha médica
que quien te atienda pueda leer sin desbloquear el teléfono.

Si vas a colaborar, empieza por estos tres archivos y en este orden:

1. **`README.md`** — qué hace cada pieza y por qué existe.
2. **`CONTINUAR.md`** — el estado real: qué está verificado, qué no, y las trampas que
   ya se han pisado para que no las vuelvas a pisar. Es la fuente de verdad del
   proyecto. Si cambias algo importante, actualízalo.
3. El código. Está comentado explicando **por qué** cada decisión es como es,
   incluidos los callejones sin salida. No vas a tener que adivinar.

---

## Lo que más falta, y no necesita saber programar

**Grabaciones de golpes.** La carpeta `fx sounds/Golpes/` está vacía, y el detector de
golpes **nunca se ha comprobado contra audio real**. Golpear una tubería, una losa o un
radiador es exactamente como pide ayuda alguien atrapado, así que es el detector que
más importa y el único sin una sola prueba. Hoy un fuego crepitando lo dispara.

Lo que hace falta: grabaciones de golpes reales sobre material de construcción —
hormigón, hierro, tubería, madera— desde distintas distancias y con ruido de fondo si
lo hay. También faltan **alarmas** (`fx sounds/Alarmas/`).

Con el móvil basta. MP3 o WAV, unos segundos cada una, y di en el nombre qué es y a
qué distancia.

---

## El banco de detectores: corre en segundos y sin móvil

Es la mejor forma de mejorar la parte más débil del proyecto sin tocar Android.

Primero se regeneran los WAV a partir de los MP3 (mono, 48 kHz, que es lo que graba el
móvil):

```bash
cd "fx sounds" && for f in */*.mp3; do ffmpeg -y -i "$f" -ac 1 -ar 48000 "wav/$(basename "${f%.*}").wav"; done
```

Y se mide:

```bash
cd "fx sounds" && python banco.py wav
```

Las perillas van por variable de entorno, así que se prueba una idea sin recompilar
nada: `UMB_EST_DB=-25 python banco.py wav`.

Los números de hoy, para que sepas contra qué compites: derrumbe acierta 3 de 8,
escombros 0 de 4, nueve animales se leen como «grito», la voz no se enciende nunca y
la maquinaria da 3 falsos derrumbes. **Callejones ya explorados** (están en
`CONTINUAR.md`, no los repitas): la planitud espectral, el flujo espectral marco a
marco y la distancia al espectro medio. La pista que sigue sin probar es la
**modulación de la envolvente a baja frecuencia** — un motor tiene una periodicidad
marcada a su régimen de giro que un derrumbe no tiene.

Hay un segundo banco, `resp.py`, para el detector de respiración. Mismo espíritu.

---

## Compilar la app

No hace falta abrir Android Studio:

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug
```

`local.properties` no está en el repositorio (lleva rutas de tu máquina). Android
Studio lo crea solo la primera vez; si compilas a mano, escribe una línea con
`sdk.dir=` y la ruta de tu SDK.

La app se autocomprueba al arrancar y deja los resultados en el registro y en logcat:
malla, tonos, detectores, sonda y respiración. Si eso falla, no hace falta salir a
probar nada.

---

## Pruebas de campo: aquí es donde de verdad se necesita gente

Hay tres medidas que **no se pueden hacer desde un escritorio**, y hasta que estén
hechas hay tres números en el código que son suposiciones razonadas, no datos. Están
detalladas en `CONTINUAR.md` con la constante que hay que mover en cada caso:

1. La sonda de eco contra una pared a distancia conocida.
2. El detector de respiración con una persona quieta a un metro.
3. El interfono con dos personas y algo sólido en medio.

Y hacen falta móviles distintos: cada altavoz corta a una altura distinta y cada
micrófono tiene su latencia, así que lo que funciona en tres teléfonos no
necesariamente funciona en el cuarto. Si lo pruebas en un modelo que no esté en
`CONTINUAR.md`, cuéntalo aunque funcione.

---

## Dos reglas del proyecto

**Ningún indicador se inventa nada.** Si un dato no se puede medir, se dice que no se
puede medir. La barra de búsqueda no se convierte a metros porque dos metros de
escombro atenúan más que veinte de aire, y un número en metros mandaría a cavar donde
no es. Preferimos «no lo sé» a un número bonito.

**Nada sale del móvil sin que se vea.** No hay cuenta, no hay servidor y no hay
telemetría. Lo único que se emite es la baliza de radio y la ficha, solo con la alarma
o el rescate activos, y está escrito en la pantalla de la ficha con esas palabras. Si
una propuesta cambia eso, tiene que cambiar también lo que el usuario lee.
