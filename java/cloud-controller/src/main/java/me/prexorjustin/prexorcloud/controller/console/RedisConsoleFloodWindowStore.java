package me.prexorjustin.prexorcloud.controller.console;

import java.util.List;

import me.prexorjustin.prexorcloud.controller.redis.RedisKeys;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * Redis-backed console flood window shared by active controllers. The local
 * console ring buffer remains process-local; only suppression state is shared
 * so failover does not reopen a fresh flood window.
 */
public final class RedisConsoleFloodWindowStore implements ConsoleBuffer.FloodWindowStore {

    private static final String RECORD_SCRIPT = """
            local started = tonumber(redis.call('HGET', KEYS[1], 'started'))
            local emitted = tonumber(redis.call('HGET', KEYS[1], 'emitted'))
            local suppressed = tonumber(redis.call('HGET', KEYS[1], 'suppressed'))
            local now = tonumber(ARGV[1])
            local max_lines = tonumber(ARGV[2])
            local window_ms = tonumber(ARGV[3])
            local previous_suppressed = 0

            if started == nil then
              started = now
              emitted = 0
              suppressed = 0
            elseif now - started >= window_ms then
              previous_suppressed = suppressed or 0
              started = now
              emitted = 0
              suppressed = 0
            end

            if emitted >= max_lines then
              suppressed = suppressed + 1
              redis.call('HSET', KEYS[1], 'started', started, 'emitted', emitted, 'suppressed', suppressed)
              redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[4]))
              return {0, previous_suppressed, suppressed}
            end

            emitted = emitted + 1
            redis.call('HSET', KEYS[1], 'started', started, 'emitted', emitted, 'suppressed', suppressed)
            redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[4]))
            return {1, previous_suppressed, 0}
            """;

    private final RedisCommands<String, String> commands;

    public RedisConsoleFloodWindowStore(RedisCommands<String, String> commands) {
        this.commands = commands;
    }

    @Override
    public ConsoleBuffer.FloodDecision recordLine(
            String instanceId, long timestampMs, int maxLinesPerWindow, long windowMs) {
        List<?> result = commands.eval(
                RECORD_SCRIPT,
                ScriptOutputType.MULTI,
                new String[] {RedisKeys.consoleWindow(instanceId)},
                Long.toString(timestampMs),
                Integer.toString(maxLinesPerWindow),
                Long.toString(windowMs),
                Long.toString(RedisKeys.consoleWindowRetention(windowMs).toMillis()));
        return new ConsoleBuffer.FloodDecision(
                toLong(result.get(0)) == 1L,
                Math.toIntExact(toLong(result.get(1))),
                Math.toIntExact(toLong(result.get(2))));
    }

    @Override
    public void clear(String instanceId) {
        commands.del(RedisKeys.consoleWindow(instanceId));
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}
