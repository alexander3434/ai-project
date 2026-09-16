plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    application
}

group = "com.aiturbo"
version = "1.0.0"

repositories {
    mavenCentral()
}

val ktorVersion = "3.3.3"
val koinVersion = "4.1.0"
val coroutinesVersion = "1.10.2"

dependencies {
    implementation(platform("io.ktor:ktor-bom:$ktorVersion"))

    // Ktor server
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-server-call-logging")
    implementation("io.ktor:ktor-server-status-pages")
    implementation("io.ktor:ktor-serialization-kotlinx-json")

    // Ktor client (HTTP calls to the DeepSeek API)
    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-cio")
    implementation("io.ktor:ktor-client-content-negotiation")

    // Koin — dependency injection
    implementation("io.insert-koin:koin-ktor:$koinVersion")
    implementation("io.insert-koin:koin-core:$koinVersion")

    // Loading the API key from a local git-ignored .env file
    implementation("io.github.cdimascio:dotenv-kotlin:6.5.1")

    // Koog (JetBrains) — AI agents with LLM tools (function calling)
    implementation("ai.koog:koog-agents:1.2.0")

    // PostgreSQL JDBC driver (weather records)
    implementation("org.postgresql:postgresql:42.7.13")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.18")

    // Tests
    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation("io.ktor:ktor-client-mock")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
    testImplementation("io.insert-koin:koin-test:$koinVersion")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.test {
    useJUnitPlatform()
}
