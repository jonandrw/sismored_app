package red.sismo

import android.util.Log
import red.sismo.Postura.Regimen

/**
 * La cascada de decisión: qué se hace con lo que han visto los sensores.
 *
 * Hasta ahora cada detector decidía por su cuenta y todos terminaban en el mismo
 * sitio, `panico()`. Eso tenía dos consecuencias, y las dos se arreglan aquí:
 *
 *  1. **Un solo listón para acciones de coste muy distinto.** Encender la baliza
 *     es silencioso, barato y puede salvar a alguien; encender la sirena es caro,
 *     porque una alarma falsa en una red de emergencia quema la confianza de
 *     quien la recibe y a la tercera vez ya nadie corre. Con un solo umbral hay
 *     que elegir entre perder víctimas o gritar de más. Con una escalera, no:
 *     la baliza se enciende con evidencia floja y la sirena pide mucho más.
 *  2. **Se intentaba clasificar lo inclasificable.** Ningún juego de sensores
 *     distingue con certeza «enterrada e inconsciente» de «el móvil se cayó
 *     detrás del sofá». Pero no hace falta clasificar: se puede **preguntar**.
 *     Tras un terremoto confirmado la app pregunta si estás bien, y el silencio
 *     es la respuesta. Es el mismo mecanismo que la detección de caídas de los
 *     relojes, y convierte un problema imposible en uno fiable.
 *
 * Todo lo de aquí es una función pura: entra lo que se ha medido y sale una
 * decisión. Por eso se puede comprobar entera en el propio móvil ([autotest]),
 * con los escenarios reales escritos como casos, sin esperar a un terremoto.
 *
 * Lo que NO hace, a propósito: sumar confianzas de detectores en un porcentaje
 * único. Un «96 % de emergencia» sacado de promediar cuatro números correlados
 * no es una probabilidad, es un número bonito — y aquí ningún indicador se
 * inventa nada. Son reglas explícitas: cuando fallan, se sabe cuál falló.
 */
object Cascada {

    private const val TAG = "SismoRed"

    /** Lo que se hace, de menos a más caro. */
    enum class Accion {
        /** Anotar y seguir mirando. */
        NADA,
        /** Notificación flotante no invasiva («¿Sentiste un temblor?»). Cierra sola en silencio y NUNCA activa baliza. */
        PREGUNTAR_DISCRETA,
        /** Pantalla «ESTOY BIEN» con cuenta atrás. Silenciosa. */
        PREGUNTAR,
        /** Sirena de aviso + la misma pregunta: puede estar dormida. */
        AVISAR,
        /** Baliza, ficha y malla. Sin ruido: quizá esté enterrada. */
        BALIZA,
        /**
         * Se preguntó, nadie contestó, y el móvil llevaba horas sin tocarse.
         *
         * Es la única forma de sirena automática que queda en pie, y la unica
         * que se sostiene sin una segunda opinion: no la dispara un sensor, la
         * dispara el SILENCIO de alguien a quien se acaba de preguntar. Un
         * empujon en la mesa lanza la misma pregunta, pero ahi hay alguien
         * delante que la descarta de un toque.
         *
         * Suena en rampa —vibracion, tono suave, sirena— para que equivocarse
         * cueste un zumbido y no un susto.
         */
        DESPERTAR,
        /** Todo: baliza, sirena, modo rescate. Hay pruebas de que hay alguien. */
        AUXILIO,
        /**
         * **Quien necesita ayuda es otro, no tú.** Un vecino pide auxilio por
         * la malla y aquí no ha pasado nada.
         *
         * Hace falta una acción propia porque ninguna de las de arriba sirve:
         * [DESPERTAR] enciende la baliza en el primer peldaño y escala a
         * alarma, y [AVISAR] pregunta «¿estás bien?» y, si no contestas,
         * la escalera de abajo acaba encendiéndola igual. Las dos convierten
         * en víctima a quien solo es el vecino, y de ahí salía que un salón
         * entero se pusiera a gritar por una sola pulsación.
         *
         * Lo que hace: ruido para despertar a quien duerma, decir **por qué**
         * —«un vecino ha detectado movimiento peligroso»— y abrir la búsqueda.
         * Lo que no hace nunca: baliza propia, modo víctima, ni escalar por
         * falta de respuesta. Y el sonido es distinto del de una víctima, para
         * que quien llegue a buscar distinga de oído a quién están avisando de
         * quién pide ayuda.
         */
        AVISAR_VECINO
    }

    /** Qué se puede AFIRMAR, que no es lo mismo que qué se sospecha. Va escrito
     *  en la pantalla del rescatista tal cual: mandarle a cavar donde solo hay un
     *  teléfono cuesta minutos que alguien no tiene. */
    enum class Quien {
        NADIE,
        /** Se sabe que hay un móvil, y que salió despedido. La persona puede no
         *  estar aquí. */
        MOVIL,
        /** El móvil de alguien que no ha contestado. Es lo más común y lo más
         *  honesto que se puede decir. */
        PERSONA_PROBABLE,
        /** Se le oye: voz o golpes junto al móvil. */
        PERSONA
    }

    /** Pasos después del suceso que bastan para decir «está andando». Tres, los
     *  mismos que definen «lo lleva encima»: nadie da tres pasos aplastado. */
    const val PASOS_VIVA = 3
    /** Sin moverse ni un poco: ni respirar mueve el móvil. */
    const val INMOVIL_MS = 120_000L

    /**
     * Cuánto tiene que llevar sin tocarse para que valga la pena la sirena.
     *
     * Veinte minutos. Es bastante más que un rato sin mirar el móvil —comer,
     * ducharse, una reunión— y bastante menos que una noche. Lo que se está
     * decidiendo no es «está dormida» con certeza, que no se puede saber: es si
     * merece la pena el coste de una sirena. Con el móvil recién usado, no.
     */
    const val DORMIDA_MS = 20 * 60_000L

