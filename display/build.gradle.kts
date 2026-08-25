plugins {
    application
}

application {
    mainClass = "com.minecart.display.Main"
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        applicationDefaultJvmArgs = listOf("-XstartOnFirstThread", "-Djava.net.preferIPv4Stack=true")
    }
}

// Standalone 3D model viewer for iterating on part models: ./gradlew :display:preview
tasks.register<JavaExec>("preview") {
    group = "application"
    description = "Launch the part model preview viewer"
    mainClass = "com.minecart.display.preview.ModelPreviewApp"
    classpath = sourceSets["main"].runtimeClasspath
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        jvmArgs("-XstartOnFirstThread", "-Djava.net.preferIPv4Stack=true")
    }
}

val gdxVersion = "1.14.0"

dependencies {
    implementation(project(":core"))
    implementation(project(":protocol"))
    implementation(project(":client"))
    implementation(project(":server"))

    // Source: https://mvnrepository.com/artifact/io.netty/netty-common
    implementation("io.netty:netty-common:4.2.12.Final")
    // Source: https://mvnrepository.com/artifact/io.netty/netty-transport
    implementation("io.netty:netty-transport:4.2.12.Final")

    // Source: https://mvnrepository.com/artifact/com.badlogicgames.gdx/gdx
    implementation("com.badlogicgames.gdx:gdx:$gdxVersion")
    // Source: https://mvnrepository.com/artifact/com.badlogicgames.gdx/gdx-backend-lwjgl3
    implementation("com.badlogicgames.gdx:gdx-backend-lwjgl3:$gdxVersion")
    // Source: https://mvnrepository.com/artifact/com.badlogicgames.gdx/gdx-platform
    runtimeOnly("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-desktop")

    // Logback is the chosen SLF4J implementation for the desktop client binary. runtimeOnly keeps it off
    // the compile classpath so application code can't accidentally reach into Logback APIs (except
    // SessionLog, which deliberately does — see its file for the rationale).
    // Source: https://mvnrepository.com/artifact/ch.qos.logback/logback-classic
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")
    // SessionLog programmatically builds a FileAppender, which needs Logback types at compile time.
    // compileOnly avoids double-jaring at runtime (the runtimeOnly above already provides it).
    compileOnly("ch.qos.logback:logback-classic:1.5.18")
}