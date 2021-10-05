package org.bf2.cos.fleetshard.operator.support;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;

public class MetricsRecorder {
    private final MeterRegistry registry;
    private final String id;
    private final List<Tag> tags;

    private MetricsRecorder(MeterRegistry registry, String id, List<Tag> tags) {
        this.registry = registry;
        this.id = id;
        this.tags = tags;
    }

    public void record(Runnable action) {
        try {
            Timer.builder(id + ".time")
                .tags(tags)
                .publishPercentiles(0.3, 0.5, 0.95)
                .publishPercentileHistogram()
                .register(registry)
                .record(action);

            Counter.builder(id + ".count")
                .tags(tags)
                .register(registry)
                .increment();

        } catch (Exception e) {
            Counter.builder(id + ".count.failure")
                .tags(tags)
                .tag("exception", e.getClass().getName())
                .register(registry)
                .increment();

            throw new RuntimeException("Failure recording method execution (id: " + id + ")", e);
        }
    }

    public <T> T recordCallable(Callable<T> action) {
        try {
            var answer = Timer.builder(id + ".time")
                .tags(tags)
                .publishPercentiles(0.3, 0.5, 0.95)
                .publishPercentileHistogram()
                .register(registry)
                .recordCallable(action);

            Counter.builder(id + ".count")
                .tags(tags)
                .register(registry)
                .increment();

            return answer;
        } catch (Exception e) {
            Counter.builder(id + ".count.failure")
                .tags(tags)
                .tag("exception", e.getClass().getName())
                .register(registry)
                .increment();

            throw new RuntimeException("Failure recording method execution (id: " + id + ")", e);
        }
    }

    public static MetricsRecorder of(MeterRegistry registry, String id) {
        return new MetricsRecorder(registry, id, Collections.emptyList());
    }

    public static MetricsRecorder of(MeterRegistry registry, String id, List<Tag> tags) {
        return new MetricsRecorder(registry, id, tags);
    }
}