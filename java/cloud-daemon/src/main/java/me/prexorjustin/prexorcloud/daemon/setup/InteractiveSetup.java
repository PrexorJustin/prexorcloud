package me.prexorjustin.prexorcloud.daemon.setup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;

import me.prexorjustin.prexorcloud.common.config.YamlConfigLoader;
import me.prexorjustin.prexorcloud.daemon.config.*;

/**
 * Interactive first-run setup for the daemon. Prompts the user for controller
 * connection details and join token, then writes the config to daemon.yml.
 */
public final class InteractiveSetup {

    private InteractiveSetup() {}

    /**
     * Returns true if interactive setup is needed (no config file and no existing
     * certificate).
     */
    public static boolean isNeeded(Path configPath) {
        return !Files.exists(configPath);
    }

    /**
     * Runs the interactive setup prompts and writes the resulting config file.
     */
    public static DaemonConfig run(Path configPath) throws IOException {
        var scanner = new Scanner(System.in);

        System.out.println();
        System.out.println("  ____                          ____ _                 _");
        System.out.println(" |  _ \\ _ __ _____  _____  _ __/ ___| | ___  _   _  __| |");
        System.out.println(" | |_) | '__/ _ \\ \\/ / _ \\| '__| |   | |/ _ \\| | | |/ _` |");
        System.out.println(" |  __/| | |  __/>  < (_) | |  | |___| | (_) | |_| | (_| |");
        System.out.println(" |_|   |_|  \\___/_/\\_\\___/|_|   \\____|_|\\___/ \\__,_|\\__,_|");
        System.out.println();
        System.out.println("  First-time setup -- configure this daemon node");
        System.out.println();

        String nodeId = prompt(scanner, "Node ID", "node-1");
        String advertiseAddress =
                prompt(scanner, "Advertise address (IP/hostname reachable by proxies, empty = auto-detect)", "");
        String host = prompt(scanner, "Controller host", "127.0.0.1");
        int grpcPort = promptInt(scanner, "Controller gRPC port", 9090);
        String joinToken = promptRequired(scanner, "Join token (from dashboard)");

        System.out.println();
        System.out.println("  Configuration summary:");
        System.out.println("    Node ID:          " + nodeId);
        System.out.println(
                "    Advertise address:" + (advertiseAddress.isEmpty() ? " (auto-detect)" : " " + advertiseAddress));
        System.out.println("    Controller:       " + host + ":" + grpcPort);
        System.out.println("    Join token:       " + joinToken.substring(0, Math.min(8, joinToken.length())) + "...");
        System.out.println();

        var config = new DaemonConfig(
                nodeId,
                advertiseAddress,
                new ControllerConnectionConfig(host, grpcPort),
                new HealthConfig(),
                new SecurityDaemonConfig("config/security", joinToken),
                new InstancesConfig(),
                new ResourcesConfig(),
                null, // logging -- use defaults
                new ReconnectConfig(),
                null, // modules -- use defaults
                null, // telemetry -- use defaults (disabled)
                null // labels -- use defaults
                );

        // Write config to disk
        Files.createDirectories(configPath.getParent());
        YamlConfigLoader.mapper().writeValue(configPath.toFile(), config);
        System.out.println("  Config saved to " + configPath);
        System.out.println();

        return config;
    }

    private static String prompt(Scanner scanner, String label, String defaultValue) {
        System.out.printf("  %s [%s]: ", label, defaultValue);
        System.out.flush();
        String input = scanner.nextLine().trim();
        return input.isEmpty() ? defaultValue : input;
    }

    private static int promptInt(Scanner scanner, String label, int defaultValue) {
        while (true) {
            System.out.printf("  %s [%d]: ", label, defaultValue);
            System.out.flush();
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) return defaultValue;
            try {
                int value = Integer.parseInt(input);
                if (value > 0 && value <= 65535) return value;
                System.out.println("  Please enter a valid port number (1-65535)");
            } catch (NumberFormatException _) {
                System.out.println("  Please enter a valid number");
            }
        }
    }

    private static String promptRequired(Scanner scanner, String label) {
        while (true) {
            System.out.printf("  %s: ", label);
            System.out.flush();
            String input = scanner.nextLine().trim();
            if (!input.isEmpty()) return input;
            System.out.println("  This field is required");
        }
    }
}
