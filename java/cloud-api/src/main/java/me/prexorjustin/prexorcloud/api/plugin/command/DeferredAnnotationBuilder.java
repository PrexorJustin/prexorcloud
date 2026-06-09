package me.prexorjustin.prexorcloud.api.plugin.command;

import me.prexorjustin.prexorcloud.api.plugin.command.tree.LiteralNode;

/**
 * A deferred {@link LiteralBuilder} produced by {@link Commands#node(Object)}.
 * Holds an annotated POJO and is resolved by {@code AbstractCommandRegistry}
 * via {@code AnnotationCompiler} before the tree is built. Plugin authors never
 * instantiate or interact with this class directly.
 */
public final class DeferredAnnotationBuilder extends LiteralBuilder {

    private final Object annotatedPojo;

    DeferredAnnotationBuilder(Object annotatedPojo) {
        super("__deferred__", new String[0]);
        this.annotatedPojo = annotatedPojo;
    }

    public Object annotatedPojo() {
        return annotatedPojo;
    }

    @Override
    public LiteralNode build() {
        throw new UnsupportedOperationException(
                "DeferredAnnotationBuilder must be resolved by AbstractCommandRegistry before build(). "
                        + "Use ctx.commands().register(LiteralBuilder) instead of calling build() directly.");
    }
}
