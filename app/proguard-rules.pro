# SwiftVault Backup Proguard Rules
-keepattributes *Annotation*
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.swiftvault.backup.data.model.** { *; }
-dontwarn com.topjohnwu.superuser.**
