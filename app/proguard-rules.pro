# ── Crash symbolication ───────────────────────────────────────────────────────
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
# FIX: also keep the class name itself (not just constructors) so Shizuku can
# find it by the ComponentName stored in UserServiceArgs — R8 would otherwise
# rename the class and break bindUserService().
-keep class app.hypermtz.service.PrivilegedService {
    public <init>();
    public <init>(android.content.Context);
}

# ShizukuServiceManager.ShizukuState enum — keep for LiveData observers that
# switch on the enum value; R8 can prune enum entries if they appear unused.
-keepclassmembers enum app.hypermtz.util.ShizukuServiceManager$ShizukuState { *; }

# ── Android framework components ──────────────────────────────────────────────
-keep public class * extends android.accessibilityservice.AccessibilityService

# ── R fields ─────────────────────────────────────────────────────────────────
-keepclassmembers class **.R$* {
    public static <fields>;
}
