plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.shadow)
    application
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.testJunit)
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass = "smartagent.MainKt"
}

tasks.shadowJar {
    archiveBaseName.set("smartagent")
    archiveClassifier.set("")
    archiveVersion.set("")
    manifest {
        attributes["Main-Class"] = "smartagent.MainKt"
    }
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks.register<JavaExec>("runMcp") {
    group = "application"
    description = "Run MCP client (connects to filesystem MCP server via stdio)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("smartagent.mcp_handler.McpMainKt")
}
