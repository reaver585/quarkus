package io.quarkus.gradle.metadata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import groovy.json.JsonOutput;

@CacheableTask
public abstract class GenerateProjectMetadata extends DefaultTask {

    @Input
    public abstract Property<ProjectMetadata> getMetadata();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    public GenerateProjectMetadata() {
        setDescription("Generates Quarkus project metadata");
        setGroup("quarkus");
    }

    @TaskAction
    public void generate() throws IOException {
        var outputFile = getOutputFile().getAsFile().get();
        outputFile.getParentFile().mkdirs();
        var json = JsonOutput.prettyPrint(JsonOutput.toJson(getMetadata().get()));
        Files.writeString(outputFile.toPath(), json, StandardCharsets.UTF_8);
    }
}
