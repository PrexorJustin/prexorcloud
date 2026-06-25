package me.prexorjustin.prexorcloud.controller.state;

import java.util.List;

import me.prexorjustin.prexorcloud.controller.group.spec.VariableDef;
import me.prexorjustin.prexorcloud.controller.group.spec.VariableDef.Scope;
import me.prexorjustin.prexorcloud.controller.group.spec.VariableDef.Validation;
import me.prexorjustin.prexorcloud.controller.group.spec.VariableDef.VarType;
import me.prexorjustin.prexorcloud.controller.group.spec.VariableDef.Visibility;

import org.bson.Document;

/**
 * Bson codec for typed {@link VariableDef}s in the template {@code variables} field.
 *
 * <p>Back-compatible with the pre-v2 untyped layout {@code {key, value, description}}: the typed
 * fields ({@code type/required/scope/visibility/validation}) are optional and a legacy document with
 * only the three original keys reads as a {@code STRING}, {@code INSTANCE}-scoped, {@code OPERATOR}
 * variable whose {@code defaultValue} is the stored {@code value}. The {@code value} key is kept as the
 * persisted name for {@code defaultValue} so the build-time {@code {{var}}} substitution (which reads
 * each template variable's default) keeps working unchanged for templates authored before v2.
 */
final class VariableDefCodec {

    private VariableDefCodec() {}

    static Document toDocument(VariableDef def) {
        Document doc = new Document("key", def.key())
                .append("value", def.defaultValue())
                .append("description", def.description())
                .append("type", def.type().name())
                .append("required", def.required())
                .append("scope", def.scope().name())
                .append("visibility", def.visibility().name());

        Validation v = def.validation();
        if (v != null) {
            Document vd = new Document();
            if (v.regex() != null) vd.append("regex", v.regex());
            if (v.min() != null) vd.append("min", v.min());
            if (v.max() != null) vd.append("max", v.max());
            if (v.enumValues() != null && !v.enumValues().isEmpty()) vd.append("enumValues", v.enumValues());
            if (!vd.isEmpty()) doc.append("validation", vd);
        }
        return doc;
    }

    static VariableDef fromDocument(Document d) {
        VarType type = parseEnum(d.getString("type"), VarType.class, VarType.STRING);
        boolean required = d.getBoolean("required", false);
        Scope scope = parseEnum(d.getString("scope"), Scope.class, Scope.INSTANCE);
        Visibility visibility = parseEnum(d.getString("visibility"), Visibility.class, Visibility.OPERATOR);

        Validation validation = null;
        Document vd = d.get("validation", Document.class);
        if (vd != null) {
            validation =
                    new Validation(vd.getString("regex"), toLong(vd.get("min")), toLong(vd.get("max")), enumValues(vd));
        }

        return new VariableDef(
                d.getString("key"),
                type,
                d.getString("value"),
                required,
                validation,
                scope,
                visibility,
                d.getString("description"));
    }

    private static <E extends Enum<E>> E parseEnum(String raw, Class<E> type, E fallback) {
        if (raw == null) return fallback;
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static Long toLong(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> enumValues(Document vd) {
        Object raw = vd.get("enumValues");
        return raw instanceof List<?> list ? (List<String>) list : null;
    }
}
