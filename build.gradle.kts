val taskGroupName = "surfer"

plugins {
    kotlin("jvm") version "1.9.20"
    kotlin("plugin.serialization") version "1.9.20"
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
    implementation("io.netty:netty-all:4.1.101.Final")
    implementation("io.jpower.kcp:kcp-netty:1.5.2")

    //ssl server support
    implementation("org.bouncycastle:bcpkix-jdk18on:1.76")
//    implementation("net.peanuuutz:tomlkt:0.2.0")
    //kotlin-logging
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    implementation("ch.qos.logback:logback-classic:1.4.11")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.1")

    testImplementation(kotlin("test"))
}

if (properties["release"]!="true"){
    tasks.test {
        useJUnitPlatform()
    }
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
tasks.register("github") {
    group = taskGroupName
    dependsOn(tasks.getByName("build"))
    dependsOn(tasks.getByName("jvmDockerBuildx"))
}

tasks.register<Exec>("jvmDockerBuildx") {
    group = taskGroupName
    dependsOn(tasks.getByName("build"))
    if(properties["release"]=="true"){
        dependsOn(tasks.getByName("dockerLogin"))
    }
    val arguments= listOfNotNull(
        "docker",
        "buildx",
        "build",
        "--platform",
        "linux/amd64,linux/arm/v7,linux/arm64/v8,linux/ppc64le,linux/s390x,windows/amd64",
        "-t",
        "selcarpa/surfer:$version",
        if(properties["release"]=="true"){
            "--push"
        }else{
            null
        },
        "-t",
        "selcarpa/surfer:latest",
        "."
    )
    commandLine(
        arguments
    )
}

tasks.register<Exec>("dockerLogin") {
    group = taskGroupName
    commandLine(
        "docker",
        "login",
        "-u",
        "${properties["dockerUserName"]}",
        "-p",
        "${properties["dockerPassword"]}"
    )
}

