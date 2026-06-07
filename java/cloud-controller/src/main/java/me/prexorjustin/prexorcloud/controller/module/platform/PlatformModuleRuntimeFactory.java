package me.prexorjustin.prexorcloud.controller.module.platform;

import java.io.Closeable;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import me.prexorjustin.prexorcloud.api.module.platform.PlatformModule;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModuleManifest;

/**
 * Opens a stored platform-module jar into an isolated runtime container.
 */
public interface PlatformModuleRuntimeFactory {

    LoadedRuntime open(PlatformModuleStore.StoredModule storedModule) throws Exception;

    record LoadedRuntime(
            PlatformModuleManifest manifest,
            PlatformModule entrypoint,
            Closeable closeable,
            ClassLoader isolationClassLoader) {

        public LoadedRuntime(PlatformModuleManifest manifest, PlatformModule entrypoint, Closeable closeable) {
            this(manifest, entrypoint, closeable, null);
        }

        public Optional<ClassLoader> isolationClassLoaderOptional() {
            return Optional.ofNullable(isolationClassLoader);
        }
    }

    final class JarRuntimeFactory implements PlatformModuleRuntimeFactory {

        private static final Set<String> PARENT_PREFIXES = new LinkedHashSet<>(
                Set.of("java.", "javax.", "jdk.", "sun.", "org.slf4j.", "me.prexorjustin.prexorcloud.api."));

        @Override
        public LoadedRuntime open(PlatformModuleStore.StoredModule storedModule) throws Exception {
            Objects.requireNonNull(storedModule, "storedModule");

            FilteringParentClassLoader apiParent =
                    new FilteringParentClassLoader(PlatformModule.class.getClassLoader(), PARENT_PREFIXES);
            URLClassLoader classLoader =
                    new URLClassLoader(new URL[] {storedModule.jarPath().toUri().toURL()}, apiParent);
            boolean success = false;
            try {
                var controllerSpec = storedModule.manifest().backend().controller();
                if (controllerSpec == null) {
                    throw new IllegalStateException(
                            "module '" + storedModule.manifest().id() + "' has no controller backend entrypoint");
                }
                String entrypointFqcn = controllerSpec.entrypoint();
                Class<?> entrypointType = Class.forName(entrypointFqcn, true, classLoader);
                if (!PlatformModule.class.isAssignableFrom(entrypointType)) {
                    throw new IllegalStateException(
                            "backend entrypoint does not implement PlatformModule: " + entrypointFqcn);
                }

                PlatformModule entrypoint = PlatformModule.class.cast(
                        entrypointType.getDeclaredConstructor().newInstance());
                success = true;
                return new LoadedRuntime(storedModule.manifest(), entrypoint, classLoader, classLoader);
            } finally {
                if (!success) {
                    closeQuietly(classLoader);
                }
            }
        }

        private static void closeQuietly(URLClassLoader classLoader) {
            try {
                classLoader.close();
            } catch (IOException _) {
            }
        }

        private static final class FilteringParentClassLoader extends ClassLoader {

            private final ClassLoader delegate;
            private final Set<String> allowedPrefixes;

            private FilteringParentClassLoader(ClassLoader delegate, Set<String> allowedPrefixes) {
                super(null);
                this.delegate = Objects.requireNonNull(delegate, "delegate");
                this.allowedPrefixes = Set.copyOf(allowedPrefixes);
            }

            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> alreadyLoaded = findLoadedClass(name);
                    if (alreadyLoaded != null) {
                        return alreadyLoaded;
                    }

                    if (!isAllowed(name)) {
                        throw new ClassNotFoundException(name);
                    }

                    Class<?> loaded = delegate.loadClass(name);
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
            }

            private boolean isAllowed(String className) {
                for (String prefix : allowedPrefixes) {
                    if (className.startsWith(prefix)) {
                        return true;
                    }
                }
                return false;
            }
        }
    }
}
