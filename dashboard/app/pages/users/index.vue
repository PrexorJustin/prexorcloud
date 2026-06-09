<script setup lang="ts">
import { computed, onMounted, ref } from "vue"
import { Plus, Trash2, User, Mail, Shield, Clock } from "lucide-vue-next"
import { Button } from "~/components/ui/button"
import { Input } from "~/components/ui/input"
import { Label } from "~/components/ui/label"
import { Avatar, AvatarFallback } from "~/components/ui/avatar"
import { DetailSheet } from "~/components/ui/detail-sheet"
import { StatusBadge } from "~/components/ui/status-badge"
import { BulkActionBar } from "~/components/ui/bulk-action-bar"
import { Checkbox } from "~/components/ui/checkbox"
import { Dialog, DialogContent, DialogFooter, DialogTitle } from "~/components/ui/dialog"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "~/components/ui/select"
import { toast } from "vue-sonner"
import { timeAgo, getInitials } from "~/lib/utils"
import type { DashboardUser } from "~/stores/users"

const store = useUsersStore()
const rolesStore = useRolesStore()
const { t } = useI18n()

onMounted(() => {
  store.fetchUsers()
  rolesStore.fetchRoles()
})

const { search, filteredItems: filteredUsers } = useFilteredList(
  () => store.users,
  {
    searchFields: u => [u.username, u.email ?? "", u.role],
    defaultView: "table",
  },
)

const { count: selectedCount, isAll, has: isSelected, toggle: toggleSelected, toggleAll, clear: clearSelection, selected } =
  useSelection(filteredUsers, u => u.username)

// Detail sheet
const sheetUser = ref<DashboardUser | null>(null)
const sheetOpen = computed({
  get: () => sheetUser.value !== null,
  set: (v) => { if (!v) sheetUser.value = null },
})
const sheetRole = ref("")
const sheetSaving = ref(false)

function openSheet(u: DashboardUser) {
  sheetUser.value = u
  sheetRole.value = u.role
}

async function saveSheet() {
  if (!sheetUser.value || sheetRole.value === sheetUser.value.role) return
  sheetSaving.value = true
  try {
    await store.updateUser(sheetUser.value.username, { role: sheetRole.value })
    sheetUser.value = null
  } catch { /* toast handled in store */ }
  finally { sheetSaving.value = false }
}

// Create dialog
const createOpen = ref(false)
const createForm = ref({ username: "", password: "", role: "VIEWER", email: "" })
const creating = ref(false)

async function submitCreate() {
  if (!createForm.value.username.trim() || !createForm.value.password) return
  creating.value = true
  try {
    await store.createUser({ ...createForm.value })
    createOpen.value = false
    createForm.value = { username: "", password: "", role: "VIEWER", email: "" }
  } catch { /* toast handled */ }
  finally { creating.value = false }
}

// Bulk delete
const bulkBusy = ref(false)
async function bulkDelete() {
  bulkBusy.value = true
  try {
    const usernames = Array.from(selected.value)
    await Promise.allSettled(usernames.map(u => store.deleteUser(u)))
    toast.success(t('pages.users.bulkDeleted', { count: usernames.length }, usernames.length))
    clearSelection()
  } finally { bulkBusy.value = false }
}
</script>

