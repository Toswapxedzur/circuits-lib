plugins {
    application
}

application {
    mainClass = "com.minecart.display.Main"
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        applicationDefaultJvmArgs = listOf("-XstartOnFirstThread", "-Djava.net.preferIPv4Stack=true")
    }
}

// One-time: draw the part sprites to fixed PNGs under src/main/resources/textures/parts.
// ./gradlew :display:seedtextures   (then commit the generated PNGs)
tasks.register<JavaExec>("seedtextures") {
    group = "application"
    description = "Generate the fixed part-sprite PNGs for the atlas"
    mainClass = "com.minecart.display.render.engine.SeedPartTextures"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = projectDir // so src/main/resources/... resolves to this module
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        jvmArgs("-XstartOnFirstThread", "-Djava.net.preferIPv4Stack=true")
    }
}

// Model generator (datagen half producing JSON; seedtextures produces the PNGs). Run AFTER seedtextures.
// ./gradlew :display:genmodels   (then commit the generated JSON under src/main/resources/models/parts)
tasks.register<JavaExec>("genmodels") {
    group = "application"
    description = "Generate the part model JSONs (Minecraft-style) from the datagen source"
    mainClass = "com.minecart.display.render.engine.GenModels"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = projectDir // so src/main/resources/... resolves to this module
}

// GPU-instanced component renderer engine demo: ./gradlew :display:enginedemo
tasks.register<JavaExec>("enginedemo") {
    group = "application"
    description = "Launch the instanced component renderer engine demo"
    mainClass = "com.minecart.display.render.engine.EngineDemoApp"
    classpath = sourceSets["main"].runtimeClasspath
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        jvmArgs("-XstartOnFirstThread", "-Djava.net.preferIPv4Stack=true")
    }
}

// Bullet physics proof for the world-entity system: drop a body onto a ramp. ./gradlew :display:entityproof
tasks.register<JavaExec>("entityproof") {
    group = "application"
    description = "Bullet 3D-physics proof — an entity falls and rests against a ramp"
    mainClass = "com.minecart.display.entity.EntityPhysicsProof"
    classpath = sourceSets["main"].runtimeClasspath
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        jvmArgs("-XstartOnFirstThread", "-Djava.net.preferIPv4Stack=true")
    }
}

// Entity lifecycle demo — battery: box-data <-> physical entity (E eject / Q insert / R reset).
tasks.register<JavaExec>("entitydemo") {
    group = "application"
    description = "World-entity lifecycle demo — a battery ejects into a physics entity and re-sockets"
    mainClass = "com.minecart.display.entity.EntityDemoApp"
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

    // Model datagen writes / the runtime loader reads part model JSON.
    // Source: https://mvnrepository.com/artifact/com.google.code.gson/gson
    implementation("com.google.code.gson:gson:2.13.2")

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

    // Bullet 3D rigid-body physics (JNI) for the world-ENTITY system (a battery that falls / rests at an angle).
    // Source: https://mvnrepository.com/artifact/com.badlogicgames.gdx/gdx-bullet
    implementation("com.badlogicgames.gdx:gdx-bullet:$gdxVersion")
    runtimeOnly("com.badlogicgames.gdx:gdx-bullet-platform:$gdxVersion:natives-desktop")

    // Logback is the chosen SLF4J implementation for the desktop client binary. runtimeOnly keeps it off
    // the compile classpath so application code can't accidentally reach into Logback APIs (except
    // SessionLog, which deliberately does — see its file for the rationale).
    // Source: https://mvnrepository.com/artifact/ch.qos.logback/logback-classic
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")
    // SessionLog programmatically builds a FileAppender, which needs Logback types at compile time.
    // compileOnly avoids double-jaring at runtime (the runtimeOnly above already provides it).
    compileOnly("ch.qos.logback:logback-classic:1.5.18")
}