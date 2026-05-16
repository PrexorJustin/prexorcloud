package me.prexorjustin.prexorcloud.api.module;

import java.util.ArrayList;
import java.util.List;

/**
 * A conjunction (AND) of semver comparators. Supports the subset the
 * ModuleSystem v3 manifest needs:
 *
 * <ul>
 *   <li>{@code *} or empty — matches anything
 *   <li>{@code X.Y.Z} (bare) — exact match, equivalent to {@code =X.Y.Z}
 *   <li>{@code [1.20,1.21)}, {@code (1.20,1.21]}, {@code [1.20,)}, {@code (,1.21]} — interval syntax
 *   <li>{@code >=X.Y.Z}, {@code >X.Y.Z}, {@code <=X.Y.Z}, {@code <X.Y.Z}, {@code =X.Y.Z}
 *   <li>{@code ^X.Y.Z} — {@code >=X.Y.Z <(X+1).0.0}
 *   <li>{@code ~X.Y.Z} — {@code >=X.Y.Z <X.(Y+1).0}
 *   <li>Whitespace-separated compound: {@code ">=1.0.0 <2.0.0"}
 * </ul>
 *
 * <p>No OR ({@code ||}). If a future slice needs it, revisit. Range matching is
 * deterministic and free of external dependencies.
 */
public final class SemverRange {

    public static final SemverRange ANY = new SemverRange(List.of(), "*");

    public enum Op {
        EQ,
        GT,
        GTE,
        LT,
        LTE
    }

    public record Bound(Version value, boolean inclusive) {}

    public record Constraint(Op op, Version bound) {}

    private final List<Constraint> constraints;
    private final String raw;

    private SemverRange(List<Constraint> constraints, String raw) {
        this.constraints = List.copyOf(constraints);
        this.raw = raw;
    }

    public static SemverRange parse(String input) {
        if (input == null) {
            throw new IllegalArgumentException("range must not be null");
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty() || trimmed.equals("*")) {
            return new SemverRange(List.of(), trimmed.isEmpty() ? "*" : trimmed);
        }
        if ((trimmed.startsWith("[") || trimmed.startsWith("(")) && (trimmed.endsWith("]") || trimmed.endsWith(")"))) {
            return parseInterval(trimmed, input);
        }

        List<Constraint> out = new ArrayList<>();
        for (String token : trimmed.split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            out.addAll(parseToken(token, input));
        }
        if (out.isEmpty()) {
            return new SemverRange(List.of(), trimmed);
        }
        return new SemverRange(out, trimmed);
    }

    private static SemverRange parseInterval(String token, String original) {
        if (token.length() < 3) {
            throw new IllegalArgumentException("invalid interval range: " + original);
        }

        boolean includeLower = token.charAt(0) == '[';
        boolean includeUpper = token.charAt(token.length() - 1) == ']';
        String body = token.substring(1, token.length() - 1);
        int comma = body.indexOf(',');
        if (comma < 0 || body.indexOf(',', comma + 1) >= 0) {
            throw new IllegalArgumentException("interval range must contain exactly one comma: " + original);
        }

        String lowerToken = body.substring(0, comma).trim();
        String upperToken = body.substring(comma + 1).trim();
        if (lowerToken.isEmpty() && upperToken.isEmpty()) {
            throw new IllegalArgumentException("interval range must declare at least one bound: " + original);
        }

        List<Constraint> out = new ArrayList<>(2);
        if (!lowerToken.isEmpty() && !lowerToken.equals("*")) {
            Version lower = Version.parse(lowerToken);
            out.add(new Constraint(includeLower ? Op.GTE : Op.GT, lower));
        }
        if (!upperToken.isEmpty() && !upperToken.equals("*")) {
            Version upper = Version.parse(upperToken);
            out.add(new Constraint(includeUpper ? Op.LTE : Op.LT, upper));
        }

        if (out.isEmpty()) {
            return new SemverRange(List.of(), "*");
        }
        return new SemverRange(out, token);
    }

    private static List<Constraint> parseToken(String token, String original) {
        try {
            if (token.startsWith("^")) {
                Version v = Version.parse(token.substring(1));
                Version upper = Version.of(v.major() + 1, 0, 0);
                return List.of(new Constraint(Op.GTE, v), new Constraint(Op.LT, upper));
            }
            if (token.startsWith("~")) {
                Version v = Version.parse(token.substring(1));
                Version upper = Version.of(v.major(), v.minor() + 1, 0);
                return List.of(new Constraint(Op.GTE, v), new Constraint(Op.LT, upper));
            }
            if (token.startsWith(">=")) {
                return List.of(new Constraint(Op.GTE, Version.parse(token.substring(2))));
            }
            if (token.startsWith("<=")) {
                return List.of(new Constraint(Op.LTE, Version.parse(token.substring(2))));
            }
            if (token.startsWith(">")) {
                return List.of(new Constraint(Op.GT, Version.parse(token.substring(1))));
            }
            if (token.startsWith("<")) {
                return List.of(new Constraint(Op.LT, Version.parse(token.substring(1))));
            }
            if (token.startsWith("=")) {
                return List.of(new Constraint(Op.EQ, Version.parse(token.substring(1))));
            }
            return List.of(new Constraint(Op.EQ, Version.parse(token)));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid range token '" + token + "' in: " + original, e);
        }
    }

    public boolean contains(Version v) {
        if (v == null) {
            return false;
        }
        for (Constraint c : constraints) {
            int cmp = v.compareTo(c.bound());
            boolean ok =
                    switch (c.op()) {
                        case EQ -> cmp == 0;
                        case GT -> cmp > 0;
                        case GTE -> cmp >= 0;
                        case LT -> cmp < 0;
                        case LTE -> cmp <= 0;
                    };
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    public boolean isAny() {
        return constraints.isEmpty();
    }

    public boolean isExact() {
        var lower = lowerBound();
        var upper = upperBound();
        return lower.isPresent()
                && upper.isPresent()
                && lower.get().inclusive()
                && upper.get().inclusive()
                && lower.get().value().equals(upper.get().value());
    }

    public java.util.Optional<Bound> lowerBound() {
        Bound strongest = null;
        for (Constraint constraint : constraints) {
            Bound candidate =
                    switch (constraint.op()) {
                        case EQ, GTE -> new Bound(constraint.bound(), true);
                        case GT -> new Bound(constraint.bound(), false);
                        case LT, LTE -> null;
                    };
            if (candidate == null) {
                continue;
            }
            if (strongest == null) {
                strongest = candidate;
                continue;
            }
            int cmp = candidate.value().compareTo(strongest.value());
            if (cmp > 0 || (cmp == 0 && !candidate.inclusive() && strongest.inclusive())) {
                strongest = candidate;
            }
        }
        return java.util.Optional.ofNullable(strongest);
    }

    public java.util.Optional<Bound> upperBound() {
        Bound strongest = null;
        for (Constraint constraint : constraints) {
            Bound candidate =
                    switch (constraint.op()) {
                        case EQ, LTE -> new Bound(constraint.bound(), true);
                        case LT -> new Bound(constraint.bound(), false);
                        case GT, GTE -> null;
                    };
            if (candidate == null) {
                continue;
            }
            if (strongest == null) {
                strongest = candidate;
                continue;
            }
            int cmp = candidate.value().compareTo(strongest.value());
            if (cmp < 0 || (cmp == 0 && !candidate.inclusive() && strongest.inclusive())) {
                strongest = candidate;
            }
        }
        return java.util.Optional.ofNullable(strongest);
    }

    public List<Constraint> constraints() {
        return constraints;
    }

    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return raw;
    }
}
