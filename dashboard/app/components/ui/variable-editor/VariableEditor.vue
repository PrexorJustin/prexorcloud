<script setup lang="ts">
import { Plus, Trash2 } from "lucide-vue-next"
import type { VariableDef, VariableValidation } from "~/types/api"
import { Button } from "~/components/ui/button"
import { Input } from "~/components/ui/input"
import { Switch } from "~/components/ui/switch"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "~/components/ui/select"
import { newVariableDef, VAR_SCOPES, VAR_TYPES, VAR_VISIBILITIES } from "~/lib/variable-defs"

const props = defineProps<{
  modelValue: VariableDef[]
  readonly?: boolean
}>()

const emit = defineEmits<{
  "update:modelValue": [value: VariableDef[]]
}>()

const { t } = useI18n()

const KEY_PATTERN = /^[a-zA-Z_][a-zA-Z0-9_]*$/

function isKeyValid(key: string): boolean {
  return key.length === 0 || KEY_PATTERN.test(key)
}

const duplicateKeys = computed(() => {
  const counts = new Map<string, number>()
  for (const v of props.modelValue) {
    if (v.key) counts.set(v.key, (counts.get(v.key) ?? 0) + 1)
  }
  const dupes = new Set<string>()
  for (const [key, count] of counts) {
    if (count > 1) dupes.add(key)
  }
  return dupes
})

function hasKeyError(key: string | undefined): boolean {
  const k = key ?? ""
  return (!isKeyValid(k) && k.length > 0) || duplicateKeys.value.has(k)
}

function patchAt(index: number, patch: Partial<VariableDef>) {
  emit("update:modelValue", props.modelValue.map((v, i) => (i === index ? { ...v, ...patch } : { ...v })))
}

/**
 * Merge a validation patch, then prune empty fields so we never persist an
 * `{}` validation block (which the backend would otherwise have to interpret).
 */
function isEmptyValidationValue(value: unknown): boolean {
  return value === undefined || value === null || value === "" || (Array.isArray(value) && value.length === 0)
}

function patchValidation(index: number, patch: Partial<VariableValidation>) {
  const merged: VariableValidation = { ...(props.modelValue[index]?.validation ?? {}), ...patch }
  const cleaned = Object.fromEntries(
    Object.entries(merged).filter(([, value]) => !isEmptyValidationValue(value)),
  ) as VariableValidation
  patchAt(index, { validation: Object.keys(cleaned).length ? cleaned : undefined })
}

function parseNumberOrUndefined(raw: string): number | undefined {
  const trimmed = raw.trim()
  if (!trimmed) return undefined
  const n = Number(trimmed)
  return Number.isFinite(n) ? n : undefined
}

function parseEnumValues(raw: string): string[] {
  return raw.split(",").map(s => s.trim()).filter(Boolean)
}

function addVariable() {
  emit("update:modelValue", [...props.modelValue, newVariableDef()])
}

function removeVariable(index: number) {
  emit("update:modelValue", props.modelValue.filter((_, i) => i !== index))
}
</script>

