<script setup lang="ts">
import { Network as NetworkIcon, Pencil, Trash2, Server, GitBranch, ArrowRight } from "lucide-vue-next"
import { Badge } from "~/components/ui/badge"
import type { Schema } from "@prexorcloud/api-sdk"
import NetworkDialog from "~/components/networks/NetworkDialog.vue"

type NetworkComposition = Schema<'NetworkComposition'>

const store = useNetworksStore()
const groupsStore = useGroupsStore()
const { can } = useCan()

const { search, viewMode, filteredItems: filteredNetworks } = useFilteredList(
  () => store.networks as NetworkComposition[],
  {
    searchFields: n => [n.name, n.description ?? "", n.lobbyGroup],
  },
)

const editing = ref<NetworkComposition | null>(null)
const editOpen = ref(false)
const confirmDeleteName = ref<string | null>(null)
const deleting = ref(false)

function openEdit(n: NetworkComposition) {
  editing.value = n
  editOpen.value = true
}
watch(editOpen, (v) => { if (!v) editing.value = null })

onMounted(() => { store.fetchNetworks(); groupsStore.fetchGroups() })

async function deleteNetwork() {
  if (!confirmDeleteName.value) return
  deleting.value = true
  try {
    await store.deleteNetwork(confirmDeleteName.value)
  } finally {
    deleting.value = false
    confirmDeleteName.value = null
  }
}
</script>

