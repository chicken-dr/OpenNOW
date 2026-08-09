# ProGuard rules for OpenNOW Android

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.HiltAndroidApp { *; }

# Keep WebRTC classes
-keep class org.webrtc.** { *; }

# Keep MediaCodec classes
-keep class android.media.MediaCodec** { *; }
-keep class android.media.MediaFormat** { *; }

# Keep our application classes
-keep class com.opennow.** { *; }

# Keep Parcelable implementations
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep Kotlin serialization
-keep class kotlinx.serialization.** { *; }