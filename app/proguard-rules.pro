# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# ============================================================================
# ОСНОВНЫЕ ПРАВИЛА ДЛЯ ANDROID КОМПОНЕНТОВ
# ============================================================================

# Сохраняем основные Android компоненты
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends androidx.appcompat.app.AppCompatActivity

# ============================================================================
# WEBVIEW И JAVASCRIPT ИНТЕРФЕЙСЫ
# ============================================================================

# Сохраняем все методы с аннотацией @JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Сохраняем наш JavaScript интерфейс полностью
-keep class ru.fourpda.tickets.TicketMonitorInterface { *; }

# Сохраняем WebView классы
-keep class android.webkit.** { *; }
-keep class androidx.webkit.** { *; }

# Сохраняем методы WebView
-keepclassmembers class android.webkit.WebView {
    public *;
}

# Сохраняем WebViewClient и WebChromeClient
-keep class * extends android.webkit.WebViewClient { *; }
-keep class * extends android.webkit.WebChromeClient { *; }

# ============================================================================
# VIEW BINDING
# ============================================================================

# Сохраняем ViewBinding классы
-keep class * implements androidx.viewbinding.ViewBinding {
    public static *** inflate(android.view.LayoutInflater);
    public static *** inflate(android.view.LayoutInflater, android.view.ViewGroup, boolean);
    public static *** bind(android.view.View);
}

# Сохраняем наш binding класс
-keep class ru.fourpda.tickets.databinding.** { *; }

# ============================================================================
# АТРИБУТЫ И АННОТАЦИИ
# ============================================================================

# Сохраняем аннотации для отладки
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Сохраняем информацию о номерах строк для stack traces
-keepattributes SourceFile,LineNumberTable

# ============================================================================
# KOTLIN СПЕЦИФИЧНЫЕ ПРАВИЛА
# ============================================================================

# Сохраняем Kotlin metadata
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeVisibleTypeAnnotations

# Сохраняем Kotlin классы
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }

# Kotlin coroutines (если используются)
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ============================================================================
# УВЕДОМЛЕНИЯ И СЕРВИСЫ
# ============================================================================

# Сохраняем NotificationCompat
-keep class androidx.core.app.NotificationCompat** { *; }

# Background service (если используется)
-keep class ru.fourpda.tickets.BackgroundMonitorService { *; }

# ============================================================================
# DATA КЛАССЫ И МОДЕЛИ
# ============================================================================

# Сохраняем data классы (NotificationData и т.д.)
-keep class ru.fourpda.tickets.MainActivity$NotificationData { *; }

# ============================================================================
# СЕТЬ И HTTP
# ============================================================================

# OkHttp (если используется)
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# ============================================================================
# УДАЛЕНИЕ ЛОГОВ В RELEASE
# ============================================================================

# Удаляем все логи в release версии
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
    public static int println(...);
}

# ============================================================================
# ОПТИМИЗАЦИИ
# ============================================================================

# Настройки оптимизации
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 5
-allowaccessmodification
-dontpreverify

# Убираем неиспользуемые классы
-dontwarn javax.annotation.**
-dontwarn javax.inject.**
-dontwarn sun.misc.Unsafe

# ============================================================================
# REFLECTION И GSON (если используется)
# ============================================================================

# Если используете Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class sun.misc.Unsafe { *; }
-keep class com.google.gson.** { *; }

# ============================================================================
# ANDROIDX И MATERIAL DESIGN
# ============================================================================

# Сохраняем Material Design компоненты
-keep class com.google.android.material.** { *; }
-keep class androidx.** { *; }

# ============================================================================
# ДОПОЛНИТЕЛЬНЫЕ ПРАВИЛА БЕЗОПАСНОСТИ
# ============================================================================

# Не обфусцируем имена полей для сериализации
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Сохраняем enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
