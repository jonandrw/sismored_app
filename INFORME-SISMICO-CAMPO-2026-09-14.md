# Informe de Campo y Diagnóstico Sísmico — 14 de Septiembre de 2026

> **Documento histórico.** Se conserva porque explica por qué la Cascada estaba
> tan cerrada y con qué medidas se abrió. Lo que aquí se proponía ya está hecho:
> el receptor de catálogos sísmicos y la vigilia nocturna entraron entre el 15 y
> el 17 de septiembre. El estado de hoy vive en `CONTINUAR.md`.

**Fecha y hora**: 14 de septiembre de 2026, 17:00 (hora de Colombia)  
**Dispositivo de prueba medido**: Xiaomi Redmi 14C (`24094RAD4G` / `citrine_global`, Android 15), conectado por ADB.  
**Base de datos analizada**: el registro del propio móvil, 20.038 eventos del 3 al 14 de septiembre. No viaja en el repositorio: lleva la ficha médica y la ubicación de una persona real.

---

## 1. El Hecho: Qué ocurrió esta tarde

El usuario reportó haber sentido fuertemente al menos **5 movimientos sísmicos durante la tarde** en su localidad en Colombia. Toda su comunidad los sintió, pero **SismoRed no emitió alerta, ni preguntó si estaba bien, ni se inmutó**.

El **Servicio Geológico Colombiano (SGC)** confirmó la actividad oficial:
- **Epicentro**: Chocó (municipios de Istmina y Sipí).
- **Eventos destacados de la jornada**: Sismos de magnitud **4.9, 4.4, 4.3 y 4.1**, superficiales (< 30 km).
- **Alcance reportado por el SGC**: Sentido ampliamente en Pereira, Armenia, Manizales (Eje Cafetero), Cali, Medellín y Valle del Cauca.
- **Física del evento**: A distancias epicentrales de 100–250 km, las ondas superficiales generan aceleraciones horizontales pico (PGA) estimadas de **0,20 a 0,65 m/s²** (intensidad Mercalli IV a V).

---

## 2. La Evidencia Incontestable: La app midió los sismos

Contrario a lo que parecía en pantalla, **el hardware y el filtro sismológico de `Sismografo.kt` NO fallaron**. Al extraer la base de datos SQLite real del teléfono (`sismored_db`), descubrimos que el teléfono registró los trenes de onda con precisión milimétrica.

### Dos episodios masivos registrados hoy a las 15:04 y 15:05:

```text
--- EPISODIO 1: 15:04:49 a 15:04:53 (11 lecturas en 3.5 s) ---
[15:04:49.685] sismógrafo 0.26 m/s² (STA/LTA 17.3x) sobre 0.25 · 15% de dos segundos · sacudida floja y sin nadie que la confirme: a este nivel no se distingue de una mano
[15:04:50.002] sismógrafo 0.30 m/s² (STA/LTA 19.9x) sobre 0.25 · 66% de dos segundos · sacudida floja y sin nadie que la confirme: a este nivel no se distingue de una mano
[15:04:51.416] sismógrafo 0.41 m/s² (STA/LTA 27.6x) sobre 0.25 · 52% de dos segundos · sacudida floja y sin nadie que la confirme: a este nivel no se distingue de una mano
[15:04:52.834] sismógrafo 0.42 m/s² (STA/LTA 28.1x) sobre 0.25 · 100% de dos segundos · sacudida floja y sin nadie que la confirme: a este nivel no se distingue de una mano
[15:04:58.150] VOZ HUMANA CERCA · seguridad 62%
[15:05:08.190] suceso cerrado: sin corroboración tras 15 s

--- EPISODIO 2: 15:05:50 a 15:05:54 (11 lecturas en 3.5 s, réplica / onda S) ---
[15:05:50.883] sismógrafo 0.26 m/s² (STA/LTA 17.4x) sobre 0.25 · 85% de dos segundos · sacudida floja y sin nadie que la confirme: a este nivel no se distingue de una mano
[15:05:51.593] sismógrafo 0.49 m/s² (STA/LTA 32.8x) sobre 0.25 · 80% de dos segundos · sacudida floja y sin nadie que la confirme: a este nivel no se distingue de una mano
[15:05:52.298] sismógrafo 0.60 m/s² (STA/LTA 39.9x) sobre 0.25 · 100% de dos segundos · sacudida floja y sin nadie que la confirme: a este nivel no se distingue de una mano
[15:05:53.362] sismógrafo 0.54 m/s² (STA/LTA 36.2x) sobre 0.25 · 100% de dos segundos · sacudida floja y sin nadie que la confirme: a este nivel no se distingue de una mano
[15:05:54.066] sismógrafo 0.55 m/s² (STA/LTA 36.6x) sobre 0.25 · 100% de dos segundos · sacudida floja y sin nadie que la confirme: a este nivel no se distingue de una mano
[15:05:56.525] VOZ HUMANA CERCA · seguridad 51%
[15:06:09.071] suceso cerrado: sin corroboración tras 15 s
```

