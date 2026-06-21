package me.prexorjustin.prexorcloud.controller.rest.middleware;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import me.prexorjustin.prexorcloud.controller.cluster.Leadership;

import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import org.junit.jupiter.api.Test;

class LeaderRedirectMiddlewareTest {

    private static Leadership follower() {
        return new Leadership() {
            @Override
            public boolean isLeader() {
                return false;
            }

            @Override
            public long currentEpoch() {
                return 0L;
            }
        };
    }

    @Test
    void leaderServesLocallyWithoutTouchingTheRequest() {
        var mw = new LeaderRedirectMiddleware(Leadership.alwaysLeader(), () -> "leader:8080");
        Context ctx = mock(Context.class);

        mw.handle(ctx);

        verifyNoInteractions(ctx);
    }

    @Test
    void followerRedirectsApiRequestToLeaderPreservingPathAndQuery() {
        var mw = new LeaderRedirectMiddleware(follower(), () -> "10.0.0.3:8080");
        Context ctx = mock(Context.class);
        when(ctx.method()).thenReturn(HandlerType.POST);
        when(ctx.path()).thenReturn("/api/v1/groups");
        when(ctx.scheme()).thenReturn("https");
        when(ctx.queryString()).thenReturn("page=2");

        mw.handle(ctx);

        verify(ctx).status(307);
        verify(ctx).header("Location", "https://10.0.0.3:8080/api/v1/groups?page=2");
        verify(ctx).skipRemainingHandlers();
    }

    @Test
    void followerWithNoKnownLeaderReturns503() {
        var mw = new LeaderRedirectMiddleware(follower(), () -> "");
        Context ctx = mock(Context.class);
        when(ctx.method()).thenReturn(HandlerType.GET);
        when(ctx.path()).thenReturn("/api/v1/groups");

        mw.handle(ctx);

        verify(ctx).status(503);
        verify(ctx).header("Retry-After", "2");
        verify(ctx).skipRemainingHandlers();
        verify(ctx, never()).header(eq("Location"), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void followerDoesNotRedirectExemptHealthProbe() {
        var mw = new LeaderRedirectMiddleware(follower(), () -> "10.0.0.3:8080");
        Context ctx = mock(Context.class);
        when(ctx.method()).thenReturn(HandlerType.GET);
        when(ctx.path()).thenReturn("/api/v1/system/health");

        mw.handle(ctx);

        verify(ctx, never()).status(anyInt());
        verify(ctx, never()).skipRemainingHandlers();
    }

    @Test
    void followerDoesNotRedirectOptionsPreflight() {
        var mw = new LeaderRedirectMiddleware(follower(), () -> "10.0.0.3:8080");
        Context ctx = mock(Context.class);
        when(ctx.method()).thenReturn(HandlerType.OPTIONS);

        mw.handle(ctx);

        verify(ctx, never()).status(anyInt());
        verify(ctx, never()).skipRemainingHandlers();
    }
}
