plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
    kotlin("plugin.jpa") version "2.2.21"
    kotlin("plugin.noarg") version "2.2.21"
    id("org.springframework.boot") version "4.0.3"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.graalvm.buildtools.native") version "0.11.3"
}

group = "malibu.llm"
description = "prompt-box"

//extra["springAiVersion"] = "2.0.0-M3"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

allprojects {
    repositories {
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/adrenalinee/tracer")
            credentials {
                username = project.findProperty("gpr.user") as String? ?: System.getenv("USERNAME")
                password = project.findProperty("gpr.key") as String? ?: System.getenv("TOKEN")
            }
        }
    }
}

subprojects {
    apply {
        plugin("kotlin")
        plugin("io.spring.dependency-management")
        plugin("org.jetbrains.kotlin.plugin.spring")
    }
}

dependencies {
    implementation(project(":llm-stream-client"))

    implementation("org.springframework.boot:spring-boot-starter-data-rest")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    implementation("org.springframework:spring-webflux")
//    implementation("org.springframework.boot:spring-boot-starter-webclient")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
implementation("org.jetbrains.kotlin:kotlin-reflect")
//    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
//    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("io.projectreactor:reactor-core")
    implementation("tools.jackson.module:jackson-module-kotlin")

    implementation("malibu.tracer:tracer-webmvc:14.0")
    implementation("io.github.microutils:kotlin-logging:3.0.5")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.5")
    implementation("com.google.cloud.sql:postgres-socket-factory:1.28.0")
    implementation("com.openai:openai-java:4.26.0")
//    implementation("com.google.genai:google-genai:1.36.0")
//    implementation("dev.langchain4j:langchain4j-google-ai-gemini:1.11.0")
//    implementation("dev.langchain4j:langchain4j-reactor:1.11.0-beta19")


    compileOnly("org.springframework:spring-test")

    developmentOnly("org.springframework.boot:spring-boot-devtools")

    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.liquibase:liquibase-core")

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-rest-test")
    testImplementation("org.springframework.boot:spring-boot-starter-restclient-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")


    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")

    testImplementation("io.mockk:mockk:1.14.6")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.postgresql:postgresql")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("spring.test.constructor.autowire", "all")
    systemProperty("spring.test.constructor.autowire.mode", "all")
}