    /**
     * Lo mismo, pero para decidir si hay que avisar de que **un vecino** pide
     * ayuda. Más corto a propósito: cinco minutos.
     *
     * **El árbol es uno, pero la matriz de costes no.** [DORMIDA_MS] decide si
     * se enciende la sirena de víctima, y equivocarse ahí cuesta un susto. Aquí
     * lo que se decide es una vibración con un aviso que se explica solo, y
     * equivocarse cuesta un zumbido — mientras que no avisar cuesta que alguien
     * atrapado se quede sin que nadie lo oiga. Con los costes al revés, el
     * listón tiene que estar más bajo.
     *
     * Elegido razonando, no midiendo: no hay forma de medir esto sin un
     * derrumbe.
     */
    const val VECINO_DORMIDA_MS = 5 * 60_000L

    /**
     * Todo lo medido en un instante. `pasosDespues = -1` significa **no se sabe**
     * —hay móviles sin contador—, y eso nunca puede leerse como «no ha andado»:
     * un dato que no existe no es un dato que valga cero.
     */
    class Pruebas(
        val regimen: Postura.Regimen = Postura.Regimen.DESCONOCIDO,
        /** El sismógrafo de ESTE móvil ha notado el suelo moverse. */
        val sacudida: Boolean = false,
        /**
         * La sacudida fue lo bastante grande como para no poder confundirse con
         * una mano moviendo el teléfono.
         *
         * MEDIDO, y es el resultado más incómodo de todo el banco: al nivel de
         * un MMI V —el que «lo nota todo el mundo»— empujar el móvil en una mesa
         * y un terremoto de verdad **dan lo mismo**. No hay umbral que los
         * separe; es el límite del acelerómetro de un teléfono, no un ajuste mal
         * puesto. Ver `Sismografo.CICLO_FUERTE`.
         *
         * De ahí sale la regla de abajo, que es la única honesta: por encima de
         * ese nivel el móvil va solo, y por debajo pide una segunda opinión.
         */
        val sacudidaFuerte: Boolean = false,
        /** Frente de onda P primaria vertical detectado previamente (AUD-05). */
        val ondaP: Boolean = false,
        /** El micrófono ha oído un estruendo. */
        val estruendo: Boolean = false,
        /** Otro móvil de la malla dice lo mismo. Es la corroboración que a Google
         *  se la da su servidor y a nosotros nos la tiene que dar la malla. */
        val corroborada: Boolean = false,
        /**
         * Una red sísmica nacional ha confirmado el terremoto: la alerta de
         * Google ha entrado por la notificación.
         *
         * **No sustituye a nada de lo de arriba: se suma.** El móvil sigue
         * midiendo con su acelerómetro y su micrófono exactamente igual; esto es
         * una prueba MÁS, y de otra naturaleza — la primera que no sale de este
         * teléfono ni de otro teléfono, sino de una red de sismómetros de verdad.
         *
         * Y por eso **no abre un suceso ella sola**, aunque sea la mejor prueba
         * que va a llegar nunca: la alerta llega SEGUNDOS ANTES de que sacuda, o
         * sea que en ese instante todavía no ha pasado nada. Preguntar «¿estás
         * bien?» ahí sería gastar la pregunta justo antes del terremoto, con la
         * persona contestando que sí porque aún está todo quieto. Lo que hace es
         * ARMAR: cuando llegue la sacudida, ya no hay que dudar de ella.
         */
        val alertaExterna: Boolean = false,
        /** Caída libre seguida de impacto: el móvil se soltó y golpeó. */
        val caidaImpacto: Boolean = false,
        /** Ya se ha preguntado y la cuenta atrás ha terminado. */
        val preguntado: Boolean = false,
        /** Ha pulsado ESTOY BIEN. */
        val contestado: Boolean = false,
        /** Pasos desde el suceso. −1 = no se sabe. */
        val pasosDespues: Int = -1,
        /** Alguien ha desbloqueado la pantalla DESPUÉS del suceso. */
        val interaccion: Boolean = false,
        /** Una mano ha levantado o girado el móvil DESPUÉS del suceso. */
        val manoDespues: Boolean = false,
        /**
         * Cuánto hace que alguien tocó este móvil, en ms. −1 = no se sabe.
         *
         * Es lo que separa «en reposo» de «dormida», que no son lo mismo y se
         * estaban confundiendo. Un móvil apoyado en una mesa con su dueño
         * delante está en reposo igual que uno en una mesilla a las tres de la
         * mañana, y a uno hay que despertarlo con una sirena y al otro no.
         */
        val msDesdeInteraccion: Long = -1L,
        /** Milisegundos que lleva el móvil sin que el acelerómetro note nada. */
        val quietoMs: Long = 0L,
        /** Golpes rítmicos junto al móvil. Es la señal canónica de alguien
         *  atrapado y consciente: un ritmo regular no lo produce el entorno. */
        val golpesCerca: Boolean = false,
        /**
         * El clasificador ha leído un grito junto al móvil.
         *
         * **Va aparte de [golpesCerca] porque no vale lo mismo, y eso está
         * medido.** Sobre la biblioteca de `fx sounds` con el banco del 18 de
         * septiembre de 2026, cuatro de las siete grabaciones de sirenas se
         * clasifican como GRITO — camión de bomberos, ambulancia, sirena de
         * policía y un SOS en morse—. Y no se arregla con un umbral: la
         * modulación silábica de un grito infantil real es 0,11 y la de una
         * sirena de policía 0,13, así que los rangos se solapan y cualquier
         * corte que quite las sirenas se lleva los gritos por delante. Lo
         * mismo con el vibrato, la tonalidad y la frecuencia.
         *
         * En un terremoto va a haber sirenas. Así que un grito enciende la
         * baliza igual, pero **no basta para decirle a un rescatista que se
         * oye a alguien**: esa frase la sostienen los golpes.
         */
        val gritoCerca: Boolean = false,
        /**
         * Un vecino pide ayuda por la malla, y a cuántos móviles está (1..4).
         * 0 = nadie.
         *
         * **Esto no puede llevar nunca a [Accion.AUXILIO].** Antes ni pasaba
         * por aquí: el gestor de la malla llamaba a `panico()` directo, así
         * que quien oía a un vecino atrapado **se convertía él mismo en
         * víctima** —sirena, baliza propia en bucle, linterna—. En un salón
         * con varios móviles, una sola pulsación dejaba a todos gritando y
         * emitiendo, y eso rompe justo aquello para lo que existe la malla:
         * las sirenas tapan a la víctima y la saturación del altavoz hace
         * leer números de salto equivocados, que es lo único que orienta a
         * quien busca.
         *
         * El propio proyecto ya tenía escrito el principio, pero solo para
         * quien había pulsado «buscar»: *«quien está buscando NO grita,
         * porque entonces no oye los escombros, que es lo único que tiene»*.
         * Vale igual para todo el que no esté atrapado.
         *
         * Así que lo máximo aquí es despertar a alguien que duerme. El que
         * está despierto se entera en silencio.
         */
        val socorroVecino: Int = 0,
        /** Relación STA/LTA medida en el sismógrafo contra el piso de ruido. */
        val ratioStaLta: Double = 1.0,
        /**
         * Un catálogo sísmico oficial confirma un terremoto cerca, y **ya ha
         * pasado**. No es lo mismo que [alertaExterna] de una alerta temprana,
         * que llega SEGUNDOS ANTES de que sacuda: esto llega después, así que no
         * hay nada que anticipar y sí algo que contar.
         *
         * Es la única prueba que hoy distingue un terremoto de un camión, porque
         * el acelerómetro de un teléfono no llega: el M5.0 del 16 de septiembre
         * de 2026, a 59 km de profundidad, no dejó ni una lectura en el registro.
         */
        val alertaCatalogo: Boolean = false,
        /**
         * Vigilia nocturna: el suelo lleva moviéndose sin parar el tiempo que
         * pide `Opciones.VIGILIA_SOSTENIDO_MS`, dentro de la franja de
         * madrugada y con el modo encendido.
         *
         * Es la única prueba de este teléfono que basta ella sola, y se lo ha
         * ganado midiendo: en once días de registro, doce episodios llegaron a
         * tres segundos y **ninguno de madrugada**. Lo que la hace fiable no es
         * el sensor sino las condiciones — móvil quieto, superficie plana, nadie
         * cerca— y por eso la app tiene que explicárselas al usuario antes de
         * dejarle encender esto.
         */
        val sostenidaNocturna: Boolean = false,
        /** Aquí es de madrugada, el móvil lleva su reposo hecho y nadie lo
         *  toca. No es que haya detectado nada: es que está en condiciones
         *  de creerse lo que le digan. */
        val vigiliaArmada: Boolean = false,
    )

