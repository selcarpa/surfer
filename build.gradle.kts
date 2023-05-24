plugins {
    kotlin("jvm") version "1.8.0"
    application
    id("com.github.johnrengelman.shadow") version "7.1.2"
    idea
}

group = "one.tain"
version = "1.2-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.netty:netty-all:4.1.90.Final")
    implementation("com.google.code.gson:gson:2.10.1")

    //kotlin-logging
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    implementation("ch.qos.logback:logback-classic:1.4.7")

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

