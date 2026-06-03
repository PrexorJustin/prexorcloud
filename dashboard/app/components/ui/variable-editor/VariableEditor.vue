<script setup lang="ts">
import { Plus, Trash2 } from "lucide-vue-next"
import { Button } from "~/components/ui/button"
import { Input } from "~/components/ui/input"

export interface Variable {
  key: string
  value: string
  description?: string
}

const props = defineProps<{
  modelValue: Variable[]
  readonly?: boolean
}>()

const emit = defineEmits<{
  "update:modelValue": [value: Variable[]]
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

function hasKeyError(key: string): boolean {
  return (!isKeyValid(key) && key.length > 0) || duplicateKeys.value.has(key)
}

function updateVariable(index: number, field: keyof Variable, value: string) {
  const updated = props.modelValue.map((v, i) =>
    i === index ? { ...v, [field]: value } : { ...v },
  )
  emit("update:modelValue", updated)
}

function addVariable() {
  emit("update:modelValue", [...props.modelValue, { key: "", value: "", description: "" }])
}

function removeVariable(index: number) {
  emit("update:modelValue", props.modelValue.filter((_, i) => i !== index))
}
</script>

<template>
  <div class="flex flex-col gap-3">
    <!-- Header row -->
    <div v-if="modelValue.length > 0" class="grid grid-cols-[1fr_1fr_1fr_auto] gap-3 px-1">
      <span class="text-xs font-medium text-muted-foreground uppercase tracking-wider">{{ t('components.variableEditor.key') }}</span>
      <span class="text-xs font-medium text-muted-foreground uppercase tracking-wider">{{ t('components.variableEditor.value') }}</span>
      <span class="text-xs font-medium text-muted-foreground uppercase tracking-wider">{{ t('components.variableEditor.description') }}</span>
      <span class="w-8" />
    </div>

    <!-- Variable rows -->
    <div
      v-for="(variable, index) in modelValue"
      :key="index"
      class="grid grid-cols-[1fr_1fr_1fr_auto] gap-3 items-center group/row"
    >
      <Input
        :model-value="variable.key"
        :disabled="readonly"
        :placeholder="t('components.variableEditor.keyPlaceholder')"
        :class="[
          'font-mono bg-glass border-glass-border rounded-lg text-sm h-9',
          hasKeyError(variable.key) ? 'border-destructive focus-visible:ring-destructive/50' : '',
        ]"
        @update:model-value="updateVariable(index, 'key', String($event))"
      />
      <Input
        :model-value="variable.value"
        :disabled="readonly"
        :placeholder="t('components.variableEditor.valuePlaceholder')"
        class="bg-glass border-glass-border rounded-lg text-sm h-9"
        @update:model-value="updateVariable(index, 'value', String($event))"
      />
      <Input
        :model-value="variable.description ?? ''"
        :disabled="readonly"
        :placeholder="t('components.variableEditor.descPlaceholder')"
        class="bg-glass border-glass-border rounded-lg text-sm h-9 text-muted-foreground"
        @update:model-value="updateVariable(index, 'description', String($event))"
      />
      <button
        v-if="!readonly"
        class="size-8 inline-flex items-center justify-center rounded-lg text-muted-foreground hover:text-destructive opacity-0 group-hover/row:opacity-100 transition-all"
        :title="t('components.variableEditor.remove')"
        @click="removeVariable(index)"
      >
        <Trash2 class="size-4" />
      </button>
      <span v-else class="w-8" />
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
