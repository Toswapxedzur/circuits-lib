plugins {
    id("java")
    application
}

group = "com.minecart"
version = "0.0.1"

repositories {
    mavenCentral()
}

dependencies {
    // Source: https://mvnrepository.com/artifact/com.google.code.gson/gson
    implementation("com.google.code.gson:gson:2.13.2")
    // Source: https://mvnrepository.com/artifact/com.google.guava/guava
    implementation("com.google.guava:guava:33.5.0-jre")
    // Source: https://mvnrepository.com/artifact/org.apache.commons/commons-lang3
    implementation("org.apache.commons:commons-lang3:3.20.0")
    // Source: https://mvnrepository.com/artifact/org.ejml/ejml-dsparse
    implementation("org.ejml:ejml-dsparse:0.44.0")
}

application {
    mainClass = "com.minecart.Main"
}

tasks.test {
    useJUnitPlatform()
}