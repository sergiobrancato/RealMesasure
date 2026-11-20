// Root build configuration for RealMeasure.
//
// Plugin versions are declared once here; the :app module applies them without
// repeating versions. AGP 9.2.1 + Kotlin 2.2.0 is a proven pairing for
// Gradle 9.4.1 (see gradle/wrapper/gradle-wrapper.properties).

plugins {
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0" apply false
}
