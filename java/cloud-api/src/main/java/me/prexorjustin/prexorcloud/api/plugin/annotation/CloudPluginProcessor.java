package me.prexorjustin.prexorcloud.api.plugin.annotation;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

import me.prexorjustin.prexorcloud.api.client.version.ForVersion;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;

/**
 * Generates platform bridge classes and descriptor files for
 * {@link CloudPlugin}-annotated types.
 *
 * <p>
 * The target platform is resolved in the following priority order:
 * <ol>
 * <li>Explicit {@code -Acloud.platform=&lt;platform&gt;} compiler argument</li>
 * <li>Classpath auto-detection (Geyser → Velocity → BungeeCord → Folia → Paper)</li>
 * <li>Default: {@code paper} (with a build-time WARNING)</li>
 * </ol>
 *
 * <p>
 * Supported platforms and their generated outputs:
 * <ul>
 * <li>{@code paper} → {@code *CloudBridge extends JavaPlugin} +
 * {@code paper-plugin.yml}</li>
 * <li>{@code spigot} → {@code *CloudBridge extends JavaPlugin} +
 * {@code plugin.yml}</li>
 * <li>{@code folia} → {@code *FoliaBridge extends JavaPlugin} +
 * {@code plugin.yml} ({@code folia-supported: true})</li>
 * <li>{@code velocity} → {@code *VelocityBridge} +
 * {@code velocity-plugin.json}</li>
 * <li>{@code bungeecord} / {@code bungee} / {@code waterfall} →
 * {@code *BungeeBridge extends Plugin} + {@code plugin.yml}</li>
 * <li>{@code bedrock-geyser} → {@code *GeyserBridge implements Extension} +
 * {@code extension.yml}</li>
 * </ul>
 */
