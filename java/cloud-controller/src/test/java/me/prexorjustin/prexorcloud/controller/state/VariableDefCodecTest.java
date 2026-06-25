package me.prexorjustin.prexorcloud.controller.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import me.prexorjustin.prexorcloud.controller.group.spec.VariableDef;
import me.prexorjustin.prexorcloud.controller.group.spec.VariableDef.Scope;
import me.prexorjustin.prexorcloud.controller.group.spec.VariableDef.Validation;
import me.prexorjustin.prexorcloud.controller.group.spec.VariableDef.VarType;
import me.prexorjustin.prexorcloud.controller.group.spec.VariableDef.Visibility;

import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("VariableDefCodec")
class VariableDefCodecTest {

    @Test
    @DisplayName("reads a legacy untyped {key,value,description} doc as a STRING/INSTANCE/OPERATOR def")
    void legacyDocReadsAsStringDef() {
        Document legacy = new Document("key", "motd").append("value", "Welcome").append("description", "the motd");

        VariableDef def = VariableDefCodec.fromDocument(legacy);

        assertEquals("motd", def.key());
        assertEquals(VarType.STRING, def.type());
        assertEquals("Welcome", def.defaultValue());
        assertEquals(false, def.required());
        assertEquals(Scope.INSTANCE, def.scope());
        assertEquals(Visibility.OPERATOR, def.visibility());
        assertNull(def.validation());
    }

    @Test
    @DisplayName("round-trips a typed INT def with min/max validation")
    void roundTripsIntWithRange() {
        VariableDef def = new VariableDef(
                "maxp",
                VarType.INT,
                "20",
                true,
                new Validation(null, 1L, 100L, null),
                Scope.GROUP,
                Visibility.ADMIN,
                "max players");

        VariableDef back = VariableDefCodec.fromDocument(VariableDefCodec.toDocument(def));

        assertEquals(def, back);
    }

    @Test
    @DisplayName("round-trips an ENUM def with allowed values")
    void roundTripsEnum() {
        VariableDef def = new VariableDef(
                "difficulty",
                VarType.ENUM,
                "normal",
                false,
                new Validation(null, null, null, List.of("peaceful", "easy", "normal", "hard")),
                Scope.GROUP,
                Visibility.OPERATOR,
                "world difficulty");

        VariableDef back = VariableDefCodec.fromDocument(VariableDefCodec.toDocument(def));

        assertEquals(def, back);
    }

    @Test
    @DisplayName("keeps the 'value' key so the build-time {{}} default still reads")
    void writesValueKeyForLegacyReadPath() {
        VariableDef def = new VariableDef(
                "brand", VarType.STRING, "Prexor", false, null, Scope.TEMPLATE, Visibility.OPERATOR, "brand");

        Document doc = VariableDefCodec.toDocument(def);

        assertEquals("Prexor", doc.getString("value"));
        assertEquals("brand", doc.getString("key"));
    }
}
