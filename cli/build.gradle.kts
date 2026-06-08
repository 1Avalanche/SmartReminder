plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
}

application {
    mainClass = "cli.MainKt"
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
