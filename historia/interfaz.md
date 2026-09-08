# Historia y Decisiones — Interfaz y Experiencia de Usuario

Registro histórico de diseño, componentes UI, tipografía, layouts y pruebas visuales en SismoRed Android (`Vistas.kt`, layouts, strings, estilos).

---

## Las animaciones que no arrancaban

`activo` era un campo normal, y las vistas solo se reprograman **mientras están
activas**: al encenderlas no había nadie que las despertara. Asignar un campo no
repinta nada. Ahora el setter llama a `invalidate()`. El síntoma que lo delató fue que
el barrido sonaba fuerte y no dibujaba nada.

---

## Textos e interfaz

- **Jerarquía de verdad**: el ladrillo `paso` tenía un solo TextView, así que la
  jerarquía se intentaba con MAYÚSCULAS y puntos medios, que no es jerarquía. Ahora cada
  paso tiene **título en negrita** y **cuerpo** en gris, y están reescritos los **27
  pasos** de la app. Los rótulos de sección llevan su subtítulo aparte en vez de
  «RÓTULO · media frase».
- **Los muros de ayuda son tarjetas de pasos**: CÓMO VIAJA LA ALERTA, CÓMO FUNCIONA
  ESTA FICHA, CÓMO SE USA, el interfono en tres pasos y la caja de internet en tres.
- **Las palabras cortadas** tenían causa: Android hifena por defecto y en columna
  estrecha deja trozos de palabra. `hyphenationFrequency="none"` y
  `breakStrategy="simple"`.
- **Consolas que escriben** (`VistaConsola`): el texto se revela carácter a carácter con
  un cursor de bloque parpadeando, y si el texto nuevo empieza por el viejo sigue desde
  donde estaba. Está en las siete consolas de herramientas y en el registro. La del
  interfono además **apila** con la hora delante, como un terminal.
- **Nada se parte ni se aprieta**: los ghost pasaron de altura fija a `match_parent` con
  mínimo y rótulo autoescalado; los rótulos de una línea (MARCAR EVENTO, DETENER) usan
  `Boton.UnaLinea`; el rótulo del detector cabe en una línea —«DERRUMBE» se partía
  dejando una «E» sola y estiraba la casilla—; el logotipo cede ancho para que los
  iconos no se salgan; las cinco pestañas se autoescalan y Búsqueda se llama «Buscar».
- **El título ya no cambia de tamaño solo**: la etiqueta «ESCUCHANDO 00:12» crecía cada
  segundo y el título se reflowaba con ella. Ahora la etiqueta dice OYENDO y el
  cronómetro vive en la fila de estado.
- **La fila clave/valor** reparte por peso: con el valor en `wrap_content`, «Escuchando
  16-18 kHz» se comía el ancho y tapaba el rótulo. Sin autoescalado a propósito: con
  `wrap_content` en el alto, Android calcula tamaño cero y **el texto desaparece** — se
  vio en «Estado de la malla».
- **La ficha a pantalla completa** es un layout con pesos que se reparte la pantalla
  entera: nombre (2), grupo en rojo a 64 sp junto a la edad (3), alergias y medicación
  (4, el más alto porque es el que no se puede leer a medias) y contacto (2). Los campos
  vacíos se esconden y sueltan su peso. Y es un `Dialog` pelado, no un `AlertDialog`:
  ése envuelve la vista en un contenedor `wrap_content` y los pesos colapsaban — la
  ficha salía a media pantalla. El toque para cerrar va en la vista, porque
  `setCanceledOnTouchOutside` cierra al tocar **fuera** y una ventana a pantalla completa
  no tiene fuera.
- **«Si aparece internet» ya hace algo.** El endpoint existe (contesta 400 validando, no
  404); el problema era que `vaciar()` se rendía en silencio en cuatro casos y ninguno
  decía nada. Ahora cada salida tiene su frase en la consola, y **PROBAR AHORA** encola
  un parte marcado como prueba y fuerza el intento.
- **Detección en vivo**: la escucha es un interruptor con su estado escrito, y las nueve
  filas van en dos grupos con rótulo (LO QUE OYE AHORA / LO ÚLTIMO QUE RECONOCIÓ).

---

## La pantalla ACERCA DE (hecha, y en parte es obligatoria)

Se llega desde Diagnóstico. No es cortesía:

- **La política de privacidad tiene que estar accesible dentro de la app.** Lo
  exige Google Play para cualquier app, y aquí hay ficha médica (dato de salud),
  micrófono y ubicación.
- **El permiso de accesibilidad hay que justificarlo donde se lea.** Es el mayor
  riesgo de rechazo que tiene este proyecto: Play exige que las APIs de
  accesibilidad se usen para accesibilidad, y aquí se usan para oír el botón de
  volumen con la pantalla apagada. La tarjeta lo dice con todas las letras: no
  lee la pantalla, ni lo que escribes, ni otras apps.
- **Una app que dice detectar terremotos tiene que decir lo que NO puede hacer.**
  Hay una tarjeta entera en rojo: no sustituye a los servicios de emergencia, no
  garantiza que te encuentren, no detecta personas —detecta móviles— y no
  predice nada.

