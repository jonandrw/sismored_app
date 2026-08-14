# Golpes — la carpeta que más falta hace

Está vacía, y por eso el detector de golpes **no se ha comprobado nunca contra un
sonido real**. Es el más importante de los cinco: golpear una tubería o una losa es
justo como pide ayuda alguien atrapado, porque gritar bajo hormigón no se oye y
agota. Hoy, sin una sola medida, un fuego crepitando lo dispara.

## Qué grabar

Golpes de verdad sobre material de construcción, con el móvil, unos segundos cada uno:

- **Hormigón**, con la mano, con una piedra y con algo metálico.
- **Tubería** de hierro y de cobre, que es lo que suele haber a mano bajo un edificio.
- **Radiador, viga, barandilla, puerta metálica.**
- Y lo mismo **a distintas distancias**: pegado, a cinco metros, a veinte, y con una
  pared o una losa de por medio. Un golpe a un metro no se parece a uno a través de
  medio metro de escombro, y el detector tiene que ver los dos.

Interesan sobre todo los **ritmos**: tres golpes seguidos, golpes lentos y regulares,
golpes desesperados y sin ritmo. El detector no busca un golpe, busca **regularidad**
—es lo que separa a una persona de una viga que cruje sola—, así que un golpe suelto
enseña menos que una tanda.

Y hace falta también lo contrario, para saber qué NO debe encender: una puerta que se
cierra, algo que se cae, pasos, una obra al fondo. Eso va en `../Ambiente/`.

## Cómo nombrarlo

El nombre dice qué es y a qué distancia, en español:

```
tubo hierro tres golpes a 5 m.mp3
hormigon con piedra ritmo lento a 1 m.mp3
radiador golpes rapidos tras pared.mp3
```

Cuando haya archivos aquí, `python banco.py wav` empieza a dar una fila `Golpes` con
sus aciertos, y esta carpeta deja de salir en los avisos.