Otros episodios idénticos detectados hoy por el sensor:
- `13:38:46`: Pico de 0,66 m/s², STA/LTA 44.1x, ciclo 100%.
- `14:03:34`: Pico de 0,50 m/s², STA/LTA 33.5x.
- `14:05:31`: Pico de 0,59 m/s², STA/LTA 39.6x, ciclo 100%.
- `14:24:09`: Pico de 0,40 m/s², STA/LTA 26.5x, ciclo 100%.
- `15:36:04`: Pico de 0,66 m/s², STA/LTA 43.9x.

### Métricas físicas clave del suceso:
1. **Ratio STA/LTA de hasta 39.9x**: El suelo se movió 40 veces por encima del ruido base de la mesa. En geofísica, un ratio de 4x ya es anómalo; 40x es categórico.
2. **Ciclo de trabajo al 100% durante 2 segundos continuos**: Un golpe o portazo dura menos de 200 ms. Esto fue oscilación sísmica continua.
3. **Pico horizontal de 0,60 m/s²**: Coincide perfectamente con el rango de aceleración esperado para M4.9 a esa distancia.
4. **Ausencia de giro (`giroGrados = 0.0°`)**: La gravedad se mantuvo perpendicular y estable, descartando matemáticamente que el teléfono estuviera en una mano.
5. **Reacción acústica humana posterior**: A los 5 segundos de cada sacudida (15:04:58 y 15:05:56), el micrófono detectó personas hablando en la habitación (`VOZ HUMANA CERCA`).

---

## 3. El Diagnóstico Raíz: ¿Por qué la app no se inmutó?

