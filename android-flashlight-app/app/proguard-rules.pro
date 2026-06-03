# Keep SpeechRecognizer listener
-keep class android.speech.** { *; }
-keep class com.flashlightapp.** { *; }

# Kotlin
-keepattributes *Annotation*
-keepclassmembers class ** {
    @kotlin.jvm.JvmStatic *;
}
