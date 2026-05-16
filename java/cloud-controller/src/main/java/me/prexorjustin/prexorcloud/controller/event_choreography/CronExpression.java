package me.prexorjustin.prexorcloud.controller.event_choreography;

import java.time.ZonedDateTime;
import java.util.BitSet;
import java.util.Locale;

/**
 * Five-field cron parser ({@code "m h dom mon dow"}). Each field supports
 * {@code *}, comma lists, dashed ranges, and {@code /step}. No seconds, no
 * aliases, no {@code @hourly}-style shorthands.
 *
 * <p>Day-of-month and day-of-week use Vixie-style OR semantics: if both fields
 * are restricted (non-{@code *}), the cron matches when <em>either</em> field
 * matches; if exactly one is restricted, only that field is consulted.
 *
 * <p>DOW uses cron numbering: {@code 0} and {@code 7} both denote Sunday.
 */
public final class CronExpression {

    private final BitSet minutes;
    private final BitSet hours;
    private final BitSet daysOfMonth;
    private final BitSet months;
    private final BitSet daysOfWeek;
    private final boolean domRestricted;
    private final boolean dowRestricted;
    private final String raw;

    private CronExpression(
            BitSet minutes,
            BitSet hours,
            BitSet daysOfMonth,
            BitSet months,
            BitSet daysOfWeek,
            boolean domRestricted,
            boolean dowRestricted,
            String raw) {
        this.minutes = minutes;
        this.hours = hours;
        this.daysOfMonth = daysOfMonth;
        this.months = months;
        this.daysOfWeek = daysOfWeek;
        this.domRestricted = domRestricted;
        this.dowRestricted = dowRestricted;
        this.raw = raw;
    }

    public static CronExpression parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("cron expression is blank");
        }
        String trimmed = expression.trim();
        String[] fields = trimmed.split("\\s+");
        if (fields.length != 5) {
            throw new IllegalArgumentException("cron expression must have 5 fields (m h dom mon dow), got: " + trimmed);
        }
        BitSet minutes = parseField(fields[0], 0, 59, "minute");
        BitSet hours = parseField(fields[1], 0, 23, "hour");
        BitSet daysOfMonth = parseField(fields[2], 1, 31, "dayOfMonth");
        BitSet months = parseField(fields[3], 1, 12, "month");
        BitSet daysOfWeekRaw = parseField(fields[4], 0, 7, "dayOfWeek");
        BitSet daysOfWeek = new BitSet(7);
        for (int i = 0; i <= 7; i++) {
            if (daysOfWeekRaw.get(i)) daysOfWeek.set(i % 7);
        }
        boolean domRestricted = !"*".equals(fields[2]);
        boolean dowRestricted = !"*".equals(fields[4]);
        return new CronExpression(
                minutes, hours, daysOfMonth, months, daysOfWeek, domRestricted, dowRestricted, trimmed);
    }

    public boolean matches(ZonedDateTime time) {
        if (!minutes.get(time.getMinute())) return false;
        if (!hours.get(time.getHour())) return false;
        if (!months.get(time.getMonthValue())) return false;
        boolean domMatch = daysOfMonth.get(time.getDayOfMonth());
        int dowCron = time.getDayOfWeek().getValue() % 7; // Mon=1..Sun=7→0
        boolean dowMatch = daysOfWeek.get(dowCron);
        if (domRestricted && dowRestricted) {
            return domMatch || dowMatch;
        }
        if (domRestricted) return domMatch;
        if (dowRestricted) return dowMatch;
        return true;
    }

    public String raw() {
        return raw;
    }

    private static BitSet parseField(String field, int min, int max, String label) {
        BitSet bits = new BitSet(max + 1);
        for (String part : field.split(",")) {
            String value = part.trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("empty " + label + " entry in: " + field);
            }
            int step = 1;
            String range = value;
            int slash = value.indexOf('/');
            if (slash >= 0) {
                String stepStr = value.substring(slash + 1);
                if (stepStr.isEmpty()) {
                    throw new IllegalArgumentException(label + " step is empty in: " + value);
                }
                try {
                    step = Integer.parseInt(stepStr);
                } catch (NumberFormatException _) {
                    throw new IllegalArgumentException(label + " step is not a number in: " + value);
                }
                if (step <= 0) {
                    throw new IllegalArgumentException(label + " step must be > 0 in: " + value);
                }
                range = value.substring(0, slash);
            }
            int from;
            int to;
            if ("*".equals(range)) {
                from = min;
                to = max;
            } else if (range.contains("-")) {
                String[] bounds = range.split("-", 2);
                from = parseInt(bounds[0], label, value);
                to = parseInt(bounds[1], label, value);
            } else {
                from = parseInt(range, label, value);
                to = step == 1 ? from : max;
            }
            if (from < min || to > max || from > to) {
                throw new IllegalArgumentException(
                        label + " range " + from + "-" + to + " is outside [" + min + "," + max + "] in: " + value);
            }
            for (int i = from; i <= to; i += step) {
                bits.set(i);
            }
        }
        if (bits.isEmpty()) {
            throw new IllegalArgumentException("empty " + label + " bitset from: " + field);
        }
        return bits;
    }

    private static int parseInt(String token, String label, String fullValue) {
        try {
            return Integer.parseInt(token.trim());
        } catch (NumberFormatException _) {
            throw new IllegalArgumentException(label + " expected integer, got '" + token + "' in: " + fullValue);
        }
    }

    @Override
    public String toString() {
        return "CronExpression[" + raw.toLowerCase(Locale.ROOT) + "]";
    }
}
