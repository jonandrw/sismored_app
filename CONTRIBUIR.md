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

Grabar sonidos. `fx sounds/README.md` explica las diez carpetas y qué se espera de
cada una; las que están vacías llevan dentro un `LEEME.md` con qué grabar y cómo
nombrarlo. Con el móvil basta, MP3 o WAV.

**Golpes** (`fx sounds/Golpes/`) es la que más falta hace: está vacía, así que el
detector de golpes **nunca se ha comprobado contra audio real**. Golpear una tubería,
una losa o un radiador es exactamente como pide ayuda alguien atrapado, así que es el
detector que más importa y el único sin una sola prueba. Hoy un fuego crepitando lo
dispara. Hace falta hormigón, hierro, tubería y madera, a distintas distancias y con
algo de por medio.

**Ambiente** (`fx sounds/Ambiente/`) es la que más vale y la más fácil: ahí no van
efectos de sonido, va el rato aburrido —una casa un martes por la tarde, una noche en
la mesilla— y hacen falta **horas**. Es lo único que puede contestar a la pregunta
que decide si esto se puede llevar encendido: cuántas alarmas falsas da al día. Hoy
hay 44 segundos y por eso esa cifra todavía no existe.

Y **alarmas** (`fx sounds/Alarmas/`), que es lo primero que suena en un edificio que
se mueve.

---

## El banco de detectores: corre en segundos y sin móvil

Es la mejor forma de mejorar la parte más débil del proyecto sin tocar Android.

Las carpetas llegan vacías: los 63 audios con los que están medidas las cifras de la
auditoría son de Pixabay y no se pueden volver a repartir sueltos.
[`fx sounds/fuentes.md`](fx%20sounds/fuentes.md) lleva la lista con el identificador
de cada uno; descargándolos con el mismo nombre y en la misma carpeta, los números
salen iguales. Valen igual tus propias grabaciones, y hacen más falta.

Después se regeneran los WAV a partir de los originales (mono, 48 kHz, que es lo que
graba el móvil). El script solo rehace lo que falte, así que correrlo de más no
cuesta nada:

```bash
cd "fx sounds" && ./convertir.sh
```

Y se mide, en unos diez segundos:

```bash
cd "fx sounds" && python banco.py wav
```

Las perillas van por variable de entorno, así que se prueba una idea sin recompilar
nada: `UMB_EST_DB=-30 python banco.py wav`. Los valores por defecto son los que lleva
`Escucha.kt` instalado, ni uno más cómodo: correr el banco sin tocar nada mide la app
que se usa.

Los números de hoy, para que sepas contra qué compites:

| | |
|---|---|
| Derrumbe | acierta **8 de 8**, en 0,9 s de mediana |
| Escombros | **0 de 4**: dos se leen como grito o animal y dos no dicen nada |
| Animales | **1 de 11**, y **8 se leen como grito** — o sea, como una persona pidiendo auxilio |
| Falsos que levantan la sirena | **32,7 por hora** sobre el material que tiene que callar |
| Falsos de los otros cuatro | 267 por hora, que son anotaciones en el registro |

La cifra que decide es la penúltima, no la primera. Un detector que acierta el 99 %
pero grita una vez por hora es inservible: a la tercera vez nadie corre.

**Callejones ya explorados** (están en `CONTINUAR.md`, no los repitas): la planitud
espectral, el flujo espectral marco a marco y la distancia al espectro medio. La
pista que sigue sin probar es la **modulación de la envolvente a baja frecuencia** —
un motor tiene una periodicidad marcada a su régimen de giro que un derrumbe no
tiene.

Y una regla que costó una tarde: si tocas un umbral, tócalo **en los dos sitios en la
misma sesión**. El banco se separó de `Escucha.kt` sin que nadie lo notara y estuvo
midiendo un detector que la app ya no llevaba.

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
