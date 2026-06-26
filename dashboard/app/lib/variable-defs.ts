import type { VariableDef, VariableScope, VariableVisibility, VarType } from "~/types/api"

/** Selectable option lists for the typed variable editor. */
export const VAR_TYPES: VarType[] = ["STRING", "INT", "BOOL", "ENUM", "SECRET"]
export const VAR_SCOPES: VariableScope[] = ["TEMPLATE", "GROUP", "INSTANCE"]
export const VAR_VISIBILITIES: VariableVisibility[] = ["ADMIN", "OPERATOR"]

/**
 * A fresh, fully-typed variable definition with sane defaults. Used both when
 * the operator adds an empty row and when "scan for placeholders" turns a bare
 * `{{KEY}}` into an editable definition.
 */
export function newVariableDef(key = ""): VariableDef {
  return {
    key,
    type: "STRING",
    defaultValue: "",
    required: false,
    scope: "TEMPLATE",
    visibility: "OPERATOR",
    description: "",
  }
}
