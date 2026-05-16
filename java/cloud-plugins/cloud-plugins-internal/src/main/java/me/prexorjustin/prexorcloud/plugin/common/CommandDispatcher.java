package me.prexorjustin.prexorcloud.plugin.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import me.prexorjustin.prexorcloud.api.plugin.command.Arg;
import me.prexorjustin.prexorcloud.api.plugin.command.CommandContext;
import me.prexorjustin.prexorcloud.api.plugin.command.CommandException;
import me.prexorjustin.prexorcloud.api.plugin.command.CommandHandler;
import me.prexorjustin.prexorcloud.api.plugin.command.tree.ArgumentNode;
import me.prexorjustin.prexorcloud.api.plugin.command.tree.CommandNode;
import me.prexorjustin.prexorcloud.api.plugin.command.tree.LiteralNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Recursively walks a {@link CommandNode} tree to dispatch a command or
 * generate tab-completion suggestions. Has no depth limit — depth is simply
 * tree depth.
 *
 * <p>
 * Thread-safe and stateless — one shared instance per registry.
 */
public final class CommandDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(CommandDispatcher.class);

    // ── Execute ───────────────────────────────────────────────────────────────

    /**
     * Dispatches {@code ctx} against {@code root}. Walks the token list
     * recursively, matching literals and parsing argument nodes, then calls the
     * matched executor.
     */
    public void dispatch(CommandNode root, CommandContext ctx) {
        try {
            walk(root, ctx, ctx.args(), new LinkedHashMap<>());
        } catch (CommandException e) {
            ctx.sender().sendMessage("§c" + e.commandMessage());
        } catch (Exception e) {
            logger.error("Unexpected error dispatching command '{}': {}", root.name(), e.getMessage(), e);
            ctx.sender().sendMessage("§cAn internal error occurred.");
        }
    }

    private void walk(
            CommandNode node, CommandContext ctx, List<String> remaining, LinkedHashMap<Arg<?>, Object> accumulator) {

        applyGuards(node, ctx);

        if (remaining.isEmpty()) {
            execute(node, ctx, accumulator);
            return;
        }

        String token = remaining.get(0);
        List<String> rest = remaining.subList(1, remaining.size());

        // 1. Try literal children first (exact name or alias match)
        for (LiteralNode child : node.literalChildren()) {
            if (!hasPermission(ctx, child.permission())) continue;
            if (child.matches(token)) {
                walk(child, ctx, rest, accumulator);
                return;
            }
        }

        // 2. Try argument child
        var argChild = node.argumentChild();
        if (argChild.isPresent()) {
            consumeArgument(argChild.get(), ctx, token, rest, accumulator);
            return;
        }

        // 3. Nothing matched
        throw new CommandException(
                "Unknown subcommand: §7" + token + "§c. Use §7/" + node.name() + " help§c for usage.");
    }

    private <T> void consumeArgument(
            ArgumentNode<T> argNode,
            CommandContext ctx,
            String token,
            List<String> rest,
            LinkedHashMap<Arg<?>, Object> accumulator) {
        Arg<T> arg = argNode.arg();
        String raw;
        List<String> remaining;

        if (arg.isGreedy()) {
            raw = token + (rest.isEmpty() ? "" : " " + String.join(" ", rest));
            remaining = List.of();
        } else {
            raw = token;
            remaining = rest;
        }

        T value = arg.parse(raw, ctx);
        accumulator.put(arg, value);
        walk(argNode, ctx, remaining, accumulator);
    }

    private void execute(CommandNode node, CommandContext ctx, LinkedHashMap<Arg<?>, Object> accumulator) {
        // Fill in defaults for optional args that were not consumed
        fillDefaults(node, accumulator);

        CommandHandler executor = node.executor();
        if (executor == null) {
            showHelp(node, ctx);
            return;
        }

        ctx.setResolved(accumulator);
        executor.execute(ctx);
    }

    private void fillDefaults(CommandNode node, LinkedHashMap<Arg<?>, Object> accumulator) {
        var argChild = node.argumentChild();
        if (argChild.isEmpty()) return;
        Arg<?> arg = argChild.get().arg();
        if (!accumulator.containsKey(arg) && !arg.isRequired()) {
            Object def = arg.defaultValue();
            if (def != null) accumulator.put(arg, def);
        }
    }

    private void applyGuards(CommandNode node, CommandContext ctx) {
        if (!hasPermission(ctx, node.permission())) {
            throw new CommandException("You don't have permission to use this command.");
        }
        if (node.requiresPlayer() && !ctx.sender().isPlayer()) {
            throw new CommandException(node.requiresPlayerMsg());
        }
        if (node.requiresConsole() && !ctx.sender().isConsole()) {
            throw new CommandException(node.requiresConsoleMsg());
        }
    }

    private boolean hasPermission(CommandContext ctx, String permission) {
        return permission == null || permission.isEmpty() || ctx.sender().hasPermission(permission);
    }

    // ── Help page ─────────────────────────────────────────────────────────────

    private void showHelp(CommandNode node, CommandContext ctx) {
        List<LiteralNode> subs = node.literalChildren();
        if (subs.isEmpty()) {
            ctx.sender().sendMessage("§cNo subcommands available.");
            return;
        }
        ctx.sender().sendMessage("§6/" + node.name() + " — subcommands:");
        for (LiteralNode sub : subs) {
            if (!hasPermission(ctx, sub.permission())) continue;
            String usage = buildUsage(sub);
            String desc = sub.description().isEmpty() ? "" : " §7— " + sub.description();
            ctx.sender().sendMessage("  §e" + usage + desc);
        }
    }

    private String buildUsage(LiteralNode node) {
        StringBuilder sb = new StringBuilder("/").append(node.name());
        appendArgUsage(node, sb);
        return sb.toString();
    }

    private void appendArgUsage(CommandNode node, StringBuilder sb) {
        var argChild = node.argumentChild();
        if (argChild.isEmpty()) return;
        Arg<?> arg = argChild.get().arg();
        if (arg.isRequired()) {
            sb.append(" <").append(arg.name).append('>');
        } else {
            sb.append(" [").append(arg.name).append(']');
        }
        appendArgUsage(argChild.get(), sb);
    }

    // ── Tab complete ──────────────────────────────────────────────────────────

    /**
     * Returns completion suggestions for the partial token the sender is currently
     * typing.
     */
    public List<String> tabComplete(CommandNode root, CommandContext ctx) {
        List<String> tokens = ctx.args();
        if (tokens.isEmpty()) return List.of();
        return completeWalk(root, ctx, tokens);
    }

    private List<String> completeWalk(CommandNode node, CommandContext ctx, List<String> tokens) {
        String current = tokens.get(0);
        List<String> rest = tokens.subList(1, tokens.size());

        // More tokens remain — descend into the tree
        if (!rest.isEmpty()) {
            // Descend through matching literal
            for (LiteralNode child : node.literalChildren()) {
                if (child.matches(current)) return completeWalk(child, ctx, rest);
            }
            // Descend through argument node (consume current token, continue with rest)
            var argChild = node.argumentChild();
            if (argChild.isPresent() && !argChild.get().arg().isGreedy()) {
                return completeWalk(argChild.get(), ctx, rest);
            }
            return List.of();
        }

        // current is the partial token being completed right now
        String partial = current.toLowerCase();
        List<String> results = new ArrayList<>();

        // Literal names that start with partial
        for (LiteralNode child : node.literalChildren()) {
            if (!hasPermission(ctx, child.permission())) continue;
            if (child.name().toLowerCase().startsWith(partial)) results.add(child.name());
            for (String alias : child.aliases()) {
                if (alias.toLowerCase().startsWith(partial)) results.add(alias);
            }
        }

        // Argument node completions
        node.argumentChild().ifPresent(argNode -> {
            argNode.arg().complete(ctx, partial).stream()
                    .filter(s -> s.toLowerCase().startsWith(partial))
                    .forEach(results::add);
        });

        return results;
    }
}
