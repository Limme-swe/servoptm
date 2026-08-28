plugins {
    java
}

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${providers.gradleProperty("paperVersion").get()}")

    testImplementation(platform("org.junit:junit-bom:${providers.gradleProperty("junitVersion").get()}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror", "-parameters"))
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
    failFast = false
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.jar {
    archiveFileName = "Headroom.jar"
    manifest {
        attributes(
            "Implementation-Title" to "Headroom",
            "Implementation-Version" to project.version,
            "Automatic-Module-Name" to "dev.headroom"
        )
    }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val verifyPluginJar by tasks.registering {
    group = "verification"
    description = "Verifies the deployable plugin JAR contains its descriptor and entry point."
    dependsOn(tasks.jar)

    doLast {
        val archive = tasks.jar.get().archiveFile.get().asFile
        require(archive.isFile) { "Missing plugin archive: $archive" }

        java.util.zip.ZipFile(archive).use { zip ->
            require(zip.getEntry("plugin.yml") != null) { "Headroom.jar does not contain plugin.yml" }
            require(zip.getEntry("dev/headroom/HeadroomPlugin.class") != null) {
                "Headroom.jar does not contain the plugin entry point"
            }
        }
    }
}

tasks.check {
    dependsOn(verifyPluginJar)
}
