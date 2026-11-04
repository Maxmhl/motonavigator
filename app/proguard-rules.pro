# MapLibre
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**
# BRouter (Reflection auf Profile/Expressions)
-keep class btools.** { *; }
-dontwarn btools.**
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class **$$serializer { *; }
-keep,includedescriptorclasses class de.tetnavigator.**$$serializer { *; }
-dontwarn org.slf4j.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
