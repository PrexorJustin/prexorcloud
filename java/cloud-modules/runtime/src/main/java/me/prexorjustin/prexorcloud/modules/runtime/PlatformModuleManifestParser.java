package me.prexorjustin.prexorcloud.modules.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import me.prexorjustin.prexorcloud.api.module.SemverRange;
import me.prexorjustin.prexorcloud.api.module.platform.ActivationPolicy;
import me.prexorjustin.prexorcloud.api.module.platform.CapabilityDeclaration;
import me.prexorjustin.prexorcloud.api.module.platform.ModuleHost;
import me.prexorjustin.prexorcloud.api.module.platform.ModuleStorageRequest;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModuleManifest;
import me.prexorjustin.prexorcloud.api.module.platform.RuntimeTarget;
import me.prexorjustin.prexorcloud.api.module.platform.WorkloadExtensionManifest;
import me.prexorjustin.prexorcloud.api.module.platform.WorkloadExtensionVariant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Strict parser for the new YAML-based platform module manifest.
 */
public final class PlatformModuleManifestParser {

    public static final String FILE_NAME = "META-INF/prexor/module.yaml";
    public static final int MIN_FRONTEND_SDK_VERSION = 1;
    public static final int MAX_FRONTEND_SDK_VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory());
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z][a-z0-9-]*$");
    private static final Pattern VERSION_PATTERN = Pattern.compile("^\\d+\\.\\d+\\.\\d+(-[a-zA-Z0-9.-]+)?$");
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[a-fA-F0-9]{64}$");

    private static final Set<String> ROOT_FIELDS = Set.of(
            "manifestVersion",
            "id",
            "version",
            "hosts",
            "backend",
            "frontend",
            "capabilities",
            "storage",
            "extensions");
    private static final Set<String> BACKEND_FIELDS = Set.of("entrypoint", "controller", "daemon");
    private static final Set<String> ENTRYPOINT_SPEC_FIELDS_V1 = Set.of("entrypoint");
    private static final Set<String> ENTRYPOINT_SPEC_FIELDS_V2 = Set.of("entrypoint", "reloadable");
    private static final Set<String> FRONTEND_FIELDS = Set.of("sdkVersion", "entry");
    private static final Set<String> EXTENSION_FIELDS = Set.of("id", "target", "activation", "conflicts", "variants");
    private static final Set<String> CAPABILITIES_FIELDS = Set.of("provides", "requires");
    private static final Set<String> CAPABILITY_PROVIDES_FIELDS_V1 = Set.of("id", "version");
    private static final Set<String> CAPABILITY_PROVIDES_FIELDS_V2 =
            Set.of("id", "version", "deprecatedSince", "removedIn");
    private static final Set<String> CAPABILITY_REQUIRES_FIELDS = Set.of("id", "versionRange");
    private static final Set<String> STORAGE_FIELDS = Set.of("mongo", "limits");
    private static final Set<String> STORAGE_LIMIT_FIELDS = Set.of("mongoDocuments");
    private static final Pattern SEMVER_PATTERN = Pattern.compile("^\\d+\\.\\d+\\.\\d+(?:[-+].*)?$");
    private static final Set<String> VARIANT_FIELDS =
            Set.of("id", "mcVersionRange", "runtimeApiVersion", "artifact", "sha256", "installPath");

    private PlatformModuleManifestParser() {}

    public static PlatformModuleManifest parse(InputStream in, String source) {
        if (in == null) {
            throw new PlatformModuleManifestException(source, FILE_NAME + " not found");
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(in);
        } catch (IOException e) {
            throw new PlatformModuleManifestException(source, "failed to read " + FILE_NAME, e);
        }

        if (root == null || !root.isObject()) {
            throw new PlatformModuleManifestException(source, "manifest root must be an object");
        }

        rejectUnknownFields(root, ROOT_FIELDS, "root", source);

        int manifestVersion =
                optionalInt(root, "manifestVersion").orElse(PlatformModuleManifest.CURRENT_MANIFEST_VERSION);
        if (manifestVersion < PlatformModuleManifest.MIN_MANIFEST_VERSION
                || manifestVersion > PlatformModuleManifest.CURRENT_MANIFEST_VERSION) {
            throw new PlatformModuleManifestException(
                    source,
                    "unsupported manifestVersion "
                            + manifestVersion
                            + " (supported: "
                            + PlatformModuleManifest.MIN_MANIFEST_VERSION
                            + ".."
                            + PlatformModuleManifest.CURRENT_MANIFEST_VERSION
                            + ")");
        }

        String id = requireString(root, "id", source);
        validateId(id, "id", source);

        String version = requireString(root, "version", source);
        if (!VERSION_PATTERN.matcher(version).matches()) {
            throw new PlatformModuleManifestException(source, "'version' is not semver-shaped: " + version);
        }

        List<ModuleHost> hosts = parseHosts(root.get("hosts"), source);
        PlatformModuleManifest.Backend backend = parseBackend(root.get("backend"), hosts, manifestVersion, source);
        PlatformModuleManifest.Frontend frontend = parseFrontend(root.get("frontend"), source);
        CapabilityDeclaration capabilities = parseCapabilities(root.get("capabilities"), manifestVersion, source);
        ModuleStorageRequest storage = parseStorage(root.get("storage"), source);
        List<WorkloadExtensionManifest> extensions = parseExtensions(root.get("extensions"), source);

        return new PlatformModuleManifest(
                manifestVersion, id, version, backend, frontend, capabilities, storage, extensions, hosts);
    }

    private static List<ModuleHost> parseHosts(JsonNode node, String source) {
        if (node == null || node.isNull()) {
            return List.of(ModuleHost.CONTROLLER);
        }
        if (!node.isArray() || node.isEmpty()) {
            throw new PlatformModuleManifestException(source, "'hosts' must be a non-empty array");
        }
        List<ModuleHost> hosts = new ArrayList<>();
        Set<ModuleHost> seen = new HashSet<>();
        for (int i = 0; i < node.size(); i++) {
            JsonNode entry = node.get(i);
            if (!entry.isTextual() || entry.textValue().isBlank()) {
                throw new PlatformModuleManifestException(source, "'hosts[" + i + "]' must be a non-blank string");
            }
            ModuleHost host;
            try {
                host = ModuleHost.valueOf(entry.textValue().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                throw new PlatformModuleManifestException(
                        source, "'hosts[" + i + "]' is not a known host: " + entry.textValue());
            }
            if (!seen.add(host)) {
                throw new PlatformModuleManifestException(
                        source,
                        "'hosts' declares '" + host.name().toLowerCase(java.util.Locale.ROOT) + "' more than once");
            }
            hosts.add(host);
        }
        return List.copyOf(hosts);
    }

    private static PlatformModuleManifest.Backend parseBackend(
            JsonNode node, List<ModuleHost> hosts, int manifestVersion, String source) {
        if (node == null || node.isNull()) {
            throw new PlatformModuleManifestException(source, "'backend' is required");
        }
        if (!node.isObject()) {
            throw new PlatformModuleManifestException(source, "'backend' must be an object");
        }
        rejectUnknownFields(node, BACKEND_FIELDS, "backend", source);

        // Legacy single-string form: backend.entrypoint = "...". Treated as the controller entrypoint.
        if (node.has("entrypoint")) {
            if (node.has("controller") || node.has("daemon")) {
                throw new PlatformModuleManifestException(
                        source, "'backend' cannot mix legacy 'entrypoint' with 'controller'/'daemon' fields");
            }
            String legacyEntrypoint = requireString(node, "entrypoint", source);
            if (!hosts.contains(ModuleHost.CONTROLLER)) {
                throw new PlatformModuleManifestException(
                        source, "'backend.entrypoint' is set but 'hosts' does not include 'controller'");
            }
            return new PlatformModuleManifest.Backend(legacyEntrypoint);
        }

        PlatformModuleManifest.EntrypointSpec controllerSpec =
                parseEntrypointSpec(node.get("controller"), "backend.controller", manifestVersion, source);
        PlatformModuleManifest.EntrypointSpec daemonSpec =
                parseEntrypointSpec(node.get("daemon"), "backend.daemon", manifestVersion, source);

        if (hosts.contains(ModuleHost.CONTROLLER) && controllerSpec == null) {
            throw new PlatformModuleManifestException(
                    source, "'backend.controller.entrypoint' is required when 'hosts' includes 'controller'");
        }
        if (hosts.contains(ModuleHost.DAEMON) && daemonSpec == null) {
            throw new PlatformModuleManifestException(
                    source, "'backend.daemon.entrypoint' is required when 'hosts' includes 'daemon'");
        }
        if (controllerSpec == null && daemonSpec == null) {
            throw new PlatformModuleManifestException(
                    source, "'backend' must declare at least one of 'controller' or 'daemon'");
        }
        return new PlatformModuleManifest.Backend(controllerSpec, daemonSpec);
    }

    private static PlatformModuleManifest.EntrypointSpec parseEntrypointSpec(
            JsonNode node, String path, int manifestVersion, String source) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new PlatformModuleManifestException(source, "'" + path + "' must be an object");
        }
        Set<String> allowed = manifestVersion >= 2 ? ENTRYPOINT_SPEC_FIELDS_V2 : ENTRYPOINT_SPEC_FIELDS_V1;
        rejectUnknownFields(node, allowed, path, source);
        boolean reloadable = optionalBoolean(node, "reloadable", path, source);
        return new PlatformModuleManifest.EntrypointSpec(requireString(node, "entrypoint", source), reloadable);
    }

    private static PlatformModuleManifest.Frontend parseFrontend(JsonNode node, String source) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new PlatformModuleManifestException(source, "'frontend' must be an object");
        }
        rejectUnknownFields(node, FRONTEND_FIELDS, "frontend", source);
        int sdkVersion = requireInt(node, "sdkVersion", source);
        if (sdkVersion < MIN_FRONTEND_SDK_VERSION || sdkVersion > MAX_FRONTEND_SDK_VERSION) {
            throw new PlatformModuleManifestException(
                    source,
                    "'frontend.sdkVersion' "
                            + sdkVersion
                            + " is not supported by this controller (supported: "
                            + MIN_FRONTEND_SDK_VERSION
                            + ".."
                            + MAX_FRONTEND_SDK_VERSION
                            + ")");
        }
        return new PlatformModuleManifest.Frontend(sdkVersion, requireString(node, "entry", source));
    }

    private static List<WorkloadExtensionManifest> parseExtensions(JsonNode node, String source) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new PlatformModuleManifestException(source, "'extensions' must be an array");
        }

        List<WorkloadExtensionManifest> extensions = new ArrayList<>();
        Set<String> seenExtensionIds = new HashSet<>();
        for (int i = 0; i < node.size(); i++) {
            JsonNode extensionNode = node.get(i);
            String where = "extensions[" + i + "]";
            if (!extensionNode.isObject()) {
                throw new PlatformModuleManifestException(source, where + " must be an object");
            }
            rejectUnknownFields(extensionNode, EXTENSION_FIELDS, where, source);

            String id = requireString(extensionNode, "id", source);
            validateId(id, where + ".id", source);
            if (!seenExtensionIds.add(id)) {
                throw new PlatformModuleManifestException(source, "duplicate extension id: " + id);
            }

            RuntimeTarget target = parseRuntimeTarget(requireString(extensionNode, "target", source), where, source);
            ActivationPolicy activationPolicy =
                    ActivationPolicy.fromWireValue(requireString(extensionNode, "activation", source));
            List<String> conflicts = optionalStringArray(extensionNode, "conflicts", source);
            List<WorkloadExtensionVariant> variants = parseVariants(extensionNode.get("variants"), where, source);
            extensions.add(new WorkloadExtensionManifest(id, target, activationPolicy, conflicts, variants));
        }

        return List.copyOf(extensions);
    }

    private static List<WorkloadExtensionVariant> parseVariants(JsonNode node, String path, String source) {
        if (node == null || node.isNull()) {
            throw new PlatformModuleManifestException(source, path + ".variants is required");
        }
        if (!node.isArray() || node.isEmpty()) {
            throw new PlatformModuleManifestException(source, path + ".variants must be a non-empty array");
        }

        List<WorkloadExtensionVariant> variants = new ArrayList<>();
        Set<String> seenVariantIds = new HashSet<>();
        for (int i = 0; i < node.size(); i++) {
            JsonNode variantNode = node.get(i);
            String where = path + ".variants[" + i + "]";
            if (!variantNode.isObject()) {
                throw new PlatformModuleManifestException(source, where + " must be an object");
            }
            rejectUnknownFields(variantNode, VARIANT_FIELDS, where, source);

            String id = requireString(variantNode, "id", source);
            validateId(id, where + ".id", source);
            if (!seenVariantIds.add(id)) {
                throw new PlatformModuleManifestException(source, path + " declares duplicate variant id: " + id);
            }

            String mcVersionRange = requireString(variantNode, "mcVersionRange", source);
            validateRange(mcVersionRange, where + ".mcVersionRange", source);

            int runtimeApiVersion = requireInt(variantNode, "runtimeApiVersion", source);
            if (runtimeApiVersion < 1) {
                throw new PlatformModuleManifestException(source, where + ".runtimeApiVersion must be >= 1");
            }

            String artifact = requireRelativePath(variantNode, "artifact", where, source);
            String sha256 = requireString(variantNode, "sha256", source);
            if (!SHA256_PATTERN.matcher(sha256).matches()) {
                throw new PlatformModuleManifestException(source, where + ".sha256 must be a 64-char hex string");
            }
            String installPath = requireRelativePath(variantNode, "installPath", where, source);

            variants.add(
                    new WorkloadExtensionVariant(id, mcVersionRange, runtimeApiVersion, artifact, sha256, installPath));
        }
        return List.copyOf(variants);
    }

    private static RuntimeTarget parseRuntimeTarget(String value, String where, String source) {
        try {
            return RuntimeTarget.parse(value);
        } catch (IllegalArgumentException e) {
            throw new PlatformModuleManifestException(source, where + ".target: " + e.getMessage());
        }
    }

    private static void validateRange(String value, String field, String source) {
        try {
            SemverRange.parse(value);
        } catch (IllegalArgumentException e) {
            throw new PlatformModuleManifestException(source, field + " is not a valid range: " + e.getMessage());
        }
    }

    private static void validateId(String value, String field, String source) {
        if (!ID_PATTERN.matcher(value).matches()) {
            throw new PlatformModuleManifestException(source, "'" + field + "' must match [a-z][a-z0-9-]*: " + value);
        }
    }

    private static String requireRelativePath(JsonNode node, String field, String path, String source) {
        String value = requireString(node, field, source);
        if (value.startsWith("/") || value.startsWith("\\") || value.contains("..")) {
            throw new PlatformModuleManifestException(
                    source, path + "." + field + " must be a relative path without '..': " + value);
        }
        return value;
    }

    private static void rejectUnknownFields(JsonNode node, Set<String> allowed, String path, String source) {
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            String field = fields.next().getKey();
            if (!allowed.contains(field)) {
                throw new PlatformModuleManifestException(source, path + " contains unknown field '" + field + "'");
            }
        }
    }

    private static String requireString(JsonNode node, String field, String source) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new PlatformModuleManifestException(source, "'" + field + "' is required");
        }
        if (!value.isTextual()) {
            throw new PlatformModuleManifestException(source, "'" + field + "' must be a string");
        }
        String stringValue = value.textValue();
        if (stringValue.isBlank()) {
            throw new PlatformModuleManifestException(source, "'" + field + "' must not be blank");
        }
        return stringValue;
    }

    private static int requireInt(JsonNode node, String field, String source) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new PlatformModuleManifestException(source, "'" + field + "' is required");
        }
        if (!value.isInt() && !value.canConvertToInt()) {
            throw new PlatformModuleManifestException(source, "'" + field + "' must be an integer");
        }
        return value.intValue();
    }

    private static String optionalSemver(JsonNode node, String field, String path, String source) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new PlatformModuleManifestException(source, "'" + path + "." + field + "' must be a string");
        }
        String text = value.textValue();
        if (text.isBlank()) {
            throw new PlatformModuleManifestException(source, "'" + path + "." + field + "' must not be blank");
        }
        if (!SEMVER_PATTERN.matcher(text).matches()) {
            throw new PlatformModuleManifestException(
                    source, "'" + path + "." + field + "' must be semver (x.y.z): " + text);
        }
        return text;
    }

    private static Optional<Integer> optionalInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || (!value.isInt() && !value.canConvertToInt())) {
            return Optional.empty();
        }
        return Optional.of(value.intValue());
    }

    private static CapabilityDeclaration parseCapabilities(JsonNode node, int manifestVersion, String source) {
        if (node == null || node.isNull()) {
            return CapabilityDeclaration.EMPTY;
        }
        if (!node.isObject()) {
            throw new PlatformModuleManifestException(source, "'capabilities' must be an object");
        }
        rejectUnknownFields(node, CAPABILITIES_FIELDS, "capabilities", source);

        Set<String> providesFields =
                manifestVersion >= 2 ? CAPABILITY_PROVIDES_FIELDS_V2 : CAPABILITY_PROVIDES_FIELDS_V1;

        List<CapabilityDeclaration.Provides> provides = new ArrayList<>();
        JsonNode providesNode = node.get("provides");
        if (providesNode != null && !providesNode.isNull()) {
            if (!providesNode.isArray()) {
                throw new PlatformModuleManifestException(source, "'capabilities.provides' must be an array");
            }
            int idx = 0;
            for (JsonNode entry : providesNode) {
                String where = "capabilities.provides[" + idx++ + "]";
                if (!entry.isObject()) {
                    throw new PlatformModuleManifestException(source, where + " must be an object");
                }
                rejectUnknownFields(entry, providesFields, where, source);
                String id = requireIdentifier(entry, "id", where, source);
                String version = requireString(entry, "version", source);
                if (!SEMVER_PATTERN.matcher(version).matches()) {
                    throw new PlatformModuleManifestException(
                            source, where + ".version must be semver (x.y.z): " + version);
                }
                String deprecatedSince = optionalSemver(entry, "deprecatedSince", where, source);
                String removedIn = optionalSemver(entry, "removedIn", where, source);
                if (removedIn != null && deprecatedSince == null) {
                    throw new PlatformModuleManifestException(
                            source, where + ".removedIn requires .deprecatedSince to also be set");
                }
                provides.add(new CapabilityDeclaration.Provides(id, version, deprecatedSince, removedIn));
            }
        }

        List<CapabilityDeclaration.Requires> requires = new ArrayList<>();
        JsonNode requiresNode = node.get("requires");
        if (requiresNode != null && !requiresNode.isNull()) {
            if (!requiresNode.isArray()) {
                throw new PlatformModuleManifestException(source, "'capabilities.requires' must be an array");
            }
            int idx = 0;
            for (JsonNode entry : requiresNode) {
                String where = "capabilities.requires[" + idx++ + "]";
                if (!entry.isObject()) {
                    throw new PlatformModuleManifestException(source, where + " must be an object");
                }
                rejectUnknownFields(entry, CAPABILITY_REQUIRES_FIELDS, where, source);
                String id = requireIdentifier(entry, "id", where, source);
                String versionRange = requireString(entry, "versionRange", source);
                try {
                    SemverRange.parse(versionRange);
                } catch (IllegalArgumentException ignored) {
                    throw new PlatformModuleManifestException(
                            source, where + ".versionRange is invalid: " + versionRange);
                }
                requires.add(new CapabilityDeclaration.Requires(id, versionRange));
            }
        }

        rejectDuplicateProvides(provides, source);
        return new CapabilityDeclaration(provides, requires);
    }

    private static void rejectDuplicateProvides(List<CapabilityDeclaration.Provides> provides, String source) {
        Set<String> seen = new java.util.HashSet<>();
        for (var p : provides) {
            if (!seen.add(p.id())) {
                throw new PlatformModuleManifestException(
                        source, "capabilities.provides declares '" + p.id() + "' more than once");
            }
        }
    }

    private static ModuleStorageRequest parseStorage(JsonNode node, String source) {
        if (node == null || node.isNull()) {
            return ModuleStorageRequest.NONE;
        }
        if (!node.isObject()) {
            throw new PlatformModuleManifestException(source, "'storage' must be an object");
        }
        rejectUnknownFields(node, STORAGE_FIELDS, "storage", source);
        boolean mongo = optionalBoolean(node, "mongo", source);
        ModuleStorageRequest.StorageLimits limits = parseStorageLimits(node.get("limits"), source);
        if (!mongo && limits.hasMongoDocumentLimit()) {
            throw new PlatformModuleManifestException(
                    source, "'storage.limits.mongoDocuments' requires 'storage.mongo: true'");
        }
        return new ModuleStorageRequest(mongo, limits);
    }

    private static ModuleStorageRequest.StorageLimits parseStorageLimits(JsonNode node, String source) {
        if (node == null || node.isNull()) {
            return ModuleStorageRequest.StorageLimits.NONE;
        }
        if (!node.isObject()) {
            throw new PlatformModuleManifestException(source, "'storage.limits' must be an object");
        }
        rejectUnknownFields(node, STORAGE_LIMIT_FIELDS, "storage.limits", source);
        return new ModuleStorageRequest.StorageLimits(
                optionalPositiveLong(node, "mongoDocuments", "storage.limits", source));
    }

    private static boolean optionalBoolean(JsonNode node, String field, String source) {
        return optionalBoolean(node, field, "storage", source);
    }

    private static boolean optionalBoolean(JsonNode node, String field, String path, String source) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return false;
        if (!value.isBoolean()) {
            throw new PlatformModuleManifestException(source, "'" + path + "." + field + "' must be a boolean");
        }
        return value.booleanValue();
    }

    private static long optionalPositiveLong(JsonNode node, String field, String path, String source) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return 0;
        }
        if (!value.canConvertToLong()) {
            throw new PlatformModuleManifestException(source, "'" + path + "." + field + "' must be an integer");
        }
        long longValue = value.longValue();
        if (longValue <= 0) {
            throw new PlatformModuleManifestException(source, "'" + path + "." + field + "' must be > 0");
        }
        return longValue;
    }

    private static String requireIdentifier(JsonNode node, String field, String path, String source) {
        String value = requireString(node, field, source);
        if (!ID_PATTERN.matcher(value).matches()) {
            throw new PlatformModuleManifestException(
                    source, path + "." + field + " must match " + ID_PATTERN.pattern() + ": " + value);
        }
        return value;
    }

    private static List<String> optionalStringArray(JsonNode node, String field, String source) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            throw new PlatformModuleManifestException(source, "'" + field + "' must be an array");
        }
        List<String> out = new ArrayList<>();
        for (JsonNode element : value) {
            if (!element.isTextual() || element.textValue().isBlank()) {
                throw new PlatformModuleManifestException(source, "'" + field + "' entries must be non-blank strings");
            }
            out.add(element.textValue());
        }
        return List.copyOf(out);
    }
}
