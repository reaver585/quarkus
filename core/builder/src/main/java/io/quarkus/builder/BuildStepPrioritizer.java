package io.quarkus.builder;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Ensures a chosen build item is produced early in the Quarkus build by making its producing step
 * a dependency of every other step that does not already depend on it (directly or transitively).
 * This is useful for items that need to be available for most build steps (for example logging setup).
 */
public final class BuildStepPrioritizer {

    /**
     * Default build item to prioritize when the caller wants logging available early.
     */
    public static final String LOGGING_SETUP_BUILD_ITEM_CLASS = "io.quarkus.deployment.logging.LoggingSetupBuildItem";

    private BuildStepPrioritizer() {
        // Utility class - prevent instantiation
    }

    /**
     * Prioritizes the given build item by making its producing step a dependency of all other build
     * steps that are not already in that producing step's dependency chain.
     *
     * @param buildItemClassName the fully qualified name of the build item to prioritize
     * @param includedSteps the set of build steps included in the build chain
     * @param dependencies a map from each build step to the set of {@link Produce} items it depends on
     */
    static void prioritize(
            String buildItemClassName,
            Set<BuildStepBuilder> includedSteps,
            Map<BuildStepBuilder, Set<Produce>> dependencies) {

        ItemInfo itemInfo = findItemInfo(includedSteps, buildItemClassName);
        if (itemInfo == null) {
            // item info not found; nothing to prioritize
            return;
        }

        Set<BuildStepBuilder> itemDependencyChain = collectTransitiveDependencies(
                itemInfo.producingStepBuilder(), dependencies);

        addItemAsDependencyToOtherSteps(
                includedSteps,
                itemDependencyChain,
                itemInfo.produce(),
                dependencies);
    }

    /**
     * Locates the prioritized build step and its corresponding {@link Produce} for the requested build item.
     *
     * @return the build item step info, or {@code null} if the step was not found
     */
    private static ItemInfo findItemInfo(Set<BuildStepBuilder> includedSteps,
            String buildItemClassName) {
        for (BuildStepBuilder stepBuilder : includedSteps) {
            for (ItemId itemId : stepBuilder.getProduces().keySet()) {
                if (buildItemClassName.equals(itemId.getType().getName())) {
                    if (itemId.isMulti()) {
                        // not supported now for multi build items
                        return null;
                    }
                    Produce produce = stepBuilder.getProduces().get(itemId);
                    return new ItemInfo(stepBuilder, produce);
                }
            }
        }

        return null;
    }

    /**
     * Collects all transitive dependencies of a build step using a breadth-first traversal.
     * <p>
     * This includes the step itself and all steps that it directly or indirectly depends on.
     * </p>
     *
     * @param rootStep the starting build step
     * @param dependencies the dependency map for all steps
     * @return the set of all steps in the transitive dependency chain (including the root step)
     */
    private static Set<BuildStepBuilder> collectTransitiveDependencies(BuildStepBuilder rootStep,
            Map<BuildStepBuilder, Set<Produce>> dependencies) {

        Set<BuildStepBuilder> visited = new HashSet<>();
        Deque<BuildStepBuilder> queue = new ArrayDeque<>();
        queue.add(rootStep);

        while (!queue.isEmpty()) {
            BuildStepBuilder current = queue.poll();
            if (visited.add(current)) {
                Set<Produce> stepDependencies = dependencies.get(current);
                if (stepDependencies != null) {
                    for (Produce dependency : stepDependencies) {
                        queue.add(dependency.getStepBuilder());
                    }
                }
            }
        }

        return visited;
    }

    /**
     * Adds the prioritized build step as a dependency to all build steps that are not part of
     * its own dependency chain.
     */
    private static void addItemAsDependencyToOtherSteps(Set<BuildStepBuilder> includedSteps,
            Set<BuildStepBuilder> dependencyChain,
            Produce produce,
            Map<BuildStepBuilder, Set<Produce>> dependencies) {

        for (BuildStepBuilder stepBuilder : includedSteps) {
            if (!dependencyChain.contains(stepBuilder)) {
                // Register that this step should run after the prioritized item is produced
                stepBuilder.afterProduce(produce.getItemId().getType());

                // Add the prioritized produce to this step's dependencies
                Set<Produce> stepDependencies = dependencies.computeIfAbsent(stepBuilder, k -> new LinkedHashSet<>());
                stepDependencies.add(produce);
            }
        }
    }

    /**
     * Holds information about the prioritized build step.
     */
    private record ItemInfo(BuildStepBuilder producingStepBuilder, Produce produce) {
    }
}
