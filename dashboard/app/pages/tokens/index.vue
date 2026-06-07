<script setup lang="ts">
import { computed, onMounted, ref } from "vue"
import { Plus, Ticket, Trash2, Copy, Check, AlertOctagon } from "lucide-vue-next"
import { Button } from "~/components/ui/button"
import { Input } from "~/components/ui/input"
import { Label } from "~/components/ui/label"
import { StatusBadge } from "~/components/ui/status-badge"
import { Dialog, DialogContent, DialogFooter, DialogTitle } from "~/components/ui/dialog"
import { Callout, CalloutTitle } from "~/components/ui/callout"
import { TerminalBlock } from "~/components/ui/terminal-block"

const { t } = useI18n()
const store = useAdminTokensStore()

onMounted(() => store.fetchTokens())

const { search, filteredItems: filteredTokens } = useFilteredList(
  () => store.tokens,
  {
    searchFields: t => [t.tokenId, t.nodeId ?? ""],
    defaultView: "table",
  },
)

function expiryTone(expiresAt: string) {
  const ms = new Date(expiresAt).getTime() - Date.now()
  if (ms < 0) return 'destructive' as const
  if (ms < 24 * 3600 * 1000) return 'warning' as const
  return 'success' as const
}

function expiryLabel(expiresAt: string) {
  const ms = new Date(expiresAt).getTime() - Date.now()
  if (ms < 0) return t('pages.tokens.expiry.expired')
  const days = Math.floor(ms / (24 * 3600 * 1000))
  const hours = Math.floor((ms % (24 * 3600 * 1000)) / 3600 / 1000)
  if (days > 0) return t('pages.tokens.expiry.daysHours', { days, hours })
  return t('pages.tokens.expiry.hours', { hours })
}

// Generate dialog
const createOpen = ref(false)
const createNodeId = ref("")
const creating = ref(false)
const issuedToken = ref<string | null>(null)
const copied = ref(false)

async function submitCreate() {
  creating.value = true
  try {
    const created = await store.generateToken({ nodeId: createNodeId.value.trim() || undefined })
    if (created?.joinToken) issuedToken.value = created.joinToken
    createNodeId.value = ""
  } finally { creating.value = false }
}

async function copyToken() {
  if (!issuedToken.value) return
  await navigator.clipboard.writeText(issuedToken.value)
  copied.value = true
  setTimeout(() => { copied.value = false }, 1500)
}

function closeIssued() {
  issuedToken.value = null
  createOpen.value = false
}

async function revoke(id: string) {
  await store.revokeToken(id)
}
</script>

