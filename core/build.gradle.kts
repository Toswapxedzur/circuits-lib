plugins {
    application
}

application {
    mainClass = "com.minecart.Main"
}

dependencies {
    implementation(project(":physics"))
    // Source: https://mvnrepository.com/artifact/org.ejml/ejml-dsparse
    implementation("org.ejml:ejml-dsparse:0.44.0")
    // ngspice shared library binding (electrical solver backend). Source: https://mvnrepository.com/artifact/net.java.dev.jna/jna
    implementation("net.java.dev.jna:jna:5.15.0")
}