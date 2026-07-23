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
    mainClass.set("smartagent.investigator.MainKt")
}

tasks.shadowJar {
    archiveBaseName.set("investigator")
    archiveClassifier.set("")
    archiveVersion.set("")
    manifest {
        attributes["Main-Class"] = "smartagent.investigator.MainKt"
    }
}

tasks.named("startScripts") { dependsOn(tasks.named("shadowJar")) }

tasks.register("jpackageDmg") {
    dependsOn(tasks.shadowJar)

    val shadowJarFile = tasks.shadowJar.flatMap { it.archiveFile }
    val buildDirProv  = layout.buildDirectory

    doLast {
        fun run(vararg cmd: String) {
            val exit = ProcessBuilder(*cmd).inheritIO().start().waitFor()
            check(exit == 0) { "Command failed: ${cmd.toList()}" }
        }

        val buildDir = buildDirProv.get().asFile
        val staging  = buildDir.resolve("jpackage-input").also { it.mkdirs() }
        val distDir  = buildDir.resolve("dist").also { it.mkdirs() }
        val appDir   = distDir.resolve("Investigator.app")
        val dmgFile  = distDir.resolve("Investigator-1.0.0.dmg")

        shadowJarFile.get().asFile.copyTo(staging.resolve("investigator.jar"), overwrite = true)
        projectDir.resolve(".properties").takeIf { it.exists() }
            ?.copyTo(staging.resolve(".properties"), overwrite = true)
        rootProject.projectDir.parentFile.resolve("channels.json").takeIf { it.exists() }
            ?.copyTo(staging.resolve("channels.json"), overwrite = true)

        appDir.deleteRecursively()

        val iconFile = projectDir.resolve("Investigator.icns")

        val jpackageArgs = mutableListOf(
            "jpackage",
            "--type", "app-image",
            "--name", "Investigator",
            "--input", staging.absolutePath,
            "--main-jar", "investigator.jar",
            "--dest", distDir.absolutePath,
            "--java-options", "-Dinvestigator.config=\$APPDIR/.properties",
            "--java-options", "-Dinvestigator.channels=\$APPDIR/channels.json",
            "--app-version", "1.0.0",
            "--vendor", "SmartAgent"
        )
        if (iconFile.exists()) jpackageArgs.addAll(listOf("--icon", iconFile.absolutePath))

        run(*jpackageArgs.toTypedArray())

        val macosDir = appDir.resolve("Contents/MacOS")
        macosDir.resolve("Investigator").renameTo(macosDir.resolve("Investigator_jvm"))
        appDir.resolve("Contents/app/Investigator.cfg").renameTo(appDir.resolve("Contents/app/Investigator_jvm.cfg"))

        val wrapper = macosDir.resolve("Investigator")
        wrapper.writeText(
            "#!/bin/bash\n" +
            "APP_DIR=\"\$(cd \"\$(dirname \"\$0\")\" && pwd)\"\n" +
            "JVM=\"\${APP_DIR}/Investigator_jvm\"\n" +
            "osascript - \"\${JVM}\" <<'APPLESCRIPT'\n" +
            "on run argv\n" +
            "    set launcherPath to item 1 of argv\n" +
            "    tell application \"Terminal\"\n" +
            "        do script quoted form of launcherPath\n" +
            "        activate\n" +
            "    end tell\n" +
            "end run\n" +
            "APPLESCRIPT\n"
        )
        wrapper.setExecutable(true)

        dmgFile.delete()
        run(
            "hdiutil", "create",
            "-volname", "Investigator",
            "-srcfolder", appDir.absolutePath,
            "-ov", "-format", "UDZO",
            dmgFile.absolutePath
        )
    }
}

tasks.register("jpackageWindows") {
    dependsOn(tasks.shadowJar)

    val shadowJarFile = tasks.shadowJar.flatMap { it.archiveFile }
    val buildDirProv  = layout.buildDirectory

    doLast {
        fun run(vararg cmd: String) {
            val exit = ProcessBuilder(*cmd).inheritIO().start().waitFor()
            check(exit == 0) { "Command failed: ${cmd.toList()}" }
        }

        val buildDir = buildDirProv.get().asFile
        val staging  = buildDir.resolve("jpackage-input-win").also { it.mkdirs() }
        val distDir  = buildDir.resolve("dist").also { it.mkdirs() }

        shadowJarFile.get().asFile.copyTo(staging.resolve("investigator.jar"), overwrite = true)
        projectDir.resolve(".properties").takeIf { it.exists() }
            ?.copyTo(staging.resolve(".properties"), overwrite = true)
        rootProject.projectDir.parentFile.resolve("channels.json").takeIf { it.exists() }
            ?.copyTo(staging.resolve("channels.json"), overwrite = true)

        val iconFile = projectDir.resolve("Investigator.ico")

        val args = mutableListOf(
            "jpackage",
            "--type", "exe",
            "--name", "Investigator",
            "--input", staging.absolutePath,
            "--main-jar", "investigator.jar",
            "--dest", distDir.absolutePath,
            "--app-version", "1.0.0",
            "--vendor", "SmartAgent",
            "--win-console",
            "--win-dir-chooser",
            "--win-shortcut",
            "--win-menu",
            "--java-options", "-Dinvestigator.config=\$APPDIR\\.properties",
            "--java-options", "-Dinvestigator.channels=\$APPDIR\\channels.json"
        )
        if (iconFile.exists()) args.addAll(listOf("--icon", iconFile.absolutePath))

        run(*args.toTypedArray())
    }
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    workingDir = rootProject.projectDir
}