    class Decision(val accion: Accion, val quien: Quien, val motivo: String) {
        override fun toString() = "$accion/$quien · $motivo"
    }

    fun decidir(p: Pruebas): Decision {
        /* ---- 1. Sin suceso, no hay cascada ----
           Esto solo es una puerta, y arregla los dos falsos que hoy pueden
           encender la sirena sin que se haya movido nada:

             · el móvil que se cae de la mesa (caída libre + impacto, que hoy
               llama a panico() directamente desde el Sismografo), y
             · el generador diésel, que el banco cuenta como 52 estruendos falsos
               por hora.

           Ninguno de los dos es un terremoto, y ninguno de los dos tiene por qué
           volver a sonar. Lo que se pierde: un accidente doméstico aislado, sin
           terremoto, ya no dispara nada. Es una decisión, no un descuido — sin
           el terremoto delante, los falsos se comen el sistema. */
        /* Un catálogo oficial también cuenta como «algo ha pasado», aunque aquí
           no se haya movido nada. El 16 de septiembre de 2026 el M5.0 de las
           14:32 no dejó ni una lectura en este acelerómetro —59 km de
           profundidad—, y sin embargo había ocurrido. Que el sensor no llegue no
           significa que no pasara: significa que el sensor no llega.
           Ojo con la diferencia: la alerta TEMPRANA (Google) sigue fuera, porque
           esa llega antes de que sacuda y ahí todavía no ha pasado nada. */
        /* Y un vecino pidiendo ayuda cuenta igual, por el mismo motivo que el
           catálogo: que aquí no se haya movido nada no significa que no haya
           pasado, significa que aquí no llegó. Sin esto, la petición de un
           vecino se cae en esta puerta y no la oye nadie — que es justo el
           caso para el que existe la malla. */
        val algoPasó = p.sacudida || p.corroborada || p.alertaCatalogo || p.socorroVecino > 0
        if (!algoPasó) {
            if (p.estruendo) return Decision(Accion.NADA, Quien.NADIE,
                "estruendo sin sacudida: se anota y no se dispara")
            if (p.caidaImpacto) return Decision(Accion.NADA, Quien.NADIE,
                "el móvil se ha caído, pero el suelo no se ha movido")
            return Decision(Accion.NADA, Quien.NADIE, "sin novedad")
        }

        /* ---- 2. ¿Me creo la sacudida? Depende de dónde esté el móvil ----
           En la mesa el acelerómetro es un sismógrafo. Encima de una persona
           mide a la persona: andar pasa de 3 m/s² sin esfuerzo, y ahí una
           sacudida sola no es evidencia de nada. Se pide algo más — que se oiga
           el derrumbe, o que otro móvil lo confirme. */
        /* Y aquí es donde entra la alerta de Google, sin desplazar a nadie: si
           una red sísmica ha dicho que viene un terremoto y acto seguido el móvil
           se sacude, esa sacudida es el terremoto. No hace falta que además se
           oiga el derrumbe. Es la corroboración que hasta ahora solo podía darnos
           otro móvil de la malla, y llega de fuera y antes. */
        /* Una sacudida floja y SOLA no basta, ni siquiera en reposo.
        
           Esto es lo que cambió tras la prueba de campo en la que bastó mover el
           móvil por la mesa para llegar a la cuenta atrás. La medida dice que a
           nivel MMI V un empujón y un terremoto son el mismo número, así que
           creerse el acelerómetro a solas es prometer algo que el sensor no
           puede dar — y el precio de esa promesa ya se pagó: la app se
           desinstala.
        
           Lo que se pierde, y hay que decirlo: un MMI V aislado, con ningún otro
           móvil cerca y sin alerta de Google, ya no pregunta — se anota y se
           arma. Lo que se gana es que deje de preguntar cada vez que alguien
           mueve la mesa, que es lo que la estaba matando. Y no es un parche
           resignado: un terremoto de verdad sacude MUCHOS móviles a la vez, así
           que la corroboración que se pide es justo la que un terremoto trae y
           un empujón no. Es la razón de ser de la malla. */
        /* EL AUDIO YA NO CUENTA COMO SEGUNDA OPINIÓN.
        
           Estaba en la misma fila que la malla y que la alerta de la red
           sísmica, y no juega en esa liga: el micrófono oye la habitación, no el
           terremoto. Con el móvil en el bolsillo, el roce de la tela cuenta como
           estruendo y la marcha cuenta como sacudida — las dos a la vez daban
           «confirmado» y encendían la sirena. Es literalmente lo que pasó en el
           bolsillo, y su umbral es además el único de la app calibrado contra
           señal sintética.
        
           Una segunda opinión tiene que venir de FUERA de este teléfono: otro
           móvil de la malla, o una red de sismómetros. El estruendo se sigue
           midiendo, se sigue anotando y sigue sirviendo para lo que sí sabe
           hacer —oír a alguien junto al móvil, en el paso 5—, pero ya no
           convierte una sacudida en un terremoto. */
        /* La corroboración externa o por pánico acústico evidente. */
        /* La voz NO entra aquí. Una exclamación de pánico es una pista y se
           anota, pero sale del micrófono de ESTE móvil, igual que la onda P sale
           de su acelerómetro: el que la oye es el mismo que decide. Medido el 16
           de septiembre de 2026 en el Redmi, diez detecciones en quince horas de
           conversación normal, y una de ellas —a las 10:23— llegó a sacar el
           «¿estás bien?» a pantalla completa sin que hubiera temblado nada. Una
           segunda opinión tiene que venir de otro aparato. */
        val opinionAjena = p.corroborada || p.alertaExterna

        /* Y una sacudida fuerte SOLA sigue siendo creíble —si no, un terremoto
           de verdad sin ningún vecino con la app no dispararía nada— pero de
           aquí sale ya solo la pregunta silenciosa, nunca la sirena. Eso se
           decide más abajo. */
        /* Y si YA se pregunto, el terremoto no esta en duda: la puerta sirve
           para decidir si se ABRE un suceso, no para volver a juzgarlo cuando ya
           esta abierto. Sin esto, el derrumbe con el movil despedido y la voz
           junto al movil se caian aqui —lo caza el autotest— y era peor que el
           fallo que se venia a arreglar: perder a alguien que se oye debajo del
           escombro. */
        val creible = when {
            p.preguntado || p.contestado -> true
            p.sacudidaFuerte -> true
            /* La onda P NO cuenta como segunda opinión. Sale del mismo
               acelerómetro que la sacudida, solo que de otra banda: dos lecturas
               del mismo sensor no son dos testigos. Medido el 8 de septiembre de
               2026 en un Huawei STK-LX3 quieto sobre una mesa — 0,34 m/s², ciclo
               del 7 %, «P-wave true»— la app preguntó «¿estás bien?» sin que
               hubiera pasado nada. Se queda como dato en el registro, no como
               prueba en la decisión. */
            else -> opinionAjena
        }
        /* El aviso discreto es la respuesta a «ha temblado cerca y aquí casi no
           se ha notado», y eso solo lo puede decir un catálogo oficial.
           Antes lo decidía el propio acelerómetro con STA/LTA >= 10x, y el 16 de
           septiembre de 2026 eso dio CATORCE avisos en quince horas, ninguno
           coincidente con un sismo real — mientras el M5.0 de las 14:32, a 59 km
           de profundidad, no movió la mesa lo suficiente para dejar ni una sola
           lectura en el registro. Catorce avisos, cero aciertos y un terremoto
           perdido: la relación señal/ruido de este sensor no da para más.
           Si además aquí sacudió fuerte, manda la escalera de abajo. */
        /* La vigilia nocturna va delante de todo lo demás y no pide segunda
           opinión. Es la excepción a la regla de que nada dispara con un solo
           sensor, y se sostiene porque lo que corrobora aquí no es otro aparato
           sino la DURACIÓN: tres segundos seguidos de suelo moviéndose, de
           madrugada, con el móvil quieto sobre una superficie. Eso no lo hace
           una mesa ni un camión, y está medido.
           Suena directamente porque el objetivo es despertar a alguien, y
           preguntarle primero a quien duerme gasta los segundos que importan.

           Y va a AUXILIO, no a AVISAR: de madrugada no hay nadie mirando la
           pantalla para escalar la alarma si la cosa empeora, así que o se
           hace todo en el primer segundo o no se hace. Quien esté bien lo
           apaga en cinco segundos; quien no lo esté ya tiene la baliza y el
           aviso a los vecinos en marcha sin haber tocado nada. */
        if (p.sostenidaNocturna && !p.contestado) {
            return Decision(Accion.AUXILIO, Quien.PERSONA_PROBABLE,
                "vigilia nocturna: el suelo lleva tres segundos moviéndose sin parar")
        }
        /* Un vecino pide ayuda y AQUÍ NO HA PASADO NADA: este móvil es el que
           ayuda, no una víctima. Ver [Pruebas.socorroVecino] y [Accion.AVISAR_VECINO].

           La guarda de los tres sensores propios es la que evita el caso que
           mata: si el techo también se cayó aquí, este móvil TIENE que
           encender su baliza, y de eso decide la escalera de abajo. Sin ella,
           dos vecinos enterrados en el mismo derrumbe se avisarían el uno al
           otro y ninguno pediría ayuda. */
        if (p.socorroVecino > 0 && !p.sacudida && !p.estruendo && !p.caidaImpacto) {
            val aCuantos = if (p.socorroVecino > 1) "a ${p.socorroVecino} móviles" else "justo al lado"
            /* Y solo se hace ruido si de verdad no hay nadie mirando: al
               despierto se le dice en silencio. Es lo mismo que separa el
               salón de clase —donde N sirenas taparían a la víctima— de las
               tres de la mañana, donde sin ruido no se entera nadie. */
            val duerme = p.regimen == Postura.Regimen.EN_REPOSO &&
                (p.msDesdeInteraccion < 0L || p.msDesdeInteraccion > VECINO_DORMIDA_MS)
            return if (duerme)
                Decision(Accion.AVISAR_VECINO, Quien.NADIE,
                    "un vecino pide ayuda $aCuantos y aquí no se entera nadie")
            else
                Decision(Accion.PREGUNTAR_DISCRETA, Quien.NADIE,
                    "un vecino pide ayuda $aCuantos: te aviso sin ruido")
        }
        /* Un vecino ha confirmado un terremoto y aquí es de madrugada con
           la vigilia armada: eso suena, no pregunta en silencio.

           Medido el 17 de septiembre: la alerta llegó en 2,5 s y el móvil
           decidió PREGUNTAR, que es lo correcto de día —te avisa sin
           asustarte— y no sirve de nada a las tres de la mañana, porque
           le pregunta a alguien que está dormido.

           AVISAR y no AUXILIO a propósito: este móvil no ha detectado
           nada, solo lo ha oído. Sonar para despertar está justificado;
           encender la baliza de rescate por alguien que quizá está
           perfectamente, no. Si no contesta, la escalera de abajo ya se
           encarga. */
        if (p.corroborada && p.vigiliaArmada && !p.contestado && !p.preguntado) {
            return Decision(Accion.AVISAR, Quien.PERSONA_PROBABLE,
                "otro móvil confirma un terremoto y aquí es de madrugada")
        }
        if (p.alertaCatalogo && !p.sacudidaFuerte && !p.preguntado && !p.contestado) {
            return Decision(Accion.PREGUNTAR_DISCRETA, Quien.NADIE,
                "un catálogo sísmico confirma un terremoto cerca: aviso discreto")
        }
        if (!creible && p.regimen == Postura.Regimen.EN_REPOSO) {
            return Decision(Accion.NADA, Quien.NADIE,
                "sacudida floja y sin nadie que la confirme: a este nivel no se distingue de una mano")
        }
        if (!creible) return Decision(Accion.NADA, Quien.NADIE,
            "sacudida con el móvil encima y sin confirmar: no basta")

        /* ---- 3. Lo primero es que se entere ----
           En reposo puede estar dormida en un quinto piso y no haber sentido
           nada: esa es la única razón por la que existe una sirena automática, y
           es el caso en el que de verdad hace falta. Si lo lleva encima está
           despierta, así que se pregunta sin ruido. */
        if (!p.preguntado && !p.contestado) {
            /* La sirena automática existe para UNA cosa: alguien dormido en un
               quinto piso que no ha sentido nada. Estaba puesta con «el móvil
               está en reposo», y eso metía en el mismo saco la mesilla de noche
               a las tres de la mañana y la mesa del salón con su dueño delante
               mirando el teléfono. En el segundo caso la sirena no despierta a
               nadie: sobresalta, y una alarma que sobresalta sin motivo es una
               alarma que se acaba apagando para siempre.

               El dato que los separa ya se medía y no se usaba aquí: cuánto hace
               que alguien tocó el móvil. Si lo has usado hace un rato estás
               despierto, y basta con preguntar sin ruido. */
            /* LA SIRENA PIDE UNA OPINIÓN DE FUERA.
            
               «Sacudida fuerte» bastaba para encenderla sola, y ahí estaba el
               otro agujero: el propio comentario de `sacudidaFuerte` dice que a
               nivel MMI V un empujón en la mesa y un terremoto **dan el mismo
               número**, y aun así ese caso iba directo a la sirena. Es el
               «entraba de una a modo emergencia» de las pruebas de campo.
            
               Sin opinión ajena se pregunta en silencio, que no despierta a
               nadie ni quema la confianza de la red. Y lo que se pierde está
               acotado y es asumible: alguien dormido, en un terremoto lo
               bastante fuerte como para no confundirse, sin un solo móvil con la
               app cerca y sin alerta de la red sísmica. En ese caso el propio
               terremoto es lo que despierta, no la sirena. */
            val puedeEstarDormida = p.regimen == Postura.Regimen.EN_REPOSO &&
                opinionAjena &&
                (p.msDesdeInteraccion < 0L || p.msDesdeInteraccion > DORMIDA_MS)
            return if (puedeEstarDormida)
                Decision(Accion.AVISAR, Quien.NADIE,
                    "terremoto y el móvil lleva horas sin tocarse: puede estar dormida")
            else
                Decision(Accion.PREGUNTAR, Quien.NADIE,
                    "terremoto confirmado: pregunto si está bien")
        }

        /* ---- 4. Señales de vida ----
           Contestar es la mejor, pero no la única: quien huye corriendo no se
           para a pulsar nada, y sus pasos lo dicen igual. Desbloquear la pantalla
           también vale — está consciente y con las manos libres. */
        if (p.contestado) return Decision(Accion.NADA, Quien.NADIE,
            "ha contestado que está bien: paso a repetidor de la malla")
        if (p.pasosDespues >= PASOS_VIVA) return Decision(Accion.NADA, Quien.NADIE,
            "sigue andando (${p.pasosDespues} pasos): está bien, paso a repetidor")
        if (p.interaccion) return Decision(Accion.NADA, Quien.NADIE,
            "ha desbloqueado el móvil después del terremoto: está consciente")
        /* Cogerlo cuenta tanto como desbloquearlo. Medido en campo: un tirón del
           cable disparó el sismógrafo con el móvil todavía clasificado en reposo,
           nadie contestó la pregunta —no había nada que contestar— y a los 60 s
           salió la baliza de rescate. Entre medias el móvil había sido levantado
           y girado, que es algo que un inconsciente no hace.

           No borra la detección hacia atrás, y por eso vale también si el
           terremoto fue real: quien coge su móvil está consciente, y marcarlo
           como víctima que no contesta es mentirle al que busca. */
        if (p.manoDespues) return Decision(Accion.NADA, Quien.NADIE,
            "ha cogido el móvil después del terremoto: está consciente")

        /* ---- 5. Nadie ha contestado ----
           A partir de aquí se enciende la baliza, y el rótulo importa tanto como
           la acción. */
        if (p.golpesCerca) return Decision(Accion.AUXILIO, Quien.PERSONA,
            "no contesta y se oye a alguien golpeando junto al móvil")
        /* Mismo AUXILIO, pero sin prometer que hay alguien: el clasificador
           confunde un grito con una sirena y en un terremoto va a haber
           sirenas. Ver [Pruebas.gritoCerca] para las cifras. */
        if (p.gritoCerca) return Decision(Accion.AUXILIO, Quien.PERSONA_PROBABLE,
            "no contesta y se oye un grito junto al móvil, que también puede ser una sirena")
        if (p.caidaImpacto && p.quietoMs > INMOVIL_MS) return Decision(Accion.BALIZA, Quien.MOVIL,
            "el móvil salió despedido y lleva inmóvil: marca el sitio del móvil, no el de nadie")
        /* Y aqui se recupera lo unico que se perdio al pedirle a la sirena una
           opinion de fuera: la persona dormida en un quinto piso.
        
           Hasta ahora este caso caia en BALIZA, que es silenciosa, y de ahi
           pasaba al pulso de rescate cada 12 s. Los dos estan pensados para que
           te OIGA quien busca, no para DESPERTARTE a ti — y quien duerme sigue
           durmiendo mientras su movil pide ayuda por el.
        
           No hace falta ningun sensor nuevo ni ningun vecino: si se pregunto y
           no contesto nadie, y ademas el movil llevaba horas sin tocarse, o esta
           dormida o esta inconsciente. En los dos casos hay que hacer ruido. */
        val nadieCerca = p.regimen == Postura.Regimen.EN_REPOSO &&
            (p.msDesdeInteraccion < 0L || p.msDesdeInteraccion > DORMIDA_MS)
        if (nadieCerca) return Decision(Accion.DESPERTAR, Quien.PERSONA_PROBABLE,
            "se pregunto, no contesto nadie y el movil llevaba horas sin tocarse")
        return Decision(Accion.BALIZA, Quien.PERSONA_PROBABLE,
            "terremoto confirmado y nadie ha contestado")
    }