<template>
  <div class="flex flex-1 flex-col gap-5">
    <PageHeader :title="t('pages.users.title')" :description="t('pages.users.description')">
      <template #actions>
        <Button @click="createOpen = true">
          <Plus class="mr-2 size-4" /> {{ t('pages.users.createButton') }}
        </Button>
      </template>
    </PageHeader>

    <FilterToolbar
      v-model:search="search"
      :filters="[]"
      :active-filters="new Set(['ALL'])"
      :search-placeholder="t('pages.users.searchPlaceholder')"
      :show-view-toggle="false"
    />

    <LoadingSkeleton v-if="store.loading" mode="table" :count="6" />

    <EmptyState
      v-else-if="filteredUsers.length === 0"
      :icon="User"
      :title="search ? t('pages.users.emptyMatchesTitle') : t('pages.users.emptyTitle')"
      :description="search ? t('pages.users.emptySearchHint') : t('pages.users.emptyHint')"
    >
      <Button v-if="!search" @click="createOpen = true">
        <Plus class="mr-2 size-4" /> {{ t('pages.users.createButton') }}
      </Button>
    </EmptyState>

    <div v-else class="overflow-hidden rounded-2xl border border-glass-border bg-glass/60 backdrop-blur-xl">
      <div class="flex h-10 items-center border-b border-glass-border px-4 eyebrow">
        <div class="w-8 shrink-0 -ml-1">
          <Checkbox :model-value="isAll" :aria-label="t('pages.users.selectAll')" @update:model-value="toggleAll" />
        </div>
        <div class="w-64 shrink-0">{{ t('pages.users.columns.user') }}</div>
        <div class="flex-1">{{ t('pages.users.columns.email') }}</div>
        <div class="w-32 shrink-0">{{ t('pages.users.columns.role') }}</div>
        <div class="w-36 shrink-0 text-right">{{ t('pages.users.columns.lastSignIn') }}</div>
      </div>
      <div
        v-for="u in filteredUsers"
        :key="u.username"
        class="flex h-14 cursor-pointer select-none items-center border-b border-glass-border/50 px-4 transition-colors last:border-0 hover:bg-glass-hover"
        :class="isSelected(u.username) ? 'bg-glass/40' : ''"
        @click="openSheet(u)"
      >
        <div class="w-8 shrink-0 -ml-1" @click.stop>
          <Checkbox :model-value="isSelected(u.username)" :aria-label="t('pages.users.selectOne', { username: u.username })" @update:model-value="toggleSelected(u.username)" />
        </div>
        <div class="flex w-64 shrink-0 items-center gap-3">
          <Avatar class="size-8 bg-primary/15 text-primary">
            <AvatarFallback class="text-xs font-bold">{{ getInitials(u.username) }}</AvatarFallback>
          </Avatar>
          <span class="truncate text-sm font-medium mono">{{ u.username }}</span>
        </div>
        <div class="flex-1 truncate text-sm text-muted-foreground">{{ u.email || '—' }}</div>
        <div class="w-32 shrink-0">
          <StatusBadge tone="primary" :label="u.role" />
        </div>
        <div class="w-36 shrink-0 text-right text-sm text-muted-foreground tabular">
          {{ u.lastLogin ? timeAgo(u.lastLogin) : t('pages.users.never') }}
        </div>
      </div>
    </div>

    <BulkActionBar :count="selectedCount" singular="user" plural="users" @clear="clearSelection">
      <Button variant="outline" size="sm" :disabled="bulkBusy" class="border-destructive/50 text-destructive hover:bg-destructive/10" @click="bulkDelete">
        <Trash2 class="mr-1.5 size-3.5" /> {{ t('pages.users.bulkDelete') }}
      </Button>
    </BulkActionBar>

    <!-- Detail sheet -->
    <DetailSheet
      :open="sheetOpen"
      :title="sheetUser?.username"
      :eyebrow="t('pages.users.eyebrow')"
      :full-page-path="sheetUser ? `/users/${sheetUser.username}` : undefined"
      @update:open="sheetOpen = $event"
    >
      <template v-if="sheetUser" #status>
        <StatusBadge tone="primary" :label="sheetUser.role" />
      </template>

      <div v-if="sheetUser" class="space-y-5">
        <section class="space-y-3">
          <div class="flex items-center gap-2 text-sm">
            <Mail class="size-4 text-muted-foreground" />
            <span class="text-muted-foreground">{{ t('pages.users.detail.email') }}</span>
            <span class="ml-auto">{{ sheetUser.email || '—' }}</span>
          </div>
          <div class="flex items-center gap-2 text-sm">
            <Clock class="size-4 text-muted-foreground" />
            <span class="text-muted-foreground">{{ t('pages.users.detail.created') }}</span>
            <span class="ml-auto">{{ sheetUser.createdAt ? new Date(sheetUser.createdAt).toLocaleDateString() : '—' }}</span>
          </div>
          <div class="flex items-center gap-2 text-sm">
            <Clock class="size-4 text-muted-foreground" />
            <span class="text-muted-foreground">{{ t('pages.users.detail.lastSignIn') }}</span>
            <span class="ml-auto tabular">{{ sheetUser.lastLogin ? timeAgo(sheetUser.lastLogin) : t('pages.users.never') }}</span>
          </div>
        </section>

        <section class="space-y-2">
          <Label for="sheet-role">{{ t('pages.users.detail.role') }}</Label>
          <Select v-model="sheetRole">
            <SelectTrigger id="sheet-role">
              <SelectValue :placeholder="sheetUser.role" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem v-for="r in rolesStore.roles" :key="r.name" :value="r.name">{{ r.name }}</SelectItem>
            </SelectContent>
          </Select>
          <Button :disabled="sheetSaving || sheetRole === sheetUser.role" class="w-full" @click="saveSheet">
            {{ sheetSaving ? t('pages.users.detail.savingRole') : t('pages.users.detail.saveRole') }}
          </Button>
        </section>

        <section class="space-y-2">
          <Label class="eyebrow">{{ t('pages.users.detail.permissions') }}</Label>
          <div class="flex flex-wrap gap-1.5">
            <span
              v-for="p in (sheetUser.permissions ?? [])"
              :key="p"
              class="inline-flex items-center gap-1 rounded-md border border-glass-border bg-glass px-1.5 py-0.5 mono text-[10px] text-muted-foreground"
            >
              <Shield class="size-3" /> {{ p }}
            </span>
            <span v-if="!sheetUser.permissions?.length" class="text-sm text-muted-foreground">{{ t('pages.users.detail.inheritsFrom', { role: sheetUser.role }) }}</span>
          </div>
        </section>
      </div>
    </DetailSheet>

    <!-- Create dialog -->
    <Dialog :open="createOpen" @update:open="createOpen = $event">
      <DialogContent class="sm:max-w-md">
        <DialogTitle>{{ t('pages.users.createDialog.title') }}</DialogTitle>
        <form class="flex flex-col gap-4" @submit.prevent="submitCreate">
          <div class="flex flex-col gap-1.5">
            <Label for="cu-username">{{ t('pages.users.createDialog.username') }}</Label>
            <Input id="cu-username" v-model="createForm.username" autocomplete="off" :placeholder="t('pages.users.createDialog.usernamePlaceholder')" />
          </div>
          <div class="flex flex-col gap-1.5">
            <Label for="cu-email">{{ t('pages.users.createDialog.email') }}</Label>
            <Input id="cu-email" v-model="createForm.email" type="email" :placeholder="t('pages.users.createDialog.emailPlaceholder')" />
          </div>
          <div class="flex flex-col gap-1.5">
            <Label for="cu-password">{{ t('pages.users.createDialog.password') }}</Label>
            <Input id="cu-password" v-model="createForm.password" type="password" :placeholder="t('pages.users.createDialog.passwordPlaceholder')" />
          </div>
          <div class="flex flex-col gap-1.5">
            <Label for="cu-role">{{ t('pages.users.createDialog.role') }}</Label>
            <Select v-model="createForm.role">
              <SelectTrigger id="cu-role"><SelectValue :placeholder="t('pages.users.createDialog.rolePlaceholder')" /></SelectTrigger>
              <SelectContent>
                <SelectItem v-for="r in rolesStore.roles" :key="r.name" :value="r.name">{{ r.name }}</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <DialogFooter class="flex-row! gap-2 pt-2">
            <Button type="button" variant="outline" @click="createOpen = false">{{ t('pages.users.createDialog.cancel') }}</Button>
            <Button type="submit" :disabled="creating || !createForm.username || createForm.password.length < 8">
              {{ creating ? t('pages.users.createDialog.creating') : t('pages.users.createDialog.create') }}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  </div>
</template>
