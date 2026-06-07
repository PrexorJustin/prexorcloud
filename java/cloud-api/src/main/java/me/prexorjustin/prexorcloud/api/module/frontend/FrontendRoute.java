package me.prexorjustin.prexorcloud.api.module.frontend;

/**
 * A route the module registers in the dashboard.
 */
public record FrontendRoute(
        String path,
        String component,
        String title,
        String icon,
        boolean nav,
        String navGroup,
        Integer navGroupOrder,
        boolean adminOnly) {

    public FrontendRoute {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("Route path is required");
        if (component == null || component.isBlank()) throw new IllegalArgumentException("Route component is required");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("Route title is required");
    }
}
