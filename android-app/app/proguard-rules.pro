# Keep NanoHTTPD classes
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# Keep Gson Models
-keepclassmembers class com.smsbridge.gateway.data.** { <fields>; }
