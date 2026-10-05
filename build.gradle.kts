// ---------------------------------------------------------------------------
// SmartSkip — root build script
//
// Plugins are declared here with `apply false` so that the version catalog stays
// the single source of truth for versions, and each module opts in explicitly.
// ---------------------------------------------------------------------------
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
