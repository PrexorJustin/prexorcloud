package me.prexorjustin.prexorcloud.controller.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import me.prexorjustin.prexorcloud.api.module.capability.InstanceFileAccess;
import me.prexorjustin.prexorcloud.api.module.capability.InstanceFileAccess.InstanceFileBytes;
import me.prexorjustin.prexorcloud.api.module.capability.InstanceFileAccess.InstanceFileEntry;
import me.prexorjustin.prexorcloud.api.module.capability.InstanceFileAccess.InstanceFileTree;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.controller.state.StateStore;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;

class DeployBackServiceTest {

    @TempDir
    Path tempDir;

    private StateStore stateStore;
    private TemplateManager templateManager;
    private InstanceFileAccess fileAccess;
    private DeployBackService service;

    private static InstanceInfo running(String id) {
        return new InstanceInfo(id, "lobby", "node-1", InstanceState.RUNNING, 25565, 0, 0, Instant.now());
    }

    @BeforeEach
    void setUp() {
        stateStore = mock(StateStore.class);
        lenient()
                .doAnswer(inv -> {
                    inv.<Runnable>getArgument(0).run();
                    return null;
                })
                .when(stateStore)
                .runInTransaction(ArgumentMatchers.any());
        templateManager = new TemplateManager(tempDir, stateStore, new EventBus());
        fileAccess = mock(InstanceFileAccess.class);
        service = new DeployBackService(fileAccess, templateManager);
    }

    @Test
    void capturesConfigFilesAndSkipsBinaryAndOversize() throws IOException {
        templateManager.save(new TemplateConfig("lobby-tpl", "lobby base", "", "", 0));

        when(fileAccess.walk(any(), any(), any()))
                .thenReturn(new InstanceFileTree(
                        List.of(
                                new InstanceFileEntry("config", 0, true, 0L),
                                new InstanceFileEntry("server.properties", 12, false, 0L),
                                new InstanceFileEntry("config/foo.yml", 4, false, 0L),
                                new InstanceFileEntry("server.jar", 5_000_000, false, 0L),
                                new InstanceFileEntry("big.yml", 200_000, false, 0L)),
                        false,
                        ""));
        when(fileAccess.read(any(), any(), any(), eq("server.properties"), anyInt()))
                .thenReturn(new InstanceFileBytes("motd=hello", 10, false, ""));
        when(fileAccess.read(any(), any(), any(), eq("config/foo.yml"), anyInt()))
                .thenReturn(new InstanceFileBytes("a: 1", 4, false, ""));
        when(fileAccess.read(any(), any(), any(), eq("big.yml"), anyInt()))
                .thenReturn(new InstanceFileBytes("", 200_000, true, ""));

        var result = service.saveToTemplate(running("lobby-1"), "lobby-tpl", null);

        // Two text files captured; the jar (by extension) and the oversize yml were skipped.
        assertEquals(2, result.filesWritten());
        assertEquals(2, result.skipped().size());
        assertTrue(result.skipped().stream().anyMatch(s -> s.startsWith("server.jar")));
        assertTrue(result.skipped().stream().anyMatch(s -> s.startsWith("big.yml")));

        Path filesDir = templateManager.getTemplateFilesDir("lobby-tpl");
        assertEquals("motd=hello", Files.readString(filesDir.resolve("server.properties")));
        assertEquals("a: 1", Files.readString(filesDir.resolve("config/foo.yml")));

        // The template was rehashed into a real version and recorded.
        assertTrue(result.hash().length() > 0);
        assertEquals(result.hash(), templateManager.get("lobby-tpl").orElseThrow().hash());
        verify(stateStore).recordTemplateVersion(eq("lobby-tpl"), eq(result.hash()), ArgumentMatchers.anyLong());
    }

    @Test
    void rejectsInstanceThatIsNotRunning() throws IOException {
        templateManager.save(new TemplateConfig("lobby-tpl", "", "", "", 0));
        var stopped = new InstanceInfo("lobby-1", "lobby", "node-1", InstanceState.STOPPED, 25565, 0, 0, Instant.now());
        assertThrows(IllegalStateException.class, () -> service.saveToTemplate(stopped, "lobby-tpl", null));
    }

    @Test
    void rejectsUnknownTemplate() {
        assertThrows(IllegalArgumentException.class, () -> service.saveToTemplate(running("lobby-1"), "ghost", null));
    }

    @Test
    void failsWhenNothingCaptured() throws IOException {
        templateManager.save(new TemplateConfig("lobby-tpl", "", "", "", 0));
        when(fileAccess.walk(any(), any(), any()))
                .thenReturn(new InstanceFileTree(List.of(new InstanceFileEntry("server.jar", 100, false, 0L)), false, ""));
        assertThrows(IllegalStateException.class, () -> service.saveToTemplate(running("lobby-1"), "lobby-tpl", null));
    }
}
