plugins {
}

dependencies {
    implementation(project(":core"))

    // Source: https://mvnrepository.com/artifact/io.netty/netty-common
    implementation("io.netty:netty-common:4.2.12.Final")
    // Source: https://mvnrepository.com/artifact/io.netty/netty-buffer
    implementation("io.netty:netty-buffer:4.2.12.Final")
    // Source: https://mvnrepository.com/artifact/io.netty/netty-codec
    implementation("io.netty:netty-codec:4.2.12.Final")
    // Source: https://mvnrepository.com/artifact/io.netty/netty-transport
    implementation("io.netty:netty-transport:4.2.12.Final")
}