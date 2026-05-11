plugins {
    application
}

application {
    mainClass = "com.minecart.display.Main"
    if (System.getProperty("os.name").lowercase().contains("mac")) {
        applicationDefaultJvmArgs = listOf("-XstartOnFirstThread")
    }
}

val gdxVersion = "1.14.0"

dependencies {
    implementation(project(":client"))

    // Source: https://mvnrepository.com/artifact/com.badlogicgames.gdx/gdx
    implementation("com.badlogicgames.gdx:gdx:$gdxVersion")
    // Source: https://mvnrepository.com/artifact/com.badlogicgames.gdx/gdx-backend-lwjgl3
    implementation("com.badlogicgames.gdx:gdx-backend-lwjgl3:$gdxVersion")
    // Source: https://mvnrepository.com/artifact/com.badlogicgames.gdx/gdx-platform
    runtimeOnly("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-desktop")
}
