# ---------------------------------------------------------------------------
# SmartSkip — R8 / ProGuard rules
#
# The detection engine is driven from an AccessibilityService, which is resolved
# reflectively by the system from AndroidManifest.xml, so the service entry point
# and the XML-parsed accessibility-service config must survive shrinking.
# ---------------------------------------------------------------------------

# --- Accessibility service (system instantiates it by name) ----------------
-keep class io.github.szzgx.smartskip.accessibility.SmartSkipAccessibilityService { *; }
-keep class * extends android.accessibilityservice.AccessibilityService { *; }

# --- Room ------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# --- kotlinx.serialization -------------------------------------------------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class io.github.szzgx.smartskip.data.rules.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.szzgx.smartskip.data.rules.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Keep line numbers for readable crash reports --------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Misc ------------------------------------------------------------------
-dontwarn org.jetbrains.annotations.**
-dontwarn javax.annotation.**
