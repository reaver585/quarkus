plugins {
    id("io.quarkus.devtools.java-library")
}

dependencies {
    compileOnly(libs.kotlin.gradle.plugin.api)
    implementation("org.apache.maven:maven-core")
    implementation(libs.maven.resolver.connector.basic)
    implementation(libs.maven.resolver.transport.wagon)
    implementation(libs.wagon.http)
    implementation(libs.wagon.file)
    gradleApi()
}

group = "io.quarkus"

java {
    withSourcesJar()
    withJavadocJar()
}

// to generate reproducible jars
tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

publishing {
    publications.create<MavenPublication>("maven") {
        artifactId = "quarkus-gradle-model"
        from(components["java"])
    }
}
