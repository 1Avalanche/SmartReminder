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
    testImplementation(libs.kotlin.testJunit)
    testImplementation(testFixtures(project(":llm-client")))
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("smartagent.MainKt")
}

tasks.shadowJar {
    archiveBaseName.set("smartagent-cli")
    archiveClassifier.set("")
    archiveVersion.set("")

    manifest {
        attributes["Main-Class"] = "smartagent.MainKt"
    }
}

tasks.named("startScripts") { dependsOn(tasks.named("shadowJar")) }

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    workingDir = rootProject.projectDir
}
