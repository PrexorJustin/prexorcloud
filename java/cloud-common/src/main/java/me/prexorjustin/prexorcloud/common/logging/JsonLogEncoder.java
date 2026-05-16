package me.prexorjustin.prexorcloud.common.logging;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.encoder.EncoderBase;

/**
 * Logback encoder that outputs structured JSON, one object per line (NDJSON).
 *
 * <p>
 * Includes MDC properties for correlation in distributed cloud environments
 * (request IDs, trace context, node IDs).
 * </p>
 */
public class JsonLogEncoder extends EncoderBase<ILoggingEvent> {

    private static final byte[] EMPTY = new byte[0];
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    @Override
    public byte[] headerBytes() {
        return EMPTY;
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"timestamp\":\"");
        sb.append(FORMATTER.format(Instant.ofEpochMilli(event.getTimeStamp())));
        sb.append("\",\"level\":\"").append(event.getLevel());
        sb.append("\",\"logger\":\"").append(escapeJson(event.getLoggerName()));
        sb.append("\",\"thread\":\"").append(escapeJson(event.getThreadName()));
        sb.append("\",\"message\":\"").append(escapeJson(event.getFormattedMessage()));
        sb.append('"');

        appendMdc(sb, event.getMDCPropertyMap());
        appendThrowable(sb, event.getThrowableProxy());

        sb.append("}\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public byte[] footerBytes() {
        return EMPTY;
    }

    private static void appendMdc(StringBuilder sb, Map<String, String> mdc) {
        if (mdc == null || mdc.isEmpty()) return;
        sb.append(",\"mdc\":{");
        boolean first = true;
        for (Map.Entry<String, String> entry : mdc.entrySet()) {
            if (!first) sb.append(',');
            sb.append('"')
                    .append(escapeJson(entry.getKey()))
                    .append("\":\"")
                    .append(escapeJson(entry.getValue()))
                    .append('"');
            first = false;
        }
        sb.append('}');
    }

    private static void appendThrowable(StringBuilder sb, IThrowableProxy tp) {
        if (tp == null) return;
        sb.append(",\"exception\":\"")
                .append(escapeJson(ThrowableProxyUtil.asString(tp)))
                .append('"');
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append("\\u%04x".formatted(c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
