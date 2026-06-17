// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // Kotlin is built into AGP 9+ — do NOT apply org.jetbrains.kotlin.android (it errors).
    alias(libs.plugins.kotlin.compose) apply false
}
