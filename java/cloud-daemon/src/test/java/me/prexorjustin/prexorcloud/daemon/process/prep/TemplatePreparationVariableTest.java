package me.prexorjustin.prexorcloud.daemon.process.prep;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("TemplatePreparation variable substitution")
class TemplatePreparationVariableTest {

    @Test
    @DisplayName("substitutes controller-resolved variables and lets builtins win on a name collision")
    void appliesResolvedVariablesWithBuiltinPrecedence(@TempDir Path instanceDir) throws Exception {
        Path file = instanceDir.resolve("server.properties");
        Files.writeString(file, "motd=%CUSTOM_MOTD%\nport=%PORT%\ngroup=%GROUP%\n");

        // A daemon builtin name (PORT) deliberately collides with an operator variable to prove the
        // builtin wins; CUSTOM_MOTD is a genuine operator variable threaded from the controller.
        var spec = spec(Map.of("CUSTOM_MOTD", "Welcome to Prexor", "PORT", "1"));

        new TemplatePreparation(null, null, "node-7").applyVariableSubstitution(spec, instanceDir);

        assertEquals("motd=Welcome to Prexor\nport=25565\ngroup=lobby\n", Files.readString(file));
    }

    private static ResolvedStartSpec spec(Map<String, String> resolvedVariables) {
        return new ResolvedStartSpec(
                "lobby-1",
                "lobby",
                25565,
                1024,
                0.0,
                0L,
                List.of(),
                Map.of(),
                "server.jar",
                "",
                false,
                List.of(),
                30,
                50,
                "SERVER",
                "paper",
                "PAPER",
                "1.21.4",
                "",
                "",
                List.of(),
                List.of(),
                List.of(),
                "hash",
                resolvedVariables);
    }
}
