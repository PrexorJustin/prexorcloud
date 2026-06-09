package me.prexorjustin.prexorcloud.modules.discord.data;

import java.util.List;

import me.prexorjustin.prexorcloud.api.module.data.ModuleDataStore;
import me.prexorjustin.prexorcloud.api.module.data.Query;
import me.prexorjustin.prexorcloud.api.module.data.Sort;

public final class DiscordTargetRepository {

    private static final String COLLECTION = "discord_targets";

    private final ModuleDataStore store;

    public DiscordTargetRepository(ModuleDataStore store) {
        this.store = store;
        store.ensureCollection(COLLECTION);
    }

    public List<DiscordTarget> findAll() {
        return store.find(COLLECTION, Query.all(), Sort.asc("url"), 1000, DiscordTarget.class);
    }

    public String save(DiscordTarget target) {
        return store.insertOne(COLLECTION, target);
    }

    public boolean deleteByUrl(String url) {
        return store.deleteOne(COLLECTION, Query.where("url").eq(url));
    }
}
