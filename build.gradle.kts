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
        // SLF4J facade is the only logging surface every module compiles against; the implementation
        // (Logback) is pinned only by the binaries (display + server) so libraries stay impl-agnostic.
        // Source: https://mvnrepository.com/artifact/org.slf4j/slf4j-api
        implementation("org.slf4j:slf4j-api:2.0.16")
        // Tests share the production logging impl so JUnit output goes through the same pipeline as
        // a live run (and we don't get SLF4J's "no provider" fallback warnings on stderr during builds).
        // Source: https://mvnrepository.com/artifact/ch.qos.logback/logback-classic
        testRuntimeOnly("ch.qos.logback:logback-classic:1.5.18")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}

tasks.test {
    useJUnitPlatform()
}