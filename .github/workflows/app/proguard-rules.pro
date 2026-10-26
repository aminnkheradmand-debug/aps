# APS V4 WebView host — R8 keep rules
-keepattributes *Annotation*,JavascriptInterface,Signature,InnerClasses,EnclosingMethod
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.example.webapp.** { *; }
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