<template>
  <div class="flex flex-1 flex-col gap-5">
    <PageHeader :title="t('pages.tokens.title')" :description="t('pages.tokens.description')">
      <template #actions>
        <Button @click="createOpen = true">
          <Plus class="mr-2 size-4" /> {{ t('pages.tokens.generate') }}
        </Button>
      </template>
    </PageHeader>

    <FilterToolbar
      v-model:search="search"
      :filters="[]"
      :active-filters="new Set(['ALL'])"
      :search-placeholder="t('pages.tokens.searchPlaceholder')"
      :show-view-toggle="false"
    />

    <LoadingSkeleton v-if="store.loading" mode="table" :count="4" />

    <EmptyState
      v-else-if="filteredTokens.length === 0"
      :icon="Ticket"
      :title="search ? t('pages.tokens.emptyMatchesTitle') : t('pages.tokens.emptyTitle')"
      :description="search ? t('pages.tokens.emptyMatchesHint') : t('pages.tokens.emptyHint')"
    >
      <Button v-if="!search" @click="createOpen = true">
        <Plus class="mr-2 size-4" /> {{ t('pages.tokens.generate') }}
      </Button>
    </EmptyState>

    <div v-else class="overflow-hidden rounded-2xl border border-glass-border bg-glass/60 backdrop-blur-xl">
      <div class="flex h-10 items-center border-b border-glass-border px-4 eyebrow">
        <div class="w-64 shrink-0">{{ t('pages.tokens.columns.tokenId') }}</div>
        <div class="flex-1">{{ t('pages.tokens.columns.node') }}</div>
        <div class="w-44 shrink-0">{{ t('pages.tokens.columns.expiry') }}</div>
        <div class="w-36 shrink-0 text-right">{{ t('pages.tokens.columns.created') }}</div>
        <div class="w-20 shrink-0" />
      </div>
      <div
        v-for="tok in filteredTokens"
        :key="tok.tokenId"
        class="flex h-12 select-none items-center border-b border-glass-border/50 px-4 transition-colors last:border-0 hover:bg-glass-hover group/row"
      >
        <div class="w-64 shrink-0 truncate mono text-xs">{{ tok.tokenId }}</div>
        <div class="flex-1 truncate text-sm text-muted-foreground mono">{{ tok.nodeId || '—' }}</div>
        <div class="w-44 shrink-0">
          <StatusBadge :tone="expiryTone(tok.expiresAt)" :label="expiryLabel(tok.expiresAt)" />
        </div>
        <div class="w-36 shrink-0 text-right text-sm text-muted-foreground tabular">
          {{ tok.createdAt ? new Date(tok.createdAt).toLocaleString() : '—' }}
        </div>
        <div class="flex w-20 shrink-0 justify-end">
          <button
            type="button"
            :aria-label="t('pages.tokens.revoke')"
            class="inline-flex size-7 items-center justify-center rounded-md text-muted-foreground/0 transition-all group-hover/row:text-muted-foreground hover:bg-destructive/10 hover:text-destructive"
            @click="revoke(tok.tokenId)"
          >
            <Trash2 class="size-3.5" />
          </button>
        </div>
      </div>
    </div>

    <!-- Generate dialog -->
    <Dialog :open="createOpen" @update:open="(v) => { if (!v) closeIssued(); createOpen = v }">
      <DialogContent class="sm:max-w-md">
        <DialogTitle>{{ t('pages.tokens.dialog.title') }}</DialogTitle>

        <template v-if="!issuedToken">
          <form class="flex flex-col gap-4" @submit.prevent="submitCreate">
            <Callout variant="info">
              <CalloutTitle>{{ t('pages.tokens.dialog.oneTimeTitle') }}</CalloutTitle>
              <p class="text-sm text-muted-foreground">{{ t('pages.tokens.dialog.oneTimeBody') }}</p>
            </Callout>
            <div class="flex flex-col gap-1.5">
              <Label for="ct-nodeId">{{ t('pages.tokens.dialog.pinLabel') }}</Label>
              <Input id="ct-nodeId" v-model="createNodeId" placeholder="node-pending-east-1" class="mono" />
              <p class="text-xs text-muted-foreground">{{ t('pages.tokens.dialog.pinHint') }}</p>
            </div>
            <DialogFooter class="flex-row! gap-2 pt-2">
              <Button type="button" variant="outline" @click="createOpen = false">{{ t('common.cancel') }}</Button>
              <Button type="submit" :disabled="creating">
                {{ creating ? t('pages.tokens.dialog.generating') : t('pages.tokens.dialog.generate') }}
              </Button>
            </DialogFooter>
          </form>
        </template>

        <template v-else>
          <div class="flex flex-col gap-4">
            <Callout variant="warning">
              <CalloutTitle>{{ t('pages.tokens.issued.title') }}</CalloutTitle>
              <p class="text-sm text-muted-foreground">{{ t('pages.tokens.issued.body') }}</p>
              <template #next>
                {{ t('pages.tokens.issued.runPrefix') }} <code class="mono">prexorctl daemon join --token=&lt;below&gt;</code> {{ t('pages.tokens.issued.runSuffix') }}
              </template>
            </Callout>
            <TerminalBlock :copy="issuedToken" :traffic-lights="false" title="join-token">{{ issuedToken }}</TerminalBlock>
            <DialogFooter class="flex-row! gap-2 pt-2">
              <Button variant="outline" @click="copyToken">
                <Check v-if="copied" class="mr-1.5 size-4 text-success" />
                <Copy v-else class="mr-1.5 size-4" />
                {{ copied ? t('pages.tokens.issued.copied') : t('pages.tokens.issued.copy') }}
              </Button>
              <Button @click="closeIssued">{{ t('pages.tokens.issued.done') }}</Button>
            </DialogFooter>
          </div>
        </template>

        <Callout v-if="issuedToken" variant="error" class="mt-2">
          <CalloutTitle>{{ t('pages.tokens.issued.passwordTitle') }}</CalloutTitle>
          <p class="text-sm text-muted-foreground">{{ t('pages.tokens.issued.passwordBody') }}</p>
          <template #next>
            <span class="inline-flex items-center gap-1"><AlertOctagon class="size-3.5" /> {{ t('pages.tokens.issued.revokeWarning') }}</span>
          </template>
        </Callout>
      </DialogContent>
    </Dialog>
  </div>
</template>
