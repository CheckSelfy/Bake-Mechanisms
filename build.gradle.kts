import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    java
}

group = "io.github.checkselfy"
// version comes from gradle.properties; release builds override it with -Pversion=<tag without v>.

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

val mockitoAgent = configurations.create("mockitoAgent")

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.246-stable")
    testImplementation("io.papermc.paper:paper-api:26.3.build.246-stable")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core:5.20.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    mockitoAgent("org.mockito:mockito-core:5.20.0") { isTransitive = false }
}

tasks.test {
    useJUnitPlatform()
    // Load Mockito as an explicit agent: JDK 21+ restricts dynamic self-attach,
    // which fails on some machines and CI runners.
    val agentPath = mockitoAgent.asPath
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-javaagent:$agentPath") })
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

// Opt-in test-server tooling. Never included in the production plugin JAR.
val fixtures = sourceSets.create("fixtures")
configurations[fixtures.compileOnlyConfigurationName].extendsFrom(configurations.compileOnly.get())
tasks.check {
    dependsOn(tasks.named(fixtures.classesTaskName))
}

tasks.register<Jar>("fixturesJar") {
    group = "verification"
    description = "Builds the separate, operator-only test-world generator."
    archiveFileName.set("bake-test-fixtures.jar")
    from(fixtures.output)
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

// The lab builder bundles test-worlds/ppl9 and test-worlds/ppl9-mobs; on first start it builds them into a separate
// farm_lab world of the local server and sends joining players there. Never install it on a public server.
tasks.register<Copy>("deployFarmLab") {
    group = "application"
    description = "Copies the test-worlds farm builder to the local Paper server."
    from(tasks.named("farmLabJar"))
    into(layout.projectDirectory.dir("server/plugins"))
}

tasks.register("resetFarmLab") {
    group = "application"
    description = "Deletes the local server's farm_lab world and the Bake Mechanisms data that referred to it; " +
        "the next runServer rebuilds the lab from test-worlds."
    val serverDir = layout.projectDirectory.dir("server").asFile
    doLast {
        val lock = serverDir.resolve("world/session.lock")
        if (lock.exists()) {
            RandomAccessFile(lock, "rw").use { file ->
                val held = try { file.channel.tryLock() } catch (e: OverlappingFileLockException) { null }
                    ?: throw GradleException("The local server is running. Type stop in its console, then retry.")
                held.release()
            }
        }
        val labData = serverDir.resolve("plugins/BakeFarmLab")
        val labWorld = labData.resolve("world-uid.txt").takeIf { it.isFile }?.readText()?.trim()
        val farms = serverDir.resolve("plugins/BakeMechanisms/farms")
        farms.listFiles { file -> file.isDirectory }.orEmpty().forEach { farm ->
            val world = farm.resolve("farm.yml").takeIf { it.isFile }?.readLines()
                ?.firstOrNull { it.startsWith("world:") }?.substringAfter(":")?.trim()?.trim('\'', '"')
            // Without a recorded lab world, every registration is treated as the lab's: this is a local test server.
            if (labWorld == null || world == null || world == labWorld) {
                logger.lifecycle("Deleting farm registration ${farm.name} (world $world).")
                farm.deleteRecursively()
            }
        }
        // Departure positions of players; recreated on the next logout.
        serverDir.resolve("plugins/BakeMechanisms/offline-players.dat").delete()
        serverDir.resolve("world/dimensions/minecraft/farm_lab").deleteRecursively()
        labData.deleteRecursively()
        logger.lifecycle("Farm lab reset; runServer will rebuild it from test-worlds.")
    }
}

tasks.register<JavaExec>("runServer") {
    group = "application"
    description = "Deploys the plugin with the test-worlds farm lab and runs the local Paper server on Java 25."
    dependsOn("setupServer", "deploy", "deployFarmLab")
    workingDir = layout.projectDirectory.dir("server").asFile
    classpath = files(layout.projectDirectory.file("server/paper.jar"))
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    })
    jvmArgs("-Djava.net.preferIPv4Stack=true", "-Xms1G", "-Xmx1G")
    args("nogui")
    standardInput = System.`in`
}

// Runs destructive snapshot checks only in a separate disposable test server.
val smokeTest = sourceSets.create("smokeTest") {
    compileClasspath += sourceSets.main.get().output + configurations.compileClasspath.get()
}

