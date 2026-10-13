# ProGuard rules for KaveliPay
# Add project-specific keep rules here.

# If you use Jetpack Compose, you may need to keep some classes
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keep class androidx.compose.material3.** { *; }

# Keep OkHttp
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Keep ZXing (QR Code).
# O `QRCodeWriter` é construído por nome de classe dentro do próprio ZXing.
# Sem este keep, o R8 renomeia e a chamada quebra SÓ no APK release — o app
# funciona em debug e morre em produção, que é a pior divergência possível.
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# Keep Kotlin coroutines
-keep class kotlinx.coroutines.** { *; }

# Keep org.json (Android SDK)
-keep class org.json.** { *; }

# Keep our app classes
-keep class com.kavelipay.app.** { *; }