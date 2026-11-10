import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    java
}

group = "io.github.checkselfy"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.246-stable")
    testImplementation("io.papermc.paper:paper-api:26.3.build.246-stable")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core:5.20.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveFileName.set("bake-mechanisms.jar")
}

tasks.register("setupServer") {
    group = "application"
    description = "Downloads Paper 26.3 build 246 and prepares the local test server."

    val serverDir = layout.projectDirectory.dir("server").asFile
    val userAgent = "Bake-Mechanisms/0.1 (https://github.com/CheckSelfy/Bake-Mechanisms)"
    val downloads = listOf(
        Triple(
            "https://fill-data.papermc.io/v1/objects/d158542561e440c4026be7ccb32f551ee88313864792f38ea0ce5d1b88f1b27c/paper-26.3-246.jar",
            "paper.jar",
            "d158542561e440c4026be7ccb32f551ee88313864792f38ea0ce5d1b88f1b27c",
        ),
        Triple(
            "https://piston-data.mojang.com/v1/objects/33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c/server.jar",
            "cache/mojang_26.3.jar",
            "d052f14d7a173734fba553711e5b570162e2f2a313267ee31a21b975a679be64",
        ),
    )

    doLast {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun download(url: String, output: File) {
            val partial = File(output.parentFile, output.name + ".part")
            var lastError: Exception? = null
            repeat(4) { attempt ->
                try {
                    logger.lifecycle("Downloading ${output.name} (attempt ${attempt + 1})...")
                    val connection = URI(url).toURL().openConnection()
                    connection.connectTimeout = 30_000
                    connection.readTimeout = 60_000
                    connection.setRequestProperty("User-Agent", userAgent)
                    connection.getInputStream().use { input ->
                        partial.outputStream().use { input.copyTo(it) }
                    }
                    Files.move(
                        partial.toPath(),
                        output.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                    return
                } catch (e: IOException) {
                    lastError = e
                    partial.delete()
                }
            }
            throw GradleException("Failed to download $url", lastError)
        }

        serverDir.resolve("plugins").mkdirs()
        serverDir.resolve("cache").mkdirs()

        for ((url, path, expectedSha256) in downloads) {
            val output = serverDir.resolve(path)
            if (!output.isFile) {
                download(url, output)
            }
            if (sha256(output) != expectedSha256) {
                throw GradleException("Checksum mismatch for $output. Delete it and run setupServer again.")
            }
        }

        val eula = serverDir.resolve("eula.txt")
        if (!eula.exists()) {
            eula.writeText("eula=true\n")
        }

        val properties = serverDir.resolve("server.properties")
        if (!properties.exists()) {
            properties.writeText("online-mode=false\nwhite-list=false\n")
        }

        logger.lifecycle("Paper 26.3 build 246 is ready in server/.")
    }
}

tasks.register<Copy>("deploy") {
    group = "application"
    description = "Builds the plugin and copies it to the local Paper server."
    dependsOn(tasks.jar)
    from(tasks.jar)
    into(layout.projectDirectory.dir("server/plugins"))
}

tasks.register<JavaExec>("runServer") {
    group = "application"
    description = "Deploys the plugin and runs the local Paper server on Java 25."
    dependsOn("setupServer", "deploy")
    workingDir = layout.projectDirectory.dir("server").asFile
    classpath = files(layout.projectDirectory.file("server/paper.jar"))
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    })
    jvmArgs("-Djava.net.preferIPv4Stack=true", "-Xms1G", "-Xmx1G")
    args("nogui")
    standardInput = System.`in`
}
