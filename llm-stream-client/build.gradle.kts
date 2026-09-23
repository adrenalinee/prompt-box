import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    kotlin("plugin.spring")
    `maven-publish`
}

group = "malibu"
version = "3.7-SNAPSHOT"

dependencyManagement {
    imports {
        mavenBom(SpringBootPlugin.BOM_COORDINATES)
//        mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
    }
}

dependencies {
    api("tools.jackson.core:jackson-databind")
    api("tools.jackson.module:jackson-module-kotlin")
    api("io.projectreactor:reactor-core")
    api("org.jetbrains.kotlin:kotlin-reflect")

    api("io.swagger.core.v3:swagger-annotations:2.2.45")
    api("io.github.microutils:kotlin-logging:3.0.5")


    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-webflux")

    implementation("com.openai:openai-java:4.36.0")
    implementation("com.google.genai:google-genai:1.58.0")

    testImplementation("io.mockk:mockk:1.14.6")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

java {
    withSourcesJar()
}

configure<PublishingExtension> {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/mystix-ai/maven-artifacts")
            credentials {
                username = project.findProperty("gpr.user") as String? ?: System.getenv("USERNAME")
                password = project.findProperty("gpr.key") as String? ?: System.getenv("TOKEN")
            }
        }
    }

    publications {
        register<MavenPublication>("gpr") {
            from(components["java"])
        }
    }
}
