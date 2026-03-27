package io.quarkus.gradle.metadata;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.initialization.Settings;
import org.gradle.api.tasks.SourceSetContainer;

public class ProjectMetadataPlugin implements Plugin<Settings> {

    public static final String METADATA_ELEMENTS_CONFIGURATION_NAME = "quarkusProjectMetadataElements";
    public static final String GENERATE_TASK_NAME = "generateQuarkusProjectMetadata";

    public static final Attribute<String> QUARKUS_METADATA_ATTRIBUTE = Attribute.of("io.quarkus.project.metadata",
            String.class);
    public static final String QUARKUS_METADATA_ATTRIBUTE_VALUE = "project-metadata";

    @Override
    public void apply(Settings settings) {
        settings.getGradle().getLifecycle().afterProject(this::configureProject);
    }

    private void configureProject(Project project) {
        System.out.println("[DEBUG] ProjectMetadataPlugin.configureProject / project path is " + project.getPath());

        if (!project.getPlugins().hasPlugin("java")) {
            System.out.println("[DEBUG] Project %s does not have 'java' plugin".formatted(project.getPath()));
            return;
        }

        project.getDependencies().getAttributesSchema().attribute(QUARKUS_METADATA_ATTRIBUTE);
        ProjectMetadata metadata = collectMetadata(project);

        var generateTask = project.getTasks().register(
                GENERATE_TASK_NAME,
                GenerateProjectMetadata.class,
                task -> {
                    task.getMetadata().set(project.provider(() -> collectMetadata(project)));
                    task.getOutputFile().set(
                            project.getLayout().getBuildDirectory()
                                    .file("quarkus-project-metadata/metadata.json"));
                });

        Configuration metadataElements = project.getConfigurations().create(METADATA_ELEMENTS_CONFIGURATION_NAME,
                conf -> {
                    conf.setCanBeConsumed(true);
                    conf.setCanBeResolved(false);
                    conf.setDescription("Quarkus project metadata elements");
                    conf.attributes(attrs -> attrs.attribute(QUARKUS_METADATA_ATTRIBUTE,
                            QUARKUS_METADATA_ATTRIBUTE_VALUE));
                });

        project.getArtifacts().add(
                metadataElements.getName(),
                generateTask.flatMap(GenerateProjectMetadata::getOutputFile));
    }

    private ProjectMetadata collectMetadata(Project project) {
        SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);

        List<SourceSetMetadata> sourceSetList = new ArrayList<>();
        for (var sourceSet : sourceSets) {
            List<String> javaSrcDirs = sourceSet.getAllJava().getSrcDirs().stream()
                    .map(File::getAbsolutePath)
                    .toList();

            List<String> classesOutputDirs = sourceSet.getOutput().getClassesDirs().getFiles().stream()
                    .map(File::getAbsolutePath)
                    .toList();

            List<String> resourcesSrcDirs = sourceSet.getResources().getSrcDirs().stream()
                    .map(File::getAbsolutePath)
                    .toList();

            String resourcesOutputDir = sourceSet.getOutput().getResourcesDir() != null
                    ? sourceSet.getOutput().getResourcesDir().getAbsolutePath()
                    : null;

            sourceSetList.add(new SourceSetMetadata(
                    sourceSet.getName(),
                    javaSrcDirs,
                    classesOutputDirs,
                    resourcesSrcDirs,
                    resourcesOutputDir));
        }

        return new ProjectMetadata(
                project.getPath(),
                String.valueOf(project.getGroup()),
                project.getName(),
                String.valueOf(project.getVersion()),
                project.getLayout().getProjectDirectory().getAsFile().getAbsolutePath(),
                project.getLayout().getBuildDirectory().get().getAsFile().getAbsolutePath(),
                project.getBuildFile().getAbsolutePath(),
                sourceSetList);
    }
}