// Headless correctness review; isolated from both the shipped JAR and snapshot smoke plugin.
val correctness = sourceSets.create("correctness") {
    compileClasspath += sourceSets.main.get().output + configurations.compileClasspath.get()
}
tasks.register<Jar>("correctnessJar") {
    group = "verification"
    archiveFileName.set("bake-correctness.jar")
    from(correctness.output)
    from("test-worlds/ppl9-mobs") { into("mob-fixtures"); exclude("README.md") }
}

// Small extracted map fixtures, shared by manual and headless testing.
val farmLab = sourceSets.create("farmLab") {
    compileClasspath += sourceSets.main.get().output + configurations.compileClasspath.get()
}
tasks.check { dependsOn(tasks.named(farmLab.classesTaskName)) }
tasks.register<Jar>("farmLabJar") {
    group = "verification"
    archiveFileName.set("bake-farm-lab.jar")
    from(farmLab.output) { exclude("**/FarmLabReviewPlugin*", "**/SlimeGallery*") }
    from("test-worlds/ppl9") { into("fixtures"); exclude("README.md") }
    from("test-worlds/ppl9-mobs") { into("mob-fixtures"); exclude("README.md") }
}
tasks.register<Jar>("slimeGalleryJar") {
    group = "verification"
    archiveFileName.set("bake-slime-gallery.jar")
    from(farmLab.output) { include("**/SlimeGallery*") }
    from("src/farmLab/slime-plugin.yml") { rename { "plugin.yml" } }
}

tasks.register<Jar>("farmLabReviewJar") {
    group = "verification"
    archiveFileName.set("bake-farm-lab-review.jar")
    from(farmLab.output) { include("**/FarmLabReviewPlugin*") }
    from("src/farmLab/review-plugin.yml") { rename { "plugin.yml" } }
}

val stasisSmokeJar = tasks.register<Jar>("stasisSmokeJar") {
    archiveFileName.set("bake-stasis-smoke.jar")
    from(smokeTest.output)
}

tasks.register<JavaExec>("stasisSmoke") {
    group = "verification"
    description = "Checks block/entity snapshots on a separate Paper server in run/stasis-smoke, then stops it."
    dependsOn("setupServer", tasks.jar, stasisSmokeJar)
    val smokeDir = layout.projectDirectory.dir("run/stasis-smoke").asFile
    workingDir = smokeDir
    classpath = files(smokeDir.resolve("paper.jar"))
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    jvmArgs("-Dterminal.jline=false", "-Dterminal.ansi=false", "-Xms512M", "-Xmx1G")
    args("nogui")
    doFirst {
        smokeDir.resolve("plugins").mkdirs()
        smokeDir.resolve("cache").mkdirs()
        layout.projectDirectory.file("server/paper.jar").asFile.copyTo(smokeDir.resolve("paper.jar"), overwrite = true)
        layout.projectDirectory.file("server/cache/mojang_26.3.jar").asFile
            .copyTo(smokeDir.resolve("cache/mojang_26.3.jar"), overwrite = true)
        layout.projectDirectory.file("server/eula.txt").asFile.copyTo(smokeDir.resolve("eula.txt"), overwrite = true)
        tasks.jar.get().archiveFile.get().asFile.copyTo(smokeDir.resolve("plugins/bake-mechanisms.jar"), overwrite = true)
        stasisSmokeJar.get().archiveFile.get().asFile.copyTo(smokeDir.resolve("plugins/bake-stasis-smoke.jar"), overwrite = true)
        smokeDir.resolve("server.properties").writeText(
            "online-mode=false\nserver-ip=127.0.0.1\nserver-port=0\nview-distance=2\nsimulation-distance=2\npause-when-empty-seconds=-1\n"
        )
        smokeDir.resolve("spigot.yml").writeText("""
            world-settings:
              default:
                entity-activation-range:
                  animals: 0
                  monsters: 0
                  villagers: 0
                  raiders: 0
                  water: 0
                  flying-monsters: 0
                  misc: 0
        """.trimIndent())
        Files.deleteIfExists(smokeDir.resolve("logs/latest.log").toPath())
    }
    doLast {
        val log = smokeDir.resolve("logs/latest.log").readText()
        if (!log.contains("BLOCK_STASIS_SMOKE_PASSED") || !log.contains("ENTITY_STASIS_SMOKE_PASSED")
            || !log.contains("CHICKEN_STASIS_SMOKE_PASSED")
            || !log.contains("SNAPSHOT_SAVE_FAILURE_SMOKE_PASSED")
            || !log.contains("FRAGILE_BLOCK_STASIS_SMOKE_PASSED")
            || !log.contains("SIGN_STASIS_SMOKE_PASSED")
            || !log.contains("EXTENDED_BLOCK_STASIS_SMOKE_PASSED")
            || !log.contains("MOVING_PISTON_STASIS_SMOKE_PASSED")
            || !log.contains("MOB_BEHAVIOR_SMOKE_PASSED")
            || !log.contains("PROFILING_INPUT_SMOKE_PASSED")
            || !log.contains("PROFILING_NATURAL_INPUT_SMOKE_PASSED")
            || !log.contains("SLIME_FILLER_SMOKE_PASSED")
            || log.contains("BLOCK_STASIS_SMOKE_FAILED")) {
            throw GradleException("Stasis smoke check failed. See run/stasis-smoke/logs/latest.log")
        }
    }
}

