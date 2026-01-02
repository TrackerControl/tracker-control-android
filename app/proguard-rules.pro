# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /home/marcel/Android/Sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

#Line numbers
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

#NetGuard
-keepnames class eu.faircode.netguard.** { *; }
-keepnames class net.kollnig.missioncontrol.** { *; }

#JNI
-keepclasseswithmembernames class * {
    native <methods>;
}

#JNI callbacks
-keep class eu.faircode.netguard.Allowed { *; }
-keep class eu.faircode.netguard.Packet { *; }
-keep class eu.faircode.netguard.ResourceRecord { *; }
-keep class eu.faircode.netguard.Usage { *; }
-keep class eu.faircode.netguard.ServiceSinkhole {
    void nativeExit(java.lang.String);
    void nativeError(int, java.lang.String);
    void logPacket(eu.faircode.netguard.Packet);
    void dnsResolved(eu.faircode.netguard.ResourceRecord);
    boolean isDomainBlocked(java.lang.String);
    int getUidQ(int, int, java.lang.String, int, java.lang.String, int);
    eu.faircode.netguard.Allowed isAddressAllowed(eu.faircode.netguard.Packet);
    void accountUsage(eu.faircode.netguard.Usage);
}

#AndroidX
-keep class androidx.appcompat.widget.** { *; }
-keep class androidx.appcompat.app.AppCompatViewInflater { <init>(...); }
-keepclassmembers class * implements android.os.Parcelable { static ** CREATOR; }

#Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep enum com.bumptech.glide.** {*;}
#-keep public enum com.bumptech.glide.load.resource.bitmap.ImageHeaderParser$** {
#    **[] $VALUES;
#    public *;
#}

#WorkManager
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

#GeoIP2
-keep class com.maxmind.** { *; }
-dontwarn com.maxmind.**

#dexlib2
-dontwarn org.jf.dexlib2.**

#ACRA
-keep class org.acra.** { *; }

# Suppress warnings for missing annotation processor classes
-dontwarn javax.annotation.**
-dontwarn com.google.auto.service.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.ParametersAreNonnullByDefault
-dontwarn javax.annotation.CheckReturnValue
-dontwarn javax.annotation.concurrent.GuardedBy

