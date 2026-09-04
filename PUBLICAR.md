# Publicar SismoRed

Lo que hace falta para subirla a Google Play, y lo que todavía no está listo.
Se escribe aquí porque la mitad no es código y se olvida.

## Lo que ya está hecho

- `targetSdk = 35`, que es lo que Play exige a las apps nuevas.
- Build de release con R8: encoge de 7,3 MB a 2,9 y conserva las vistas del
  XML y las entidades de Room, que son lo que R8 no puede ver por sí solo
  (ver `app/proguard-rules.pro`).
- Firma leída de `keystore.properties`, fuera del repositorio.
- Sin telemetría, sin cuentas y sin servidor. El formulario de Seguridad de
  los Datos sale limpio, que es raro y conviene no estropearlo.

## La clave de firma

Se crea una vez y **no se puede perder**: sin ella, Google no deja volver a
actualizar la app nunca. Ver `keystore.properties.ejemplo`.

## Lo que NO está listo, y es lo que de verdad frena

### 1. Los falsos positivos

Es el requisito de verdad, y no es de Play: es de si la app sirve. En el
registro de campo del 3 de septiembre la app preguntó cuatro veces en una
hora sin que hubiera pasado nada. Publicar una app de emergencia así no es
una decisión de producto, es un error.

El detector de sonido da «ESTRUENDO / DERRUMBE 100 %» cada diez segundos
durante horas seguidas. Ya no cuenta como corroboración —por eso no
dispara— pero mientras siga así no puede volver a contar para nada.

### 2. La malla nunca se ha probado entre dos móviles

Es la razón de ser del proyecto y sigue sin verificarse en campo. Todo lo que
se sabe de ella sale del banco de pruebas y del autotest.

### 3. Tres permisos que Play puede no conceder

Y no son accesorios: sin ellos la app pierde lo que la hace útil dormido.
Conviene preparar las declaraciones **antes** de escribir más código, porque
si Google dice que no, la arquitectura cambia.

- **Servicio de accesibilidad** (`ServicioTeclas`, el atajo de volumen). Es el
  de mayor riesgo con diferencia: Google exige que un `AccessibilityService`
  sirva a personas con discapacidad, y retira apps que lo usan para otra cosa.
  Leer el botón de volumen con la pantalla bloqueada es un uso legítimo y bien
  intencionado, pero no es el que la política contempla.
- **`USE_FULL_SCREEN_INTENT`.** Desde Android 14 se concede a apps de llamada
  y de alarma, con declaración. SismoRed sí es una app de alarma, así que hay
  caso que defender, pero pasa por revisión humana.
- **Micrófono en segundo plano y ubicación en segundo plano.** Los dos exigen
  declaración con vídeo y aviso destacado dentro de la app.

Las políticas cambian: hay que leerlas en el momento de subir, no fiarse de
esta lista.

### 4. Lo legal

- Descargo de responsabilidad visible, no enterrado en un «acerca de».
- Decir explícitamente que **no sustituye a los servicios de emergencia**.
- Cuidado con las afirmaciones. «Detecta terremotos» es una afirmación
  técnica, y ahora mismo el aparato no la sostiene: el umbral está calibrado
  contra señal sintética y no contra un terremoto real. La app ya lo dice en
  su propia pantalla y la tienda tiene que decir lo mismo.

Esto lo tiene que mirar alguien con formación jurídica.

### 5. Una sola persona probando, en un solo móvil

Todo lo que se sabe del comportamiento real sale de un Redmi con HyperOS. Y
la malla, además, tiene un problema de huevo y gallina: hoy no hay a quién
oír. Una prueba cerrada en Play con veinte personas resuelve las dos cosas a
la vez, y es el paso siguiente razonable antes de una publicación abierta.
