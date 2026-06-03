<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue"
import { Wrench, AlertTriangle, Plus, Trash2 } from "lucide-vue-next"
import { Button } from "~/components/ui/button"
import { Input } from "~/components/ui/input"
import { Label } from "~/components/ui/label"
import { Switch } from "~/components/ui/switch"
import { Textarea } from "~/components/ui/textarea"
import { Callout, CalloutTitle } from "~/components/ui/callout"
import { StatusBadge } from "~/components/ui/status-badge"
import { Eyebrow } from "~/components/ui/eyebrow"

const { t } = useI18n()
const store = useMaintenanceStore()
const groupsStore = useGroupsStore()

onMounted(() => {
  store.fetchState()
  groupsStore.fetchGroups()
})

// Local editable copies — apply via Save buttons to avoid every keystroke
// hitting the controller.
const globalEnabled = ref(false)
const globalMessage = ref("")
const globalBypass = ref<string>("")

watch(() => store.state, (s) => {
  globalEnabled.value = s.globalEnabled
  globalMessage.value = s.globalMessage ?? ""
  globalBypass.value = (s.globalBypassUsernames ?? []).join(", ")
}, { immediate: true, deep: true })

const globalDirty = computed(() =>
  globalEnabled.value !== store.state.globalEnabled
  || globalMessage.value !== (store.state.globalMessage ?? "")
  || globalBypass.value !== (store.state.globalBypassUsernames ?? []).join(", "),
)

const globalSaving = ref(false)
async function saveGlobal() {
  globalSaving.value = true
  try {
    await store.updateState({
      globalEnabled: globalEnabled.value,
      globalMessage: globalMessage.value,
      globalBypassUsernames: globalBypass.value.split(",").map(s => s.trim()).filter(Boolean),
    })
  } finally { globalSaving.value = false }
}

// Per-group editable overrides — only show groups that currently have an
// override; an "Add override" button lets operators pick a group.
const groupOverrides = computed(() => store.state.groups)

const overrideEdits = ref<Record<string, { enabled: boolean; message: string }>>({})

watch(groupOverrides, (groups) => {
  for (const g of groups) {
    if (!overrideEdits.value[g.groupName]) {
      overrideEdits.value[g.groupName] = { enabled: g.enabled, message: g.message ?? "" }
    }
  }
}, { immediate: true, deep: true })

async function saveOverride(groupName: string) {
  const e = overrideEdits.value[groupName]
  if (!e) return
  await store.setGroupMaintenance(groupName, e.enabled, e.message)
}

async function removeOverride(groupName: string) {
  await store.setGroupMaintenance(groupName, false)
  delete overrideEdits.value[groupName]
}

const addOpen = ref(false)
const addGroup = ref("")
const eligibleGroups = computed(() =>
  groupsStore.groups.filter(g => !groupOverrides.value.some(o => o.groupName === g.name)),
)

async function addOverride() {
  if (!addGroup.value) return
  await store.setGroupMaintenance(addGroup.value, true)
  addGroup.value = ""
  addOpen.value = false
}
</script>

