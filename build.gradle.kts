plugins {
    java
}
group = "com.bymatej"

// These are independent versions. A 26.2 / 1.1 release is MSS-26.2.1.1.jar.
val minecraftVersion = providers.gradleProperty("minecraftVersion").orElse("26.1").get()
val pluginVersion = providers.gradleProperty("pluginVersion").orElse("0.1.0-SNAPSHOT").get()
val paperApiVersion = providers.gradleProperty("paperApiVersion")
    .orElse("26.1.2.build.74-stable").get()

version = pluginVersion

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}
dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}
tasks.processResources {
    inputs.property("pluginVersion", pluginVersion)
    inputs.property("minecraftVersion", minecraftVersion)
    filesMatching("plugin.yml") {
        expand(mapOf("pluginVersion" to pluginVersion, "minecraftVersion" to minecraftVersion))
    }
}
tasks.test {
    useJUnitPlatform()
}
tasks.jar {
    archiveFileName.set("MSS-$minecraftVersion.$pluginVersion.jar")
}
