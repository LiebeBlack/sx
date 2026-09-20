# ============================================================================================
# Ofuscación extrema del APK de release (R8) sin romper la telemetría.
#
# Regla de oro: el sistema operativo es el único "cliente" que no puede leer este código.
# Todo lo que Android instancia por su cuenta —activity, service, receivers, y las PendingIntent
# que viajan dentro de alarmas y notificaciones— llega por NOMBRE DE CLASE desde el manifest y
# desde AlarmManager, así que esos nombres jamás se renombran. El resto se tritura sin piedad:
# repaquetado, sobrecarga de nombres y firmas únicas.
#
# Este proyecto no usa reflexión, ni Serializable, ni Parcelable, ni Class.forName, ni nombres
# de clase en cadenas: por eso se pueden activar las agresiones más duras sin efectos colaterales.
# Si algún día se añade cualquiera de esas técnicas, hay que excluirla aquí.
# ============================================================================================

# --- Agresiones de ofuscación -----------------------------------------------------------------
# Repaqueta TODAS las clases renombrables en un solo paquete raíz: elimina la estructura de
# paquetes de la app (y con ella la pista de qué hace cada cosa) y reduce el tamaño del dex.
-repackageclasses 'o'
# Permite a R8 subir/bajar la visibilidad de miembros para poder renombrarlos y fusionarlos.
-allowaccessmodification
# Reutiliza el mismo nombre para miembros distintos cuando la firma lo permite.
-overloadaggressively
# Hace únicos los nombres de miembros entre clases, dificultando el cruce de referencias.
-useuniqueclassmembernames
# Reescribe las cadenas que coincidan con nombres de clases renombradas, para que sigan siendo
# válidas (aquí no hay ninguna, pero evita sorpresas si alguien añade una).
-adaptclassstrings
# Elimina los metadatos de origen (nombres de fichero .java) manteniendo la tabla de líneas: las
# trazas de crash siguen siendo legibles para depurar, pero no revelan la estructura del código.
-renamesourcefileattribute SourceFile
-keepattributes SourceFile, LineNumberTable

# NO se usa -mergeinterfacesaggressively: puede romper 'instanceof' contra interfaces fusionadas
# y el ahorro real es nulo en un proyecto sin dependencias.

# --- Componentes instanciados por el sistema (NO renombrar) -----------------------------------
# El nombre de estas clases está escrito en AndroidManifest.xml, en las PendingIntent de alarma
# y en los Intent explícitos de arranque. Renombrarlas rompería el rastreo al primer reinicio.
# La clase Application se instancia POR NOMBRE desde AndroidManifest.xml antes que nada: si R8 la
# renombra, el proceso no arranca.
-keep class com.example.telemetry.TelemetryApp { *; }
-keepclassmembers class * extends android.app.Application {
    public void onCreate();
    public void onLowMemory();
    public void onTrimMemory(int);
}

-keep class com.example.telemetry.MainActivity { *; }
-keep class com.example.telemetry.LocationTrackerService { *; }
-keep class com.example.telemetry.TelemetryAccessibilityService { *; }
-keep class com.example.telemetry.TelemetryAdminReceiver { *; }
-keep class com.example.telemetry.WatchdogReceiver { *; }

# WorkManager instancia su Worker por NOMBRE de clase leído de su base de datos de trabajos. Si R8
# lo renombra, el trabajo simplemente deja de ejecutarse (sin error visible) y se pierde la segunda
# vía de persistencia. Es el único componente de la app que no va en el manifest y aun así debe
# conservar su nombre.
-keep class com.example.telemetry.TelemetryWorker { *; }
-keep class * extends androidx.work.ListenableWorker { public <init>(...); }

# Los constructores que invoca el framework por reflexión (Service, BroadcastReceiver, Provider).
# Sin esto, R8 puede eliminar el constructor público vacío y el sistema no puede instanciarlas.
-keepclassmembers class * extends android.app.Service { public <init>(...); }
-keepclassmembers class * extends android.content.BroadcastReceiver { public <init>(...); }
-keepclassmembers class * extends android.content.ContentProvider { public <init>(...); }

# Métodos del ciclo de vida: los llama el framework, no el código de la app, así que un
# renombrado o una eliminación por "no usado" dejarían el servicio mudo.
-keepclassmembers class * extends android.app.Service {
    public void onCreate();
    public int onStartCommand(android.content.Intent, int, int);
    public void onDestroy();
    public android.os.IBinder onBind(android.content.Intent);
    public void onTaskRemoved(android.content.Intent);
    public void onRebind(android.content.Intent);
    public boolean onUnbind(android.content.Intent);
}
-keepclassmembers class * extends android.content.BroadcastReceiver { public void onReceive(...); }
-keepclassmembers class * extends android.accessibilityservice.AccessibilityService { public void onAccessibilityEvent(...); public void onInterrupt(); }
-keepclassmembers class * extends android.app.admin.DeviceAdminReceiver { public void onEnabled(...); public void onDisabled(...); }
-keepclassmembers class * extends android.app.Activity {
    public void onCreate(android.os.Bundle);
    public void onResume();
    public void onPause();
    public void onDestroy();
}

# El resto de la app (Watchdog, TelemetryClient, SensorFusion, GnssMonitor) solo se referencia
# desde código Java, nunca por nombre: se ofusca y se repaqueta sin ninguna regla extra.

# --- Framework (vive en android.jar: no se ofusca, solo evita avisos) --------------------------
-keep class org.json.** { *; }
-dontwarn org.json.**
-dontwarn android.location.**

# Play Services y AndroidX traen sus propias reglas de consumidor (R8 las aplica solo); estas
# líneas evitan que las clases opcionales de GMS que no usamos rompan la compilación del release.
-dontwarn com.google.android.gms.**
-dontwarn androidx.work.**
# El puente al motor fusionado se conserva tal cual (no se renombra ni se recorta), y con él no se
# mantiene el resto del paquete de ubicación de GMS, que R8 recortará como código no usado.
-keep class com.google.android.gms.location.LocationServices { *; }
-keep class com.google.android.gms.location.FusedLocationProviderClient { *; }
-keep class com.google.android.gms.location.LocationCallback { *; }
-keep class com.google.android.gms.location.LocationResult { *; }

# --- Huella en el APK --------------------------------------------------------------------------
# Log silenciado por completo: no queda ni una traza de telemetría escrita en el release
# (ni ubicaciones, ni identificadores de dispositivo en logcat).
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
    public static *** println(...);
}
