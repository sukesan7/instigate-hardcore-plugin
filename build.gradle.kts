plugins {
    java
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "dev.instigatehardcore"
version = "1.0.4"

repositories {
    // Scope third-party repositories strictly to the artifact groups they own
    // so dependencies cannot be shadowed across repositories.
    exclusiveContent {
        forRepository {
            maven("https://repo.papermc.io/repository/maven-public/") {
                name = "papermc"
            }
        }
        filter {
            includeGroup("io.papermc.paper")
            includeGroup("net.md-5")
            includeGroup("com.mojang")
        }
    }

    exclusiveContent {
        forRepository {
            maven("https://repo.codemc.io/repository/maven-releases/") {
                name = "codemc-releases"
                mavenContent {
                    releasesOnly()
                }
            }
        }
        filter {
            includeGroup("com.github.retrooper")
        }
    }

    mavenCentral {
        mavenContent {
            releasesOnly()
        }
    }
}

dependencies {
    // Paper API
    compileOnly("io.papermc.paper:paper-api:26.3.build.157-beta")

    // PacketEvents — optional runtime dependency for rendered Deathcam playback.
    // Supplied by the separate PacketEvents server plugin at runtime.
    compileOnly("com.github.retrooper:packetevents-spigot:2.14.0")

    // Testing
    testImplementation(platform("org.junit:junit-bom:6.1.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.papermc.paper:paper-api:26.3.build.157-beta")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
    }

    processResources {
        filteringCharset = "UTF-8"
    }

    test {
        useJUnitPlatform()
    }

    jar {
        archiveBaseName.set("InstigateHardcore")
        from(rootProject.file("LICENSE.md")) {
            into("META-INF")
        }
    }

    runServer {
        minecraftVersion("26.3")
        runDirectory = rootDir.resolve("server")

        javaLauncher = project.javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
        }

        jvmArgs("-Dcom.mojang.eula.agree=true")

        // Automatically install PacketEvents 2.14.0
        // into the local Paper test server.
        downloadPlugins {
            modrinth("packetevents", "m78nFxYg")
        }
    }
}
