# Política de Privacidad y Términos de SismoRed

**Última actualización:** 14 de septiembre de 2026  
**Proyecto:** SismoRed Android (Código Abierto)  
**Licencia:** Software Libre / Código Abierto  
**Repositorio oficial:** [https://github.com/jonandrw/sismored_app](https://github.com/jonandrw/sismored_app)

---

## 1. Declaración de Principios y Privacidad por Diseño

SismoRed es una herramienta comunitaria de código abierto orientada a la detección inercial de terremotos, asistencia en colapsos estructurales y localización de personas en situaciones de emergencia. Su arquitectura se fundamenta en los principios de **cero telemetría de usuario, privacidad absoluta y soberanía de datos (*Local-First*)**:

1. **Sin servidores privados ni recopilación de usuarios:** La aplicación no cuenta con servidores centrales de rastreo, analítica de uso, publicidad ni registro de perfiles.
2. **Sin cuentas ni credenciales:** No se solicita correo electrónico, nombre de usuario, número telefónico ni credenciales para su funcionamiento.
3. **Cálculo y procesamiento estrictamente local:** Los algoritmos sismológicos, el filtrado inercial, el análisis acústico y la toma de decisiones se ejecutan de manera autónoma en el procesador y la memoria RAM de su propio dispositivo.

---

## 2. Datos Tratados en el Dispositivo y Sensores

### A. Ficha Médica de Emergencia (Opcional)
- **Datos que comprende:** Nombre de pila, grupo sanguíneo, edad orientativa, alergias, medicación vital y número telefónico de contacto de emergencia.
- **Almacenamiento:** Se guarda de forma exclusiva en el almacenamiento privado de la aplicación (`SharedPreferences`) en su teléfono. Ninguna otra aplicación estándar tiene acceso a este espacio.
- **Control total:** Completar esta ficha es 100% voluntario. Puede modificarse o borrarse en cualquier momento desde la pestaña *Ficha*. Al desinstalar la app, el sistema operativo destruye estos datos de forma definitiva.

### B. Análisis del Micrófono (Audio Forense y KWS de Pánico por Voz)
- **Propósito:** El micrófono se utiliza para la detección acústica de colapsos estructurales, golpes rítmicos de auxilio (patrón SOS), herramientas de ecolocalización/interfono y para el **reconocimiento acústico de expresiones espontáneas de auxilio y pánico** (*"¡temblor!"*, *"¡Dios mío!"*, *"¡ayúdame!"*, *"¡terremoto!"*).
- **Activación por eventos (Event-Driven / Cero escucha continua en reposo):**  
  El motor de reconocimiento de frases de pánico **permanece inactivo en reposo**. Se activa únicamente en una **ventana temporal de 10 segundos** tras detectarse una perturbación inercial en el acelerómetro (STA/LTA anómalo). Si transcurren los 10 segundos sin detección de formantes de alarma, el analizador se apaga.
- **Privacidad estricta y descarte inmediato:**  
  **El audio NUNCA se graba en archivos de sonido, NUNCA se guarda en disco y NUNCA se transmite por internet ni a terceros.** La señal se analiza en la memoria RAM volátil mediante ventanas de milisegundos (transformadas matemáticas de Fourier y cruces por cero) y se purga instantáneamente. No existe grabación acústica recuperable.

### C. Sensores de Movimiento (Acelerómetro y Sismógrafo STA/LTA)
- **Uso:** El acelerómetro opera en segundo plano para registrar variaciones mecánicas y ondas sísmicas en el plano horizontal (ondas S) y vertical (ondas P) contra el piso de ruido dinámico (algoritmo recursivo STA/LTA).
- **Calibración y perfiles de entorno:** La app permite elegir o ajustar automáticamente perfiles de entorno (*Tranquilo*, *Normal*, *Ruidoso*) para evitar que vibraciones domésticas o tráfico pesado generen falsos disparos. Ninguna serie de datos de movimiento continuo se envía al exterior.

### D. Ubicación Geográfica
- **Uso pasivo:** SismoRed **NUNCA enciende el receptor GPS por su propia cuenta**. Únicamente lee de manera pasiva la "última ubicación conocida" (*last known location*) registrada con anterioridad por el sistema o por otras aplicaciones.
- **Propósito:** En caso de que el usuario quede atrapado, esta coordenada ayuda a las brigadas de rescate a acotar el área de búsqueda. La coordenada solo se transmite en la baliza cuando la alarma o el modo rescate han sido activados expresamente.

---

## 3. Conectividad y Redes Abiertas

### A. Consulta Pasiva a Catálogos Sísmicos Abiertos (EMSC y SGC)
- **Qué es:** Para evitar que un sismo real pase inadvertido cuando el usuario se encuentra solo o sin otros nodos de la malla cercanos, SismoRed puede consultar periódicamente los catálogos sismológicos públicos y abiertos (FDSN GeoJSON vía EMSC, y el feed del Servicio Geológico Colombiano, que es el que cubre los sismos locales pequeños que los catálogos globales no publican).
- **Viene apagado.** Es la única función de la app que sale a internet, así que
  no se enciende sola: hay un interruptor en *Ajustes → Si aparece internet*, y
  mientras usted no lo active, SismoRed no hace ninguna petición de red.
- **Privacidad y unidireccionalidad:**  
  - Esta comunicación es **estrictamente de solo lectura (recepción unidireccional)**.
  - **SismoRed NUNCA envía su ubicación, ni identificadores de hardware (IMEI, Android ID), ni datos de la ficha médica a los servicios sísmicos.**
  - Lo que sí ocurre, y conviene decirlo sin adornos: como en cualquier visita a
    una página web, el servidor consultado ve **la dirección IP desde la que se
    pide** y la hora en que se pide. Eso no lo puede evitar ninguna aplicación
    que consulte datos en internet, y es la razón por la que esta función es
    opcional y está apagada de fábrica.
  - El filtrado de proximidad (distancia ortodrómica por fórmula de Haversine inferior a 450 km) y la verificación de magnitud se calculan **100% de manera local en el teléfono**.
  - Si el dispositivo se encuentra sin internet, la aplicación sigue funcionando plenamente con sus sensores locales y malla peer-to-peer.

### B. Emisiones Locales en Caso de Emergencia Confirmada
Únicamente cuando se activa la **alarma** o el **modo rescate** (manualmente o tras confirmación sísmica sin respuesta del usuario):
1. **Baliza Bluetooth Low Energy (BLE):** Emite paquetes de radio local abierta (alcance de 10 a 30 metros) para que los rescatistas puedan orientar sus receptores. Los datos médicos opcionales viajan en tramas abiertas para permitir la asistencia inmediata de brigadistas.
2. **Difusión Wi-Fi Local (UDP):** Si el móvil está en una red Wi-Fi local de emergencia, difunde su estado y coordenada a los dispositivos de auxilio conectados a la misma subred.
3. **Malla Acústica Ultrasónica y Sirena:** Emite tonos de alta frecuencia o sirenas de advertencia audibles según la gravedad del evento.

**En cuanto el usuario pulsa "ESTOY BIEN", "DETENER" o cancela la alerta, toda emisión de radio y sonido cesa de inmediato.**

---

## 4. Escalonamiento Gradual y Protección contra Alarmas Falsas

Para evitar situaciones invasivas que perturben la tranquilidad del usuario o provoquen desinstalaciones por falsos positivos:
- **Aviso Discreto (*¿Sentiste un temblor?*):** Ante sacudidas en reposo sin confirmación externa destructiva, SismoRed despliega una notificación flotante no invasiva con opciones directas de `[ESTOY BIEN]` y `[FALSA ALARMA]`.
- **Cierre silencioso automático:** Si el usuario no responde al aviso discreto tras 60 segundos, **el aviso se cierra solo en absoluto silencio**, sin activar sirenas, linternas ni balizas de emergencia.
- **Escalón de Emergencia Mayor:** Las sirenas audibles y la pantalla de emergencia a brillo completo se reservan estrictamente para sismos violentos de alta destructividad comprobada o confirmados concurrentemente por la red sísmica, la malla local o gritos de auxilio acústicos.

---

## 5. Ejercicio de Derechos y Borrado de Datos

Al no almacenar información en servidores externos, no existen bases de datos remotas que gestionar. Para eliminar cualquier dato de su dispositivo:
- Pulse el botón *Borrar ficha* en la pestaña *Ficha*.
- O desinstale la aplicación desde el gestor de aplicaciones de Android. La desinstalación purga de forma inmediata e irreversible la base de datos local y las preferencias.

---

## 6. Descargo de Responsabilidad y Aviso Legal de Emergencia (*Disclaimer*)

> ### ⚠️ AVISO LEGAL Y CONDICIONES DE USO:
>
> 1. **No sustituye a los servicios de emergencia del Estado:**  
>    SismoRed es una herramienta tecnológica de soporte y alerta comunitaria basada en código abierto. **En ningún caso reemplaza las llamadas a las líneas oficiales de emergencia (123 en Colombia, 911, 112, Cruz Roja, Defensa Civil o Bomberos).** Siempre que disponga de cobertura, comuníquese con las autoridades competentes.
>
> 2. **Sin garantía absoluta de detección o rescate:**  
>    La detección y la eficacia de localización dependen de factores físicos imprevistos: nivel de carga de la batería, aplastamiento o daño mecánico del teléfono durante el terremoto, grosor y apantallamiento de los escombros sobre las señales de radio y sonido, y presencia de personas con receptores compatibles en las proximidades.
>
> 3. **No predicción previa:**  
>    SismoRed no predice cuándo ocurrirá un terremoto en el futuro; actúa como sensor inercial y acústico en tiempo real una vez que el movimiento telúrico ha comenzado o tras la difusión de eventos por redes sismológicas abiertas.
>
> 4. **Aceptación:**  
>    El uso de esta aplicación implica la comprensión y aceptación de la naturaleza experimental y solidaria del proyecto.

---

## Quién responde por estos datos

**Nadie los trata, porque nadie los recibe.** No hay servidor, ni cuenta, ni
analítica: la ficha médica, los ajustes y el registro viven en el móvil y no
llegan al autor de la aplicación ni a ningún tercero. Por eso aquí no hay un
«Responsable del Tratamiento» en el sentido de la Ley 1581 de 2012: no existe
una base de datos que alguien mantenga sobre nadie.

Lo que sí hay es una **emisión que el titular autoriza**. El grupo sanguíneo,
las alergias y la medicación son datos sensibles de salud (art. 5), así que la
app pide autorización explícita antes de dejar rellenar la ficha, advierte de
que se emiten sin cifrar y guarda la fecha en que se concedió. La emisión se
apoya en el supuesto que la propia ley prevé en el art. 6: salvaguardar el
interés vital del titular. Se revoca borrando la ficha.

El ámbito doméstico que excluye el art. 2 cubre lo que se guarda en el móvil,
pero **deja de cubrir en cuanto se suministra a terceros** — y eso es
exactamente lo que hace la baliza. De ahí que la autorización no sea opcional.

## Contacto

El proyecto lo mantiene **Juan Andrés Torres Orozco**.

- Avisos legales y retirada de contenido: `legal@sismored.app`
- Fallos de seguridad: `seguridad@sismored.app`
