package me.prexorjustin.prexorcloud.api.plugin.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link me.prexorjustin.prexorcloud.api.plugin.CloudPluginBase}
 * subclass as a cloud plugin.
 * <p>
 * The annotation processor reads this and generates:
 * <ul>
 * <li>A platform bridge class ({@code *CloudBridge}, {@code *FoliaBridge},
 * {@code *VelocityBridge}, {@code *BungeeBridge}) depending on
 * {@code -Acloud.platform=}</li>
 * <li>The appropriate descriptor file ({@code plugin.yml} or
 * {@code velocity-plugin.json})</li>
 * </ul>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface CloudPlugin {

    String name();

    String version();

    String description() default "";

    String[] authors() default {};

    /**
     * Hard dependencies — must be loaded before this plugin. PrexorCloud is always
     * added automatically.
     */
    String[] dependencies() default {};

    /** Soft dependencies — loaded before this plugin if present. */
    String[] softDependencies() default {};

    /**
     * Minimum Bukkit/Paper {@code api-version}. Ignored on non-Bukkit platforms.
     *
     * <p>
     * If your plugin uses
     * {@link me.prexorjustin.prexorcloud.api.client.version.ForVersion}
     * annotations, the processor automatically infers the effective value from the
     * lowest {@code @ForVersion(min=...)} it finds — you typically don't need to
     * set this manually. The processor always uses whichever is lower: this value
     * or the inferred minimum.
     *
     * <p>
     * Example: a plugin with {@code @ForVersion(min="1.16")} and
     * {@code @ForVersion(min="1.21")} will have {@code api-version: '1.16'} in the
     * generated {@code plugin.yml} regardless of what this field is set to.
     */
    String apiVersion() default "1.21";
}
