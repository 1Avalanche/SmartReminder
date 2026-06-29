plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.shadow)
    application
}

dependencies {
    implementation(project(":llm-client"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("smartagent.TelegramMainKt")
}

tasks.shadowJar {
    archiveBaseName.set("smartagent-telegram")
    archiveClassifier.set("")
    archiveVersion.set("")

    manifest {
        attributes["Main-Class"] = "smartagent.TelegramMainKt"
    }
}
