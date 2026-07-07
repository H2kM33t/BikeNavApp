# Strip verbose/debug logging in release builds. This app logs navigation
# instructions (street names, turn text) via Log.d/NavLog - we don't want
# that ending up in a shipped, readable logcat stream in release builds.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}

# Keep BLE callback / AccessibilityService classes reachable via reflection
# from the Android framework.
-keep class com.bikenav.navlistenertest.NavAccessibilityService { *; }
-keep class com.bikenav.navlistenertest.NavNotificationListener { *; }
-keep class com.bikenav.navlistenertest.BikeNavApplication { *; }
