package io.quarkus.gradle.metadata;

import java.io.Serializable;
import java.util.List;

public record SourceSetMetadata(
        String name,
        List<String> javaSrcDirs,
        List<String> classesOutputDirs,
        List<String> resourcesSrcDirs,
        String resourcesOutputDir) implements Serializable {
}
