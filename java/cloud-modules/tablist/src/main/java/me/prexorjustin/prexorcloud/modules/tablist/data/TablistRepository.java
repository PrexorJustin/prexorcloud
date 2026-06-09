package me.prexorjustin.prexorcloud.modules.tablist.data;

import java.util.List;
import java.util.Optional;

import me.prexorjustin.prexorcloud.api.module.data.IndexSpec;
import me.prexorjustin.prexorcloud.api.module.data.ModuleDataStore;
import me.prexorjustin.prexorcloud.api.module.data.Query;
import me.prexorjustin.prexorcloud.api.module.data.Sort;
import me.prexorjustin.prexorcloud.api.module.data.Update;

/**
 * Mongo-backed CRUD for {@link TablistTemplate}.
 *
 * <p>Collection {@code mod_tablist_templates} indexed unique on {@code name},
 * non-unique on {@code group} so plugins can query their group's active
 * template by group name in one round-trip.
 */
public final class TablistRepository {

    public static final String TEMPLATES = "templates";

    private final ModuleDataStore store;

    public TablistRepository(ModuleDataStore store) {
        this.store = store;
        ensureSchema();
    }

    private void ensureSchema() {
        store.ensureCollection(TEMPLATES);
        store.createIndex(TEMPLATES, IndexSpec.asc("name").asUnique());
        store.createIndex(TEMPLATES, IndexSpec.asc("group"));
    }

    public TablistTemplate save(TablistTemplate template) {
        store.upsertOne(
                TEMPLATES,
                Query.where("name").eq(template.name()),
                Update.set("name", template.name())
                        .andSet("group", template.group())
                        .andSet("headerLines", template.headerLines())
                        .andSet("footerLines", template.footerLines())
                        .andSet("refreshSeconds", template.refreshSeconds()));
        return template;
    }

    public Optional<TablistTemplate> findByName(String name) {
        return store.findOne(TEMPLATES, Query.where("name").eq(name), TablistTemplate.class);
    }

    public Optional<TablistTemplate> findActiveForGroup(String group) {
        return store.findOne(TEMPLATES, Query.where("group").eq(group), TablistTemplate.class);
    }

    public List<TablistTemplate> all() {
        return store.find(TEMPLATES, Query.all(), Sort.asc("name"), Integer.MAX_VALUE, TablistTemplate.class);
    }

    public boolean delete(String name) {
        return store.deleteMany(TEMPLATES, Query.where("name").eq(name)) > 0;
    }
}
