package me.prexorjustin.prexorcloud.modules.tablist.rest;

import java.util.Map;

import me.prexorjustin.prexorcloud.api.module.rest.RouteRegistrar;
import me.prexorjustin.prexorcloud.modules.tablist.data.TablistRepository;
import me.prexorjustin.prexorcloud.modules.tablist.data.TablistTemplate;
import me.prexorjustin.prexorcloud.modules.tablist.rest.dto.UpsertTemplateRequest;

/**
 * REST surface for the tablist module.
 *
 * <p>Mounted under {@code /api/v1/modules/tablist/} by the controller's
 * platform-module route registrar.
 *
 * <ul>
 *   <li>{@code GET    /templates}                — list all templates</li>
 *   <li>{@code GET    /templates/{name}}         — single template</li>
 *   <li>{@code PUT    /templates/{name}}         — upsert template</li>
 *   <li>{@code DELETE /templates/{name}}         — delete template</li>
 *   <li>{@code GET    /active?group=X}           — current template for a group
 *       (this is what the in-game plugin polls)</li>
 * </ul>
 */
public final class TablistRoutes {

    private final TablistRepository repo;

    public TablistRoutes(TablistRepository repo) {
        this.repo = repo;
    }

    public void register(RouteRegistrar routes) {
        routes.get("/templates", (req, res) -> res.json(Map.of("templates", repo.all())));

        routes.get("/templates/{name}", (req, res) -> {
            String name = req.pathParam("name");
            var template = repo.findByName(name);
            if (template.isEmpty()) {
                res.status(404).json(Map.of("error", "template not found"));
                return;
            }
            res.json(template.get());
        });

        routes.put("/templates/{name}", UpsertTemplateRequest.class, (req, body, res) -> {
            String name = req.pathParam("name");
            if (body.group() == null || body.group().isBlank()) {
                res.status(400).json(Map.of("error", "group required"));
                return;
            }
            TablistTemplate saved = repo.save(new TablistTemplate(
                    name, body.group(), body.headerLines(), body.footerLines(), body.refreshSeconds()));
            res.status(200).json(saved);
        });

        routes.delete("/templates/{name}", (req, res) -> {
            String name = req.pathParam("name");
            if (!repo.delete(name)) {
                res.status(404).json(Map.of("error", "template not found"));
                return;
            }
            res.status(204);
        });

        // Plugin-side poll endpoint. Kept lightweight: one query, returns 404 if
        // no template is bound to the requested group.
        routes.get("/active", (req, res) -> {
            String group = req.queryParam("group").orElse("");
            if (group.isBlank()) {
                res.status(400).json(Map.of("error", "group query param required"));
                return;
            }
            var active = repo.findActiveForGroup(group);
            if (active.isEmpty()) {
                res.status(404).json(Map.of("error", "no template bound to group"));
                return;
            }
            res.json(active.get());
        });
    }
}
