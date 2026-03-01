# ── Crash symbolication ───────────────────────────────────────────────────────
# Preserve source file names and line numbers so stack traces are readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Shizuku ───────────────────────────────────────────────────────────────────
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

# ── HyperMTZ IPC layer ────────────────────────────────────────────────────────
# The generated Stub and Proxy classes are referenced reflectively by Binder.
-keep interface app.hypermtz.IPrivilegedService { *; }
-keep class app.hypermtz.IPrivilegedService$Stub { *; }
-keep class app.hypermtz.IPrivilegedService$Stub$Proxy { *; }

# PrivilegedService is instantiated reflectively by the Shizuku server process.
# Both the default and Context constructors must survive R8.
-keep class app.hypermtz.service.PrivilegedService {
    public <init>();
    public <init>(android.content.Context);
}

# ── Android framework components ──────────────────────────────────────────────
# AGP keeps Activity/Service/etc. via aapt, but explicit rules guard against
# edge cases when the class is only referenced from the manifest.
-keep public class * extends android.accessibilityservice.AccessibilityService

# ── R fields ─────────────────────────────────────────────────────────────────
-keepclassmembers class **.R$* {
    public static <fields>;
}
