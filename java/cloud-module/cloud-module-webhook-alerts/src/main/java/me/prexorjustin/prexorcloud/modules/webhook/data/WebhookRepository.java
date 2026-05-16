package me.prexorjustin.prexorcloud.modules.webhook.data;

import java.util.List;

import me.prexorjustin.prexorcloud.api.module.data.ModuleDataStore;
import me.prexorjustin.prexorcloud.api.module.data.Query;
import me.prexorjustin.prexorcloud.api.module.data.Sort;

public final class WebhookRepository {

    private static final String COLLECTION = "webhooks";

    private final ModuleDataStore store;

    public WebhookRepository(ModuleDataStore store) {
        this.store = store;
        store.ensureCollection(COLLECTION);
    }

    public List<WebhookConfig> findAll() {
        return store.find(COLLECTION, Query.all(), Sort.asc("url"), 1000, WebhookConfig.class);
    }

    public String save(WebhookConfig webhook) {
        return store.insertOne(COLLECTION, webhook);
    }

    public boolean deleteByUrl(String url) {
        return store.deleteOne(COLLECTION, Query.where("url").eq(url));
    }
}
