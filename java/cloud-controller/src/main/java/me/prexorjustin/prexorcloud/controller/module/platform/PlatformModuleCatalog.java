package me.prexorjustin.prexorcloud.controller.module.platform;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import me.prexorjustin.prexorcloud.api.module.platform.PlatformModuleManifest;
import me.prexorjustin.prexorcloud.modules.runtime.PlatformModuleManifestException;
import me.prexorjustin.prexorcloud.modules.runtime.PlatformModuleManifestParser;

/**
 * Discovers platform-module manifests from controller-managed module jars.
 */
public final class PlatformModuleCatalog {

    public record DiscoveredModule(Path jarPath, PlatformModuleManifest manifest) {
        public String jarFileName() {
            return jarPath.getFileName().toString();
        }
    }

    public record DiscoveryFailure(Path jarPath, String message) {
        public String jarFileName() {
            return jarPath.getFileName().toString();
        }
    }

    public record ScanResult(List<DiscoveredModule> modules, List<DiscoveryFailure> failures) {
        public ScanResult {
            modules = modules == null ? List.of() : List.copyOf(modules);
            failures = failures == null ? List.of() : List.copyOf(failures);
        }

        public ExtensionRegistry extensionRegistry() {
            return new ExtensionRegistry(
                    modules.stream().map(DiscoveredModule::manifest).toList());
        }
    }

    public ScanResult scan(Path modulesDirectory) {
        List<DiscoveredModule> modules = new ArrayList<>();
        List<DiscoveryFailure> failures = new ArrayList<>();

        if (modulesDirectory == null || !Files.isDirectory(modulesDirectory)) {
            return new ScanResult(modules, failures);
        }

        try (Stream<Path> stream = Files.list(modulesDirectory)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(path -> scanJar(path, modules, failures));
        } catch (IOException e) {
            failures.add(new DiscoveryFailure(modulesDirectory, "failed to scan modules directory: " + e.getMessage()));
        }

        return new ScanResult(modules, failures);
    }

    private static void scanJar(Path jarPath, List<DiscoveredModule> modules, List<DiscoveryFailure> failures) {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            var manifestEntry = jarFile.getJarEntry(PlatformModuleManifestParser.FILE_NAME);
            if (manifestEntry == null) {
                return;
            }

            try (InputStream inputStream = jarFile.getInputStream(manifestEntry)) {
                PlatformModuleManifest manifest = PlatformModuleManifestParser.parse(
                        inputStream, jarPath.getFileName().toString());
                modules.add(new DiscoveredModule(jarPath, manifest));
            }
        } catch (IOException | PlatformModuleManifestException e) {
            failures.add(new DiscoveryFailure(jarPath, e.getMessage()));
        }
    }
}
