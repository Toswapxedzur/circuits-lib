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
    (project.findProperty("shadows") as String?)?.let { systemProperty("snap.shadows", it) }
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        jvmArgs("-XstartOnFirstThread", "-Djava.net.preferIPv4Stack=true")
    }
}

// TEST: replicate the casin (Bluffing Valley) lobby in libGDX → build/ui_casin.png. ./gradlew :display:casinreplica
tasks.register<JavaExec>("casinreplica") {
    group = "application"
    description = "Test: libGDX replica of the bluffingvalley.blopybox.net lobby → build/ui_casin.png"
    mainClass = "com.minecart.display.ui.lmldemo.CasinReplicaApp"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = projectDir
    systemProperty("snap.uishot", "$projectDir/build/ui_casin.png")
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        jvmArgs("-XstartOnFirstThread", "-Djava.net.preferIPv4Stack=true")
    }
}

// STRESS: measure sustained FPS rendering N parts (vsync off). ./gradlew :display:enginestress -Pcount=5000 -Pmode=static -Pseconds=6
tasks.register<JavaExec>("enginestress") {
    group = "application"
    description = "Render-stress the instanced engine with N parts; prints sustained FPS"
    mainClass = "com.minecart.display.render.engine.EngineStressApp"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = projectDir
    (project.findProperty("count") as String?)?.let { systemProperty("stress.count", it) }
    (project.findProperty("mode") as String?)?.let { systemProperty("stress.mode", it) }
    (project.findProperty("seconds") as String?)?.let { systemProperty("stress.seconds", it) }
    (project.findProperty("shot") as String?)?.let { systemProperty("stress.shot", "$projectDir/build/$it") }
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        jvmArgs("-XstartOnFirstThread", "-Djava.net.preferIPv4Stack=true")
    }
}

// LIVE: open the casin lobby replica window (no screenshot/exit) to judge crispness on the real display.
tasks.register<JavaExec>("casinlive") {
    group = "application"
    description = "Open the casin lobby replica live (window stays open) — judge crispness on the display"
    mainClass = "com.minecart.display.ui.lmldemo.CasinReplicaApp"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = projectDir
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        jvmArgs("-XstartOnFirstThread", "-Djava.net.preferIPv4Stack=true")
    }
}

// DESIGN PASS: plastic snap-circuit menu identity → build/ui_design.png. ./gradlew :display:designmenu
tasks.register<JavaExec>("designmenu") {
    group = "application"
    description = "Design pass: plastic snap-circuit menu identity → build/ui_design.png"
    mainClass = "com.minecart.display.ui.lmldemo.MenuDesignApp"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = projectDir
    systemProperty("snap.uishot", "$projectDir/build/ui_design.png")
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        jvmArgs("-XstartOnFirstThread", "-Djava.net.preferIPv4Stack=true")
    }
}

// SPIKE: gdx-lml + LSS + VisUI demo → build/ui_lml.png. ./gradlew :display:lmldemo
tasks.register<JavaExec>("lmldemo") {
    group = "application"
    description = "Spike: declarative UI via gdx-lml template + LSS stylesheet + VisUI → build/ui_lml.png"
    mainClass = "com.minecart.display.ui.lmldemo.LmlDemoApp"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = projectDir
    systemProperty("snap.uishot", "$projectDir/build/ui_lml.png")
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        jvmArgs("-XstartOnFirstThread", "-Djava.net.preferIPv4Stack=true")
    }
}

// UI prototype screenshot: boot to the main menu (no autojoin), render a few frames, dump to a PNG, exit.
// ./gradlew :display:menushot    → display/build/ui_menu.png
tasks.register<JavaExec>("menushot") {
    group = "application"
    description = "Screenshot the restyled main menu to build/ui_menu.png (UI redesign prototype)"
    mainClass = "com.minecart.display.Main"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = projectDir
    systemProperty("snap.uishot", "$projectDir/build/ui_menu.png")
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        jvmArgs("-XstartOnFirstThread", "-Djava.net.preferIPv4Stack=true")
    }
}

// Model test world: discover EVERY model and lay them in a square grid (robust to concurrent datagen).
// ./gradlew :display:modelworld   (press R in-window to re-scan)
tasks.register<JavaExec>("modelworld") {
    group = "application"
    description = "Lay out every committed model in a square grid (skips any not-yet-ready model)"
    mainClass = "com.minecart.display.render.engine.ModelWorldApp"
    classpath = sourceSets["main"].runtimeClasspath
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        jvmArgs("-XstartOnFirstThread", "-Djava.net.preferIPv4Stack=true")
    }
}

