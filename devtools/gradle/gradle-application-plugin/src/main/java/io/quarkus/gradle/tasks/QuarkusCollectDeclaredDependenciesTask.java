package io.quarkus.gradle.tasks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.maven.model.Model;
import org.apache.maven.model.building.DefaultModelBuilderFactory;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import io.quarkus.gradle.tooling.ExecPhaseMavenModelResolverImpl;
import io.quarkus.gradle.tooling.MavenRepoDescriptor;
import io.quarkus.gradle.tooling.dependency.DependencyDataCollector;
import io.quarkus.gradle.tooling.taskrunner.MavenModelResolutionTaskRunner;
import io.quarkus.maven.dependency.ArtifactKey;

@CacheableTask
public abstract class QuarkusCollectDeclaredDependenciesTask extends DefaultTask {

    private static final String SCOPE_TEST = "test";

    private final Map<DeclaredDepsCacheKey, DependencyDataCollector.DeclaredDepsResult> declaredDependenciesCache = new ConcurrentHashMap<>();

    @Input
    public abstract ListProperty<DependencyDataCollector.ArtifactKeyWithVersion> getExternalModuleComponents();

    @Input
    public abstract ListProperty<MavenRepoDescriptor> getRepositoryDescriptors();

    @Input
    public abstract Property<String> getLocalRepositoryPath();

    @Internal
    public abstract MapProperty<ArtifactKey, DependencyDataCollector.DeclaredDepsResult> getPrecomputedGradleProjectDeps();

    @Input
    public abstract ListProperty<String> getPrecomputedGradleProjectDepsSnapshot();

    @Internal
    public abstract MapProperty<ArtifactKey, DependencyDataCollector.DeclaredDepsResult> getCollectedDeclaredDependencies();

    @OutputFile
    public abstract RegularFileProperty getCollectedDeclaredDependenciesFile();

    @TaskAction
    public void execute() throws java.io.IOException {
        final Map<ArtifactKey, DependencyDataCollector.DeclaredDepsResult> collected = new LinkedHashMap<>(
                getPrecomputedGradleProjectDeps().get());

        var repos = getRepositoryDescriptors().get();
        var modelResolver = new ExecPhaseMavenModelResolverImpl(repos, getLocalRepositoryPath().get());
        var modelBuilder = new DefaultModelBuilderFactory().newInstance();

        Map<ArtifactKey, DependencyDataCollector.DeclaredDepsResult> externalModuleDeps = collectDeclaredDependencies(
                getExternalModuleComponents().get(), modelResolver, modelBuilder);
        collected.putAll(externalModuleDeps);
        getCollectedDeclaredDependencies().set(collected);
        var out = getCollectedDeclaredDependenciesFile().get().getAsFile().toPath();
        DeclaredDependencyMapObjectIO.write(out, collected);
        getLogger().info("Collected declared dependencies map written to {}", out);
    }

    private Map<ArtifactKey, DependencyDataCollector.DeclaredDepsResult> collectDeclaredDependencies(
            List<DependencyDataCollector.ArtifactKeyWithVersion> moduleKeys,
            ModelResolver modelResolver,
            ModelBuilder modelBuilder) {

        var startTime = System.currentTimeMillis();
        MavenModelResolutionTaskRunner taskRunner = new MavenModelResolutionTaskRunner((task, error) -> getLogger()
                .error("Error during declared dependencies collection task execution: {}", error.getMessage(), error));
        Map<ArtifactKey, DependencyDataCollector.DeclaredDepsResult> result = new ConcurrentHashMap<>();
        for (DependencyDataCollector.ArtifactKeyWithVersion moduleKey : moduleKeys) {
            taskRunner.run(() -> collectDeclaredFromModule(
                    modelResolver, modelBuilder, moduleKey, result));
        }
        taskRunner.waitForCompletion();
        getLogger().info("Declared dependencies collection for took {} ms",
                System.currentTimeMillis() - startTime);
        return result;
    }

    private void collectDeclaredFromModule(
            ModelResolver modelResolver,
            ModelBuilder modelBuilder,
            DependencyDataCollector.ArtifactKeyWithVersion moduleKeyWithVersion,
            Map<ArtifactKey, DependencyDataCollector.DeclaredDepsResult> resultMap) {
        var moduleKey = moduleKeyWithVersion.artifactKey();
        var version = moduleKeyWithVersion.version();
        DependencyDataCollector.DeclaredDepsResult result = declaredDependenciesCache.computeIfAbsent(
                new DeclaredDepsCacheKey(moduleKey, false),
                key -> {
                    try {
                        var startTime = System.currentTimeMillis();
                        var modelSource = modelResolver.resolveModel(moduleKey.getGroupId(),
                                moduleKey.getArtifactId(),
                                version);
                        System.out
                                .println("resolve for %s took %s".formatted(moduleKey, System.currentTimeMillis() - startTime));
                        var request = new DefaultModelBuildingRequest();
                        request.setModelSource(modelSource);
                        request.setModelResolver(modelResolver);
                        request.getSystemProperties().putAll(System.getProperties());
                        request.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);
                        Model effectiveModel = modelBuilder.build(request).getEffectiveModel();
                        System.out.println(
                                "model build for %s took %s".formatted(moduleKey, System.currentTimeMillis() - startTime));
                        List<DependencyDataCollector.DeclaredDependency> declaredDeps = toDeclaredDependencies(effectiveModel);
                        return DependencyDataCollector.DeclaredDepsResult.resolved(declaredDeps);
                    } catch (UnresolvableModelException | ModelBuildingException e) {
                        getLogger().warn("Unable to resolve effective model for {}:{}:{}: {}",
                                moduleKey.getGroupId(), moduleKey.getArtifactId(), version, e.getMessage());
                        return DependencyDataCollector.DeclaredDepsResult.unresolved();
                    }
                });
        resultMap.put(moduleKey, result);
    }

    private static List<DependencyDataCollector.DeclaredDependency> toDeclaredDependencies(Model model) {
        final List<DependencyDataCollector.DeclaredDependency> declaredDeps = new ArrayList<>();
        for (org.apache.maven.model.Dependency dep : model.getDependencies()) {
            if (!SCOPE_TEST.equals(dep.getScope())) {
                declaredDeps.add(new DependencyDataCollector.DeclaredDependency(dep));
            }
        }
        return declaredDeps;
    }

    private record DeclaredDepsCacheKey(ArtifactKey artifactKey, boolean includeTestScopes) {
    }

}
