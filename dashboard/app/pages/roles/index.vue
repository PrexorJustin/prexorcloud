<script setup lang="ts">
import { computed, onMounted, ref } from "vue"
import { Plus, Shield, Lock, Trash2 } from "lucide-vue-next"
import { Button } from "~/components/ui/button"
import { Input } from "~/components/ui/input"
import { Label } from "~/components/ui/label"
import { Checkbox } from "~/components/ui/checkbox"
import { DetailSheet } from "~/components/ui/detail-sheet"
import { StatusBadge } from "~/components/ui/status-badge"
import { Dialog, DialogContent, DialogFooter, DialogTitle } from "~/components/ui/dialog"
import { Eyebrow } from "~/components/ui/eyebrow"
import { PERMISSION_GROUPS, type RoleDefinition } from "~/stores/roles"

const { t } = useI18n()
const store = useRolesStore()

onMounted(() => store.fetchRoles())

const { search, filteredItems: filteredRoles } = useFilteredList(
  () => store.roles,
  {
    searchFields: r => [r.name, ...r.permissions],
    defaultView: "table",
  },
)

// Detail sheet — built-in roles are read-only.
const sheetRole = ref<RoleDefinition | null>(null)
const sheetOpen = computed({
  get: () => sheetRole.value !== null,
  set: (v) => { if (!v) sheetRole.value = null },
})
const sheetPermissions = ref<Set<string>>(new Set())
const sheetSaving = ref(false)

function openSheet(r: RoleDefinition) {
  sheetRole.value = r
  sheetPermissions.value = new Set(r.permissions)
}

function togglePermission(p: string) {
  if (sheetPermissions.value.has(p)) sheetPermissions.value.delete(p)
  else sheetPermissions.value.add(p)
  sheetPermissions.value = new Set(sheetPermissions.value)
}

const sheetDirty = computed(() => {
  if (!sheetRole.value) return false
  const a = new Set(sheetRole.value.permissions)
  const b = sheetPermissions.value
  if (a.size !== b.size) return true
  for (const p of a) if (!b.has(p)) return true
  return false
})

async function saveSheet() {
  if (!sheetRole.value || sheetRole.value.builtIn) return
  sheetSaving.value = true
  try {
    await store.updateRole(sheetRole.value.name, { permissions: Array.from(sheetPermissions.value) })
    sheetRole.value = null
  } catch { /* toast handled */ }
  finally { sheetSaving.value = false }
}

async function deleteRole() {
  if (!sheetRole.value || sheetRole.value.builtIn) return
  await store.deleteRole(sheetRole.value.name)
  sheetRole.value = null
}

// Create dialog
const createOpen = ref(false)
const createName = ref("")
const createPermissions = ref<Set<string>>(new Set())
const creating = ref(false)

async function submitCreate() {
  if (!/^[A-Z][A-Z0-9_]*$/.test(createName.value)) return
  creating.value = true
  try {
    await store.createRole({ name: createName.value, permissions: Array.from(createPermissions.value) })
    createOpen.value = false
    createName.value = ""
    createPermissions.value = new Set()
  } catch { /* toast handled */ }
  finally { creating.value = false }
}

function toggleCreatePermission(p: string) {
  if (createPermissions.value.has(p)) createPermissions.value.delete(p)
  else createPermissions.value.add(p)
  createPermissions.value = new Set(createPermissions.value)
}
</script>

