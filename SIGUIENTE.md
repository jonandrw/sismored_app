# Lo siguiente, en orden

Relevo para abrir una ventana nueva. `CONTINUAR.md` sigue siendo la fuente de
verdad —el porqué de cada decisión y las trampas ya pisadas—; esto es solo la
lista de lo que falta, ordenada por lo que más decide.

Última revisión: **17 de septiembre de 2026**.

---

## 0. Lo que se cerró el 17 de septiembre

Un día largo, y cambia el estado de tres cosas que aquí figuraban como
pendientes.

- **La malla salta de verdad, y a más de 20 m.** Era «la pieza central que sigue
  sin verse». A las 10:49 los dos móviles se confirmaron cruzados: el Huawei
  emitió, el Redmi confirmó a los 3,2 s y repitió, y el Huawei confirmó la
  repetición con la misma latencia. Los tiempos encajan porque la línea `emitida
  baliza` se escribe **al terminar** la trama de 4 s, no al empezarla — dato que
  hay que tener delante para leer cualquier registro de la malla.
- **El receptor de catálogos funciona con sismos reales.** Dos el mismo día:
  M3.8 de Istmina a las 12:22 y M3.6 a las 13:52, cazados unos tres minutos
  después de la hora de origen. Los dos son del **SGC**, no de EMSC: los
  catálogos globales no bajan de M4 en Colombia, así que la red nacional es la
  que de verdad cubre esto.
- **El sismógrafo sigue sin detectar un terremoto real**, y ahora está medido.
  Ver §1.

---

## 1. El sismógrafo todavía no ha sentido un terremoto

Medido el 17 de septiembre cruzando los 20.928 eventos guardados del Redmi con
los 661 del catálogo del SGC, sobre once días comunes:

| | |
|---|---|
| Sismos M≥3 en la ventana | 57 |
| Disparos del acelerómetro | 1.283 (uno cada 12 min) |
| Coincidencias reales, ±3 min | 10 (18 %) |
| Coincidencias al azar, media de 40 desplazamientos | 8,4 (15 %), rango 4–15 |

**Diez está dentro del ruido.** No hay señal: lo que el acelerómetro dispara no
tiene que ver con lo que publica el catálogo. Eso no dice que el detector esté
roto, dice que **a esta distancia esos sismos no llegan al móvil** — el enjambre
del Chocó está a 60–120 km y ni el M4.9 del 14 movió nada medible.

Por eso la web dice «no se ha probado en un sismo real» y debe seguir
diciéndolo. La frase es literal: el detector del móvil nunca ha visto uno.

Lo que falta para poder afirmar lo contrario es un sismo **cerca**, o alguien con
el móvil cerca del epicentro. No es una tarea, es esperar.

---

## 2. Cinco minutos con el móvil en la mano

Cada uno cierra algo que hoy está a medias.

- [ ] **La prueba del tirón.** Provocar el falso positivo del 17/09: móvil quieto
      en la mesa 35 s, zarandeo **continuo de 3-4 s** —un golpe aislado no vale,
      se probó dos veces y el ciclo se quedó en 16 % cuando hace falta 85 %—,
      siete segundos sin tocarlo y entonces cogerlo. Sirve para ver `mano=` en la
      línea `pruebas ·` y confirmar que la escalada a baliza se corta.
- [ ] **APRENDER ESTE MÓVIL**, en la tarjeta de la sonda, con el brazo estirado y
      lejos de todo. Sin la firma restada, el eco sigue midiendo el teléfono.
- [ ] **El Bio-Sonar**: eco contra una pared a 1,5 m, doppler con la mano a 1 m,
      respiración apuntando al pecho a 0,5–1 m durante 25 s.
- [ ] **La ficha fragmentada entre dos móviles.** Ver §3.

---

## 3. La ficha fragmentada por radio

**El código está completo en los dos extremos**: `ServicioSos` monta el resto de
la ficha con `Baliza.restoDeFicha` cuando el estado es ALARMA o RESCATE, y
`Rastreador` reconoce las tramas de continuación por el marcador `0x01` y las
reensambla, con `tramas()` enseñando «7 de 9» como medida honesta de lo que le
falta.

Lo que **sí** está visto entre los dos móviles es la trama 0 —nombre, grupo y
estado—: de ahí salió el fallo de los hallazgos duplicados que se arregló en
`543ac29`.

Lo que **no** está visto es la reconstrucción multitrama. El 17/09 hubo una
ocasión real —el Huawei encendió la baliza de rescate a las 13:29— pero el Redmi
no tenía nada guardado en esa ventana, así que no sirve de prueba.

Es una comprobación de dos minutos: poner un móvil en rescate con la ficha
rellena entera y mirar `tramas()` en el otro.

---

## 4. Repetir la alerta sísmica de Google

**Estado: HECHO, y sin forma de probarlo entero.** `AlertaGoogle` es el
`NotificationListenerService`, el código 7 de la malla a 16,4 kHz reparte la
alerta y `alertaExterna` entra en la cascada como una prueba más. Seis casos en
el autotest.

Falta lo único que no se puede simular: una alerta de Google de verdad. El
reparto entre móviles **ya no es una incógnita** desde que la malla salta (§0).

El orden es **armar, no disparar**, y el motivo es de tiempos: la alerta llega
segundos ANTES de que sacuda, así que preguntar «¿estás bien?» al recibirla
gastaría la pregunta justo antes del terremoto.

1. Al recibirla: suena, avisa y reemite por la malla.
2. Arma la vigilancia unos minutos: umbral al mínimo y corroboración dada por
   buena sin esperar al acelerómetro.
