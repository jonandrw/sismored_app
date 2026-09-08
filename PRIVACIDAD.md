# Política de Privacidad de SismoRed

**Última actualización:** 4 de septiembre de 2026  
**Proyecto:** SismoRed Android (Código Abierto)  
**Repositorio oficial:** [https://github.com/jonandrw/sismored_app](https://github.com/jonandrw/sismored_app)

---

## 1. Declaración de Principios y Privacidad por Diseño

SismoRed es una aplicación comunitaria de código abierto orientada a la asistencia en situaciones de colapso de infraestructuras y catástrofes sísmicas. Su arquitectura se fundamenta en el principio de **cero telemetría y privacidad absoluta**:

1. **Sin servidores externos:** La aplicación no se conecta a servidores de recopilación de datos, analítica, publicidad ni rastreo.
2. **Sin cuentas de usuario:** No se requiere registro, correo electrónico, número telefónico ni credenciales para utilizar la aplicación.
3. **Tratamiento exclusivamente local (*Local-First*):** Todos los cálculos, análisis de sensores y almacenamiento residen únicamente en la memoria y almacenamiento interno de su propio dispositivo.

---

## 2. Datos Tratados en el Dispositivo

### A. Ficha Médica de Emergencia
- **Qué datos contiene:** Nombre de pila, grupo sanguíneo, edad aproximada, alergias, medicación relevante y teléfono de contacto de emergencia.
- **Dónde se guarda:** Exclusivamente en el almacenamiento privado de la app en su dispositivo (`SharedPreferences`), al que ninguna otra aplicación tiene acceso. No está cifrada: un teléfono con acceso de superusuario podría leerla, igual que cualquier otro dato de cualquier otra app.
- **Control del usuario:** Estos datos son de carácter opcional. El usuario puede editarlos o eliminarlos en cualquier momento desde la pestaña *Ficha*. Al desinstalar la aplicación, todos estos datos son borrados definitivamente de forma automática por el sistema operativo.

### B. Análisis del Micrófono (Audio Forense)
- **Uso:** El micrófono se utiliza para la detección local de sonidos característicos de derrumbes, gritos de auxilio, ruidos rítmicos de impacto (SOS) y para las herramientas de ecolocalización e interfono de rescate.
- **Privacidad estricta del audio:** **El audio NUNCA se graba en archivos, NUNCA se almacena en disco y NUNCA se transmite a través de internet ni a terceros.** La señal acústica se procesa exclusivamente en la memoria RAM volátil en bloques temporales de milisegundos mediante transformadas matemáticas de Fourier (FFT) y se descarta inmediatamente tras su evaluación.

### C. Sensores de Movimiento (Acelerómetro)
- **Uso:** El acelerómetro se analiza en tiempo real en la memoria del dispositivo para detectar ondas sísmicas anómalas (filtro recursivo STA/LTA) y vibraciones de colapso.
- **Almacenamiento:** Ninguna serie temporal de aceleración continua se envía fuera del dispositivo.

### D. Ubicación Geográfica
- **Uso:** SismoRed **NUNCA activa el chip GPS por su propia cuenta**. La app únicamente lee de forma pasiva la "última ubicación conocida" (*last known location*) que otra aplicación o el propio sistema operativo ya hayan determinado con anterioridad.
- **Propósito:** Facilitar las labores de localización en caso de que el usuario quede atrapado bajo escombros. Esta coordenada solo viaja si la alarma o el modo rescate son activados expresamente.

---

## 3. Emisión de Señales en Caso de Emergencia Activa

Únicamente cuando la alarma o el modo rescate se encuentran **activamente encendidos** (por orden del usuario o por confirmación sísmica), el dispositivo emite señales de baliza en su entorno físico inmediato:

1. **Baliza de Radio Bluetooth Low Energy (BLE):**  
   Emite un paquete estándar de baliza publicitaria (*advertisement*) de alcance local (10 a 30 metros según obstáculos). La primera trama lleva el identificador de la app, el nombre de pila y el grupo sanguíneo. **Con la alarma o el rescate encendidos se emiten además, en tramas sucesivas, la edad, las alergias, la medicación y el teléfono de contacto de emergencia** — es decir, la ficha médica completa que usted haya rellenado.

   Esta emisión **no va cifrada**: es una baliza abierta, y cualquiera con un receptor Bluetooth en el radio de alcance puede leerla, no solo los equipos de rescate. Es una decisión deliberada — una baliza que hubiera que descifrar no serviría para que le encuentren —, pero conviene que la conozca antes de rellenar la ficha. Los campos son todos opcionales: lo que deje en blanco no se emite.
2. **Difusión Wi-Fi Local (UDP):**  
   Si el dispositivo está conectado a una red Wi-Fi local (por ejemplo, el punto de acceso de un campamento o equipo de rescate), difunde un paquete con la ficha médica y la última ubicación conocida a la subred local para permitir su lectura rápida en pantallas de mando de brigadas de búsqueda.
3. **Malla Acústica de Emergencia:**  
   Emite tonos y pulsos acústicos audibles o de alta frecuencia por el altavoz para alertar a brigadistas y teléfonos cercanos.

**En cuanto el usuario pulsa "DETENER" o desactiva la emergencia, toda emisión de radio y sonido cesa inmediatamente.**

---

## 4. Desistimiento, Supresión de Datos y Ejercicio de Derechos

Al no existir servidores ni bases de datos remotas gestionadas por los desarrolladores de SismoRed, no existen registros de sus datos en posesión de terceros. Para eliminar cualquier rastro de información almacenada:
- Pulse el botón *Borrar ficha* en la pestaña de Ficha.
- O bien desinstale la aplicación desde el menú de Ajustes de Android. La desinstalación destruye de forma irreversible todas las bases de datos locales creadas por la app.

---

## 5. Descargo de Responsabilidad y Aviso Legal de Emergencia (*Disclaimer*)

> ### ⚠️ AVISO LEGAL IMPORTANTE:
>
> 1. **No sustituye a los servicios públicos de emergencia:**  
>    SismoRed es una herramienta tecnológica experimental de código abierto diseñada como apoyo comunitario en casos extremos de colapso de redes convencionales. **En ningún caso sustituye la llamada a los números y servicios oficiales de emergencia (911, 112, Cruz Roja, Bomberos, Defensa Civil o Policía Nacional).** Siempre que disponga de cobertura telefónica o acceso a comunicaciones convencionales, debe comunicarse inmediatamente con las autoridades oficiales.
>
> 2. **No es un dispositivo médico ni de seguridad certificado:**  
>    SismoRed no está certificado como equipo médico de soporte vital ni como instrumento industrial de protección civil. Su eficacia depende de factores externos incontrolables, tales como el estado de la batería, daños mecánicos en el teléfono provocados por el sismo, la atenuación física de los escombros sobre las ondas electromagnéticas y acústicas, y la presencia de receptores en el área circundante.
>
> 3. **No predicción sísmica:**  
>    La aplicación no predice terremotos con anticipación; responde únicamente a perturbaciones mecánicas y acústicas ya iniciadas en el entorno del dispositivo o a alertas externas compatibles.

---

## 6. Contacto y Transparencia

El código fuente íntegro de SismoRed es público y auditable bajo licencia libre en:  
[https://github.com/jonandrw/sismored_app](https://github.com/jonandrw/sismored_app)
