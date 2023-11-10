plugins {
    kotlin("jvm") version "1.9.0"
    kotlin("plugin.serialization") version "1.8.21"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
    idea
}

group = "one.tain"
version = "1.14-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.netty:netty-all:4.1.96.Final")
    implementation("io.jpower.kcp:kcp-netty:1.5.0")

    //ssl server support
    implementation("org.bouncycastle:bcpkix-jdk18on:1.75")
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

