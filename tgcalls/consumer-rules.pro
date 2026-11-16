# WebRTC

# Keep items annotated with @CalledByNative
-keep @org.webrtc.CalledByNative public class *
-keepclasseswithmembers,includedescriptorclasses class * {
    @org.webrtc.CalledByNative *;
}

# Keep items annotated with @CalledByNativeUnchecked
-keep @org.webrtc.CalledByNativeUnchecked public class *
-keepclasseswithmembers,includedescriptorclasses class * {
    @org.webrtc.CalledByNativeUnchecked *;
}

# Native entry points and their descriptor types must retain their JNI names.
-keepclasseswithmembers,includedescriptorclasses class org.webrtc.** {
    native <methods>;
}

# Manual JNI lookups in tgcalls/platform/android/AndroidInterface.cpp.
-keep,includedescriptorclasses class org.webrtc.DefaultVideoEncoderFactory {
    public <init>(org.webrtc.EglBase$Context, boolean, boolean);
}
-keep,includedescriptorclasses class org.webrtc.DefaultVideoDecoderFactory {
    public <init>(org.webrtc.EglBase$Context);
}
-keep,includedescriptorclasses class org.webrtc.HardwareVideoEncoderFactory {
    public <init>(org.webrtc.EglBase$Context, boolean, boolean);
}
# Manual JNI lookup in WebRTC modules/utility/source/jvm_android.cc.
-keep class org.webrtc.ContextUtils {
    public static void initialize(android.content.Context);
}
