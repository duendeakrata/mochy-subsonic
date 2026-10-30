// Subsonic/Octo-Fiesta source module: streams anything on the user's own
// Subsonic server (Navidrome, Gonic, Octo-Fiesta proxy...), including the
// external Deezer/Qobuz catalogue these proxies merge in as ext-* tracks.
// Built against :modules:api only, like the other built-in modules.
plugins {
    alias(libs.plugins.kotlin.serialization)
    id("kotlin")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":modules:api"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
