# ==============================================================================
# 率土全能管家 · 商业级 ProGuard / R8 混淆与防逆向规则
# ==============================================================================

# 1. 代码收敛与深层混淆
-repackageclasses 'com.stzb.assistant.core'
-allowaccessmodification
-mergeinterfacesaggressively
-overloadaggressively

# 2. 移除所有调试日志 (Log.d, Log.v) 抹除逆向定位切入点
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# 3. 保护原生 JNI 接口与 C++ 桥接方法
-keepclasseswithmembernames class * {
    native <methods>;
}

# 4. 保护 RapidOCR & OpenCV 相关类
-keep class com.benjaminwan.ocrlibrary.** { *; }
-keep class org.opencv.** { *; }

# 5. 保护 Shizuku API
-keep class dev.rikka.shizuku.** { *; }
-keep class rikka.shizuku.** { *; }

# 6. 保护 AndroidX 与核心生命周期
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep public class * extends android.app.Service
-keep public class * extends android.app.Activity
-keep public class * extends android.accessibilityservice.AccessibilityService

# 7. 保护知识库数据契约与卡密鉴权实体
-keep class com.stzb.assistant.knowledge.** { *; }
-keep class com.stzb.assistant.license.** { *; }
-keep class com.stzb.assistant.SecurityBridge { *; }
-keep class com.stzb.assistant.tactics.TacticalState** { *; }
-keep class com.stzb.assistant.ai.** { *; }