    /** Cómo se le cuenta al rescatista, con las palabras que puede leer. */
    fun rotulo(q: Quien): String = when (q) {
        Quien.PERSONA -> "SE OYE A ALGUIEN"
        Quien.PERSONA_PROBABLE -> "MÓVIL DE ALGUIEN QUE NO CONTESTÓ"
        Quien.MOVIL -> "SOLO UN MÓVIL · SALIÓ DESPEDIDO"
        Quien.NADIE -> "—"
    }

    /**
     * Los escenarios reales, escritos como casos. Corre en el móvil dentro de
     * COMPROBAR QUE TODO FUNCIONA y tarda microsegundos.
     *
     * Esto es lo que convierte el diseño en algo comprobable: si alguien toca un
     * umbral y con ello deja de encenderse la baliza de una persona enterrada,
     * lo dice aquí y no en un terremoto.
     */
    fun autotest(): Pair<Boolean, String> {
        val casos = listOf(
            Triple("el móvil se cae de la mesa", Accion.NADA,
                Pruebas(regimen = Regimen.EN_REPOSO, caidaImpacto = true)),
            Triple("generador diésel al lado", Accion.NADA,
                Pruebas(regimen = Regimen.EN_REPOSO, estruendo = true)),
            Triple("andando con el móvil en el bolsillo", Accion.NADA,
                Pruebas(regimen = Regimen.ENCIMA, sacudida = true, pasosDespues = 40)),
            /* El caso de campo que trajo la regla: mover el móvil por la mesa
               daba lo mismo que un MMI V, y llegaba a la cuenta atrás. */
            Triple("mueven el móvil en la mesa, nada más", Accion.NADA,
                Pruebas(regimen = Regimen.EN_REPOSO, sacudida = true)),
            /* Y las tres formas de tener una segunda opinión. */
            Triple("sacudida floja pero otro móvil lo confirma", Accion.AVISAR,
                Pruebas(regimen = Regimen.EN_REPOSO, sacudida = true, corroborada = true,
                    msDesdeInteraccion = 6 * 3600_000L)),
            Triple("sacudida floja con alerta de Google", Accion.AVISAR,
                Pruebas(regimen = Regimen.EN_REPOSO, sacudida = true, alertaExterna = true,
                    msDesdeInteraccion = 6 * 3600_000L)),
            /* Antes esto era AVISAR: sirena con un solo sensor, y el propio
               comentario de `sacudidaFuerte` dice que a ese nivel un empujon en
               la mesa da el mismo numero. Sigue siendo creible —se pregunta—
               pero en silencio. */
            Triple("sacudida FUERTE, va sola, sin nadie que la confirme", Accion.PREGUNTAR,
                Pruebas(regimen = Regimen.EN_REPOSO, sacudida = true, sacudidaFuerte = true,
                    msDesdeInteraccion = 6 * 3600_000L)),
            Triple("sacudida FUERTE y la malla lo confirma: ahi si suena", Accion.AVISAR,
                Pruebas(regimen = Regimen.EN_REPOSO, sacudida = true, sacudidaFuerte = true,
                    corroborada = true, msDesdeInteraccion = 6 * 3600_000L)),
            /* Los dos casos de campo que trajeron el cambio. */
            Triple("en el bolsillo: la marcha sacude y la tela suena", Accion.NADA,
                Pruebas(regimen = Regimen.ENCIMA, sacudida = true, estruendo = true)),
            Triple("en la mesa: sacudida floja y un camion pasando", Accion.NADA,
                Pruebas(regimen = Regimen.EN_REPOSO, sacudida = true, estruendo = true,
                    msDesdeInteraccion = 6 * 3600_000L)),
            /* Dormida en un quinto y NADIE mas lo confirma: se pregunta en
               silencio. La sirena pide una opinion de fuera del telefono, porque
               a este nivel un empujon en la mesa da el mismo numero. */
            Triple("dormida en un 5º, sacudida fuerte y nadie que la confirme", Accion.PREGUNTAR,
                Pruebas(regimen = Regimen.EN_REPOSO, sacudida = true, sacudidaFuerte = true,
                    msDesdeInteraccion = 6 * 3600_000L)),
            Triple("dormida en un 5º y la malla lo confirma", Accion.AVISAR,
                Pruebas(regimen = Regimen.EN_REPOSO, sacudida = true, sacudidaFuerte = true,
                    corroborada = true, msDesdeInteraccion = 6 * 3600_000L)),
            /* El caso de campo que trajo esto: el móvil en la mesa, con su dueño
               delante mirándolo, y la sirena saltando a la vez que la pregunta.
               «En reposo» y «dormida» no son lo mismo. */
            Triple("terremoto con el móvil en la mesa y tú delante", Accion.PREGUNTAR,
                Pruebas(regimen = Regimen.EN_REPOSO, sacudida = true, sacudidaFuerte = true,
                    msDesdeInteraccion = 30_000L)),
            /* Y sin dato de interacción se avisa igual: no saber no puede
               costarle la sirena a quien duerme. */
            Triple("terremoto sin saber cuándo lo tocó", Accion.PREGUNTAR,
                Pruebas(regimen = Regimen.EN_REPOSO, sacudida = true, sacudidaFuerte = true)),
            /* Este caso y el del bolsillo son EL MISMO dato: encima, sacudida y
               ruido. No hay forma de separarlos, asi que hay que elegir cual se
               pierde. Se pierde este: con el movil encima la persona esta
               despierta y sujetandolo — si es un terremoto ya lo sabe, y le
               quedan el boton de PANICO y el atajo de volumen. El del bolsillo,
               en cambio, pasa varias veces al dia. */
            Triple("terremoto con el móvil encima: no se distingue de andar", Accion.NADA,
                Pruebas(regimen = Regimen.ENCIMA, sacudida = true, estruendo = true)),
            Triple("de madrugada, otro móvil confirma un terremoto", Accion.AVISAR,
                Pruebas(regimen = Regimen.EN_REPOSO, corroborada = true, vigiliaArmada = true)),
            Triple("alerta de otro móvil de la malla", Accion.PREGUNTAR,
                Pruebas(regimen = Regimen.ENCIMA, corroborada = true)),
            Triple("huye corriendo y no pulsa nada", Accion.NADA,
                Pruebas(regimen = Regimen.ENCIMA, sacudida = true, estruendo = true,
                    preguntado = true, pasosDespues = 40)),
            Triple("contesta que está bien", Accion.NADA,
                Pruebas(regimen = Regimen.EN_REPOSO, sacudida = true, sacudidaFuerte = true,
                    preguntado = true, contestado = true)),
            /* El caso que la sirena habia dejado huerfano y que recupera
               DESPERTAR: se pregunto, no contesto nadie y el movil llevaba horas
               sin tocarse. No hace falta ni malla ni red sismica. */
            /* Y el mismo silencio con alguien delante NO despierta a nadie: si
               tocaste el movil hace un minuto, estas ahi. */
            Triple("no contesto pero acaba de usar el móvil", Accion.BALIZA,
                Pruebas(regimen = Regimen.EN_REPOSO, sacudida = true, sacudidaFuerte = true,
                    preguntado = true, quietoMs = 300_000L, msDesdeInteraccion = 60_000L)),
            Triple("dormida, no contesta y no anda", Accion.DESPERTAR,
                Pruebas(regimen = Regimen.EN_REPOSO, sacudida = true, sacudidaFuerte = true,
                    preguntado = true, pasosDespues = 0, quietoMs = 300_000L)),
            /* El tirón del cable del 17-09: disparo con el móvil aún clasificado
               en reposo, nadie contestó porque no había a quién preguntar, y a
               los 60 s baliza de rescate. La mano llegó 7 s tarde, fuera de la
               ventana retroactiva del sismógrafo, pero muy dentro del minuto. */
            Triple("lo cogió después y nunca contestó", Accion.NADA,
                Pruebas(regimen = Regimen.EN_REPOSO, sacudida = true, sacudidaFuerte = true,
                    preguntado = true, quietoMs = 300_000L, manoDespues = true)),
            Triple("derrumbe y el móvil sale despedido", Accion.BALIZA,
                Pruebas(regimen = Regimen.EN_REPOSO, sacudida = true, estruendo = true,
                    caidaImpacto = true, preguntado = true, quietoMs = 300_000L)),
            Triple("derrumbe y se la oye golpear junto al móvil", Accion.AUXILIO,
                Pruebas(regimen = Regimen.EN_REPOSO, sacudida = true, estruendo = true,
                    preguntado = true, golpesCerca = true)),
            /* Un grito enciende la baliza igual, pero el rótulo baja: cuatro
               de siete sirenas de la biblioteca se leen como GRITO. */
            Triple("derrumbe y un grito que podría ser una sirena", Accion.AUXILIO,
                Pruebas(regimen = Regimen.EN_REPOSO, sacudida = true, estruendo = true,
                    preguntado = true, gritoCerca = true)),
            /* El caso de campo que falló: se preguntó, nadie contestó, y salió
               NADA. La cascada decidía bien; lo que se había perdido era la
               prueba —`temblando` dura un minuto y la pregunta también—. Aquí
               queda escrito el invariante: preguntado y sin respuesta, con lo que
               se vio, es BALIZA. Da igual que el móvil lo llevara encima. */
            Triple("preguntó, nadie contestó, lo llevaba encima", Accion.BALIZA,
                Pruebas(regimen = Regimen.ENCIMA, sacudida = true, estruendo = true,
                    preguntado = true, pasosDespues = 0, quietoMs = 90_000L)),
            Triple("sin contador de pasos y sin contestar", Accion.DESPERTAR,
                Pruebas(regimen = Regimen.EN_REPOSO, sacudida = true, sacudidaFuerte = true,
                    preguntado = true, pasosDespues = -1, quietoMs = 300_000L)),

            /* ---- la alerta de Google, que se SUMA y no sustituye ----
               Los tres casos que definen su comportamiento, y el primero es el
               que más importa: la alerta llega antes de que sacuda, así que sola
               no puede abrir nada. Si abriera, preguntaría «¿estás bien?» a una
               persona a la que todavía no le ha pasado nada, se gastaría la
               pregunta y la cuenta atrás se cerraría justo cuando empieza el
               terremoto. */
            Triple("alerta de Google y aún no ha sacudido", Accion.NADA,
                Pruebas(regimen = Regimen.ENCIMA, alertaExterna = true)),
            /* Y en cuanto sacude, esa sacudida ya no se discute: hasta ahora, con
               el móvil encima, hacía falta además oír el derrumbe. */
            Triple("alerta de Google y luego sacude, con el móvil encima", Accion.PREGUNTAR,
                Pruebas(regimen = Regimen.ENCIMA, alertaExterna = true, sacudida = true)),
            Triple("alerta de Google, sacudió y nadie contestó", Accion.BALIZA,
                Pruebas(regimen = Regimen.ENCIMA, alertaExterna = true, sacudida = true,
                    preguntado = true, pasosDespues = 0, quietoMs = 90_000L)),
            /* ---- Los casos de campo del 14 de septiembre de 2026 (Chocó M4.9 / M4.4) ---- */
            /* Un STA/LTA alto solo dice que el suelo se movio MAS que su propio
               ruido de fondo. El 16 de septiembre de 2026 eso ocurrio catorce
               veces en quince horas sin un solo sismo detras. */
            Triple("sacudida en reposo con STA/LTA alto y nada que la confirme", Accion.NADA,
                Pruebas(regimen = Regimen.EN_REPOSO, sacudida = true, ratioStaLta = 28.1)),
            /* El vecino que pide ayuda. Los tres casos que definen la regla, y
               el tercero es el que evita un muerto. */
            Triple("un vecino pide ayuda y aquí hay alguien despierto", Accion.PREGUNTAR_DISCRETA,
                Pruebas(regimen = Regimen.ENCIMA, socorroVecino = 1, msDesdeInteraccion = 5_000L)),
            Triple("un vecino pide ayuda de madrugada y aquí duermen", Accion.AVISAR_VECINO,
                Pruebas(regimen = Regimen.EN_REPOSO, socorroVecino = 2,
                    msDesdeInteraccion = 6 * 3600_000L)),
            /* Y si el techo tambien se cayo AQUI, este movil no es el vecino:
               es otra victima, y tiene que encender su baliza. Sin esta
               guarda, dos enterrados en el mismo derrumbe se avisarian el uno
               al otro y ninguno pediria ayuda. */
            Triple("un vecino pide ayuda pero aquí también se derrumbó", Accion.BALIZA,
                Pruebas(regimen = Regimen.EN_REPOSO, socorroVecino = 1, sacudida = true,
                    estruendo = true, caidaImpacto = true, preguntado = true,
                    quietoMs = 300_000L, msDesdeInteraccion = 6 * 3600_000L)),
            /* Vigilia nocturna: la duración es la que corrobora. */
            Triple("de madrugada, tres segundos seguidos de suelo moviéndose", Accion.AUXILIO,
                Pruebas(regimen = Regimen.EN_REPOSO, sacudida = true, sostenidaNocturna = true)),
            Triple("lo mismo, pero ya ha contestado que está bien", Accion.NADA,
                Pruebas(regimen = Regimen.EN_REPOSO, sacudida = true, sostenidaNocturna = true,
                    contestado = true)),
            /* Lo que sí distingue un terremoto de un camión. */
            Triple("un catálogo oficial confirma un sismo cerca y aquí apenas se notó", Accion.PREGUNTAR_DISCRETA,
                Pruebas(regimen = Regimen.EN_REPOSO, alertaExterna = true, alertaCatalogo = true)),
            Triple("sismo con alerta externa de red (WebSocket / Google)", Accion.AVISAR,
                Pruebas(regimen = Regimen.EN_REPOSO, sacudida = true, alertaExterna = true, msDesdeInteraccion = 6 * 3600_000L)),

            /* La contraprueba, que es la mitad del trabajo: sin la alerta, una
               sacudida sola con el móvil encima sigue sin bastar. Si esto se
               pusiera en verde, es que `alertaExterna` se habría quedado dada por
               buena para todo el mundo. */
            Triple("sacudida con el móvil encima y sin nada más", Accion.NADA,
                Pruebas(regimen = Regimen.ENCIMA, sacudida = true)),
            /* La onda P no rescata una sacudida que sola no valía: es el mismo
               sensor diciendo lo mismo dos veces. Este caso guarda la regla. */
            Triple("sacudida y onda P, pero las dos del mismo acelerómetro", Accion.NADA,
                Pruebas(regimen = Regimen.ENCIMA, sacudida = true, ondaP = true))
        )
        val partes = ArrayList<String>()
        var todo = true
        for ((nombre, esperada, p) in casos) {
            val d = decidir(p)
            val ok = d.accion == esperada
            if (!ok) todo = false
            partes.add("$nombre → ${d.accion}" + if (ok) " OK" else " FALLÓ (esperaba $esperada)")
        }
        /* Dos comprobaciones sobre el rótulo, que es lo que lee el rescatista.
           Confundirlos manda a cavar donde solo hay un teléfono. */
        val despedido = decidir(Pruebas(regimen = Regimen.EN_REPOSO, sacudida = true, estruendo = true,
            caidaImpacto = true, preguntado = true, quietoMs = 300_000L))
        if (despedido.quien != Quien.MOVIL) { todo = false; partes.add("rótulo del móvil despedido FALLÓ") }
        val normal = decidir(Pruebas(regimen = Regimen.EN_REPOSO, sacudida = true,
            preguntado = true, quietoMs = 300_000L))
        if (normal.quien != Quien.PERSONA_PROBABLE) { todo = false; partes.add("rótulo sin respuesta FALLÓ") }

        val txt = partes.joinToString(" | ")
        Log.i(TAG, "autotest cascada · $txt")
        return todo to txt
    }
}