3. Cuando llega la sacudida, la cascada ya tiene la corroboración: pregunta al
   momento y, si nadie contesta, baliza.

---

## 5. Falsos positivos, lo que queda vivo

- [ ] **El detector de pánico por voz.** El 17/09 disparó cinco veces en dos
      minutos con 79 %, 81 % y 95 % de confianza diciendo «exclamación de pánico
      / auxilio», con el usuario simplemente hablando al lado. Lo salvó la capa
      de abajo —el aviso discreto expira en silencio—, pero el detector en sí
      está mal calibrado. **Decisión pendiente del autor**: bajar la
      sensibilidad tiene filo, porque es el detector que oye a quien pide ayuda.
- [ ] **Una noche entera con el móvil en la mesilla** y la vigilia encendida:
      los falsos por hora reales, con portazos, lavadora y camión de la basura.
- [ ] **Grabar sonidos.** `Golpes`, `Alarmas` y `Falsos` siguen **vacías**;
      `Ambiente` tiene 44 segundos. No hace falta programar y es lo que más falta.

Cerrados el 17/09: la escalada a baliza cuando alguien coge el móvil después del
suceso (`manoDespues`), el enmudecimiento del micrófono por el sistema —MIUI
dejaba la app sorda hasta 14 s sin avisar— y el `hace=` que daba 56 años.

---

## 6. Antes de publicar

Técnico:

- [x] **Firma de release resuelta.** `keystore.properties` está fuera del
      repositorio y quien no lo tenga compila igual, solo que sin firmar. La
      clave tiene copia cifrada fuera de la máquina, y eso no es burocracia:
      Android solo deja actualizar encima si la firma coincide, así que
      perderla obligaría a todo el mundo a desinstalar —y a perder su ficha
      médica— para poder poner una versión nueva.
- [x] Versión `0.2-fase1` (versionCode 2), publicada el 17/09.
- [x] R8 encendido, con reglas en `proguard-rules.pro` para lo que se
      instancia por nombre. **El binario de release no se ha ejecutado nunca en
      un móvil**: los dos de pruebas llevan la compilación de depuración, con
      otra firma, y cambiarla borraría los registros de campo del Redmi.
      Comprobado en su lugar sobre el APK: las clases del manifiesto conservan
      su nombre, el resto aparecen renombradas en `mapping.txt` y las cadenas
      críticas siguen dentro.

De tienda:

- [x] La política de privacidad en una URL pública: <https://sismored.app/legal>.
- [ ] Formulario de seguridad de datos.
- [ ] **El permiso de accesibilidad es el mayor riesgo de rechazo.**
- [ ] `targetSdk` está en 35; comprobar qué exige Play.
- [ ] Capturas, descripción e icono.

**La decisión de ruta**: GitHub Releases y F-Droid esquivan la política de
accesibilidad y la de acceso a notificaciones. Lo sensato es publicar ahí
mientras se miden los detectores. Publicar una app de emergencia cuyo detector
de derrumbe da falsos en el banco no es un riesgo de rechazo: es el riesgo de
que alguien deje de creerse la alarma el día que sea de verdad.

---

## 7. El sitio web

Vive en `SismoRed_Web_Landingpage` y está en <https://sismored.app>, servido
por Cloudflare Pages. El 17/09 se le corrigieron once datos falsos
contrastándolos contra el código —decía «cero permisos de red» con `INTERNET`
en el manifiesto, v0.9.4 con versionName `0.1-fase1`, y seguía enseñando las
«31 h» que la app ya había borrado por inventadas—.

- [x] **`legal.html` auditado**: nueve correcciones el 17/09, contrastadas
      contra el código.
- [x] En control de versiones, con su propio repositorio.
- [x] Dominio, alojamiento y HTTPS.
- [x] APK firmado en `descargas/`, con tamaño y sha256 calculados del fichero
      por `publicar.js` y servidos en `/api/version.json`.
- [ ] Sección dedicada a la PWA, cuando la haya.

> **Al desplegar**: la rama de producción del proyecto de Pages se llama
> `sismored`, no `main`. Con cualquier otro nombre el despliegue sube pero
> queda como vista previa y el dominio responde «Deployment Not Found». Y si
> se toca el CSS o el JS hay que subir el `?v=` de los enlaces en las dos
> páginas: la zona tiene cuatro horas de caché de navegador que no se puede
> desactivar desde `_headers`.

---

## 8. Interfaz, lo que queda suelto

- [ ] La cabecera de las vistas de segundo nivel: **«Opciones │ Centro de
      control»**. Afecta a las cuatro, por eso no se hizo.
- [ ] **La cuadrícula de respuesta sigue sin verse.**
- [ ] Dos textos cortados por la derecha, ya localizados: la fila «Ficha por
      Wi-Fi» de Diagnóstico y las dos marcas «--» encima de «Estado de la malla».
- [x] Los textos que sobreexplicaban: doce cadenas de interfaz pasaron de 3.576 a
      1.897 caracteres el 17/09. **La regla es que en pantalla va el dato y el
      porqué va en el código o en el FAQ.**

> Con el Samsung se puede **tocar y medir** (`input tap` funciona, al contrario
> que en el Redmi). `uiautomator dump` no: las consolas parpadean y la ventana
> nunca queda en reposo. Se mide sobre los píxeles de la captura, 2 px por dp.

> Los dos móviles de pruebas quedaron con **adb por WiFi**: Redmi en
> `192.168.0.9:5555` y Huawei en `192.168.0.11:5555`. Se pierde al reiniciarlos;
> hay que volver a enchufar uno y repetir `adb tcpip 5555`.
