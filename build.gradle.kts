plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    application
}

group = "com.bayshier"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("io.modelcontextprotocol.sdk:mcp:2.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.bayshier.klinevision.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
