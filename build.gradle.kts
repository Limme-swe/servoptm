import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    java
    jacoco
    checkstyle
}

val javaVersion = providers.gradleProperty("javaVersion").map(String::toInt).get()
val paperApiVersion = providers.gradleProperty("paperApiVersion").get()
val pluginApiVersion = providers.gradleProperty("pluginApiVersion").get()

group = "io.github.limmeswe"
version = providers.gradleProperty("version").get()
description = "Quality-constrained adaptive chunk delivery for Paper servers."

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")

    testImplementation(platform("org.junit:junit-bom:${providers.gradleProperty("junitVersion").get()}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
    withSourcesJar()
}

checkstyle {
    toolVersion = providers.gradleProperty("checkstyleVersion").get()
    configFile = file("config/checkstyle/checkstyle.xml")
    maxWarnings = 0
}

tasks.processResources {
    inputs.properties(
        "version" to project.version,
        "pluginApiVersion" to pluginApiVersion
    )
    filesMatching("plugin.yml") {
        expand(
            "version" to project.version,
            "pluginApiVersion" to pluginApiVersion
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(javaVersion)
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jar {
    archiveBaseName.set("Headroom")
    manifest {
        attributes(
            "Implementation-Title" to "Headroom",
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "Limme-swe",
            "Built-By" to "GitHub Actions"
        )
    }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
