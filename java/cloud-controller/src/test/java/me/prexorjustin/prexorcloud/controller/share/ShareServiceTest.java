package me.prexorjustin.prexorcloud.controller.share;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import me.prexorjustin.prexorcloud.controller.config.ShareConfig;
import me.prexorjustin.prexorcloud.controller.crash.CrashRecord;
import me.prexorjustin.prexorcloud.controller.metrics.MetricsCollector;
import me.prexorjustin.prexorcloud.controller.observability.ControllerConfigRedactor;
import me.prexorjustin.prexorcloud.controller.state.StateStore;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ShareServiceTest {

    private static final ShareConfig ENABLED = new ShareConfig(true, "https://pste.dev", null, "1d", true, false);
    private static final ShareConfig DISABLED = new ShareConfig(false, "https://pste.dev", null, "1d", true, false);
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-05-15T10:00:00Z"), ZoneOffset.UTC);

    private static PasteResult fakeResult() {
        return new PasteResult(
                "abc",
                "https://pste.dev/abc",
                "https://pste.dev/abc/raw",
                "deltok",
                Instant.parse("2026-05-16T10:00:00Z"),
                "text",
                false);
    }

    private static PasteResult fakeResultWithoutDeleteToken() {
        return new PasteResult(
                "abc",
                "https://pste.dev/abc",
                "https://pste.dev/abc/raw",
                null,
                Instant.parse("2026-05-16T10:00:00Z"),
                "text",
                false);
    }

    private static CrashRecord crash(String id) {
        return new CrashRecord(
                id,
                "lobby-1",
                "lobby",
                "node-a",
                139,
                "OOM",
                "OutOfMemoryError",
                "sig-x",
                List.of("INFO Authorization: Bearer leaked-token", "ERROR password=hunter2 ip=10.1.2.3"),
                30_000,
                Instant.parse("2026-05-15T09:55:00Z"));
    }

    @Test
    void disabledThrowsShareNotConfiguredException() {
        PasteClient client = mock(PasteClient.class);
        ShareService service = new ShareService(DISABLED, client, FIXED_CLOCK);

        assertThrows(
                ShareNotConfiguredException.class,
                () -> service.shareLogText(
                        ShareKind.CONTROLLER_LOGS, null, "x", List.of("y"), null, ShareContext.system()));
        verify(client, never()).create(any(), any());
    }

    @Test
    void crashShareRunsLogTailThroughRedactor() {
        PasteClient client = mock(PasteClient.class);
        when(client.create(any(), any())).thenReturn(fakeResult());
        ShareService service = new ShareService(ENABLED, client, FIXED_CLOCK);

        ShareResult result = service.shareCrash(crash("crash-1"), ShareRequest.empty(), ShareContext.system());
        assertEquals("https://pste.dev/abc", result.url());

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(client, atLeastOnce()).create(body.capture(), any());
        String text = body.getValue();
        assertFalse(text.contains("leaked-token"), text);
        assertFalse(text.contains("hunter2"), text);
        assertFalse(text.contains("10.1.2.3"), text);
        assertTrue(text.contains(ControllerConfigRedactor.REDACTED), text);
    }

    @Test
    void overrideExpiryAndBurnAfterRead() {
        PasteClient client = mock(PasteClient.class);
        when(client.create(any(), any())).thenReturn(fakeResult());
        ShareService service = new ShareService(ENABLED, client, FIXED_CLOCK);

        service.shareLogText(
                ShareKind.CONTROLLER_LOGS,
                null,
                "logs",
                List.of("hello"),
                new ShareRequest("30d", null, true),
                ShareContext.system());

        ArgumentCaptor<PasteOptions> opts = ArgumentCaptor.forClass(PasteOptions.class);
        verify(client).create(any(), opts.capture());
        assertEquals("30d", opts.getValue().expiry());
        assertEquals(true, opts.getValue().burnAfterRead());
    }

    @Test
    void enforcesCeilingWithTruncationMarker() {
        String big = "a".repeat(ShareService.BYTE_CEILING + 1024);
        String bounded = ShareService.enforceCeiling(big);
        assertTrue(bounded.length() < big.length(), "must shrink");
        assertTrue(bounded.contains("[truncated"), "must include truncation marker");
    }

    @Test
    void shareTextRedactsEveryLineBeforeUpload() {
        PasteClient client = mock(PasteClient.class);
        when(client.create(any(), any())).thenReturn(fakeResult());
        ShareService service = new ShareService(ENABLED, client, FIXED_CLOCK);

        String body = "line one\npassword=hunter2\nline three";
        service.shareText("diag", body, ShareRequest.empty(), ShareContext.system());

        ArgumentCaptor<String> uploaded = ArgumentCaptor.forClass(String.class);
        verify(client).create(uploaded.capture(), any());
        assertFalse(uploaded.getValue().contains("hunter2"), uploaded.getValue());
        assertTrue(uploaded.getValue().contains("line one"));
        assertTrue(uploaded.getValue().contains("line three"));
    }

    @Test
    void shareCrashForcesBurnAfterReadOnByDefault() {
        PasteClient client = mock(PasteClient.class);
        when(client.create(any(), any())).thenReturn(fakeResult());
        ShareConfig configWithBurnOff = new ShareConfig(true, "https://pste.dev", null, "1d", true, false);
        ShareService service = new ShareService(configWithBurnOff, client, FIXED_CLOCK);

        service.shareCrash(crash("c-bar"), ShareRequest.empty(), ShareContext.system());

        ArgumentCaptor<PasteOptions> opts = ArgumentCaptor.forClass(PasteOptions.class);
        verify(client).create(any(), opts.capture());
        assertTrue(
                opts.getValue().burnAfterRead(),
                "shareCrash always defaults burnAfterRead=true via BurnDefault.SINGLE_READER");
    }

    @Test
    void shareLogTextForcesBurnAfterReadOnByDefault() {
        PasteClient client = mock(PasteClient.class);
        when(client.create(any(), any())).thenReturn(fakeResult());
        ShareConfig configWithBurnOff = new ShareConfig(true, "https://pste.dev", null, "1d", true, false);
        ShareService service = new ShareService(configWithBurnOff, client, FIXED_CLOCK);

        service.shareLogText(
                ShareKind.CONTROLLER_LOGS, null, "logs", List.of("hello"), ShareRequest.empty(), ShareContext.system());

        ArgumentCaptor<PasteOptions> opts = ArgumentCaptor.forClass(PasteOptions.class);
        verify(client).create(any(), opts.capture());
        assertTrue(
                opts.getValue().burnAfterRead(),
                "shareLogText always defaults burnAfterRead=true via BurnDefault.SINGLE_READER");
    }

    @Test
    void shareDiagnosticsForcesBurnAfterReadOffByDefault() {
        PasteClient client = mock(PasteClient.class);
        when(client.create(any(), any())).thenReturn(fakeResult());
        ShareConfig configWithBurnOn = new ShareConfig(true, "https://pste.dev", null, "1d", true, false);
        ShareService service = new ShareService(configWithBurnOn, client, FIXED_CLOCK);

        service.shareText("diag", "hello", ShareRequest.empty(), ShareContext.system());

        ArgumentCaptor<PasteOptions> opts = ArgumentCaptor.forClass(PasteOptions.class);
        verify(client).create(any(), opts.capture());
        assertFalse(
                opts.getValue().burnAfterRead(),
                "shareText (diagnostics) always defaults burnAfterRead=false via BurnDefault.MULTI_READER");
    }

    @Test
    void perRequestOverrideStillWinsForCrashShare() {
        PasteClient client = mock(PasteClient.class);
        when(client.create(any(), any())).thenReturn(fakeResult());
        ShareService service = new ShareService(ENABLED, client, FIXED_CLOCK);

        service.shareCrash(crash("c-2"), new ShareRequest(null, null, false), ShareContext.system());

        ArgumentCaptor<PasteOptions> opts = ArgumentCaptor.forClass(PasteOptions.class);
        verify(client).create(any(), opts.capture());
        assertFalse(opts.getValue().burnAfterRead(), "per-request burnAfterRead=false must win");
    }

    @Test
    void exposesDeleteTokenAndBuildsDeleteUrlOnResult() {
        PasteClient client = mock(PasteClient.class);
        when(client.create(any(), any())).thenReturn(fakeResult());
        ShareService service = new ShareService(ENABLED, client, FIXED_CLOCK);

        ShareResult result = service.shareLogText(
                ShareKind.CONTROLLER_LOGS, null, "logs", List.of("hi"), ShareRequest.empty(), ShareContext.system());

        assertEquals("deltok", result.deleteToken());
        assertEquals("https://pste.dev/api/v1/paste/deltok", result.deleteUrl());
        assertNotNull(result.shareId(), "shareId is always assigned even without a persistence layer");
    }

    @Test
    void deleteUrlIsNullWhenUpstreamReturnsNoToken() {
        PasteClient client = mock(PasteClient.class);
        when(client.create(any(), any())).thenReturn(fakeResultWithoutDeleteToken());
        ShareService service = new ShareService(ENABLED, client, FIXED_CLOCK);

        ShareResult result = service.shareLogText(
                ShareKind.CONTROLLER_LOGS, null, "logs", List.of("hi"), ShareRequest.empty(), ShareContext.system());

        assertNull(result.deleteToken());
        assertNull(result.deleteUrl());
    }

    @Test
    void persistsShareRecordAndAuditsOnSuccess() {
        PasteClient client = mock(PasteClient.class);
        when(client.create(any(), any())).thenReturn(fakeResult());
        StateStore store = mock(StateStore.class);
        MetricsCollector metrics = mock(MetricsCollector.class);
        ShareService service = new ShareService(ENABLED, client, FIXED_CLOCK, store, metrics);

        ShareResult result =
                service.shareCrash(crash("c-3"), ShareRequest.empty(), ShareContext.of("alice", "1.2.3.4"));

        verify(store).saveShareRecord(any(ShareRecord.class));
        verify(store)
                .audit(eq("alice"), eq("CREATE_SHARE"), eq("share"), eq(result.shareId()), anyString(), eq("1.2.3.4"));
        verify(metrics).recordShareAttempt("CRASH", "success");
        verify(metrics).recordShareUploadBytes(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void revokeCallsPasteDeleteAndMarksRevoked() {
        PasteClient client = mock(PasteClient.class);
        StateStore store = mock(StateStore.class);
        MetricsCollector metrics = mock(MetricsCollector.class);
        var existing = new ShareRecord(
                "share-1",
                ShareKind.CRASH,
                "crash-1",
                "https://pste.dev/abc",
                "https://pste.dev/abc/raw",
                "deltok",
                Instant.parse("2026-05-16T10:00:00Z"),
                true,
                true,
                100L,
                "alice",
                Instant.parse("2026-05-15T10:00:00Z"),
                null);
        when(store.getShareRecord("share-1")).thenReturn(Optional.of(existing));
        when(client.delete("deltok")).thenReturn(true);
        ShareService service = new ShareService(ENABLED, client, FIXED_CLOCK, store, metrics);

        var revoked = service.revoke("share-1", ShareContext.of("alice", "1.2.3.4"));

        verify(client).delete("deltok");
        verify(store).markShareRevoked(eq("share-1"), any());
        verify(store).audit(eq("alice"), eq("REVOKE_SHARE"), eq("share"), eq("share-1"), anyString(), eq("1.2.3.4"));
        verify(metrics).recordShareRevoke("success");
        assertNotNull(revoked.revokedAt());
    }

    @Test
    void revokeOnUnknownIdThrowsNoSuchElement() {
        PasteClient client = mock(PasteClient.class);
        StateStore store = mock(StateStore.class);
        when(store.getShareRecord("nope")).thenReturn(Optional.empty());
        ShareService service = new ShareService(ENABLED, client, FIXED_CLOCK, store, null);

        assertThrows(NoSuchElementException.class, () -> service.revoke("nope", ShareContext.system()));
        verify(client, never()).delete(anyString());
    }

    @Test
    void revokeOnAlreadyRevokedThrows() {
        PasteClient client = mock(PasteClient.class);
        StateStore store = mock(StateStore.class);
        var existing = new ShareRecord(
                "share-2",
                ShareKind.CRASH,
                null,
                "url",
                "rawUrl",
                "deltok",
                null,
                true,
                true,
                1L,
                "u",
                Instant.EPOCH,
                Instant.parse("2026-05-15T09:00:00Z"));
        when(store.getShareRecord("share-2")).thenReturn(Optional.of(existing));
        ShareService service = new ShareService(ENABLED, client, FIXED_CLOCK, store, null);

        assertThrows(ShareAlreadyRevokedException.class, () -> service.revoke("share-2", ShareContext.system()));
        verify(client, never()).delete(anyString());
    }

    @Test
    void revokeWithoutDeleteTokenThrowsNotRevocable() {
        PasteClient client = mock(PasteClient.class);
        StateStore store = mock(StateStore.class);
        MetricsCollector metrics = mock(MetricsCollector.class);
        var existing = new ShareRecord(
                "share-3",
                ShareKind.CRASH,
                null,
                "url",
                "rawUrl",
                null,
                null,
                true,
                true,
                1L,
                "u",
                Instant.EPOCH,
                null);
        when(store.getShareRecord("share-3")).thenReturn(Optional.of(existing));
        ShareService service = new ShareService(ENABLED, client, FIXED_CLOCK, store, metrics);

        assertThrows(ShareNotRevocableException.class, () -> service.revoke("share-3", ShareContext.system()));
        verify(metrics).recordShareRevoke("missing-token");
        verify(client, never()).delete(anyString());
    }

    @Test
    void metricsOnUpstreamError() {
        PasteClient client = mock(PasteClient.class);
        when(client.create(any(), any())).thenThrow(new PasteException("nope", 413, null, null));
        MetricsCollector metrics = mock(MetricsCollector.class);
        ShareService service = new ShareService(ENABLED, client, FIXED_CLOCK, null, metrics);

        assertThrows(
                PasteException.class,
                () -> service.shareCrash(crash("c-4"), ShareRequest.empty(), ShareContext.system()));
        verify(metrics, times(1)).recordShareAttempt("CRASH", "error");
        verify(metrics, times(1)).recordShareUpstreamError(413);
    }
}
