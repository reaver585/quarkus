package io.quarkus.gradle.metadata;

import java.io.Serializable;
import java.util.List;

public record ProjectMetadata(
        String projectPath,
        String group,
        String name,
        String version,
        String projectDir,
        String buildDir,
        String buildFile,
        List<SourceSetMetadata> sourceSets) implements Serializable {
}
