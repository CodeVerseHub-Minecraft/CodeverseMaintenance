plugins {
    java
    id("com.gradleup.shadow") version "9.6.0"
}

group = "net.codeverse"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // The shared contract is provided at runtime by CodeverseAuth, which owns
    // the API registration on the proxy. Compiled against, never shipped: a
    // second copy of CodeverseApiProvider would be a second registry, and the
    // maintenance service would be contributed where nothing could find it.
    compileOnly("com.github.CodeVerseHub-Minecraft.CodeverseAPI:api:0.3.0")

    compileOnly("com.velocitypowered:velocity-api:4.0.0")
    annotationProcessor("com.velocitypowered:velocity-api:4.0.0")

    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Tests exercise the contract directly, so they need the real classes
    // rather than the runtime provided ones.
    testImplementation("com.github.CodeVerseHub-Minecraft.CodeverseAPI:api:0.3.0")
    // The platform is compileOnly for the jar but the tests need Adventure and
    // MiniMessage on the classpath to prove every bundled message parses.
    testImplementation("com.velocitypowered:velocity-api:4.0.0")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.add("-Xlint:all")
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName.set("CodeverseMaintenance")
    archiveClassifier.set("")

    relocate("com.google.gson", "net.codeverse.maintenance.libs.gson")

    mergeServiceFiles()
    // Minimization is off deliberately, matching the other plugins: it strips
    // classes reached only reflectively and the failure appears at runtime.
    exclude("META-INF/versions/*/OSGI-INF/**")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