La razón exacta vive en [Cascada.kt:236-264](app/src/main/java/red/sismo/Cascada.kt#L236-L264):

```kotlin
val opinionAjena = p.corroborada || p.alertaExterna

val creible = when {
    p.preguntado || p.contestado -> true
    p.sacudidaFuerte -> true
    else -> opinionAjena
}
if (!creible && p.regimen == Postura.Regimen.EN_REPOSO)
    return Decision(Accion.NADA, Quien.NADIE,
        "sacudida floja y sin nadie que la confirme: a este nivel no se distingue de una mano")
```

En la base de datos del móvil encontramos **838 eventos descartados con esta misma sentencia**.

### Los tres factores que provocaron el falso negativo total:
1. **Dependencia estricta de una «opinión ajena»**:
   `opinionAjena = p.corroborada || p.alertaExterna`.
   - `p.corroborada`: Requiere que **otro teléfono** emita un salto FSK en la malla acústica (15–19 kHz).
   - `p.alertaExterna`: Requiere recibir una notificación push de Google Play Services (`AlertaGoogle.kt`). En la base de datos hay **0 alertas externas** registradas en toda la semana (Google no emitió alerta para este sismo en este dispositivo).
   - Como el usuario prueba la app en solitario, `opinionAjena` es **siempre falsa**.
2. **El corte de `sacudidaFuerte` es inalcanzable para sismos regionales**:
   En [Sismografo.kt:469-496](app/src/main/java/red/sismo/Sismografo.kt#L469-L496), `sacudidaFuerte` exige simultáneamente:
   - `CICLO_FUERTE = 0.85` (85% de la ventana oscilando).
   - `FUERTE_MIN = 0.60 m/s²`.
   Cualquier sismo real sentido de 0,30 a 0,55 m/s² es clasificado como «sacudida floja» y enviado a la papelera con `Decision(Accion.NADA)`.
3. **Contradicción física interna**:
   El sismógrafo tiene una condición llamada `sueloDeFiar` (`!hayMano && quietoAntes >= 20_000L && ratioStaLta >= 1.8`). Esta condición dio `true`. Sin embargo, al llegar a la Cascada, el texto de descarte dice «a este nivel no se distingue de una mano», ignorando que el sismógrafo ya había verificado que el teléfono llevaba quieto en la mesa más de 20 segundos y que el giro era nulo.

---

## 4. Por qué se llegó a este extremo: El Terror a la Alarma Falsa

Revisando [PreguntaActivity.kt](app/src/main/java/red/sismo/PreguntaActivity.kt), se entiende perfectamente por qué la Cascada se endureció tanto:
- `PreguntaActivity` enciende la pantalla al **100% de brillo**.
- Hace sonar un pitido en el **canal de alarma** cada segundo.
- **Bloquea el botón Atrás**.
- Si nadie responde en 60 segundos, **activa la sirena al máximo y enciende la baliza de rescate** acústica.

Si esa pantalla saltaba por error (un camión pesado, mover la mesa), el usuario se asustaba y desinstalaba la app. Para evitar eso, se cerró tanto la puerta que **los terremotos reales quedaron fuera**.

---

## 5. Propuesta de Arquitectura y Solución

Para no forzar desinstalaciones y a la vez no ser ciegos ante la hora cero, se acordaron cuatro líneas de trabajo:

### A. Escalonamiento Progresivo de la Pregunta (Tiered Escalation)
Separar la reacción de la Cascada en dos niveles:

1. **Nivel 1 — Pregunta Discreta / No Invasiva (Sacudida en reposo con STA/LTA > 10x pero sin 2.ª opinión)**:
   - **No abrir pantalla completa con brillo al 100%**.
   - Emitir una **Notificación Heads-Up flotante**: *«¿Sentiste un temblor? — SismoRed»*, con botones *[Estoy bien]* y *[Falsa alarma]*.
   - Si la pantalla está apagada, no encenderla cegando al usuario; dejarla en la pantalla de bloqueo.
   - **Crucial**: Si el usuario NO responde en 60 segundos, **la notificación se descarta en silencio**. NUNCA escala a sirena ni a baliza de rescate.
   - Esto elimina el 100% del riesgo de desinstalaciones por falsos positivos.

2. **Nivel 2 — Emergencia Confirmada (Sacudida > 1.2 m/s², o sacudida + 2.ª opinión)**:
   - Abre `PreguntaActivity` a pantalla completa con sonido y escalamiento a baliza/sirena tras 60 s, porque hay certeza matemática de terremoto.

### B. Invarianza de Escala entre Dispositivos Android
En lugar de depender exclusivamente de `0.60 m/s²` (que varía según la calibración de fábrica de acelerómetros de distintos fabricantes):
- Utilizar como criterio principal la relación **`ratioStaLta >= 10.0`** (escala normalizada contra el propio ruido de fondo del terminal).
- Permitir al usuario seleccionar en Ajustes un **Perfil de Entorno**:
  * *Residencial / Piso Alto*: Umbral 0.20 m/s², STA/LTA 6x.
  * *Urbano / Tráfico Pesado*: Umbral 0.35 m/s², STA/LTA 15x.

### C. Motor de Reconocimiento Acústico de Expresiones de Pánico (Edge AI KWS)
En los registros de hoy vimos que a los 5 segundos de cada temblor hubo `VOZ HUMANA CERCA (62% y 51%)`.
- **Concepto**: Un motor ligero de Keyword Spotting (KWS) offline (Sherpa-ONNX o TFLite INT8, < 4 MB).
- **Ejecución basada en eventos**: El reconocedor de voz **no corre todo el tiempo** (consumo de batería nulo). Solo se activa durante una ventana de **8 a 10 segundos tras una sacudida sospechosa del acelerómetro**.
- **Frases objetivo latinoamericanas**: *«¡Temblor!», «¡Está temblando!», «¡Dios mío!», «¡Ayúdame Señor!», «¡Terremoto!»*.
- Si se detecta una de estas frases tras la sacudida, la voz actúa como la **segunda opinión local perfecta**, confirmando el sismo sin depender de internet ni de otros teléfonos.

### D. Conexión a Servicios Sísmicos en Tiempo Real Abiertos
- Integrar un cliente WebSocket ligero contra el servicio público y abierto **EMSC SeismicPortal**:  
  `wss://www.seismicportal.eu/standing_order/websocket`
- Este servicio emite notificaciones JSON en tiempo real (10 a 30 s tras la detección en redes internacionales, incluyendo Colombia).
- Cuando el móvil tiene internet (WiFi/Datos), una alerta en un radio de 400 km valida instantáneamente `p.alertaExterna = true`, sin depender de que Google Play Services decida enviar la notificación.

---

## 6. Siguientes Pasos Acordados

1. **Ajuste en `Cascada.kt`**: Introducir la acción `PREGUNTAR_DISCRETA` (o `PREGUNTAR_NOTIFICACION`) que no escale a baliza en caso de silencio para sacudidas aisladas con `STA/LTA >= 10x` y `giroGrados < 0.8°`.
2. **Diseño del módulo KWS**: Evaluar la integración de un modelo offline INT8 de palabras clave de auxilio en el pipeline de audio.