<template>
  <div class="flex flex-1 flex-col gap-5">
    <PageHeader :title="t('pages.roles.title')" :description="t('pages.roles.description')">
      <template #actions>
        <Button @click="createOpen = true">
          <Plus class="mr-2 size-4" /> {{ t('pages.roles.createRole') }}
        </Button>
      </template>
    </PageHeader>

    <FilterToolbar
      v-model:search="search"
      :filters="[]"
      :active-filters="new Set(['ALL'])"
      :search-placeholder="t('pages.roles.searchPlaceholder')"
      :show-view-toggle="false"
    />

    <LoadingSkeleton v-if="store.loading" mode="table" :count="4" />

    <EmptyState
      v-else-if="filteredRoles.length === 0"
      :icon="Shield"
      :title="search ? t('pages.roles.emptyMatchesTitle') : t('pages.roles.emptyTitle')"
      :description="search ? t('pages.roles.emptyMatchesHint') : t('pages.roles.emptyHint')"
    />

    <div v-else class="overflow-hidden rounded-2xl border border-glass-border bg-glass/60 backdrop-blur-xl">
      <div class="flex h-10 items-center border-b border-glass-border px-4 eyebrow">
        <div class="flex-1">{{ t('pages.roles.columns.name') }}</div>
        <div class="w-32 shrink-0">{{ t('pages.roles.columns.type') }}</div>
        <div class="w-32 shrink-0 text-right">{{ t('pages.roles.columns.permissions') }}</div>
        <div class="w-32 shrink-0 text-right">{{ t('pages.roles.columns.users') }}</div>
      </div>
      <div
        v-for="r in filteredRoles"
        :key="r.name"
        class="flex h-12 cursor-pointer select-none items-center border-b border-glass-border/50 px-4 transition-colors last:border-0 hover:bg-glass-hover"
        @click="openSheet(r)"
      >
        <div class="flex flex-1 items-center gap-2 truncate text-sm font-medium mono">
          <Shield class="size-3.5 text-muted-foreground" /> {{ r.name }}
        </div>
        <div class="w-32 shrink-0">
          <StatusBadge :tone="r.builtIn ? 'muted' : 'primary'" :label="r.builtIn ? t('pages.roles.builtIn') : t('pages.roles.custom')" />
        </div>
        <div class="w-32 shrink-0 text-right text-sm text-muted-foreground tabular">{{ r.permissions.length }}</div>
        <div class="w-32 shrink-0 text-right text-sm text-muted-foreground tabular">{{ r.userCount ?? 0 }}</div>
      </div>
    </div>

    <!-- Detail sheet -->
    <DetailSheet
      :open="sheetOpen"
      :title="sheetRole?.name"
      :eyebrow="t('pages.roles.roleEyebrow')"
      size="lg"
      @update:open="sheetOpen = $event"
    >
      <template v-if="sheetRole" #status>
        <StatusBadge :tone="sheetRole.builtIn ? 'muted' : 'primary'" :label="sheetRole.builtIn ? t('pages.roles.builtIn') : t('pages.roles.custom')" />
      </template>

      <div v-if="sheetRole" class="space-y-5">
        <p v-if="sheetRole.builtIn" class="rounded-lg border border-muted bg-muted/30 px-3 py-2 text-xs text-muted-foreground">
          <Lock class="mr-1 inline size-3" /> {{ t('pages.roles.builtInNote') }}
        </p>

        <section v-for="g in PERMISSION_GROUPS" :key="g.label" class="space-y-2">
          <Eyebrow>{{ g.label }}</Eyebrow>
          <div class="grid grid-cols-2 gap-2">
            <label
              v-for="p in g.permissions"
              :key="p"
              class="flex cursor-pointer items-center gap-2 rounded-md border border-glass-border bg-glass/40 px-3 py-2 text-sm transition-colors hover:bg-glass-hover"
              :class="sheetRole.builtIn ? 'cursor-not-allowed opacity-60' : ''"
            >
              <Checkbox
                :model-value="sheetPermissions.has(p)"
                :disabled="sheetRole.builtIn"
                @update:model-value="togglePermission(p)"
              />
              <span class="mono text-xs">{{ p }}</span>
            </label>
          </div>
        </section>
      </div>

      <template v-if="sheetRole && !sheetRole.builtIn" #footer>
        <Button variant="outline" size="sm" class="border-destructive/50 text-destructive hover:bg-destructive/10" @click="deleteRole">
          <Trash2 class="mr-1.5 size-3.5" /> {{ t('pages.roles.delete') }}
        </Button>
        <div class="ml-auto flex gap-2">
          <Button variant="outline" size="sm" @click="sheetRole = null">{{ t('common.cancel') }}</Button>
          <Button size="sm" :disabled="!sheetDirty || sheetSaving" @click="saveSheet">
            {{ sheetSaving ? t('pages.roles.saving') : t('common.save') }}
          </Button>
        </div>
      </template>
    </DetailSheet>

    <!-- Create dialog -->
    <Dialog :open="createOpen" @update:open="createOpen = $event">
      <DialogContent class="max-h-[80vh] overflow-y-auto sm:max-w-2xl">
        <DialogTitle>{{ t('pages.roles.createRole') }}</DialogTitle>
        <form class="flex flex-col gap-4" @submit.prevent="submitCreate">
          <div class="flex flex-col gap-1.5">
            <Label for="cr-name">{{ t('pages.roles.nameLabel') }}</Label>
            <Input id="cr-name" v-model="createName" placeholder="MY_ROLE" class="mono" />
            <p class="text-xs text-muted-foreground">{{ t('pages.roles.nameHintPrefix') }} <code class="mono">[A-Z][A-Z0-9_]*</code>{{ t('pages.roles.nameHintSuffix') }}</p>
          </div>
          <section v-for="g in PERMISSION_GROUPS" :key="g.label" class="space-y-2">
            <Eyebrow>{{ g.label }}</Eyebrow>
            <div class="grid grid-cols-2 gap-2">
              <label v-for="p in g.permissions" :key="p" class="flex cursor-pointer items-center gap-2 rounded-md border border-glass-border bg-glass/40 px-3 py-2 text-sm transition-colors hover:bg-glass-hover">
                <Checkbox :model-value="createPermissions.has(p)" @update:model-value="toggleCreatePermission(p)" />
                <span class="mono text-xs">{{ p }}</span>
              </label>
            </div>
          </section>
          <DialogFooter class="flex-row! gap-2 pt-2">
            <Button type="button" variant="outline" @click="createOpen = false">{{ t('common.cancel') }}</Button>
            <Button type="submit" :disabled="creating || !/^[A-Z][A-Z0-9_]*$/.test(createName)">
              {{ creating ? t('pages.roles.creating') : t('pages.roles.create') }}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  </div>
</template>
