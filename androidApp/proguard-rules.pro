# Preserve source positions for retraceable production crash reports while R8 still shrinks and obfuscates code.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Tink 1.8 references these compile-time-only Error Prone annotations through
# androidx.security:security-crypto. R8 generated these exact rules because the
# annotations have no runtime behavior and are intentionally absent here.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
