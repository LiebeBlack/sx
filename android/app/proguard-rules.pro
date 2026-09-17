# Componentes instanciados por el sistema (manifest, alarmas, accesibilidad, admin)
-keep class com.example.telemetry.LocationTrackerService { *; }
-keep class com.example.telemetry.TelemetryAccessibilityService { *; }
-keep class com.example.telemetry.TelemetryAdminReceiver { *; }
-keep class com.example.telemetry.WatchdogReceiver { *; }

# org.json vive en el framework: no hay nada que conservar de librerías externas.
-dontwarn org.json.**

# Trazas de depuración eliminadas del APK de release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
