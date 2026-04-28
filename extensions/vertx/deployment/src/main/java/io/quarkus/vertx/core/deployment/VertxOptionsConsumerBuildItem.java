package io.quarkus.vertx.core.deployment;

import java.util.function.Consumer;

import io.quarkus.builder.item.MultiBuildItem;
import io.vertx.core.VertxOptions;

/**
 * Provide a consumer of VertxOptions to allow customization of
 * Vert.x system behavior, e.g. setting MetricsOptions to enable
 * and configure a metrics provider.
 * <p>
 * Consumers will be called in priority order (lowest to highest)
 * after VertxConfiguration has been read and applied.
 */
public final class VertxOptionsConsumerBuildItem extends MultiBuildItem implements Comparable<VertxOptionsConsumerBuildItem> {
    private final Consumer<VertxOptions> optionsConsumer;
    private final int priority;
    private final String deterministicOrderKey;

    public VertxOptionsConsumerBuildItem(Consumer<VertxOptions> optionsConsumer, int priority) {
        this(optionsConsumer, priority, optionsConsumer.getClass().getName());
    }

    public VertxOptionsConsumerBuildItem(Consumer<VertxOptions> optionsConsumer, int priority, String deterministicOrderKey) {
        this.optionsConsumer = optionsConsumer;
        this.priority = priority;
        this.deterministicOrderKey = deterministicOrderKey;
    }

    public Consumer<VertxOptions> getConsumer() {
        return optionsConsumer;
    }

    @Override
    public int compareTo(VertxOptionsConsumerBuildItem o) {
        int priorityResult = Integer.compare(this.priority, o.priority);
        if (priorityResult != 0) {
            return priorityResult;
        }
        return this.deterministicOrderKey.compareTo(o.deterministicOrderKey);
    }
}
