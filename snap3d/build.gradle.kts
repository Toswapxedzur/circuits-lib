plugins {
    application
}

application {
    mainClass = "com.minecart.snap3d.Snap3DProof"
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        applicationDefaultJvmArgs = listOf("-XstartOnFirstThread", "-Djava.net.preferIPv4Stack=true")
    }
}

// jMonkeyEngine: a 3D-first engine whose built-in post filters (WaterFilter, LightScattering/god rays,
// SSAO, Bloom, PSSM soft shadows, PBR/IBL) target the Complementary-shader look without hand-writing a
// deferred pipeline. This module is the standalone proof; it will later reuse :core over the protocol.
val jme = "3.7.0-stable"

dependencies {
    implementation(project(":core")) // SnapBoard + SnapSceneGeometry/BoxSpec (engine-agnostic board geometry)

    implementation("org.jmonkeyengine:jme3-core:$jme")
    implementation("org.jmonkeyengine:jme3-desktop:$jme")
    implementation("org.jmonkeyengine:jme3-effects:$jme")
    implementation("org.jmonkeyengine:jme3-terrain:$jme")
    runtimeOnly("org.jmonkeyengine:jme3-lwjgl3:$jme")

    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")
}
