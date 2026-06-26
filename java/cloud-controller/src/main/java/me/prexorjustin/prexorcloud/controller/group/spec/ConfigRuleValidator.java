package me.prexorjustin.prexorcloud.controller.group.spec;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Structural validation of data-driven {@link ConfigRule}s at set time (Group/Template v2, Phase 3) --
 * the config-rule analogue of {@code VariableValidator.validateDefinitions}. It checks the rule shape,
 * not the target file: a rule must name a file and a path, and a {@code REGEX} rule's path must be a
 * compilable pattern (so a broken find/replace surfaces at set time, not when an instance starts).
 */
public final class ConfigRuleValidator {

    private ConfigRuleValidator() {}

    public static List<String> validateRules(List<ConfigRule> rules) {
        List<String> errors = new ArrayList<>();
        int index = 0;
        for (ConfigRule rule : rules) {
            String where = "config rule #" + index + (blank(rule.file()) ? "" : " (" + rule.file() + ")");
            if (blank(rule.file())) {
                errors.add(where + ": file must not be blank");
            }
            if (blank(rule.path())) {
                errors.add(where + ": path must not be blank");
            } else if (rule.op() == ConfigRule.Op.REGEX) {
                try {
                    Pattern.compile(rule.path());
                } catch (PatternSyntaxException e) {
                    errors.add(where + ": REGEX path is not a valid regular expression: " + e.getDescription());
                }
            }
            index++;
        }
        return List.copyOf(errors);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
