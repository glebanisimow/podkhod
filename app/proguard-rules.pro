# Room, kotlinx.serialization, Koin, Navigation and Coil ship consumer R8 rules in their AARs.
# Add project-specific keep rules here only after a release build fails on a device.

# Strip verbose/debug logging from release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
