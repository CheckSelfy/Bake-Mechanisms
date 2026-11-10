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
