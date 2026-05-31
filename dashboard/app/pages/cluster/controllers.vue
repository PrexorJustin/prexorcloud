<script setup lang="ts">
import { computed, onMounted, ref } from "vue"
import { Server, Plus, Trash2, LogOut, RefreshCw, KeyRound, Copy, Check, AlertOctagon } from "lucide-vue-next"
import { Button } from "~/components/ui/button"
import { Input } from "~/components/ui/input"
import { Label } from "~/components/ui/label"
import { StatusBadge } from "~/components/ui/status-badge"
import { Dialog, DialogContent, DialogFooter, DialogTitle } from "~/components/ui/dialog"
import { Callout, CalloutTitle } from "~/components/ui/callout"
import { TerminalBlock } from "~/components/ui/terminal-block"

// /cluster/controllers is the operator-facing view of the controller Raft
// group: which controllers are members, the active cluster config version,
// outstanding join tokens, and the destructive "leave / rotate seed" admin
// actions. Mirrors the prexorctl cluster subcommands so the CLI and dashboard
// surface the same operations.

const store = useClusterStore()
const auth = useAuthStore()

onMounted(() => {
  store.fetchStatus()
  store.fetchMembers()
  store.fetchJoinTokens()
  store.fetchLeases()
})

const canManage = computed(() => auth.can("cluster.manage"))

// --- Issue-token dialog ---
const issueOpen = ref(false)
const issueLabel = ref("")
const issueTtlHours = ref(24)
const issueJoinAddrs = ref("")
const issuing = ref(false)
const issuedToken = ref<string | null>(null)
const copied = ref(false)

async function submitIssue() {
  issuing.value = true
  try {
    const joinAddrs = issueJoinAddrs.value
      .split(/[,\n]/)
      .map(s => s.trim())
      .filter(Boolean)
    if (joinAddrs.length === 0) return
    const issued = await store.issueJoinToken({
      ttlSeconds: Math.max(60, Math.floor(issueTtlHours.value * 3600)),
      label: issueLabel.value.trim() || undefined,
      joinAddrs,
    })
    issuedToken.value = issued.token
  }
  finally { issuing.value = false }
}

async function copyToken() {
  if (!issuedToken.value) return
  await navigator.clipboard.writeText(issuedToken.value)
  copied.value = true
  setTimeout(() => { copied.value = false }, 1500)
}

function closeIssue() {
  issuedToken.value = null
  issueLabel.value = ""
  issueJoinAddrs.value = ""
  issueOpen.value = false
}

// --- Destructive admin dialogs ---
const leaveOpen = ref(false)
const seedRotateOpen = ref(false)

async function confirmLeave() {
  try {
    await store.leaveCluster()
    leaveOpen.value = false
    // No fetch — the controller is about to shut down. The operator will
    // navigate away or hit a connection error within ~1s.
  } catch { /* toast surfaces error */ }
}

async function confirmSeedRotate() {
  try {
    await store.rotateSeed()
    seedRotateOpen.value = false
    await store.fetchJoinTokens()  // outstanding tokens are now invalid
  } catch { /* toast surfaces error */ }
}

async function eject(nodeId: string) {
  // Confirm inline — eject is destructive but easy to undo by re-joining the
  // controller via a fresh join token, so a one-shot confirm is enough.
  if (!confirm(`Force-eject controller ${nodeId}? It will be removed from the Raft group.`)) return
  try { await store.ejectMember(nodeId, "ejected from dashboard") }
  catch { /* toast surfaces error */ }
}

async function revokeToken(jti: string) {
  if (!confirm(`Revoke join token ${jti.slice(0, 8)}…?`)) return
  try { await store.revokeJoinToken(jti) }
  catch { /* toast surfaces error */ }
}
</script>

