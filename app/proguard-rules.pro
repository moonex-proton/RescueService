# Add project specific ProGuard rules here.
# You can find more information about how to configure ProGuard in the official documentation:
# https://www.guardsquare.com/en/products/proguard/manual/introduction

# Keep the classes of services that the system creates reflectively
-keep class com.babenko.rescueservice.accessibility.RedHelperAccessibilityService { *; }
-keep class com.babenko.rescueservice.notifications.RedNotificationListenerService { *; }
-keep class com.babenko.rescueservice.voice.VoiceSessionService { *; }

# Keep receiver classes
-keep class com.babenko.rescueservice.battery.BatteryReceiver { *; }
-keep class com.babenko.rescueservice.airplane.AirplaneModeReceiver { *; }

# Keep classes for WorkManager
-keepnames class androidx.work.impl.workers.** { *; }

# Keep TaskState for JSON serialization
-keep class com.babenko.rescueservice.llm.TaskState { *; }