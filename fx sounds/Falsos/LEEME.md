# Falsos — el sonido que engañó a la app

Aquí va **lo que se equivocó de verdad**, en una prueba real y no en un banco: la app
dijo DERRUMBE y era una moto, dijo GRITO y era la tele. Esa grabación vale más que
diez efectos de sonido comprados, porque es exactamente el error que comete el
detector que se está usando, no uno que se ha imaginado alguien.

Está vacía porque la app todavía no se ha llevado a campo. La primera vez que se
equivoque, esto deja de estar vacío.

## Cómo se recoge

`banco.py` mide sobre WAV, así que sirve cualquier grabación del móvil de los
segundos en que la app se confundió. Con arrancar la grabadora en cuanto salte la
alarma falsa vale: el sonido que la disparó suele seguir ahí.

En el nombre, **qué era en realidad y qué dijo la app**:

```
moto arrancando dijo DERRUMBE - portal de casa.mp3
tele en el salon dijo GRITO.mp3
persiana bajando dijo GOLPES.mp3
```

## Qué se espera de esta carpeta

Que el detector **calle**. Cuenta como carpeta de silencio, o sea que cada archivo
que meta aquí empeora la cifra de falsos por hora hasta que alguien la arregle — y
eso es exactamente lo que tiene que pasar. Un banco que solo tiene sonidos que ya se
aciertan no mide nada.

Y sirve para lo contrario también: cuando se toque un umbral para arreglar un falso,
esta carpeta es la que dice si de verdad se arregló o si solo se movió el problema
de sitio.

> Cuidado con lo que se guarda: una grabación de casa lleva dentro voces de quien
> viva ahí. Esto va a un repositorio público, así que el archivo tiene que ser del
> ruido, no de la conversación. Si hay alguien hablando y no se puede recortar, no
> se sube.
