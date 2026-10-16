# Keep file names and line numbers so Play Console crash reports can be deobfuscated
# with the mapping file bundled into the AAB.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Tink (via androidx.security:security-crypto) references compile-time-only annotations.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# Library consumer rules (WireGuard, Room, Tink via security-crypto) cover their own needs.
# Add -keep rules here only if a release build shows a reflection/ClassNotFound crash.
