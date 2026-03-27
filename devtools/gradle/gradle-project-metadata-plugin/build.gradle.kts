plugins {
    id("io.quarkus.devtools.gradle-plugin")
}

group = "io.quarkus.project-metadata"

gradlePlugin {
    plugins.create("quarkusProjectMetadataPlugin") {
        id = "io.quarkus.project-metadata"
        implementationClass = "io.quarkus.gradle.metadata.ProjectMetadataPlugin"
        displayName = "Quarkus Project Metadata Plugin"
        description = "A settings plugin that registers project metadata configurations on all projects"
        tags.addAll("quarkus", "quarkusio")
    }
}

// to generate reproducible jars
tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
