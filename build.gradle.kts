plugins {
    id("java")
}

allprojects {
    group = "com.minecart"
    version = "0.0.1"
    repositories {
        mavenCentral()
    }
}

subprojects{
    apply(plugin = "java")

    java{
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    dependencies{
        testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
        // Source: https://mvnrepository.com/artifact/com.google.code.gson/gson
        implementation("com.google.code.gson:gson:2.13.2")
        // Source: https://mvnrepository.com/artifact/com.google.guava/guava
        implementation("com.google.guava:guava:33.5.0-jre")
        // Source: https://mvnrepository.com/artifact/org.apache.commons/commons-lang3
        implementation("org.apache.commons:commons-lang3:3.20.0")
    }
}

tasks.test {
    useJUnitPlatform()
}