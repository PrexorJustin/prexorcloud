package me.prexorjustin.prexorcloud.controller.state;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link ScaleActionStore} for tests -- stands in for the Mongo-backed store. */
public final class InMemoryScaleActionStore implements ScaleActionStore {

    private final Map<String, Instant> actions = new ConcurrentHashMap<>();

    @Override
    public void recordScaleAction(String group, Instant when) {
        actions.put(group, when);
    }

    @Override
    public Optional<Instant> getLastScaleAction(String group) {
        return Optional.ofNullable(actions.get(group));
    }
}
