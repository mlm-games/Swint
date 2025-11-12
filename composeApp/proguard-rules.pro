# JNA classes
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure {
    <fields>;
    <methods>;
}

-keep class mages.** { *; }
-keep class uniffi.** { *; }

-keepclasseswithmembernames class * {
    native <methods>;
}

-dontwarn com.sun.jna.**
-dontwarn java.awt.**

-keepattributes *Annotation*,Signature,InnerClasses