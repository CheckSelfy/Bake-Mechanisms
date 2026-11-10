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
    dependsOn("deploy")
    workingDir = layout.projectDirectory.dir("server").asFile
    classpath = files(layout.projectDirectory.file("server/paper.jar"))
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    })
    jvmArgs("-Djava.net.preferIPv4Stack=true", "-Xms1G", "-Xmx1G")
    args("nogui")
    standardInput = System.`in`
}
