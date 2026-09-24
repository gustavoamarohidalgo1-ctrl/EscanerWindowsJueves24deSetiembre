# FacturaStock usa anotaciones generadas por Room/Hilt y firmas genéricas en release.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod

# Room genera la implementación de la base. Sus consumer rules cubren adaptadores; estas reglas
# documentan y protegen el punto de entrada que se resuelve por nombre.
-keep class com.facturastock.app.** extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Database class com.facturastock.app.** { *; }

# WorkManager persiste el nombre de la clase worker entre versiones de la aplicación.
-keepnames class com.facturastock.app.** extends androidx.work.CoroutineWorker

# Varias preferencias y columnas guardan enum.name y restauran con valueOf. Mantener estos
# miembros evita que una optimización cambie el contrato durable/serializado.
-keepclassmembers enum com.facturastock.app.** {
    public static final ** *;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
