<script setup lang="ts">
import { computed, onMounted, ref } from "vue"
import { BadgeCheck, ShieldX, RotateCcw, AlertOctagon } from "lucide-vue-next"
import { Button } from "~/components/ui/button"
import { Input } from "~/components/ui/input"
import { Label } from "~/components/ui/label"
import { Textarea } from "~/components/ui/textarea"
import { StatusBadge } from "~/components/ui/status-badge"
import { Callout, CalloutTitle } from "~/components/ui/callout"
import { Dialog, DialogContent, DialogFooter, DialogTitle } from "~/components/ui/dialog"
import { timeAgo } from "~/lib/utils"

const store = useCertificatesStore()

onMounted(() => store.fetchRevoked())

const { search, filteredItems: filteredRevoked } = useFilteredList(
  () => store.revoked,
  {
    searchFields: r => [r.nodeId, r.serial ?? "", r.reason ?? ""],
    defaultView: "table",
  },
)

const revokeOpen = ref(false)
const revokeNodeId = ref("")
const revokeReason = ref("")
const revoking = ref(false)

async function submitRevoke() {
  if (!revokeNodeId.value.trim()) return
  revoking.value = true
  try {
    await store.revoke(revokeNodeId.value.trim(), revokeReason.value.trim() || undefined)
    revokeOpen.value = false
    revokeNodeId.value = ""
    revokeReason.value = ""
  } finally { revoking.value = false }
}
</script>

<template>
  <div class="flex flex-1 flex-col gap-5">
    <PageHeader title="Certificates" description="Compromise-response: revoke a node's TLS certificate to force-disconnect it.">
      <template #actions>
        <Button class="bg-destructive text-destructive-foreground hover:bg-destructive/90" @click="revokeOpen = true">
          <ShieldX class="mr-2 size-4" /> Revoke certificate
        </Button>
      </template>
    </PageHeader>

    <Callout variant="warning">
      <CalloutTitle>Revoking blocks the daemon's controller access immediately on next reconnect.</CalloutTitle>
      <p class="text-sm text-muted-foreground">Use this when a node is suspected leaked or compromised. Existing instances on the node keep running until the controller marks them UNREACHABLE.</p>
      <template #next>
        Drain the node first if you want graceful migration. <span class="mono">prexorctl node drain &lt;id&gt;</span>.
      </template>
    </Callout>

    <FilterToolbar
      v-model:search="search"
      :filters="[]"
      :active-filters="new Set(['ALL'])"
      search-placeholder="Search by node, serial, or reason…"
      :show-view-toggle="false"
    />

    <LoadingSkeleton v-if="store.loading" mode="table" :count="3" />

    <EmptyState
      v-else-if="filteredRevoked.length === 0"
      :icon="BadgeCheck"
      :title="search ? 'No matches' : 'No revoked certificates'"
      :description="search ? 'Try clearing the filter.' : 'Every node certificate is currently valid. Use the Revoke button if you need to lock one out.'"
    />

    <div v-else class="overflow-hidden rounded-2xl border border-glass-border bg-glass/60 backdrop-blur-xl">
      <div class="flex h-10 items-center border-b border-glass-border px-4 eyebrow">
        <div class="w-52 shrink-0">Node</div>
        <div class="w-52 shrink-0">Serial</div>
        <div class="flex-1">Reason</div>
        <div class="w-44 shrink-0 text-right">Revoked</div>
        <div class="w-44 shrink-0 text-right">Status</div>
        <div class="w-24 shrink-0" />
      </div>
      <div
        v-for="r in filteredRevoked"
        :key="r.nodeId"
        class="flex h-12 items-center border-b border-glass-border/50 px-4 transition-colors last:border-0 hover:bg-glass-hover group/row"
      >
        <div class="w-52 shrink-0 truncate mono text-sm">{{ r.nodeId }}</div>
        <div class="w-52 shrink-0 truncate mono text-xs text-muted-foreground">{{ r.serial ?? '—' }}</div>
        <div class="flex-1 truncate text-sm text-muted-foreground">{{ r.reason ?? '—' }}</div>
        <div class="w-44 shrink-0 text-right tabular text-sm text-muted-foreground">{{ timeAgo(r.revokedAt) }}</div>
        <div class="w-44 shrink-0 text-right">
          <StatusBadge tone="destructive" label="Revoked" />
        </div>
        <div class="flex w-24 shrink-0 justify-end">
          <button
            type="button"
            title="Unrevoke (allow reconnect)"
            class="inline-flex size-7 items-center justify-center rounded-md text-muted-foreground/0 transition-all group-hover/row:text-muted-foreground hover:bg-warning/10 hover:text-warning"
            @click="store.unrevoke(r.nodeId)"
          >
            <RotateCcw class="size-3.5" />
          </button>
        </div>
      </div>
    </div>

    <Dialog :open="revokeOpen" @update:open="revokeOpen = $event">
      <DialogContent class="sm:max-w-md">
        <DialogTitle>Revoke certificate?</DialogTitle>
        <Callout variant="error">
          <CalloutTitle>This locks the daemon out.</CalloutTitle>
          <p class="text-sm text-muted-foreground">
            On next reconnect, the daemon's TLS handshake is rejected. Existing controller access via active sessions remains until they expire.
          </p>
          <template #next>
            <span class="inline-flex items-center gap-1"><AlertOctagon class="size-3.5" /> Reversible — unrevoke from this page.</span>
          </template>
        </Callout>
        <form class="flex flex-col gap-4 pt-2" @submit.prevent="submitRevoke">
          <div class="flex flex-col gap-1.5">
            <Label for="rc-node">Node ID</Label>
            <Input id="rc-node" v-model="revokeNodeId" placeholder="node-east-1" class="mono" />
          </div>
          <div class="flex flex-col gap-1.5">
            <Label for="rc-reason">Reason (optional)</Label>
            <Textarea id="rc-reason" v-model="revokeReason" rows="2" placeholder="Suspected compromise; rotating cert before re-bootstrap." />
          </div>
          <DialogFooter class="flex-row! gap-2 pt-2">
            <Button type="button" variant="outline" @click="revokeOpen = false">Cancel</Button>
            <Button type="submit" :disabled="!revokeNodeId.trim() || revoking" class="bg-destructive text-destructive-foreground hover:bg-destructive/90">
              {{ revoking ? 'Revoking…' : 'Revoke' }}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  </div>
</template>
