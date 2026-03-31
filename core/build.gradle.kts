plugins {
    application
}

application {
    mainClass = "com.minecart.Main"
}

dependencies {
    // Source: https://mvnrepository.com/artifact/org.ejml/ejml-dsparse
    implementation("org.ejml:ejml-dsparse:0.44.0")
}