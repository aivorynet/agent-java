plugins {
    id("java")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.aivory.monitor"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(11)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // ByteBuddy for bytecode instrumentation
    implementation("net.bytebuddy:byte-buddy:1.14.11")
    implementation("net.bytebuddy:byte-buddy-agent:1.14.11")

    // WebSocket client
    implementation("org.java-websocket:Java-WebSocket:1.5.6")

    // JSON serialization
    implementation("com.google.code.gson:gson:2.10.1")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.11")
    implementation("org.slf4j:slf4j-simple:2.0.11")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Jar> {
    manifest {
        attributes(
            "Premain-Class" to "com.aivory.monitor.agent.AIVoryAgent",
            "Agent-Class" to "com.aivory.monitor.agent.AIVoryAgent",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true",
            "Can-Set-Native-Method-Prefix" to "true"
        )
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()

    // Relocate dependencies to avoid conflicts with application code
    relocate("net.bytebuddy", "com.aivory.shadow.bytebuddy")
    relocate("org.java_websocket", "com.aivory.shadow.websocket")
    relocate("com.google.gson", "com.aivory.shadow.gson")
    relocate("org.slf4j", "com.aivory.shadow.slf4j")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.test {
    useJUnitPlatform()
}