<template>
  <div class="flex flex-col gap-3">
    <!-- Variable cards -->
    <div
      v-for="(variable, index) in modelValue"
      :key="index"
      class="rounded-xl border border-glass-border bg-glass/40 p-4 flex flex-col gap-3"
    >
      <!-- Row 1: key · type · required · remove -->
      <div class="grid grid-cols-1 sm:grid-cols-[1fr_auto_auto_auto] gap-3 items-end">
        <div class="flex flex-col gap-1">
          <span class="text-[11px] font-medium text-muted-foreground uppercase tracking-wider">{{ t('components.variableEditor.key') }}</span>
          <Input
            :model-value="variable.key"
            :disabled="readonly"
            :placeholder="t('components.variableEditor.keyPlaceholder')"
            :aria-invalid="hasKeyError(variable.key) || undefined"
            :class="['font-mono bg-glass border-glass-border rounded-lg text-sm h-9', hasKeyError(variable.key) ? 'border-destructive focus-visible:ring-destructive/50' : '']"
            @update:model-value="patchAt(index, { key: String($event) })"
          />
        </div>
        <div class="flex flex-col gap-1">
          <span class="text-[11px] font-medium text-muted-foreground uppercase tracking-wider">{{ t('components.variableEditor.type') }}</span>
          <Select :model-value="variable.type" :disabled="readonly" @update:model-value="patchAt(index, { type: $event as VariableDef['type'] })">
            <SelectTrigger class="h-9 w-full sm:w-32 bg-glass border-glass-border rounded-lg text-sm">
              <SelectValue :placeholder="t('components.variableEditor.type')" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem v-for="opt in VAR_TYPES" :key="opt" :value="opt">{{ opt }}</SelectItem>
            </SelectContent>
          </Select>
        </div>
        <div class="flex flex-col gap-1">
          <span class="text-[11px] font-medium text-muted-foreground uppercase tracking-wider">{{ t('components.variableEditor.required') }}</span>
          <div class="h-9 flex items-center">
            <Switch
              :model-value="variable.required"
              :disabled="readonly"
              :aria-label="t('components.variableEditor.required')"
              @update:model-value="patchAt(index, { required: Boolean($event) })"
            />
          </div>
        </div>
        <button
          v-if="!readonly"
          class="size-9 inline-flex items-center justify-center rounded-lg text-muted-foreground hover:text-destructive transition-colors self-end"
          :title="t('components.variableEditor.remove')"
          @click="removeVariable(index)"
        >
          <Trash2 class="size-4" />
        </button>
      </div>

      <!-- Row 2: default value · scope · visibility -->
      <div class="grid grid-cols-1 sm:grid-cols-[1fr_auto_auto] gap-3 items-end">
        <div class="flex flex-col gap-1">
          <span class="text-[11px] font-medium text-muted-foreground uppercase tracking-wider">{{ t('components.variableEditor.defaultValue') }}</span>
          <!-- BOOL → explicit true/false; SECRET → masked; otherwise free text -->
          <Select
            v-if="variable.type === 'BOOL'"
            :model-value="variable.defaultValue || 'false'"
            :disabled="readonly"
            @update:model-value="patchAt(index, { defaultValue: String($event) })"
          >
            <SelectTrigger class="h-9 w-full bg-glass border-glass-border rounded-lg text-sm">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="true">true</SelectItem>
              <SelectItem value="false">false</SelectItem>
            </SelectContent>
          </Select>
          <Input
            v-else
            :model-value="variable.defaultValue ?? ''"
            :disabled="readonly"
            :type="variable.type === 'SECRET' ? 'password' : 'text'"
            :autocomplete="variable.type === 'SECRET' ? 'new-password' : undefined"
            :placeholder="variable.type === 'SECRET' ? t('components.variableEditor.secretPlaceholder') : t('components.variableEditor.defaultValuePlaceholder')"
            class="bg-glass border-glass-border rounded-lg text-sm h-9"
            @update:model-value="patchAt(index, { defaultValue: String($event) })"
          />
          <p v-if="variable.type === 'SECRET'" class="text-[11px] leading-snug text-muted-foreground/80">
            {{ t('components.variableEditor.secretHint') }}
          </p>
        </div>
        <div class="flex flex-col gap-1">
          <span class="text-[11px] font-medium text-muted-foreground uppercase tracking-wider">{{ t('components.variableEditor.scope') }}</span>
          <Select :model-value="variable.scope" :disabled="readonly" @update:model-value="patchAt(index, { scope: $event as VariableDef['scope'] })">
            <SelectTrigger class="h-9 w-full sm:w-36 bg-glass border-glass-border rounded-lg text-sm">
              <SelectValue :placeholder="t('components.variableEditor.scope')" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem v-for="opt in VAR_SCOPES" :key="opt" :value="opt">{{ opt }}</SelectItem>
            </SelectContent>
          </Select>
        </div>
        <div class="flex flex-col gap-1">
          <span class="text-[11px] font-medium text-muted-foreground uppercase tracking-wider">{{ t('components.variableEditor.visibility') }}</span>
          <Select :model-value="variable.visibility" :disabled="readonly" @update:model-value="patchAt(index, { visibility: $event as VariableDef['visibility'] })">
            <SelectTrigger class="h-9 w-full sm:w-32 bg-glass border-glass-border rounded-lg text-sm">
              <SelectValue :placeholder="t('components.variableEditor.visibility')" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem v-for="opt in VAR_VISIBILITIES" :key="opt" :value="opt">{{ opt }}</SelectItem>
            </SelectContent>
          </Select>
        </div>
      </div>

      <!-- Row 3: type-specific validation -->
      <div v-if="variable.type === 'ENUM'" class="flex flex-col gap-1">
        <span class="text-[11px] font-medium text-muted-foreground uppercase tracking-wider">{{ t('components.variableEditor.enumValues') }}</span>
        <Input
          :model-value="(variable.validation?.enumValues ?? []).join(', ')"
          :disabled="readonly"
          :placeholder="t('components.variableEditor.enumValuesPlaceholder')"
          class="bg-glass border-glass-border rounded-lg text-sm h-9"
          @update:model-value="patchValidation(index, { enumValues: parseEnumValues(String($event)) })"
        />
      </div>
      <div v-else-if="variable.type === 'INT'" class="grid grid-cols-2 gap-3">
        <div class="flex flex-col gap-1">
          <span class="text-[11px] font-medium text-muted-foreground uppercase tracking-wider">{{ t('components.variableEditor.min') }}</span>
          <Input
            :model-value="variable.validation?.min ?? ''"
            :disabled="readonly"
            type="number"
            inputmode="numeric"
            :placeholder="t('components.variableEditor.min')"
            class="bg-glass border-glass-border rounded-lg text-sm h-9 tabular-nums"
            @update:model-value="patchValidation(index, { min: parseNumberOrUndefined(String($event)) })"
          />
        </div>
        <div class="flex flex-col gap-1">
          <span class="text-[11px] font-medium text-muted-foreground uppercase tracking-wider">{{ t('components.variableEditor.max') }}</span>
          <Input
            :model-value="variable.validation?.max ?? ''"
            :disabled="readonly"
            type="number"
            inputmode="numeric"
            :placeholder="t('components.variableEditor.max')"
            class="bg-glass border-glass-border rounded-lg text-sm h-9 tabular-nums"
            @update:model-value="patchValidation(index, { max: parseNumberOrUndefined(String($event)) })"
          />
        </div>
      </div>
      <div v-else-if="variable.type === 'STRING'" class="flex flex-col gap-1">
        <span class="text-[11px] font-medium text-muted-foreground uppercase tracking-wider">{{ t('components.variableEditor.regex') }}</span>
        <Input
          :model-value="variable.validation?.regex ?? ''"
          :disabled="readonly"
          :placeholder="t('components.variableEditor.regexPlaceholder')"
          class="font-mono bg-glass border-glass-border rounded-lg text-sm h-9"
          @update:model-value="patchValidation(index, { regex: String($event) })"
        />
      </div>

      <!-- Row 4: description -->
      <div class="flex flex-col gap-1">
        <span class="text-[11px] font-medium text-muted-foreground uppercase tracking-wider">{{ t('components.variableEditor.description') }}</span>
        <Input
          :model-value="variable.description ?? ''"
          :disabled="readonly"
          :placeholder="t('components.variableEditor.descPlaceholder')"
          class="bg-glass border-glass-border rounded-lg text-sm h-9 text-muted-foreground"
          @update:model-value="patchAt(index, { description: String($event) })"
        />
      </div>
    </div>

    <!-- Empty state -->
    <div v-if="modelValue.length === 0" class="flex items-center justify-center py-8 text-sm text-muted-foreground">
      {{ t('components.variableEditor.empty') }}
    </div>

    <!-- Add button -->
    <Button
      v-if="!readonly"
      variant="outline"
      class="w-full border-dashed border-glass-border text-muted-foreground hover:text-foreground hover:bg-glass-hover h-9"
      @click="addVariable"
    >
      <Plus class="size-4 mr-2" />
      {{ t('components.variableEditor.addVariable') }}
    </Button>
  </div>
</template>
