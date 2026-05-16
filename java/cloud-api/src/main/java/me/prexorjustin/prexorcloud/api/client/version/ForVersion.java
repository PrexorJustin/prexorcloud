package me.prexorjustin.prexorcloud.api.client.version;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a nested class as the adapter to use for a specific Minecraft version
 * range, or as the fallback when no range matches.
 *
 * <pre>
 * {@code
 * interface WelcomeHandler {
 *     &#64;ForVersion(min = "1.21")
 *     class Modern implements WelcomeHandler { ... }
 *
 *     &#64;ForVersion(min = "1.17", max = "1.20")
 *     class Legacy implements WelcomeHandler { ... }
 *
 *     &#64;ForVersion(fallback = true)   // used when no range matches (e.g. 1.22)
 *     class Default implements WelcomeHandler { ... }
 * }
 *
 * WelcomeHandler handler = adapt(WelcomeHandler.class);
 * }
 * </pre>
 *
 * <p>
 * When {@code fallback = true}, {@code min} and {@code max} are ignored. The
 * fallback class is only selected when no versioned class matches the running
 * server. At most one fallback per container is allowed.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ForVersion {

    /**
     * Minimum version (inclusive), e.g. {@code "1.20"} or {@code "1.21.4"}. Ignored
     * when {@code fallback=true}.
     */
    String min() default "";

    /**
     * Maximum version (inclusive), e.g. {@code "1.20.6"}. Empty means unbounded.
     * Ignored when {@code fallback=true}.
     */
    String max() default "";

    /**
     * When {@code true}, this class is used as a catch-all when no versioned class
     * matches the running server. Prevents {@link UnsupportedOperationException} on
     * unknown future versions.
     */
    boolean fallback() default false;
}
