# Angus Mail loads protocol providers and data handlers by class name
# (META-INF/javamail.providers, mailcap), so R8 can't see they are used.
-keep class org.eclipse.angus.mail.imap.** { *; }
-keep class org.eclipse.angus.mail.smtp.** { *; }
-keep class org.eclipse.angus.mail.handlers.** { *; }
-keep class org.eclipse.angus.activation.** { *; }
-keep class jakarta.activation.** { *; }
-keep class jakarta.mail.** { *; }
# Desktop-only APIs referenced by optional code paths.
-dontwarn java.awt.**
-dontwarn javax.security.sasl.**
-dontwarn javax.security.auth.callback.**
-dontwarn org.graalvm.**
