<script setup lang="ts">
import { FileCode, HardDrive, Hash, Layers, Loader2, Lock, Package, Pencil, Save, Search, Trash2 } from "lucide-vue-next"
import type { Template, TemplateVariable } from "~/types/api"
import { Badge } from "~/components/ui/badge"
import { Button } from "~/components/ui/button"
import { toast } from "vue-sonner"
import { formatBytes } from "~/lib/utils"

const props = defineProps<{
  template: Template
  templateName: string
  isBaseTemplate: boolean
  inheritanceChain: { name: string; active: boolean; link: string }[]
  templateVariables: TemplateVariable[]
  variablesSaving: boolean
  scanningVariables: boolean
  usedByGroups: { name: string }[]
  groupsLoading: boolean
  deleteLoading: boolean
}>()

const emit = defineEmits<{
  (e: 'update:template', value: Template): void
  (e: 'update:templateVariables', value: TemplateVariable[]): void
  (e: 'saveVariables'): void
  (e: 'scanVariables'): void
  (e: 'inheritanceClick', name: string): void
  (e: 'requestDelete'): void
}>()

const store = useTemplatesStore()
const { t } = useI18n()

const editingField = ref<"platform" | "description" | null>(null)
const editValue = ref("")
const editSaving = ref(false)

function startEdit(field: "platform" | "description") {
  if (!props.template) return
  editingField.value = field
  editValue.value = field === "platform" ? props.template.platform : props.template.description
  nextTick(() => {
    const el = document.getElementById(`inline-edit-${field}`)
    if (el) el.focus()
  })
}

function cancelEdit() {
  editingField.value = null
  editValue.value = ""
}

async function saveEdit() {
  if (!props.template || !editingField.value || editSaving.value) return
  const field = editingField.value
  const value = editValue.value.trim()
  if (field === "platform" && !value) return

  const original = field === "platform" ? props.template.platform : props.template.description
  if (value === original) { cancelEdit(); return }

  editSaving.value = true
  try {
    const updated = await store.updateTemplate(props.templateName, {
      ...(field === "platform" ? { platform: value } : {}),
      ...(field === "description" ? { description: value } : {}),
    })
    emit('update:template', updated as Template)
    cancelEdit()
  }
  catch { toast.error(t("toast.templates.fieldUpdateFailed", { field })) }
  finally { editSaving.value = false }
}

const variablesProxy = computed({
  get: () => props.templateVariables,
  set: (v) => emit('update:templateVariables', v),
})
</script>