<template>
  <div class="flex flex-1 flex-col gap-6">
    <PageHeader :title="t('pages.maintenance.title')" :description="t('pages.maintenance.description')">
      <template #actions>
        <StatusBadge
          :tone="store.state.globalEnabled ? 'warning' : 'success'"
          :label="store.state.globalEnabled ? t('pages.maintenance.statusOn') : t('pages.maintenance.statusLive')"
          :pulse="store.state.globalEnabled"
        />
      </template>
    </PageHeader>

    <Callout v-if="store.state.globalEnabled" variant="warning">
      <CalloutTitle>{{ t('pages.maintenance.globalEnabledTitle') }}</CalloutTitle>
      <p class="text-sm text-muted-foreground">{{ t('pages.maintenance.globalEnabledBody') }}</p>
      <template #next>{{ t('pages.maintenance.globalEnabledNext') }}</template>
    </Callout>

    <!-- Global toggle card -->
    <section class="space-y-3">
      <Eyebrow>{{ t('pages.maintenance.global') }}</Eyebrow>
      <div class="space-y-5 rounded-2xl border border-glass-border bg-glass/60 p-5 backdrop-blur-xl">
        <div class="flex items-center justify-between gap-4">
          <div class="space-y-0.5">
            <p class="text-sm font-medium">{{ t('pages.maintenance.clusterMaintenance') }}</p>
            <p class="text-xs text-muted-foreground">{{ t('pages.maintenance.clusterMaintenanceHint') }}</p>
          </div>
          <Switch v-model="globalEnabled" />
        </div>

        <div class="space-y-2">
          <Label for="m-message">{{ t('pages.maintenance.messageLabel') }}</Label>
          <Textarea id="m-message" v-model="globalMessage" rows="3" :placeholder="t('pages.maintenance.messagePlaceholder')" />
          <p class="text-xs text-muted-foreground">{{ t('pages.maintenance.messageHint') }}</p>
        </div>

        <div class="space-y-2">
          <Label for="m-bypass">{{ t('pages.maintenance.bypassLabel') }}</Label>
          <Input id="m-bypass" v-model="globalBypass" :placeholder="t('pages.maintenance.bypassPlaceholder')" />
          <p class="text-xs text-muted-foreground">{{ t('pages.maintenance.bypassHint') }}</p>
        </div>

        <div class="flex items-center justify-end gap-2 pt-2">
          <Button :disabled="!globalDirty || globalSaving" @click="saveGlobal">
            {{ globalSaving ? t('pages.maintenance.saving') : t('pages.maintenance.saveChanges') }}
          </Button>
        </div>
      </div>
    </section>

    <!-- Per-group overrides -->
    <section class="space-y-3">
      <div class="flex items-center justify-between">
        <Eyebrow>{{ t('pages.maintenance.perGroup') }}</Eyebrow>
        <Button v-if="eligibleGroups.length > 0" variant="outline" size="sm" @click="addOpen = true">
          <Plus class="mr-1.5 size-3.5" /> {{ t('pages.maintenance.addOverride') }}
        </Button>
      </div>

      <div v-if="addOpen" class="flex items-end gap-3 rounded-2xl border border-glass-border bg-glass/40 p-4">
        <div class="flex-1 space-y-1.5">
          <Label for="m-add-group">{{ t('pages.maintenance.groupLabel') }}</Label>
          <select id="m-add-group" v-model="addGroup" class="h-9 w-full rounded-md border border-input bg-glass/60 px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50">
            <option value="" disabled>{{ t('pages.maintenance.pickGroup') }}</option>
            <option v-for="g in eligibleGroups" :key="g.name" :value="g.name">{{ g.name }}</option>
          </select>
        </div>
        <Button :disabled="!addGroup" @click="addOverride">{{ t('pages.maintenance.enableMaintenance') }}</Button>
        <Button variant="ghost" @click="addOpen = false">{{ t('common.cancel') }}</Button>
      </div>

      <p v-if="groupOverrides.length === 0 && !addOpen" class="rounded-2xl border border-dashed border-glass-border bg-glass/30 p-6 text-center text-sm text-muted-foreground">
        {{ t('pages.maintenance.noOverrides') }}
      </p>

      <div
        v-for="g in groupOverrides"
        :key="g.groupName"
        class="space-y-3 rounded-2xl border border-glass-border bg-glass/60 p-5 backdrop-blur-xl"
      >
        <div class="flex items-start gap-3">
          <div class="flex size-9 shrink-0 items-center justify-center rounded-lg bg-warning/10 text-warning">
            <Wrench class="size-4" />
          </div>
          <div class="min-w-0 flex-1">
            <p class="font-medium mono">{{ g.groupName }}</p>
            <p class="mt-0.5 text-xs text-muted-foreground">{{ t('pages.maintenance.overrideHint') }}</p>
          </div>
          <div class="flex shrink-0 items-center gap-2">
            <Switch v-model="overrideEdits[g.groupName]!.enabled" />
            <button type="button" :aria-label="t('pages.maintenance.removeOverride')" class="inline-flex size-8 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-destructive/10 hover:text-destructive" @click="removeOverride(g.groupName)">
              <Trash2 class="size-3.5" />
            </button>
          </div>
        </div>

        <div class="space-y-2">
          <Label :for="`m-msg-${g.groupName}`">{{ t('pages.maintenance.messageShort') }}</Label>
          <Textarea :id="`m-msg-${g.groupName}`" v-model="overrideEdits[g.groupName]!.message" rows="2" :placeholder="t('pages.maintenance.overrideMessagePlaceholder')" />
        </div>

        <div class="flex items-center justify-end">
          <Button size="sm" @click="saveOverride(g.groupName)">{{ t('common.save') }}</Button>
        </div>
      </div>
    </section>

    <Callout variant="info">
      <CalloutTitle>{{ t('pages.maintenance.footerTitle') }}</CalloutTitle>
      <p class="text-sm text-muted-foreground">{{ t('pages.maintenance.footerBody') }}</p>
    </Callout>
  </div>
</template>