@SupportedAnnotationTypes("me.prexorjustin.prexorcloud.api.plugin.annotation.CloudPlugin")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
@SupportedOptions({"cloud.platform"})
public class CloudPluginProcessor extends AbstractProcessor {

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(CloudPlugin.class)) {
            if (element instanceof TypeElement typeElement) {
                try {
                    processPlugin(typeElement);
                } catch (IOException e) {
                    processingEnv
                            .getMessager()
                            .printMessage(
                                    Diagnostic.Kind.ERROR,
                                    "Failed to generate bridge for " + typeElement.getSimpleName() + ": "
                                            + e.getMessage(),
                                    typeElement);
                }
            }
        }
        return true;
    }

    private void processPlugin(TypeElement typeElement) throws IOException {
        // Compile-time guard: annotated class must extend CloudPluginBase
        TypeElement cloudPluginBase = processingEnv
                .getElementUtils()
                .getTypeElement("me.prexorjustin.prexorcloud.api.plugin.CloudPluginBase");
        if (cloudPluginBase != null
                && !processingEnv.getTypeUtils().isAssignable(typeElement.asType(), cloudPluginBase.asType())) {
            processingEnv
                    .getMessager()
                    .printMessage(
                            Diagnostic.Kind.ERROR,
                            "@CloudPlugin on " + typeElement.getSimpleName()
                                    + " has no effect: the class must extend CloudPluginBase.",
                            typeElement);
            return;
        }

        CloudPlugin annotation = typeElement.getAnnotation(CloudPlugin.class);
        String platform = detectPlatform(typeElement);
        String qualifiedName = typeElement.getQualifiedName().toString();
        String simpleName = typeElement.getSimpleName().toString();
        String packageName =
                qualifiedName.contains(".") ? qualifiedName.substring(0, qualifiedName.lastIndexOf('.')) : "";

        // Warn on any @ForVersion groups that lack a fallback — catching version gaps
        // at build time rather than as a runtime UnsupportedOperationException.
        checkForVersionFallbacks(typeElement);

        // Infer the lowest api-version from @ForVersion(min=...) annotations so
        // developers don't have to manually keep @CloudPlugin.apiVersion() in sync
        // with the lowest version they actually support.
        String effectiveApiVersion = inferApiVersion(typeElement, annotation.apiVersion());

        switch (platform) {
            case "paper" ->
                generateBukkitBridge(
                        typeElement, annotation, packageName, simpleName, false, true, effectiveApiVersion);
            case "spigot" ->
                generateBukkitBridge(
                        typeElement, annotation, packageName, simpleName, false, false, effectiveApiVersion);
            case "folia" ->
                generateBukkitBridge(
                        typeElement, annotation, packageName, simpleName, true, false, effectiveApiVersion);
            case "velocity" -> generateVelocityBridge(typeElement, annotation, packageName, simpleName);
            case "bungeecord", "bungee", "waterfall" ->
                generateBungeeBridge(typeElement, annotation, packageName, simpleName);
            case "bedrock-geyser", "geyser" -> generateGeyserBridge(typeElement, annotation, packageName, simpleName);
            default ->
                processingEnv
                        .getMessager()
                        .printMessage(
                                Diagnostic.Kind.ERROR,
                                "Unknown cloud.platform value: '" + platform
                                        + "'. Expected: paper, spigot, folia, velocity, bungeecord, bedrock-geyser.",
                                typeElement);
        }
    }

    /**
     * Resolves the target platform using:
     * <ol>
     * <li>Explicit {@code -Acloud.platform} option (takes priority)</li>
     * <li>Classpath inspection via {@link Elements#getTypeElement}</li>
     * <li>Default {@code "paper"} with a WARNING</li>
     * </ol>
     */
    private String detectPlatform(TypeElement context) {
        String explicit = processingEnv.getOptions().get("cloud.platform");
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim().toLowerCase();
        }

        Elements eu = processingEnv.getElementUtils();
        // Geyser is checked first because Geyser-on-Velocity or Geyser-on-Paper
        // would otherwise resolve to the host platform; an explicit
        // `-Acloud.platform=bedrock-geyser` is still the recommended path for
        // those hybrid setups, but classpath-only detection should prefer Geyser
        // when its API is present.
        if (eu.getTypeElement("org.geysermc.geyser.api.GeyserApi") != null) return "bedrock-geyser";
        if (eu.getTypeElement("com.velocitypowered.api.proxy.ProxyServer") != null) return "velocity";
        if (eu.getTypeElement("net.md_5.bungee.api.plugin.Plugin") != null) return "bungeecord";
        if (eu.getTypeElement("io.papermc.paper.threadedregions.RegionizedServer") != null) return "folia";
        if (eu.getTypeElement("org.bukkit.plugin.java.JavaPlugin") != null) return "paper";

        processingEnv
                .getMessager()
                .printMessage(
                        Diagnostic.Kind.WARNING,
                        "[CloudPlugin] Cannot detect platform from classpath for " + context.getSimpleName()
                                + "; defaulting to 'paper'. Add -Acloud.platform=<platform> to suppress this warning.",
                        context);
        return "paper";
    }

    // ── Bukkit / Paper / Folia ─────────────────────────────────────────────────

    private void generateBukkitBridge(
            TypeElement typeElement,
            CloudPlugin ann,
            String pkg,
            String simpleName,
            boolean folia,
            boolean modernPaper,
            String effectiveApiVersion)
            throws IOException {
        String bridgeName = simpleName + (folia ? "FoliaBridge" : "CloudBridge");
        String bridgeFqn = pkg.isEmpty() ? bridgeName : pkg + "." + bridgeName;

        ClassName javaPlugin = ClassName.get("org.bukkit.plugin.java", "JavaPlugin");
        ClassName bukkit = ClassName.get("org.bukkit", "Bukkit");
        ClassName listener = ClassName.get("org.bukkit.event", "Listener");
        ClassName vDispatcher = ClassName.get("me.prexorjustin.prexorcloud.api.client.version", "VersionDispatcher");
        ClassName provider = ClassName.get("me.prexorjustin.prexorcloud.api", "CloudApiProvider");
        ClassName implClass = ClassName.bestGuess(typeElement.getQualifiedName().toString());

        MethodSpec onEnable = MethodSpec.methodBuilder("onEnable")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addStatement("impl = new $T()", implClass)
                .addStatement(
                        "impl.initVersionDispatcher(new $T($T.getServer().getBukkitVersion()))", vDispatcher, bukkit)
                .addStatement(
                        "if ((Object) impl instanceof $T) getServer().getPluginManager().registerEvents(($T)(Object) impl, this)",
                        listener,
                        listener)
                .addStatement("impl.onEnable($T.createPluginContext(this))", provider)
                .build();

        MethodSpec onDisable = MethodSpec.methodBuilder("onDisable")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .beginControlFlow("if (impl != null)")
                .addStatement("impl.onDisable()")
                .endControlFlow()
                .build();

        TypeSpec bridge = TypeSpec.classBuilder(bridgeName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .superclass(javaPlugin)
                .addField(implClass, "impl", Modifier.PRIVATE)
                .addMethod(onEnable)
                .addMethod(onDisable)
                .build();

        JavaFile.builder(pkg, bridge).build().writeTo(processingEnv.getFiler());

        if (modernPaper) {
            writePaperDescriptor(ann, bridgeFqn, effectiveApiVersion);
        } else {
            writeLegacyBukkitDescriptor(ann, bridgeFqn, folia, effectiveApiVersion);
        }
    }

    /**
     * Generates {@code paper-plugin.yml} — the modern Paper bootstrap descriptor
     * (Paper 1.19.3+). Uses the {@code dependencies.server} map format, which
     * enables {@code join-classpath: true} so downstream plugins can access the
     * PrexorCloud API without going through the platform classloader.
     */
    private void writePaperDescriptor(CloudPlugin ann, String mainClass, String effectiveApiVersion)
            throws IOException {
        FileObject resource =
                processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", "paper-plugin.yml");
        try (PrintWriter w = new PrintWriter(resource.openWriter())) {
            w.println("name: " + ann.name());
            w.println("version: " + ann.version());
            w.println("main: " + mainClass);
            w.println("api-version: '" + effectiveApiVersion + "'");
            if (!ann.description().isEmpty()) w.println("description: \"" + ann.description() + "\"");
            if (ann.authors().length > 0) w.println("authors: [" + String.join(", ", ann.authors()) + "]");
            w.println("dependencies:");
            w.println("  server:");
            // PrexorCloud must load before this plugin so the context factory is ready
            w.println("    PrexorCloud:");
            w.println("      load: BEFORE");
            w.println("      required: true");
            w.println("      join-classpath: true");
            for (String dep : ann.dependencies()) {
                w.println("    " + dep + ":");
                w.println("      load: BEFORE");
                w.println("      required: true");
                w.println("      join-classpath: true");
            }
            for (String dep : ann.softDependencies()) {
                w.println("    " + dep + ":");
                w.println("      load: BEFORE");
                w.println("      required: false");
                w.println("      join-classpath: true");
            }
        }
    }

    /**
     * Generates the legacy {@code plugin.yml} for Spigot and Folia targets.
     */
    private void writeLegacyBukkitDescriptor(
            CloudPlugin ann, String mainClass, boolean folia, String effectiveApiVersion) throws IOException {
        FileObject resource = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", "plugin.yml");
        try (PrintWriter w = new PrintWriter(resource.openWriter())) {
            w.println("name: " + ann.name());
            w.println("version: " + ann.version());
            w.println("main: " + mainClass);
            w.println("api-version: '" + effectiveApiVersion + "'");
            if (!ann.description().isEmpty()) w.println("description: \"" + ann.description() + "\"");
            if (ann.authors().length > 0) w.println("authors: [" + String.join(", ", ann.authors()) + "]");
            if (folia) w.println("folia-supported: true");
            w.print("depend: [PrexorCloud");
            for (String dep : ann.dependencies()) w.print(", " + dep);
            w.println("]");
            if (ann.softDependencies().length > 0)
                w.println("softdepend: [" + String.join(", ", ann.softDependencies()) + "]");
        }
    }

    // ── Velocity ───────────────────────────────────────────────────────────────

    private void generateVelocityBridge(TypeElement typeElement, CloudPlugin ann, String pkg, String simpleName)
            throws IOException {
        ClassName proxyServer = ClassName.get("com.velocitypowered.api.proxy", "ProxyServer");
        ClassName inject = ClassName.get("com.google.inject", "Inject");
        ClassName initEvent = ClassName.get("com.velocitypowered.api.event.proxy", "ProxyInitializeEvent");
        ClassName shutdownEvent = ClassName.get("com.velocitypowered.api.event.proxy", "ProxyShutdownEvent");
        ClassName subscribe = ClassName.get("com.velocitypowered.api.event", "Subscribe");
        ClassName pluginAnn = ClassName.get("com.velocitypowered.api.plugin", "Plugin");
        ClassName slf4jLogger = ClassName.get("org.slf4j", "Logger");
        ClassName provider = ClassName.get("me.prexorjustin.prexorcloud.api", "CloudApiProvider");
        ClassName vDispatcher = ClassName.get("me.prexorjustin.prexorcloud.api.client.version", "VersionDispatcher");
        ClassName implClass = ClassName.bestGuess(typeElement.getQualifiedName().toString());
        String bridgeName = simpleName + "VelocityBridge";

        AnnotationSpec pluginAnnotation = AnnotationSpec.builder(pluginAnn)
                .addMember("id", "$S", ann.name().toLowerCase().replace(" ", "-"))
                .addMember("name", "$S", ann.name())
                .addMember("version", "$S", ann.version())
                .addMember("description", "$S", ann.description())
                .build();

        // Wire the proxy-side VersionDispatcher off the running Velocity proxy
        // version (e.g. "3.4.0-SNAPSHOT"). Lets module authors use @ForVersion
        // on Velocity to absorb 3.3 vs 3.4 API drift the same way Paper plugins
        // dispatch on the running Minecraft version.
        MethodSpec constructor = MethodSpec.constructorBuilder()
                .addAnnotation(inject)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(proxyServer, "server")
                .addParameter(slf4jLogger, "logger")
                .addStatement("this.server = server")
                .addStatement("this.logger = logger")
                .addStatement("this.impl = new $T()", implClass)
                .addStatement("this.impl.initVersionDispatcher(new $T(server.getVersion().getVersion()))", vDispatcher)
                .build();

        // Mirror the Paper bridge's Listener auto-registration: hand the @Plugin
        // instance (this) to ProxyServer.getEventManager().register so the impl's
        // @Subscribe methods fire under the bridge's plugin id. Done after onEnable
        // so the impl can complete its own setup before any proxy event arrives.
        MethodSpec onInit = MethodSpec.methodBuilder("onProxyInitialization")
                .addAnnotation(subscribe)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(initEvent, "event")
                .addStatement("impl.onEnable($T.createPluginContext(this))", provider)
                .addStatement("server.getEventManager().register(this, impl)")
                .build();

        MethodSpec onShutdown = MethodSpec.methodBuilder("onProxyShutdown")
                .addAnnotation(subscribe)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(shutdownEvent, "event")
                .addStatement("impl.onDisable()")
                .build();

        // Accessors so VelocityPluginContext can reach the @Plugin-scoped
        // ProxyServer + slf4j Logger without requiring Guice on the impl side.
        MethodSpec getServer = MethodSpec.methodBuilder("server")
                .addModifiers(Modifier.PUBLIC)
                .returns(proxyServer)
                .addStatement("return server")
                .build();

        MethodSpec getLogger = MethodSpec.methodBuilder("logger")
                .addModifiers(Modifier.PUBLIC)
                .returns(slf4jLogger)
                .addStatement("return logger")
                .build();

        TypeSpec bridge = TypeSpec.classBuilder(bridgeName)
                .addAnnotation(pluginAnnotation)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addField(implClass, "impl", Modifier.PRIVATE, Modifier.FINAL)
                .addField(proxyServer, "server", Modifier.PRIVATE, Modifier.FINAL)
                .addField(slf4jLogger, "logger", Modifier.PRIVATE, Modifier.FINAL)
                .addMethod(constructor)
                .addMethod(onInit)
                .addMethod(onShutdown)
                .addMethod(getServer)
                .addMethod(getLogger)
                .build();

        JavaFile.builder(pkg, bridge).build().writeTo(processingEnv.getFiler());
        writeVelocityDescriptor(ann);
    }

    private void writeVelocityDescriptor(CloudPlugin ann) throws IOException {
        FileObject resource =
                processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", "velocity-plugin.json");
        try (PrintWriter w = new PrintWriter(resource.openWriter())) {
            w.println("{");
            w.println("  \"id\": \"" + ann.name().toLowerCase().replace(" ", "-") + "\",");
            w.println("  \"name\": \"" + ann.name() + "\",");
            w.println("  \"version\": \"" + ann.version() + "\",");
            w.println("  \"description\": \"" + ann.description() + "\",");
            w.print("  \"dependencies\": [{\"id\": \"prexorcloud\", \"optional\": false}");
            for (String dep : ann.dependencies())
                w.print(", {\"id\": \"" + dep.toLowerCase() + "\", \"optional\": false}");
            for (String dep : ann.softDependencies())
                w.print(", {\"id\": \"" + dep.toLowerCase() + "\", \"optional\": true}");
            w.println("]");
            w.println("}");
        }
    }

    // ── BungeeCord / Waterfall ─────────────────────────────────────────────────

    private void generateBungeeBridge(TypeElement typeElement, CloudPlugin ann, String pkg, String simpleName)
            throws IOException {
        String bridgeName = simpleName + "BungeeBridge";
        String bridgeFqn = pkg.isEmpty() ? bridgeName : pkg + "." + bridgeName;

        ClassName bungeePlugin = ClassName.get("net.md_5.bungee.api.plugin", "Plugin");
        ClassName bungeeProxyServer = ClassName.get("net.md_5.bungee.api", "ProxyServer");
        ClassName provider = ClassName.get("me.prexorjustin.prexorcloud.api", "CloudApiProvider");
        ClassName vDispatcher = ClassName.get("me.prexorjustin.prexorcloud.api.client.version", "VersionDispatcher");
        ClassName implClass = ClassName.bestGuess(typeElement.getQualifiedName().toString());

        // Same proxy-side VersionDispatcher wiring as the Velocity bridge — keyed
        // off the running BungeeCord version string so @ForVersion adapters work
        // on Bungee too.
        MethodSpec onEnable = MethodSpec.methodBuilder("onEnable")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addStatement("impl = new $T()", implClass)
                .addStatement(
                        "impl.initVersionDispatcher(new $T($T.getInstance().getVersion()))",
                        vDispatcher,
                        bungeeProxyServer)
                .addStatement("impl.onEnable($T.createPluginContext(this))", provider)
                .build();

        MethodSpec onDisable = MethodSpec.methodBuilder("onDisable")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .beginControlFlow("if (impl != null)")
                .addStatement("impl.onDisable()")
                .endControlFlow()
                .build();

        TypeSpec bridge = TypeSpec.classBuilder(bridgeName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .superclass(bungeePlugin)
                .addField(implClass, "impl", Modifier.PRIVATE)
                .addMethod(onEnable)
                .addMethod(onDisable)
                .build();

        JavaFile.builder(pkg, bridge).build().writeTo(processingEnv.getFiler());
        writeBungeeDescriptor(ann, bridgeFqn);
    }

    private void writeBungeeDescriptor(CloudPlugin ann, String mainClass) throws IOException {
        FileObject resource = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", "plugin.yml");
        try (PrintWriter w = new PrintWriter(resource.openWriter())) {
            w.println("name: " + ann.name());
            w.println("version: " + ann.version());
            w.println("main: " + mainClass);
            if (!ann.description().isEmpty()) w.println("description: \"" + ann.description() + "\"");
            if (ann.authors().length > 0) w.println("author: " + ann.authors()[0]);
            w.print("depends: [PrexorCloud");
            for (String dep : ann.dependencies()) w.print(", " + dep);
            w.println("]");
            if (ann.softDependencies().length > 0)
                w.println("softDepends: [" + String.join(", ", ann.softDependencies()) + "]");
        }
    }

    // ── Bedrock / Geyser ───────────────────────────────────────────────────────

    /**
     * Generates a Geyser-extension bridge. Geyser loads extensions from JARs in
     * its {@code extensions/} directory, instantiates the {@code main:} class
     * from {@code extension.yml} with a no-arg constructor, and dispatches
     * lifecycle via {@code @Subscribe} on {@code GeyserPreInitializeEvent} /
     * {@code GeyserPostInitializeEvent} / {@code GeyserShutdownEvent}. The
     * generated bridge subscribes to post-init (the safe point — all Geyser
     * APIs are usable) and shutdown.
     *
     * <p>Unlike the Paper/Velocity bridges, no {@code VersionDispatcher} is
     * wired: a Geyser extension runs inside Geyser's own runtime regardless of
     * host MC server version, so {@code @ForVersion} dispatch isn't meaningful
     * here. Module authors needing per-MC-version branching should ship a
     * sibling Paper/Folia/Velocity plugin alongside the Geyser variant.
     */
    private void generateGeyserBridge(TypeElement typeElement, CloudPlugin ann, String pkg, String simpleName)
            throws IOException {
        String bridgeName = simpleName + "GeyserBridge";
        String bridgeFqn = pkg.isEmpty() ? bridgeName : pkg + "." + bridgeName;

        ClassName extension = ClassName.get("org.geysermc.geyser.api.extension", "Extension");
        ClassName postInitEvent = ClassName.get("org.geysermc.geyser.api.event.lifecycle", "GeyserPostInitializeEvent");
        ClassName shutdownEvent = ClassName.get("org.geysermc.geyser.api.event.lifecycle", "GeyserShutdownEvent");
        ClassName subscribe = ClassName.get("org.geysermc.event.subscribe", "Subscribe");
        ClassName provider = ClassName.get("me.prexorjustin.prexorcloud.api", "CloudApiProvider");
        ClassName implClass = ClassName.bestGuess(typeElement.getQualifiedName().toString());

        // Mirror the Velocity bridge's event-bus auto-registration: after the
        // impl finishes onEnable, register it on the extension's own event bus
        // so its @Subscribe handlers fire under this extension's id.
        MethodSpec onPostInit = MethodSpec.methodBuilder("onPostInitialize")
                .addAnnotation(subscribe)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(postInitEvent, "event")
                .addStatement("impl = new $T()", implClass)
                .addStatement("impl.onEnable($T.createPluginContext(this))", provider)
                .addStatement("this.eventBus().register(impl)")
                .build();

        MethodSpec onShutdown = MethodSpec.methodBuilder("onShutdown")
                .addAnnotation(subscribe)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(shutdownEvent, "event")
                .beginControlFlow("if (impl != null)")
                .addStatement("impl.onDisable()")
                .endControlFlow()
                .build();

        TypeSpec bridge = TypeSpec.classBuilder(bridgeName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(extension)
                .addField(implClass, "impl", Modifier.PRIVATE)
                .addMethod(onPostInit)
                .addMethod(onShutdown)
                .build();

        JavaFile.builder(pkg, bridge).build().writeTo(processingEnv.getFiler());
        writeGeyserDescriptor(ann, bridgeFqn);
    }

    private void writeGeyserDescriptor(CloudPlugin ann, String mainClass) throws IOException {
        FileObject resource =
                processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", "extension.yml");
        try (PrintWriter w = new PrintWriter(resource.openWriter())) {
            // Geyser's extension manifest is YAML. id must match the @CloudPlugin
            // name (kebab-cased) so the extension is addressable on Geyser's
            // command surface; `api: "1.0.0"` is the Extension API contract
            // version, not the Geyser release version.
            w.println("id: " + ann.name().toLowerCase().replace(" ", "-"));
            w.println("name: " + ann.name());
            w.println("main: " + mainClass);
            w.println("version: " + ann.version());
            w.println("api: \"1.0.0\"");
            if (!ann.description().isEmpty()) w.println("description: \"" + ann.description() + "\"");
            if (ann.authors().length > 0) {
                w.print("authors: [");
                for (int i = 0; i < ann.authors().length; i++) {
                    if (i > 0) w.print(", ");
                    w.print("\"" + ann.authors()[i] + "\"");
                }
                w.println("]");
            }
        }
    }

    // ── api-version inference ─────────────────────────────────────────────────

    /**
     * Returns the lower of the declared {@code apiVersion} and the lowest
     * {@code @ForVersion(min=...)} value found anywhere in the class hierarchy.
     * This ensures the generated descriptor declares the true minimum version the
     * plugin supports without requiring the developer to keep
     * {@code @CloudPlugin.apiVersion()} in sync manually.
     */
    private String inferApiVersion(TypeElement typeElement, String declared) {
        String lowestForVersion = collectMinForVersion(typeElement);
        if (lowestForVersion == null) return declared;
        return lowerVersion(declared, lowestForVersion);
    }

    private String collectMinForVersion(TypeElement container) {
        String lowest = null;
        for (Element enclosed : container.getEnclosedElements()) {
            if (!(enclosed instanceof TypeElement inner)) continue;
            ForVersion ann = inner.getAnnotation(ForVersion.class);
            if (ann != null && !ann.fallback() && !ann.min().isEmpty()) {
                if (lowest == null || isLowerVersion(ann.min(), lowest)) lowest = ann.min();
            }
            String childLowest = collectMinForVersion(inner);
            if (childLowest != null && (lowest == null || isLowerVersion(childLowest, lowest))) lowest = childLowest;
        }
        return lowest;
    }

    private String lowerVersion(String a, String b) {
        return isLowerVersion(a, b) ? a : b;
    }

    private boolean isLowerVersion(String a, String b) {
        int[] va = parseMinorPatch(a), vb = parseMinorPatch(b);
        if (va[0] != vb[0]) return va[0] < vb[0];
        return va[1] < vb[1];
    }

    private int[] parseMinorPatch(String v) {
        String[] parts = v.split("\\.");
        int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
        return new int[] {minor, patch};
    }

    // ── @ForVersion fallback validation ───────────────────────────────────────

    private void checkForVersionFallbacks(TypeElement container) {
        for (Element enclosed : container.getEnclosedElements()) {
            if (!(enclosed instanceof TypeElement inner)) continue;

            List<? extends Element> forVersioned = inner.getEnclosedElements().stream()
                    .filter(e -> e instanceof TypeElement)
                    .filter(e -> e.getAnnotation(ForVersion.class) != null)
                    .toList();

            if (!forVersioned.isEmpty()) {
                boolean hasFallback = forVersioned.stream()
                        .anyMatch(e -> e.getAnnotation(ForVersion.class).fallback());
                if (!hasFallback) {
                    processingEnv
                            .getMessager()
                            .printMessage(
                                    Diagnostic.Kind.WARNING,
                                    "[CloudPlugin] "
                                            + inner.getSimpleName() + " has @ForVersion implementations but no "
                                            + "@ForVersion(fallback=true). Servers running outside the covered version ranges will "
                                            + "throw UnsupportedOperationException at runtime. "
                                            + "Add a nested class annotated @ForVersion(fallback=true) to handle unknown versions.",
                                    inner);
                }
            }

            checkForVersionFallbacks(inner);
        }
    }
}