<template>
  <div class="contents">
    <div class="grid grid-cols-2 lg:grid-cols-4 gap-4">
      <!-- Platform (editable, read-only for base templates) -->
      <div
        :class="[
          'bg-glass/60 backdrop-blur-xl rounded-2xl border p-5 transition-colors',
          isBaseTemplate
            ? 'border-glass-border'
            : 'cursor-pointer group',
          !isBaseTemplate && editingField === 'platform' ? 'border-primary/50 ring-1 ring-primary/20' : !isBaseTemplate ? 'border-glass-border hover:border-primary/30' : '',
        ]"
        @click="!isBaseTemplate && editingField !== 'platform' && startEdit('platform')"
      >
        <div class="flex items-center justify-between text-muted-foreground mb-2">
          <div class="flex items-center gap-2"><Package class="size-4" /><span class="text-sm">Platform</span></div>
          <Pencil v-if="!isBaseTemplate && editingField !== 'platform'" class="size-3 opacity-0 group-hover:opacity-100 transition-opacity" />
        </div>
        <template v-if="!isBaseTemplate && editingField === 'platform'">
          <input
            id="inline-edit-platform"
            v-model="editValue"
            class="w-full bg-transparent text-lg font-bold text-foreground outline-none placeholder:text-muted-foreground/50"
            placeholder="e.g. paper, velocity"
            @keydown.enter="saveEdit"
            @keydown.escape="cancelEdit"
            @blur="saveEdit"
          >
        </template>
        <p v-else class="text-lg font-bold text-foreground">{{ template.platform }}</p>
      </div>

      <!-- Size (read-only) -->
      <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-5">
        <div class="flex items-center gap-2 text-muted-foreground mb-2"><HardDrive class="size-4" /><span class="text-sm">Size</span></div>
        <p class="text-lg font-bold text-foreground tabular-nums">{{ formatBytes(template.sizeBytes) }}</p>
      </div>

      <!-- Hash (read-only) -->
      <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-5">
        <div class="flex items-center gap-2 text-muted-foreground mb-2"><Hash class="size-4" /><span class="text-sm">Hash</span></div>
        <p class="text-sm font-mono text-muted-foreground truncate" :title="template.hash">{{ template.hash.slice(0, 16) }}…</p>
      </div>

      <!-- Description (editable, read-only for base templates) -->
      <div
        :class="[
          'bg-glass/60 backdrop-blur-xl rounded-2xl border p-5 transition-colors',
          isBaseTemplate
            ? 'border-glass-border'
            : 'cursor-pointer group',
          !isBaseTemplate && editingField === 'description' ? 'border-primary/50 ring-1 ring-primary/20' : !isBaseTemplate ? 'border-glass-border hover:border-primary/30' : '',
        ]"
        @click="!isBaseTemplate && editingField !== 'description' && startEdit('description')"
      >
        <div class="flex items-center justify-between text-muted-foreground mb-2">
          <div class="flex items-center gap-2"><FileCode class="size-4" /><span class="text-sm">Description</span></div>
          <Pencil v-if="!isBaseTemplate && editingField !== 'description'" class="size-3 opacity-0 group-hover:opacity-100 transition-opacity" />
        </div>
        <template v-if="!isBaseTemplate && editingField === 'description'">
          <input
            id="inline-edit-description"
            v-model="editValue"
            class="w-full bg-transparent text-sm text-foreground outline-none placeholder:text-muted-foreground/50"
            placeholder="What this template is for"
            @keydown.enter="saveEdit"
            @keydown.escape="cancelEdit"
            @blur="saveEdit"
          >
        </template>
        <p v-else class="text-sm text-foreground">{{ template.description || "—" }}</p>
      </div>
    </div>

    <!-- Inheritance chain -->
    <div v-if="inheritanceChain.length" class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-4">
      <p class="text-xs font-medium text-muted-foreground uppercase tracking-wider mb-3">Template Inheritance</p>
      <UiInheritanceChain :chain="inheritanceChain" @click="(name: string) => emit('inheritanceClick', name)" />
    </div>

    <!-- Variables -->
    <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-5">
      <div class="flex items-center justify-between mb-4">
        <div>
          <p class="text-xs font-medium text-muted-foreground uppercase tracking-wider">Template Variables</p>
          <p class="text-xs text-muted-foreground mt-0.5">Define placeholders that get resolved when the template is deployed</p>
        </div>
        <div class="flex items-center gap-2">
          <Button variant="outline" size="sm" class="border-glass-border h-7 text-xs" :disabled="scanningVariables" @click="emit('scanVariables')">
            <Search class="size-3 mr-1" />
            {{ scanningVariables ? 'Scanning...' : 'Scan Files' }}
          </Button>
          <Button size="sm" class="h-7 text-xs" :disabled="variablesSaving" @click="emit('saveVariables')">
            <Save class="size-3 mr-1" />
            {{ variablesSaving ? 'Saving...' : 'Save' }}
          </Button>
        </div>
      </div>
      <UiVariableEditor v-model="variablesProxy" />
    </div>

    <!-- Used by Groups -->
    <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-5">
      <div class="flex items-center gap-2 text-muted-foreground mb-3">
        <Layers class="size-4" />
        <span class="text-sm font-medium">Used by Groups</span>
        <Badge variant="secondary" class="ml-auto text-xs tabular-nums">{{ usedByGroups.length }}</Badge>
      </div>
      <div v-if="groupsLoading" class="flex items-center gap-2 text-sm text-muted-foreground">
        <Loader2 class="size-4 animate-spin" />
        Loading...
      </div>
      <p v-else-if="usedByGroups.length === 0" class="text-sm text-muted-foreground">
        Not used by any groups
      </p>
      <div v-else class="flex flex-wrap gap-2">
        <NuxtLink
          v-for="group in usedByGroups"
          :key="group.name"
          :to="`/groups/${group.name}`"
          class="inline-flex items-center gap-1.5 rounded-lg border border-glass-border bg-glass/40 px-3 py-1.5 text-sm font-medium text-foreground transition-colors hover:border-primary/30 hover:bg-primary/5"
        >
          {{ group.name }}
        </NuxtLink>
      </div>
    </div>

    <!-- Danger Zone -->
    <div v-if="!isBaseTemplate" class="rounded-2xl border border-destructive/30 bg-destructive/5 p-6">
      <div class="flex items-center justify-between">
        <div class="flex items-start gap-3">
          <div class="size-10 rounded-xl bg-destructive/20 flex items-center justify-center shrink-0 mt-0.5">
            <Trash2 class="size-5 text-destructive" />
          </div>
          <div>
            <h3 class="font-semibold text-foreground">Delete Template</h3>
            <p class="text-sm text-muted-foreground mt-1">Permanently delete this template including all files and version history. This action cannot be undone.</p>
          </div>
        </div>
        <Button
          variant="outline"
          class="shrink-0 ml-4 border-destructive/50 text-destructive hover:bg-destructive/10"
          :disabled="deleteLoading"
          @click="emit('requestDelete')"
        >
          <Trash2 class="size-4 mr-2" />
          {{ deleteLoading ? "Deleting..." : "Delete Template" }}
        </Button>
      </div>
    </div>

    <!-- Protected base template notice -->
    <div v-else class="rounded-2xl border border-muted bg-muted/30 p-6">
      <div class="flex items-start gap-3">
        <div class="size-10 rounded-xl bg-muted flex items-center justify-center shrink-0 mt-0.5">
          <Lock class="size-5 text-muted-foreground" />
        </div>
        <div>
          <h3 class="font-semibold text-foreground">Protected Template</h3>
          <p class="text-sm text-muted-foreground mt-1">Base templates are managed by the system and cannot be deleted. They serve as the foundation for derived templates.</p>
        </div>
      </div>
    </div>
  </div>
</template>
