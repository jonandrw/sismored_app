# La biblioteca de sonidos

Aquí se decide si los detectores de audio sirven o no. Son los sonidos con los que
se mide `banco.py`, y hasta que un caso no está en una de estas carpetas **nadie lo
ha comprobado nunca**, por mucho que el código lo contemple.

Regla de la casa: la carpeta dice qué se espera del detector. No hay etiquetas
dentro de los archivos ni un índice aparte que se quede viejo — el sitio donde está
el archivo **es** la etiqueta, y `banco.py` la lee de ahí.

## Las diez carpetas

**Donde el detector TIENE que encenderse:**

| carpeta | qué va dentro | qué se espera |
|---|---|---|
| `Derrumbe/` | edificios que caen, explosiones, roca partiéndose | `estruendo` |
| `Escombros/` | cascotes, muros golpeados, temblor con crujidos | `estruendo` |
| `Golpes/` | **vacía** — golpear tubería, hormigón, hierro | `golpes` |
| `Animales/` | perros, gatos: lo que NO puede leerse como persona | `animal` |

**Donde tiene que callar, y de donde sale la cifra de falsos por hora:**

| carpeta | qué va dentro | por qué está |
|---|---|---|
| `Maquinaria/` | generadores, martillos, compresores, motores | es lo que más se parece a un derrumbe |
| `Ambiente/` | fondo normal: casa, calle, oficina, fuego | mide los falsos de un día cualquiera |
| `Rescatistas/` | sirenas, helicópteros, morse, megafonía | están justo donde hay que buscar |
| `Alarmas/` | **vacía** — alarmas de coche, humo, antirrobo | suenan solas en un terremoto |
| `Falsos/` | **vacía** — lo que engañó a la app en una prueba real | es el error que de verdad comete |
| `Humanos/` | voces, gritos, llanto, respiración | ninguno puede levantar la sirena solo |

`Humanos/` merece una aclaración: que `grito` o `voz` se enciendan ahí **no es un
acierto ni un fallo**, es una anotación en el registro y, con la alarma puesta, una
respuesta hacia abajo. Por eso está entre las que callan, contando aparte: lo que
no puede pasar nunca es que una voz levante la sirena.

## Cómo se mide

Las carpetas llegan vacías. Los 63 audios con los que se sacaron las cifras de la
auditoría son de Pixabay y su licencia no deja repartirlos sueltos: en
[`fuentes.md`](fuentes.md) está la lista con el identificador de cada uno.

```bash
./convertir.sh          # deja en wav/ todo mono a 48 kHz, que es como graba el móvil
python banco.py wav
```

`wav/` es un derivado y tampoco viaja en el repositorio. `convertir.sh` solo rehace
lo que falte, así que correrlo de más no cuesta nada.

## Cómo se aporta un sonido

1. El archivo va **dentro de la carpeta que le toca**, y ahí está su etiqueta.
2. El nombre dice qué es, en español, y lo que haga falta para repetirlo: distancia,
   material, si hay ruido de fondo. `tubo hierro a 5 m con obra al fondo.mp3` vale;
   `audio_003.mp3` no vale para nada.
3. MP3 o WAV, con el móvil basta. `convertir.sh` acepta también m4a, ogg, opus y flac.
4. Unos segundos son suficientes, salvo en `Ambiente/`, que es al revés: ahí lo que
   hace falta son **horas seguidas**.

## Lo que más falta

- **`Golpes/` está vacía**, y golpear una tubería es exactamente como pide ayuda
  alguien atrapado. Ese detector no se ha comprobado nunca contra audio real; hoy un
  fuego crepitando lo dispara.
- **`Ambiente/` tiene 44 segundos.** La cifra que decide si esto sirve son los falsos
  por hora en un día normal, y con 44 segundos esa cifra no se puede calcular: se
  necesitan horas de grabación aburrida —una casa un martes por la tarde—, no efectos
  de sonido. Es la carpeta más fácil de llenar y la que más vale.
- **`Falsos/` está vacía porque todavía no se ha usado la app en campo.** En cuanto
  se equivoque una vez, esa grabación entra aquí y ya no se vuelve a repetir el
  mismo error sin que el banco lo cante.
