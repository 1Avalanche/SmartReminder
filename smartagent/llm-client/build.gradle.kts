plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    `java-test-fixtures`
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
testFixturesImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.testJunit)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

kotlin {
    jvmToolchain(21)
}