// In-game DEBUG model gallery (the merged displayer, via Main + GameMode.DEBUG_MODELS): ./gradlew :display:modelgallery
tasks.register<JavaExec>("modelgallery") {
    group = "application"
    description = "Boot straight into the in-game debug model gallery (every model in a grid)"
    mainClass = "com.minecart.display.Main"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = projectDir
    systemProperty("snap.gallery", "1")
    (project.findProperty("skylight") as String?)?.let { systemProperty("snap.skylight", it) }
    (project.findProperty("shot") as String?)?.let { systemProperty("snap.uishot", "$projectDir/build/$it") }
    (project.findProperty("only") as String?)?.let { systemProperty("gallery.only", it) }
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        jvmArgs("-XstartOnFirstThread", "-Djava.net.preferIPv4Stack=true")
    }
}

// Pivot Phase-B keystone: render a real SnapBoard in 3D via SnapModelBridge + the engine. ./gradlew :display:boarddemo
tasks.register<JavaExec>("boarddemo") {
    group = "application"
    description = "Render a real SnapBoard through the instanced engine (SnapModelBridge bridge demo)"
    mainClass = "com.minecart.display.render.engine.BoardDemoApp"
    classpath = sourceSets["main"].runtimeClasspath
    (project.findProperty("shadows") as String?)?.let { systemProperty("snap.shadows", it) }
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
    mainClass = "com.minecart.display.render.engine.EntityDemoApp"
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
    // Headless backend for unit tests that need Gdx.files (e.g. ModelLoader reads model JSONs) without a GL context.
    // Source: https://mvnrepository.com/artifact/com.badlogicgames.gdx/gdx-backend-headless
    testImplementation("com.badlogicgames.gdx:gdx-backend-headless:$gdxVersion")
    testRuntimeOnly("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-desktop")

    // Bullet 3D rigid-body physics (JNI) for the world-ENTITY system (a battery that falls / rests at an angle).
    // Source: https://mvnrepository.com/artifact/com.badlogicgames.gdx/gdx-bullet
    implementation("com.badlogicgames.gdx:gdx-bullet:$gdxVersion")
    runtimeOnly("com.badlogicgames.gdx:gdx-bullet-platform:$gdxVersion:natives-desktop")

    // FreeType TTF rasteriser — crisp UI fonts at real sizes (replaces the default bitmap font).
    // Source: https://mvnrepository.com/artifact/com.badlogicgames.gdx/gdx-freetype
    implementation("com.badlogicgames.gdx:gdx-freetype:$gdxVersion")
    runtimeOnly("com.badlogicgames.gdx:gdx-freetype-platform:$gdxVersion:natives-desktop")

    // SPIKE (UI redesign): declarative UI via LibGDX Markup Language (HTML-like templates → scene2d) +
    // VisUI widgets (pulled transitively). Maintenance-mode fork, built against libGDX 1.14.2.
    // Source: https://github.com/crashinvaders/gdx-lml
    implementation("com.crashinvaders.lml:gdx-lml:1.10.1.14.2")
    implementation("com.crashinvaders.lml:gdx-lml-vis:1.10.1.14.2")

    // Logback is the chosen SLF4J implementation for the desktop client binary. runtimeOnly keeps it off
    // the compile classpath so application code can't accidentally reach into Logback APIs (except
    // SessionLog, which deliberately does — see its file for the rationale).
    // Source: https://mvnrepository.com/artifact/ch.qos.logback/logback-classic
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")
    // SessionLog programmatically builds a FileAppender, which needs Logback types at compile time.
    // compileOnly avoids double-jaring at runtime (the runtimeOnly above already provides it).
    compileOnly("ch.qos.logback:logback-classic:1.5.18")
}
// Dev launcher: boot straight into a snap world's engine-backed 3D SnapScreen, bypassing the menus (the GUI
// can't be automated headlessly for screenshot verification). ./gradlew :display:runsnap -Pworld=<name>
tasks.register<JavaExec>("runsnap") {
    group = "application"
    description = "Launch straight into a snap world's engine-backed 3D editor (dev; -Pworld=<name>, default snap3d)"
    mainClass = "com.minecart.display.Main"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = projectDir
    systemProperty("snap.autojoin", (project.findProperty("world") as String?) ?: "snap3d")
    (project.findProperty("testplace") as String?)?.let { systemProperty("snap.testplace", it) }
    (project.findProperty("skylight") as String?)?.let { systemProperty("snap.skylight", it) }
    (project.findProperty("fixedcam") as String?)?.let { systemProperty("snap.fixedcam", it) }
    (project.findProperty("physical") as String?)?.let { systemProperty("snap.physical", it) }
    (project.findProperty("phystest") as String?)?.let { systemProperty("snap.phystest", it) }
    (project.findProperty("deckdemo") as String?)?.let { systemProperty("snap.deckdemo", it) }
    (project.findProperty("inputtest") as String?)?.let { systemProperty("snap.inputtest", it) } // scripted-input harness
    (project.findProperty("console") as String?)?.let { systemProperty("snap.console", it) }     // live console (1 = port 4711)
    (project.findProperty("design") as String?)?.let { systemProperty("snap.design", it) }       // DESIGN WORLD: every part laid out, grid-aligned
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        jvmArgs("-XstartOnFirstThread", "-Djava.net.preferIPv4Stack=true")
    }
}
