<script setup lang="ts">
import {
  AlertTriangle,
  ArrowUpCircle,
  CheckCircle2,
  Download,
  PackageSearch,
  RefreshCw,
  ShieldCheck,
  ShieldAlert,
} from 'lucide-vue-next';
import { Badge } from '~/components/ui/badge';
import { Button } from '~/components/ui/button';
import { toast } from 'vue-sonner';
import type { RegistryModuleEntry } from '~/types/api';

const moduleStore = useModuleStore();
const auth = useAuthStore();

const refreshing = ref(false);
const installingKey = ref<string | null>(null);
const canManage = computed(() => auth.can('modules.manage'));

onMounted(refresh);

async function refresh() {
  refreshing.value = true;
  try {
    await Promise.all([moduleStore.fetchRegistryCatalog(), moduleStore.fetchPlatformOverview()]);
  } finally {
    refreshing.value = false;
  }
}

const { search, filteredItems: filteredModules } = useFilteredList(
  () => moduleStore.registryModules,
  { searchFields: (m) => [m.moduleId, m.version, m.registryName, ...m.tags] },
);

const updatable = computed(
  () =>
    moduleStore.registryModules.filter(
      (m) => m.installed && m.installedVersion && m.installedVersion !== m.version,
    ).length,
);

function entryKey(m: RegistryModuleEntry) {
  return `${m.registryUrl}::${m.moduleId}@${m.version}`;
}

function isUpdate(m: RegistryModuleEntry) {
  return m.installed && !!m.installedVersion && m.installedVersion !== m.version;
}

async function install(m: RegistryModuleEntry) {
  installingKey.value = entryKey(m);
  try {
    await moduleStore.installFromRegistry(m.moduleId, m.version, m.registryUrl);
    toast.success(`Module "${m.moduleId}" installed`, { description: `version ${m.version}` });
  } catch (e) {
    toast.error('Install failed', {
      description: e instanceof Error ? e.message : 'Unknown error',
    });
  } finally {
    installingKey.value = null;
  }
}
</script>

<template>
  <div class="flex flex-col gap-5 flex-1">
    <PageHeader
      title="Module Registry"
      description="Browse and install signed modules from the configured registries."
    >
      <template #actions>
        <Button variant="outline" :disabled="refreshing" @click="refresh">
          <RefreshCw class="mr-2 size-4" :class="refreshing ? 'animate-spin' : ''" />
          Refresh
        </Button>
      </template>
    </PageHeader>

    <div
      v-if="moduleStore.registryError"
      class="flex items-center gap-2 rounded-lg border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive"
    >
      <AlertTriangle class="size-4" />
      {{ moduleStore.registryError }}
    </div>

    <!-- No registries configured -->
    <div
      v-if="moduleStore.registries.length === 0 && !moduleStore.registryError"
      class="flex flex-col items-center justify-center gap-3 rounded-lg border border-glass-border bg-glass/50 px-6 py-16 text-center"
    >
      <PackageSearch class="size-8 text-muted-foreground" />
      <p class="text-sm font-medium text-foreground">No registries configured</p>
      <p class="max-w-md text-xs text-muted-foreground">
        Set
        <code class="font-mono">modules.registries</code>
        in
        <code class="font-mono">controller.yml</code>
        to a signed registry index URL, then refresh.
      </p>
    </div>

    <template v-else>
      <div class="flex flex-wrap items-center gap-3 text-xs text-muted-foreground">
        <span class="inline-flex items-center gap-1">
          <CheckCircle2 class="size-3.5 text-success" />
          {{ moduleStore.registries.length }} registr{{
            moduleStore.registries.length === 1 ? 'y' : 'ies'
          }}
        </span>
        <span v-if="updatable > 0" class="inline-flex items-center gap-1 text-warning">
          <ArrowUpCircle class="size-3.5" />
          {{ updatable }} update{{ updatable === 1 ? '' : 's' }} available
        </span>
      </div>

      <FilterToolbar
        v-model:search="search"
        search-placeholder="Search modules, tags…"
        :show-view-toggle="false"
        :count="moduleStore.registryModules.length"
        count-label="modules"
      />

      <div
        v-if="filteredModules.length > 0"
        class="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3"
      >
        <div
          v-for="m in filteredModules"
          :key="entryKey(m)"
          class="flex flex-col rounded-lg border border-glass-border bg-glass/60 p-5"
        >
          <div class="flex items-start justify-between gap-3">
            <div class="min-w-0">
              <h3 class="truncate font-semibold text-foreground">{{ m.moduleId }}</h3>
              <p class="mt-0.5 truncate text-xs text-muted-foreground">
                {{ m.registryName ?? m.registryUrl }}
              </p>
            </div>
            <Badge variant="outline" class="shrink-0 font-mono">{{ m.version }}</Badge>
          </div>

          <p v-if="m.readme" class="mt-2 line-clamp-3 text-sm text-muted-foreground">
            {{ m.readme }}
          </p>

          <div class="mt-3 flex flex-wrap items-center gap-1.5">
            <span
              class="inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-[11px]"
              :class="m.signed ? 'text-success' : 'text-warning'"
            >
              <component :is="m.signed ? ShieldCheck : ShieldAlert" class="size-3" />
              {{ m.signed ? 'signed' : 'unsigned' }}
            </span>
            <Badge v-for="tag in m.tags" :key="tag" variant="secondary" class="text-[11px]">
              {{ tag }}
            </Badge>
          </div>

          <div class="mt-4 flex items-center gap-2">
            <Button
              class="flex-1"
              :variant="isUpdate(m) ? 'default' : 'outline'"
              :disabled="
                !canManage || installingKey === entryKey(m) || (m.installed && !isUpdate(m))
              "
              @click="install(m)"
            >
              <component
                :is="isUpdate(m) ? ArrowUpCircle : Download"
                class="mr-2 size-4"
                :class="installingKey === entryKey(m) ? 'animate-pulse' : ''"
              />
              <template v-if="installingKey === entryKey(m)">Installing…</template>
              <template v-else-if="isUpdate(m)">Update to {{ m.version }}</template>
              <template v-else-if="m.installed">Installed</template>
              <template v-else>Install</template>
            </Button>
          </div>

          <p
            v-if="m.installed && m.installedVersion"
            class="mt-2 text-[11px] text-muted-foreground"
          >
            Installed:
            <span class="font-mono">{{ m.installedVersion }}</span>
          </p>
        </div>
      </div>

      <div
        v-else
        class="flex flex-col items-center justify-center gap-2 rounded-lg border border-glass-border bg-glass/40 px-6 py-12 text-center text-sm text-muted-foreground"
      >
        <PackageSearch class="size-6" />
        No modules match your search.
      </div>
    </template>
  </div>
</template>
