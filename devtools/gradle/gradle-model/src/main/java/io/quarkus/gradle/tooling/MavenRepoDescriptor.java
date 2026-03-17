package io.quarkus.gradle.tooling;

import java.io.Serial;
import java.io.Serializable;

public record MavenRepoDescriptor(String id, String url) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