Seis tarjetas: qué es · lo que no puede hacer · tus datos · por qué cada permiso ·
preguntas frecuentes · contacto y versión. La versión se lee del propio paquete,
que escribirla a mano es garantizar que algún día mienta. El contacto es el
repositorio, que ya estaba documentado; no se inventó ningún correo.

Jerarquía igual que la pantalla de «¿estás bien?»: **rótulo rojo pequeño →
titular blanco de una frase → cuerpo gris** (`TitularTarjeta` en themes.xml). Sin
el titular, una tarjeta de texto largo es un muro que nadie lee.

**Sin revisar en pantalla**: el Redmi no acepta toques por adb y el A10s está
desconectado, así que compila e instala pero nadie la ha visto todavía.

---

## La pasada de interfaz con el Samsung delante

El A10s **sí acepta `input tap`**, así que por primera vez se puede navegar,
tocar y medir. `uiautomator dump` no sirve en esta app —las consolas parpadean y
la ventana nunca queda en reposo: «could not get idle state»—, así que se mide
sobre los píxeles de la captura (`medir.py`, 2 px por dp a densidad 320).

Las tarjetas de instrucciones (`paso.xml`), medidas y arregladas:

- **El título no tenía jerarquía**: 13 sp contra 12,5 del cuerpo. Media décima no
  es jerarquía, es la misma línea escrita dos veces. Ahora 14 contra 12.
- **Los títulos se partían en dos líneas** y la segunda se leía como cuerpo. Los
  33 títulos están reescritos a **≤ 29 caracteres**, que es lo que cabe en una
  línea en la columna que dejan el icono y el número. No hay `maxLines`: recortar
  con puntos suspensivos es peor que partir, así que la regla la cumple el texto.
- **Los cuerpos llegaban a seis líneas.** Reescritos a **dos**, justificados con la
  misma receta de `DocCuerpo` (`high_quality` + `none` + `inter_word`).
- **Cada paso es ahora una tarjeta de verdad** (`drawable/tarjeta_paso.xml`), con
  borde y tono propios y 10 dp entre ellas; fuera las 23 líneas divisorias. El
  primer intento reusó `fila_op` y no se vio nada: es del mismo #14171A que la
  tarjeta madre, así que separar sin cambiar de tono es solo más espacio en
  blanco.
- **Márgenes medidos**: 46,5-48 dp entre pasos, 23,5 arriba y 22-23,5 abajo. La
  diferencia es tinta —una línea sin trazos descendentes mide 1,5 dp menos—; el
  relleno es exactamente 16 dp por lado.

Y la excepción, a propósito: **`paso_f3` sigue en cinco líneas**. Es la única
pantalla donde el usuario ve que por Wi-Fi sale la ficha ENTERA. Recortarla a dos
líneas sería romper una promesa escrita, no ahorrar una línea.

Dos rótulos más que se cortaban con puntos suspensivos en el centro de opciones:
«PROBAR LA FICHA POR WI-…» y «COMPROBAR QUE FUNCIO…», ahora **PROBAR LA WI-FI** y
**COMPROBAR TODO**.

**Y la malla que se montaba encima de sí misma.** En MALLA DE PROPAGACIÓN se leía
«Balizas detectadasRetransmisiones». No era un solape de dibujo: las tres columnas
de `stats3` iban a peso 1 y **sin un solo pixel entre ellas**, y «Balizas
detectadas» ocupaba 190 px de los 198 de su columna. Ahora hay 10 dp de hueco y
los rótulos son **Balizas oídas** y **Reenvíos**.

### Lo que queda visto pero sin arreglar

- En MALLA DE PROPAGACIÓN, dos marcas «--» sueltas flotando encima de «Estado de
  la malla». Es otro caso del alto calculado a cero que ya está documentado en
  esta misma tarjeta.
- En Diagnóstico, la fila «Ficha por Wi-Fi» corta el valor por la derecha:
  «192.168.101.27 (con».
- La cuadrícula de respuesta **sigue sin revisarse** en pantalla.

---

## Lo que quedó SIN verificar en pantalla

Compilado e instalado, pero no lo vi funcionando con mis propios ojos. Son un vistazo
de un minuto cada uno:

- La caja de ajustes de la señal, al final de Entorno.
- La caja de internet, al final de Red.
- La ficha a pantalla completa (Ficha → MOSTRAR FICHA).
- Las consolas escribiendo con el cursor.

> Y una lección de método, porque me costó dos diagnósticos falsos: el puente temporal
> que se mete en `MainActivity.onCreate` para poder disparar acciones por `adb`
> **hay que comprobar que se ha insertado** (`grep` al archivo) antes de creerse el
> resultado. Dos veces di por roto algo que funcionaba, porque el ancla del script no
> coincidía y la acción no se disparaba nunca. El eco «que no corría» corría
> perfectamente.


### Disclosure de la ficha completa por Wi-Fi

> **Y esto hay que decirlo donde el usuario lo vea, sin adornos**: por wifi va la
> ficha COMPLETA —nombre y apellido, alergias, medicación y contacto— a cualquiera
> que esté en la misma red, no solo trece bytes de nombre como en la radio. Es una
> disclosure mucho mayor que la de la baliza. Va en el paso 3 de CÓMO FUNCIONA ESTA
> FICHA, junto a lo que ya dice de la radio, y con la misma condición: solo mientras
> la alarma o el rescate estén activos.

