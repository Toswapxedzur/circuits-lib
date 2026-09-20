plugins {
    application
}

application {
    mainClass = "com.minecart.Main"
}

dependencies {
    implementation(project(":physics"))
    // ngspice shared library binding (the electrical solver backend). Source: https://mvnrepository.com/artifact/net.java.dev.jna/jna
    implementation("net.java.dev.jna:jna:5.15.0")
}