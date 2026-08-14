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
        /** Pantalla «ESTOY BIEN» con cuenta atrás. Silenciosa. */
        PREGUNTAR,
        /** Sirena de aviso + la misma pregunta: puede estar dormida. */
        AVISAR,
        /** Baliza, ficha y malla. Sin ruido: quizá esté enterrada. */
        BALIZA,
        /** Todo: baliza, sirena, modo rescate. Hay pruebas de que hay alguien. */
        AUXILIO
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
     * Todo lo medido en un instante. `pasosDespues = -1` significa **no se sabe**
     * —hay móviles sin contador—, y eso nunca puede leerse como «no ha andado»:
     * un dato que no existe no es un dato que valga cero.
     */
    class Pruebas(
        val regimen: Postura.Regimen = Postura.Regimen.DESCONOCIDO,
        /** El sismógrafo de ESTE móvil ha notado el suelo moverse. */
        val sacudida: Boolean = false,
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
        /** El oído ha detectado voz o golpes junto al móvil. */
        val vozOGolpesCerca: Boolean = false
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
        val algoPasó = p.sacudida || p.corroborada
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
        val creible = when (p.regimen) {
            Postura.Regimen.EN_REPOSO -> true
            else -> p.corroborada || p.alertaExterna || (p.sacudida && p.estruendo)
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
            val puedeEstarDormida = p.regimen == Postura.Regimen.EN_REPOSO &&
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

        /* ---- 5. Nadie ha contestado ----
           A partir de aquí se enciende la baliza, y el rótulo importa tanto como
           la acción. */
        if (p.vozOGolpesCerca) return Decision(Accion.AUXILIO, Quien.PERSONA,
            "no contesta y se oye a alguien junto al móvil")
        if (p.caidaImpacto && p.quietoMs > INMOVIL_MS) return Decision(Accion.BALIZA, Quien.MOVIL,
            "el móvil salió despedido y lleva inmóvil: marca el sitio del móvil, no el de nadie")
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
            Triple("terremoto y está dormida en un 5º", Accion.AVISAR,
                Pruebas(regimen = Regimen.EN_REPOSO, sacudida = true,
                    msDesdeInteraccion = 6 * 3600_000L)),
            /* El caso de campo que trajo esto: el móvil en la mesa, con su dueño
               delante mirándolo, y la sirena saltando a la vez que la pregunta.
               «En reposo» y «dormida» no son lo mismo. */
            Triple("terremoto con el móvil en la mesa y tú delante", Accion.PREGUNTAR,
                Pruebas(regimen = Regimen.EN_REPOSO, sacudida = true,
                    msDesdeInteraccion = 30_000L)),
            /* Y sin dato de interacción se avisa igual: no saber no puede
               costarle la sirena a quien duerme. */
            Triple("terremoto sin saber cuándo lo tocó", Accion.AVISAR,
                Pruebas(regimen = Regimen.EN_REPOSO, sacudida = true)),
            Triple("terremoto y lo lleva encima", Accion.PREGUNTAR,
                Pruebas(regimen = Regimen.ENCIMA, sacudida = true, estruendo = true)),
            Triple("alerta de otro móvil de la malla", Accion.PREGUNTAR,
                Pruebas(regimen = Regimen.ENCIMA, corroborada = true)),
            Triple("huye corriendo y no pulsa nada", Accion.NADA,
                Pruebas(regimen = Regimen.ENCIMA, sacudida = true, estruendo = true,
                    preguntado = true, pasosDespues = 40)),
            Triple("contesta que está bien", Accion.NADA,
                Pruebas(regimen = Regimen.EN_REPOSO, sacudida = true,
                    preguntado = true, contestado = true)),
            Triple("dormida, no contesta y no anda", Accion.BALIZA,
                Pruebas(regimen = Regimen.EN_REPOSO, sacudida = true,
                    preguntado = true, pasosDespues = 0, quietoMs = 300_000L)),
            Triple("derrumbe y el móvil sale despedido", Accion.BALIZA,
                Pruebas(regimen = Regimen.EN_REPOSO, sacudida = true, estruendo = true,
                    caidaImpacto = true, preguntado = true, quietoMs = 300_000L)),
            Triple("derrumbe y se la oye junto al móvil", Accion.AUXILIO,
                Pruebas(regimen = Regimen.EN_REPOSO, sacudida = true, estruendo = true,
                    preguntado = true, vozOGolpesCerca = true)),
            /* El caso de campo que falló: se preguntó, nadie contestó, y salió
               NADA. La cascada decidía bien; lo que se había perdido era la
               prueba —`temblando` dura un minuto y la pregunta también—. Aquí
               queda escrito el invariante: preguntado y sin respuesta, con lo que
               se vio, es BALIZA. Da igual que el móvil lo llevara encima. */
            Triple("preguntó, nadie contestó, lo llevaba encima", Accion.BALIZA,
                Pruebas(regimen = Regimen.ENCIMA, sacudida = true, estruendo = true,
                    preguntado = true, pasosDespues = 0, quietoMs = 90_000L)),
            Triple("sin contador de pasos y sin contestar", Accion.BALIZA,
                Pruebas(regimen = Regimen.EN_REPOSO, sacudida = true,
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
            /* La contraprueba, que es la mitad del trabajo: sin la alerta, una
               sacudida sola con el móvil encima sigue sin bastar. Si esto se
               pusiera en verde, es que `alertaExterna` se habría quedado dada por
               buena para todo el mundo. */
            Triple("sacudida con el móvil encima y sin nada más", Accion.NADA,
                Pruebas(regimen = Regimen.ENCIMA, sacudida = true))
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
