plugins {
    java
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "dev.instigatehardcore"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()

    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.157-beta")

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
    }

    runServer {
        minecraftVersion("26.3")
        runDirectory = rootDir.resolve("server")

        javaLauncher = project.javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
        }

        jvmArgs("-Dcom.mojang.eula.agree=true")
    }
}