<template>
  <div class="flex flex-1 flex-col gap-5">
    <PageHeader
      title="Cluster controllers"
      description="The Raft control plane that runs PrexorCloud. Members, join tokens, and leader-elected work."
    >
      <template #actions>
        <Button v-if="canManage" variant="outline" @click="seedRotateOpen = true">
          <RefreshCw class="mr-2 size-4" /> Rotate seed
        </Button>
        <Button v-if="canManage" variant="outline" @click="leaveOpen = true">
          <LogOut class="mr-2 size-4" /> Leave cluster
        </Button>
        <Button v-if="canManage" @click="issueOpen = true">
          <Plus class="mr-2 size-4" /> Issue join token
        </Button>
      </template>
    </PageHeader>

    <!-- Status summary -->
    <div class="grid grid-cols-1 gap-3 sm:grid-cols-4">
      <div class="rounded-2xl border border-glass-border bg-glass/60 p-4">
        <div class="eyebrow text-xs">Cluster ID</div>
        <div class="mono text-sm mt-1 truncate" :title="store.status?.clusterId">
          {{ store.status?.clusterId ?? "—" }}
        </div>
      </div>
      <div class="rounded-2xl border border-glass-border bg-glass/60 p-4">
        <div class="eyebrow text-xs">Members</div>
        <div class="text-2xl mt-1">{{ store.status?.memberCount ?? "—" }}</div>
      </div>
      <div class="rounded-2xl border border-glass-border bg-glass/60 p-4">
        <div class="eyebrow text-xs">Active config version</div>
        <div class="text-2xl mt-1">v{{ store.status?.activeConfigVersion ?? "—" }}</div>
      </div>
      <div class="rounded-2xl border border-glass-border bg-glass/60 p-4">
        <div class="eyebrow text-xs">Active leases</div>
        <div class="text-2xl mt-1">{{ store.leases.length }}</div>
      </div>
    </div>

    <!-- Members -->
    <section class="flex flex-col gap-2">
      <div class="flex items-center justify-between">
        <h2 class="text-lg font-medium">Controllers ({{ store.members.length }})</h2>
      </div>

      <LoadingSkeleton v-if="store.loadingMembers" mode="table" :count="3" />

      <EmptyState
        v-else-if="store.members.length === 0"
        :icon="Server"
        title="No members"
        description="The controller hasn't stamped any cluster members yet — bootstrap may still be running."
      />

      <div v-else class="overflow-hidden rounded-2xl border border-glass-border bg-glass/60 backdrop-blur-xl">
        <div class="flex h-10 items-center border-b border-glass-border px-4 eyebrow">
          <div class="w-56 shrink-0">Node ID</div>
          <div class="w-44 shrink-0">Raft addr</div>
          <div class="w-44 shrink-0">REST addr</div>
          <div class="w-44 shrink-0">gRPC addr</div>
          <div class="flex-1">Joined</div>
          <div class="w-20 shrink-0" />
        </div>
        <div
          v-for="m in store.members"
          :key="m.nodeId"
          class="flex h-12 select-none items-center border-b border-glass-border/50 px-4 transition-colors last:border-0 hover:bg-glass-hover group/row"
        >
          <div class="w-56 shrink-0 truncate mono text-xs">{{ m.nodeId }}</div>
          <div class="w-44 shrink-0 truncate mono text-xs text-muted-foreground">{{ m.raftAddr }}</div>
          <div class="w-44 shrink-0 truncate mono text-xs text-muted-foreground">{{ m.restAddr }}</div>
          <div class="w-44 shrink-0 truncate mono text-xs text-muted-foreground">{{ m.gRPCAddr }}</div>
          <div class="flex-1 truncate text-sm text-muted-foreground tabular">
            {{ m.joinedAt ? new Date(m.joinedAt).toLocaleString() : "—" }}
          </div>
          <div class="flex w-20 shrink-0 justify-end">
            <button
              v-if="canManage"
              type="button"
              aria-label="Eject controller"
              class="inline-flex size-7 items-center justify-center rounded-md text-muted-foreground/0 transition-all group-hover/row:text-muted-foreground hover:bg-destructive/10 hover:text-destructive"
              @click="eject(m.nodeId)"
            >
              <Trash2 class="size-3.5" />
            </button>
          </div>
        </div>
      </div>
    </section>

    <!-- Join tokens -->
    <section class="flex flex-col gap-2">
      <h2 class="text-lg font-medium">Outstanding join tokens ({{ store.joinTokens.length }})</h2>

      <LoadingSkeleton v-if="store.loadingTokens" mode="table" :count="2" />

      <EmptyState
        v-else-if="store.joinTokens.length === 0"
        :icon="KeyRound"
        title="No join tokens"
        description="Issue a token to add another controller to the cluster."
      >
        <Button v-if="canManage" @click="issueOpen = true">
          <Plus class="mr-2 size-4" /> Issue join token
        </Button>
      </EmptyState>

      <div v-else class="overflow-hidden rounded-2xl border border-glass-border bg-glass/60 backdrop-blur-xl">
        <div class="flex h-10 items-center border-b border-glass-border px-4 eyebrow">
          <div class="w-64 shrink-0">JTI</div>
          <div class="w-44 shrink-0">Label</div>
          <div class="w-32 shrink-0">Status</div>
          <div class="flex-1">Expires</div>
          <div class="w-20 shrink-0" />
        </div>
        <div
          v-for="tok in store.joinTokens"
          :key="tok.jti"
          class="flex h-12 select-none items-center border-b border-glass-border/50 px-4 transition-colors last:border-0 hover:bg-glass-hover group/row"
        >
          <div class="w-64 shrink-0 truncate mono text-xs">{{ tok.jti }}</div>
          <div class="w-44 shrink-0 truncate text-sm">{{ tok.label || "—" }}</div>
          <div class="w-32 shrink-0">
            <StatusBadge :tone="tok.status === 'REDEEMED' ? 'success' : tok.status === 'REVOKED' ? 'destructive' : 'warning'" :label="tok.status" />
          </div>
          <div class="flex-1 truncate text-sm text-muted-foreground tabular">
            {{ tok.expiresAt ? new Date(tok.expiresAt).toLocaleString() : "—" }}
          </div>
          <div class="flex w-20 shrink-0 justify-end">
            <button
              v-if="canManage && tok.status !== 'REVOKED'"
              type="button"
              aria-label="Revoke token"
              class="inline-flex size-7 items-center justify-center rounded-md text-muted-foreground/0 transition-all group-hover/row:text-muted-foreground hover:bg-destructive/10 hover:text-destructive"
              @click="revokeToken(tok.jti)"
            >
              <Trash2 class="size-3.5" />
            </button>
          </div>
        </div>
      </div>
    </section>

    <!-- Leases (diagnostics) -->
    <section v-if="store.leases.length > 0" class="flex flex-col gap-2">
      <h2 class="text-lg font-medium">Leases ({{ store.leases.length }})</h2>
      <div class="overflow-hidden rounded-2xl border border-glass-border bg-glass/60 backdrop-blur-xl">
        <div class="flex h-10 items-center border-b border-glass-border px-4 eyebrow">
          <div class="w-56 shrink-0">Name</div>
          <div class="w-56 shrink-0">Holder</div>
          <div class="flex-1">Renewed</div>
        </div>
        <div
          v-for="ls in store.leases"
          :key="ls.name"
          class="flex h-12 items-center border-b border-glass-border/50 px-4 last:border-0"
        >
          <div class="w-56 shrink-0 truncate mono text-xs">{{ ls.name }}</div>
          <div class="w-56 shrink-0 truncate mono text-xs text-muted-foreground">{{ ls.holder }}</div>
          <div class="flex-1 truncate text-sm text-muted-foreground tabular">
            {{ ls.renewedAt ? new Date(ls.renewedAt).toLocaleString() : "—" }}
          </div>
        </div>
      </div>
    </section>

    <!-- Issue token dialog -->
    <Dialog :open="issueOpen" @update:open="(v: boolean) => { if (!v) closeIssue(); issueOpen = v }">
      <DialogContent class="sm:max-w-md">
        <DialogTitle>Issue cluster join token</DialogTitle>

        <template v-if="!issuedToken">
          <form class="flex flex-col gap-4" @submit.prevent="submitIssue">
            <Callout variant="info">
              <CalloutTitle>One-time secret</CalloutTitle>
              <p class="text-sm text-muted-foreground">The token will be shown once. Copy it before closing this dialog.</p>
            </Callout>
            <div class="flex flex-col gap-1.5">
              <Label for="iss-label">Label</Label>
              <Input id="iss-label" v-model="issueLabel" placeholder="controller-2" />
              <p class="text-xs text-muted-foreground">Human-readable name pinned to the AddMember entry for audit.</p>
            </div>
            <div class="flex flex-col gap-1.5">
              <Label for="iss-ttl">TTL (hours)</Label>
              <Input id="iss-ttl" v-model.number="issueTtlHours" type="number" min="1" max="720" />
            </div>
            <div class="flex flex-col gap-1.5">
              <Label for="iss-addrs">Join addresses</Label>
              <textarea
                id="iss-addrs"
                v-model="issueJoinAddrs"
                class="min-h-[80px] rounded-md border border-input bg-background px-3 py-2 text-sm mono"
                placeholder="controller-1.example.internal:9090&#10;controller-3.example.internal:9090"
              />
              <p class="text-xs text-muted-foreground">One existing controller gRPC host:port per line. The joiner dials these to redeem the token.</p>
            </div>
            <DialogFooter class="flex-row! gap-2 pt-2">
              <Button type="button" variant="outline" @click="issueOpen = false">Cancel</Button>
              <Button type="submit" :disabled="issuing || !issueJoinAddrs.trim()">
                {{ issuing ? "Issuing…" : "Issue" }}
              </Button>
            </DialogFooter>
          </form>
        </template>

        <template v-else>
          <div class="flex flex-col gap-4">
            <Callout variant="warning">
              <CalloutTitle>Copy this token now</CalloutTitle>
              <p class="text-sm text-muted-foreground">It won't be shown again. Once you close this dialog, only revocation is possible.</p>
              <template #next>
                Paste it into the controller wizard's <code class="mono">Add controller</code> mode on the new host.
              </template>
            </Callout>
            <TerminalBlock :copy="issuedToken" :traffic-lights="false" title="cluster join-token">{{ issuedToken }}</TerminalBlock>
            <DialogFooter class="flex-row! gap-2 pt-2">
              <Button variant="outline" @click="copyToken">
                <Check v-if="copied" class="mr-1.5 size-4 text-success" />
                <Copy v-else class="mr-1.5 size-4" />
                {{ copied ? "Copied" : "Copy" }}
              </Button>
              <Button @click="closeIssue">Done</Button>
            </DialogFooter>
            <Callout variant="error" class="mt-2">
              <CalloutTitle>Treat this like a password.</CalloutTitle>
              <p class="text-sm text-muted-foreground">Anyone with this token can add a controller to the cluster.</p>
              <template #next>
                <span class="inline-flex items-center gap-1"><AlertOctagon class="size-3.5" /> Revoke immediately if it leaks.</span>
              </template>
            </Callout>
          </div>
        </template>
      </DialogContent>
    </Dialog>

    <!-- Leave cluster dialog -->
    <Dialog v-model:open="leaveOpen">
      <DialogContent class="sm:max-w-md">
        <DialogTitle>Leave cluster?</DialogTitle>
        <Callout variant="warning">
          <CalloutTitle>This controller will shut down.</CalloutTitle>
          <p class="text-sm text-muted-foreground">The cluster will continue with the remaining members. To re-add this controller, generate a new join token and run the installer.</p>
        </Callout>
        <DialogFooter class="flex-row! gap-2 pt-2">
          <Button variant="outline" @click="leaveOpen = false">Cancel</Button>
          <Button variant="destructive" @click="confirmLeave">Leave cluster</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>

    <!-- Rotate seed dialog -->
    <Dialog v-model:open="seedRotateOpen">
      <DialogContent class="sm:max-w-md">
        <DialogTitle>Rotate cluster seed?</DialogTitle>
        <Callout variant="warning">
          <CalloutTitle>Outstanding join tokens will be invalidated.</CalloutTitle>
          <p class="text-sm text-muted-foreground">The seed is the HMAC key for every join token. Rotating it means any token issued before this point can no longer be redeemed.</p>
        </Callout>
        <DialogFooter class="flex-row! gap-2 pt-2">
          <Button variant="outline" @click="seedRotateOpen = false">Cancel</Button>
          <Button variant="destructive" @click="confirmSeedRotate">Rotate seed</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  </div>
</template>
