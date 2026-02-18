plugins {
    id("java")
    id("com.github.johnrengelman.shadow") version "8.1.1"
    `maven-publish`
    `signing`
}

group = "net.aivory"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(11)
    }
    withSourcesJar()
    withJavadocJar()
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
    archiveClassifier.set("all")
    mergeServiceFiles()

    // Relocate dependencies to avoid conflicts with application code
    relocate("net.bytebuddy", "com.aivory.shadow.bytebuddy")
    relocate("org.java_websocket", "com.aivory.shadow.websocket")
    relocate("com.google.gson", "com.aivory.shadow.gson")
    relocate("org.slf4j", "com.aivory.shadow.slf4j")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<Javadoc> {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            groupId = "net.aivory"
            artifactId = "aivory-monitor-agent-java"
            version = project.version.toString()

            pom {
                name.set("AIVory Monitor Java Agent")
                description.set("AIVory Monitor Java Agent - Remote debugging with AI-powered fix generation")
                url.set("https://aivory.net/monitor/")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }

                developers {
                    developer {
                        id.set("ilscipio")
                        name.set("ILSCIPIO GmbH")
                        email.set("info@ilscipio.com")
                    }
                }

                scm {
                    url.set("https://github.com/aivorynet/agent-java")
                    connection.set("scm:git:https://github.com/aivorynet/agent-java.git")
                    developerConnection.set("scm:git:ssh://git@github.com:aivorynet/agent-java.git")
                }
            }
        }
    }

    repositories {
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("staging-deploy"))
        }
    }
}

signing {
    val signingKey = findProperty("signingKey") as String?
    val signingPassword = findProperty("signingPassword") as String? ?: ""
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications["mavenJava"])
    }
}
