package me.prexorjustin.prexorcloud.modules.runtime;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import me.prexorjustin.prexorcloud.api.module.rest.RouteHandler;
import me.prexorjustin.prexorcloud.api.module.rest.RouteRegistrar;

/**
 * Per-module REST registry. Each loaded {@code PlatformModule} gets a
 * {@link RouteRegistrar} view tied to its moduleId; on dispatch the registry
 * walks the recorded templates and returns the first match for the
 * {@code (moduleId, method, subpath)} triple. The controller's
 * {@code RestServer} mounts a single wildcard handler per HTTP method at
 * {@code /api/v1/modules/{moduleId}/<sub>} that delegates here, so a module's
 * routes follow its install/upgrade/uninstall lifecycle without ever touching
 * Javalin's route table at runtime (Javalin doesn't gracefully unmount routes
 * after start).
 */
public final class ModuleRouteRegistry {

    /** Single recorded route. {@code template} is the in-module path with {@code {param}} placeholders. */
    public record RegisteredRoute(String httpMethod, String template, RouteHandler handler) {}

    public record DispatchMatch(RegisteredRoute route, Map<String, String> pathParams) {}

    /** Lightweight callback the lifecycle manager talks to. Tests get a no-op via {@link #NOOP_HOOK}. */
    public interface Hook {

        RouteRegistrar registrarFor(String moduleId);

        void clearRoutes(String moduleId);
    }

    public static final Hook NOOP_HOOK = new Hook() {
        @Override
        public RouteRegistrar registrarFor(String moduleId) {
            return new NoopRegistrar();
        }

        @Override
        public void clearRoutes(String moduleId) {}
    };

    private final Map<String, List<RegisteredRoute>> routesByModuleId = new LinkedHashMap<>();

    public synchronized RouteRegistrar registrarFor(String moduleId) {
        Objects.requireNonNull(moduleId, "moduleId");
        return new RecordingRegistrar(moduleId);
    }

    public synchronized void clearRoutes(String moduleId) {
        Objects.requireNonNull(moduleId, "moduleId");
        routesByModuleId.remove(moduleId);
    }

    public synchronized Set<String> moduleIds() {
        return new LinkedHashSet<>(routesByModuleId.keySet());
    }

    public synchronized Optional<DispatchMatch> resolve(String moduleId, String httpMethod, String subpath) {
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(httpMethod, "httpMethod");
        Objects.requireNonNull(subpath, "subpath");
        List<RegisteredRoute> routes = routesByModuleId.get(moduleId);
        if (routes == null || routes.isEmpty()) {
            return Optional.empty();
        }
        String[] requestSegments = splitPath(subpath);
        String normalizedMethod = httpMethod.toUpperCase(Locale.ROOT);
        for (RegisteredRoute route : routes) {
            if (!route.httpMethod().equals(normalizedMethod)) {
                continue;
            }
            Map<String, String> params = matchTemplate(route.template(), requestSegments);
            if (params != null) {
                return Optional.of(new DispatchMatch(route, params));
            }
        }
        return Optional.empty();
    }

    public Hook asHook() {
        return new Hook() {
            @Override
            public RouteRegistrar registrarFor(String moduleId) {
                return ModuleRouteRegistry.this.registrarFor(moduleId);
            }

            @Override
            public void clearRoutes(String moduleId) {
                ModuleRouteRegistry.this.clearRoutes(moduleId);
            }
        };
    }

    private synchronized void record(String moduleId, String httpMethod, String template, RouteHandler handler) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(handler, "handler");
        if (template.isBlank()) {
            throw new IllegalArgumentException("route template must not be blank");
        }
        if (template.contains("?") || template.contains("#")) {
            throw new IllegalArgumentException("route template must not contain '?' or '#': " + template);
        }
        String normalizedTemplate = template.startsWith("/") ? template : "/" + template;
        routesByModuleId
                .computeIfAbsent(moduleId, ignored -> new java.util.ArrayList<>())
                .add(new RegisteredRoute(httpMethod.toUpperCase(Locale.ROOT), normalizedTemplate, handler));
    }

    /** Returns captured path params, or {@code null} when the segments don't match the template. */
    private static Map<String, String> matchTemplate(String template, String[] requestSegments) {
        String[] templateSegments = splitPath(template);
        if (templateSegments.length != requestSegments.length) {
            return null;
        }
        Map<String, String> captured = new HashMap<>();
        for (int i = 0; i < templateSegments.length; i++) {
            String t = templateSegments[i];
            String r = requestSegments[i];
            if (t.length() >= 2 && t.charAt(0) == '{' && t.charAt(t.length() - 1) == '}') {
                captured.put(t.substring(1, t.length() - 1), r);
                continue;
            }
            if (!t.equals(r)) {
                return null;
            }
        }
        return captured;
    }

    private static String[] splitPath(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return new String[0];
        }
        String trimmed = path;
        if (trimmed.charAt(0) == '/') {
            trimmed = trimmed.substring(1);
        }
        if (!trimmed.isEmpty() && trimmed.charAt(trimmed.length() - 1) == '/') {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) {
            return new String[0];
        }
        return trimmed.split("/", -1);
    }

    /** Records routes for one module. */
    private final class RecordingRegistrar implements RouteRegistrar {

        private final String moduleId;

        private RecordingRegistrar(String moduleId) {
            this.moduleId = moduleId;
        }

        @Override
        public void get(String path, RouteHandler handler) {
            record(moduleId, "GET", path, handler);
        }

        @Override
        public void post(String path, RouteHandler handler) {
            record(moduleId, "POST", path, handler);
        }

        @Override
        public void put(String path, RouteHandler handler) {
            record(moduleId, "PUT", path, handler);
        }

        @Override
        public void delete(String path, RouteHandler handler) {
            record(moduleId, "DELETE", path, handler);
        }

        @Override
        public void patch(String path, RouteHandler handler) {
            record(moduleId, "PATCH", path, handler);
        }
    }

    private static final class NoopRegistrar implements RouteRegistrar {
        @Override
        public void get(String path, RouteHandler handler) {}

        @Override
        public void post(String path, RouteHandler handler) {}

        @Override
        public void put(String path, RouteHandler handler) {}

        @Override
        public void delete(String path, RouteHandler handler) {}

        @Override
        public void patch(String path, RouteHandler handler) {}
    }
}
