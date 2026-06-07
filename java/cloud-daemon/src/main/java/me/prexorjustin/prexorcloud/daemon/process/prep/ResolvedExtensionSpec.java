package me.prexorjustin.prexorcloud.daemon.process.prep;

/**
 * One extension artefact (module-scoped JAR) the daemon has to download
 * + install for an instance start. Distilled from
 * {@code StartInstance.composition_plan.extensions} by
 * {@code ProcessManager#resolveSpec}.
 *
 * <p>The triple {@code (moduleId, extensionId, variantId)} is the cache
 * key — see {@code daemon/template/ArtifactCache}.
 *
 * @param moduleId    owning module's id, e.g. {@code matchmaking-module}
 * @param extensionId extension id within the module, e.g. {@code matchmaking-paper}
 * @param variantId   variant chosen for this group's runtime target,
 *                    e.g. {@code paper-1-21}
 * @param fileName    on-disk filename inside the install path
 * @param downloadUrl HTTPS URL of the artefact
 * @param sha256      expected SHA-256 of the downloaded bytes
 * @param installPath relative path under the instance dir where the
 *                    extension lands (typically {@code plugins/} on Paper,
 *                    {@code mods/} on Fabric, etc.)
 */
public record ResolvedExtensionSpec(
        String moduleId,
        String extensionId,
        String variantId,
        String fileName,
        String downloadUrl,
        String sha256,
        String installPath) {}
