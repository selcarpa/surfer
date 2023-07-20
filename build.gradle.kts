plugins {
    kotlin("jvm") version "1.8.0"
    kotlin("plugin.serialization") version "1.8.21"
    application
    id("com.github.johnrengelman.shadow") version "7.1.2"
    idea
}

group = "one.tain"
version = "1.12-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.netty:netty-all:4.1.94.Final")
//    runtimeOnly("io.netty:netty-tcnative-boringssl-static:2.0.61.Final:linux-aarch_64")
    runtimeOnly("io.netty:netty-tcnative:2.0.61.Final")
    implementation("io.jpower.kcp:kcp-netty:1.5.0")

//    implementation("net.peanuuutz:tomlkt:0.2.0")
    //kotlin-logging
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    implementation("ch.qos.logback:logback-classic:1.4.7")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.1")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

idea {
    module{
        isDownloadJavadoc = true
        isDownloadSources = true
    }
}


kotlin {
    jvmToolchain(11)
}

application {
    mainClass.set("MainKt")
}

