package io.quarkus.gradle.tasks;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import io.quarkus.gradle.tooling.dependency.DependencyDataCollector;
import io.quarkus.maven.dependency.ArtifactKey;

final class DeclaredDependencyMapObjectIO {

    private DeclaredDependencyMapObjectIO() {
    }

    static void write(Path file, Map<ArtifactKey, DependencyDataCollector.DeclaredDepsResult> declaredDependencies)
            throws IOException {
        Files.createDirectories(file.getParent());
        try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(file))) {
            out.writeObject(declaredDependencies);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<ArtifactKey, DependencyDataCollector.DeclaredDepsResult> read(Path file) throws IOException {
        if (!Files.exists(file)) {
            return Map.of();
        }
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(file))) {
            return (Map<ArtifactKey, DependencyDataCollector.DeclaredDepsResult>) in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException("Failed to deserialize declared dependencies map from " + file, e);
        }
    }
}