// Two JVMs with a clean shutdown between them: no in-memory entity objects can reach restore.
val entityNbtDir = layout.projectDirectory.dir("run/entity-nbt-smoke").asFile
val entityNbtCapture = tasks.register<JavaExec>("entityNbtCapture") {
    group = "verification"
    description = "Captures all audited living/entity types and shuts down a disposable Paper server."
    dependsOn("setupServer", tasks.jar, stasisSmokeJar)
    workingDir = entityNbtDir
    classpath = files(entityNbtDir.resolve("paper.jar"))
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    jvmArgs("-Dterminal.jline=false", "-Dterminal.ansi=false", "-Dbake.entity-nbt.phase=capture", "-Xms512M", "-Xmx1G")
    args("nogui")
    doFirst {
        entityNbtDir.resolve("plugins").mkdirs()
        entityNbtDir.resolve("cache").mkdirs()
        layout.projectDirectory.file("server/paper.jar").asFile.copyTo(entityNbtDir.resolve("paper.jar"), overwrite = true)
        layout.projectDirectory.file("server/cache/mojang_26.3.jar").asFile
            .copyTo(entityNbtDir.resolve("cache/mojang_26.3.jar"), overwrite = true)
        layout.projectDirectory.file("server/eula.txt").asFile.copyTo(entityNbtDir.resolve("eula.txt"), overwrite = true)
        tasks.jar.get().archiveFile.get().asFile.copyTo(entityNbtDir.resolve("plugins/bake-mechanisms.jar"), overwrite = true)
        stasisSmokeJar.get().archiveFile.get().asFile.copyTo(entityNbtDir.resolve("plugins/bake-stasis-smoke.jar"), overwrite = true)
        entityNbtDir.resolve("server.properties").writeText(
            "online-mode=false\nserver-ip=127.0.0.1\nserver-port=0\nview-distance=2\nsimulation-distance=2\n"
        )
        Files.deleteIfExists(entityNbtDir.resolve("logs/latest.log").toPath())
    }
    doLast {
        val log = entityNbtDir.resolve("logs/latest.log").readText()
        if (!log.contains("ENTITY_NBT_CAPTURE_PASSED") || log.contains("BLOCK_STASIS_SMOKE_FAILED")) {
            throw GradleException("Entity NBT capture failed. See run/entity-nbt-smoke/logs/latest.log")
        }
        entityNbtDir.resolve("logs/entity-capture.log").writeText(log)
    }
}

tasks.register<JavaExec>("entityNbtSmoke") {
    group = "verification"
    description = "Restarts Paper after entityNbtCapture and checks entity NBT, UUIDs and passenger trees."
    dependsOn(entityNbtCapture)
    workingDir = entityNbtDir
    classpath = files(entityNbtDir.resolve("paper.jar"))
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    jvmArgs("-Dterminal.jline=false", "-Dterminal.ansi=false", "-Dbake.entity-nbt.phase=restore", "-Xms512M", "-Xmx1G")
    args("nogui")
    doLast {
        val log = entityNbtDir.resolve("logs/latest.log").readText()
        if (!log.contains("ENTITY_NBT_RESTART_PASSED") || log.contains("BLOCK_STASIS_SMOKE_FAILED")) {
            throw GradleException("Entity NBT restart check failed. See run/entity-nbt-smoke/logs/latest.log")
        }
    }
}
