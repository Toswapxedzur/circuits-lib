plugins {
    application
}

application {
    mainClass = "com.minecart.server.dedicated.DedicatedServerMain"
}

dependencies {
    implementation(project(":core"))
    implementation(project(":protocol"))

    // Source: https://mvnrepository.com/artifact/io.netty/netty-common
    implementation("io.netty:netty-common:4.2.12.Final")
    // Source: https://mvnrepository.com/artifact/io.netty/netty-buffer
    implementation("io.netty:netty-buffer:4.2.12.Final")
    // Source: https://mvnrepository.com/artifact/io.netty/netty-codec
    implementation("io.netty:netty-codec:4.2.12.Final")
    // Source: https://mvnrepository.com/artifact/io.netty/netty-transport
    implementation("io.netty:netty-transport:4.2.12.Final")

    // Logback is the chosen SLF4J implementation for the dedicated server binary.
    // Source: https://mvnrepository.com/artifact/ch.qos.logback/logback-classic
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")
}