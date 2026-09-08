# R8 no ve lo que se crea por nombre en vez de por codigo. Aqui va solo eso.

# Las vistas propias las infla el sistema leyendo el nombre de clase del XML.
# Si R8 las renombra, el layout casca al inflarse y no hay forma de verlo hasta
# que alguien abre esa pantalla — que en esta app puede ser durante un terremoto.
-keep class red.sismo.Vista* { <init>(...); }
-keep class red.sismo.Tarjeta* { <init>(...); }
-keepclasseswithmembers class * extends android.view.View {
    public <init>(android.content.Context, android.util.AttributeSet);
}

# Las entidades de Room se leen por reflexion.
-keep class red.sismo.data.** { *; }

# Los servicios y actividades van en el manifiesto por nombre. AGP ya los
# conserva, pero dejarlo escrito evita sorpresas si alguien cambia el paquete.
-keep class red.sismo.ServicioSos
-dontwarn red.sismo.ServicioTeclas
-keep class red.sismo.ServicioTeclas
-keep class red.sismo.AlertaGoogle

# Los numeros de linea, para que un informe de fallo se pueda leer.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