<template>
  <div class="flex flex-col gap-5 flex-1">
    <PageHeader title="Networks" description="Velocity proxies fronting one or more groups, with lobby and fallback routing.">
      <template #actions>
        <NetworkDialog v-if="can('networks.create')" />
      </template>
    </PageHeader>

    <FilterToolbar
      v-model:search="search"
      :view-mode="viewMode"
      search-placeholder="Search networks..."
      @update:view-mode="viewMode = $event"
    />

    <div>
      <LoadingSkeleton v-if="store.loading" />

      <EmptyState
        v-else-if="filteredNetworks.length === 0"
        :icon="NetworkIcon"
        title="No networks configured"
        :description="search ? 'Try adjusting your search' : 'Create a network to centralize lobby + fallback routing for your proxies'"
      />

      <template v-else-if="viewMode === 'grid'">
        <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div
            v-for="n in filteredNetworks"
            :key="n.name"
            class="relative bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border hover:bg-glass-hover hover:border-glass-border-hover p-5 transition-all duration-300 select-none group flex flex-col gap-3"
          >
            <div class="flex items-start justify-between gap-3">
              <div class="flex items-center gap-3 min-w-0 flex-1">
                <div class="size-10 rounded-xl bg-glass flex items-center justify-center shrink-0">
                  <NetworkIcon class="size-5 text-muted-foreground" />
                </div>
                <div class="min-w-0">
                  <p class="font-semibold text-foreground truncate">{{ n.name }}</p>
                  <p v-if="n.description" class="text-xs text-muted-foreground truncate">{{ n.description }}</p>
                </div>
              </div>
              <div class="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                <button
                  v-if="can('networks.update')"
                  class="size-7 rounded-lg flex items-center justify-center text-muted-foreground hover:text-foreground hover:bg-glass-hover transition-all"
                  title="Edit network"
                  @click.stop="openEdit(n)"
                >
                  <Pencil class="size-3.5" />
                </button>
                <button
                  v-if="can('networks.delete')"
                  class="size-7 rounded-lg flex items-center justify-center text-muted-foreground hover:text-destructive! hover:bg-destructive/10 transition-all"
                  title="Delete network"
                  @click.stop="confirmDeleteName = n.name"
                >
                  <Trash2 class="size-3.5" />
                </button>
              </div>
            </div>

            <!-- Routing chain preview -->
            <div class="flex items-center gap-1.5 flex-wrap">
              <Badge variant="outline" class="text-[11px] bg-success/5 border-success/30 text-foreground">
                <Server class="size-3 mr-1" />{{ n.lobbyGroup }}
              </Badge>
              <template v-for="(g, i) in (n.fallbackGroups ?? [])" :key="g">
                <ArrowRight class="size-3 text-muted-foreground/50" />
                <Badge variant="outline" class="text-[11px] bg-glass/40 border-glass-border">
                  <span class="text-muted-foreground/50 mr-1">{{ i + 1 }}.</span>{{ g }}
                </Badge>
              </template>
            </div>

            <!-- Member / proxy stats -->
            <div class="grid grid-cols-2 gap-3 pt-2 border-t border-glass-border/50">
              <div class="flex items-center gap-2">
                <Server class="size-3.5 text-muted-foreground" />
                <span class="text-xs text-muted-foreground">Members</span>
                <span class="text-xs font-medium text-foreground tabular-nums ml-auto">
                  {{ (n.memberGroups?.length ?? 0) || '—' }}
                </span>
              </div>
              <div class="flex items-center gap-2">
                <NetworkIcon class="size-3.5 text-muted-foreground" />
                <span class="text-xs text-muted-foreground">Proxies</span>
                <span class="text-xs font-medium text-foreground tabular-nums ml-auto">
                  {{ n.proxyGroups?.length ? n.proxyGroups.length : 'all' }}
                </span>
              </div>
            </div>
          </div>
        </div>
      </template>

      <template v-else>
        <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border overflow-hidden">
          <div class="flex items-center h-10 px-4 border-b border-glass-border text-xs font-medium text-muted-foreground uppercase tracking-wider">
            <div class="w-44 shrink-0">Name</div>
            <div class="w-32 shrink-0">Lobby</div>
            <div class="flex-1 min-w-0">Fallback chain</div>
            <div class="w-20 shrink-0 text-right">Members</div>
            <div class="w-20 shrink-0 text-right">Proxies</div>
            <div class="w-16 shrink-0" />
          </div>
          <div
            v-for="n in filteredNetworks"
            :key="n.name"
            class="flex items-center h-12 px-4 border-b border-glass-border/50 last:border-0 transition-colors group hover:bg-glass-hover"
          >
            <div class="w-44 shrink-0 text-sm font-medium text-foreground truncate">{{ n.name }}</div>
            <div class="w-32 shrink-0 text-sm text-foreground truncate">{{ n.lobbyGroup }}</div>
            <div class="flex-1 min-w-0 px-2 flex items-center gap-1 overflow-hidden">
              <template v-if="(n.fallbackGroups ?? []).length === 0">
                <span class="text-xs text-muted-foreground/50">—</span>
              </template>
              <template v-for="(g, i) in (n.fallbackGroups ?? [])" :key="g">
                <ArrowRight v-if="i > 0" class="size-3 text-muted-foreground/40 shrink-0" />
                <span class="text-xs text-muted-foreground shrink-0">{{ g }}</span>
              </template>
            </div>
            <div class="w-20 shrink-0 text-right text-sm text-muted-foreground tabular-nums">
              {{ (n.memberGroups?.length ?? 0) || '—' }}
            </div>
            <div class="w-20 shrink-0 text-right text-sm text-muted-foreground tabular-nums">
              {{ n.proxyGroups?.length ? n.proxyGroups.length : 'all' }}
            </div>
            <div class="w-16 shrink-0 flex justify-end gap-0.5">
              <button
                v-if="can('networks.update')"
                class="size-7 rounded-lg flex items-center justify-center text-muted-foreground/0 group-hover:text-muted-foreground hover:text-foreground hover:bg-glass-hover transition-all"
                title="Edit network"
                @click.stop="openEdit(n)"
              >
                <Pencil class="size-3.5" />
              </button>
              <button
                v-if="can('networks.delete')"
                class="size-7 rounded-lg flex items-center justify-center text-muted-foreground/0 group-hover:text-muted-foreground hover:text-destructive! hover:bg-destructive/10 transition-all"
                title="Delete network"
                @click.stop="confirmDeleteName = n.name"
              >
                <Trash2 class="size-3.5" />
              </button>
            </div>
          </div>
        </div>
      </template>
    </div>

    <NetworkDialog v-model:open="editOpen" :network="editing" />

    <ConfirmDialog
      :open="!!confirmDeleteName"
      title="Delete network"
      :description="`Permanently delete network '${confirmDeleteName}'? Proxies will fall back to default-group routing.`"
      confirm-label="Delete network"
      :loading="deleting"
      @update:open="confirmDeleteName = null"
      @confirm="deleteNetwork"
    />
  </div>
</template>
