<script setup lang="ts">
import { Plus, Network as NetworkIcon, Loader2, X, Search, ArrowDown, ArrowUp, Server, GitBranch } from "lucide-vue-next"
import { Button } from "~/components/ui/button"
import { Input } from "~/components/ui/input"
import { Label } from "~/components/ui/label"
import { Badge } from "~/components/ui/badge"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogTitle, DialogTrigger } from "~/components/ui/dialog"
import type { Schema } from "@prexorcloud/api-sdk"
import { toast } from "vue-sonner"

type NetworkComposition = Schema<'NetworkComposition'>
type ServerGroup = Schema<'GroupDto'>

const props = withDefaults(defineProps<{
  /** Existing network to edit; absent ⇒ create mode. */
  network?: NetworkComposition | null
  /** External controlled open state (used by the edit trigger). */
  open?: boolean
}>(), {
  network: null,
  open: undefined,
})

const emit = defineEmits<{
  "update:open": [value: boolean]
}>()

const store = useNetworksStore()
const groupsStore = useGroupsStore()
const { t } = useI18n()

const isEdit = computed(() => !!props.network)
const internalOpen = ref(false)
const open = computed({
  get: () => props.open !== undefined ? props.open : internalOpen.value,
  set: (v: boolean) => {
    if (props.open !== undefined) emit("update:open", v)
    else internalOpen.value = v
  },
})
const loading = ref(false)

const name = ref("")
const description = ref("")
const lobbyGroup = ref("")
const fallbackGroups = ref<string[]>([])
const memberGroups = ref<string[]>([])
const proxyGroups = ref<string[]>([])
const kickMessage = ref("")

const PROXY_PLATFORMS = new Set(["velocity", "bungeecord", "waterfall"])
const allServerGroupNames = computed(() => groupsStore.groups
  .filter((g: ServerGroup) => !PROXY_PLATFORMS.has(g.platform.toLowerCase()))
  .map(g => g.name))
const allProxyGroupNames = computed(() => groupsStore.groups
  .filter((g: ServerGroup) => PROXY_PLATFORMS.has(g.platform.toLowerCase()))
  .map(g => g.name))

const nameValid = computed(() =>
  /^[a-z0-9_][a-z0-9_-]*$/.test(name.value) && name.value.length <= 32,
)
const nameError = computed(() => {
  if (!name.value) return null
  if (name.value.length > 32) return t("components.networkDialog.errors.nameTooLong")
  if (!/^[a-z0-9_][a-z0-9_-]*$/.test(name.value)) return t("components.networkDialog.errors.nameInvalidChars")
  if (!isEdit.value && store.networks.find(n => n.name === name.value)) return t("components.networkDialog.errors.nameExists")
  return null
})

const lobbyError = computed(() => {
  if (!lobbyGroup.value) return null
  if (!allServerGroupNames.value.includes(lobbyGroup.value)) return t("components.networkDialog.errors.lobbyInvalid")
  return null
})
const fallbackError = computed(() => {
  if (fallbackGroups.value.includes(lobbyGroup.value)) return t("components.networkDialog.errors.fallbackHasLobby")
  if (new Set(fallbackGroups.value).size !== fallbackGroups.value.length) return t("components.networkDialog.errors.duplicateEntries")
  return null
})
const proxyError = computed(() => {
  for (const g of proxyGroups.value) {
    if (!allProxyGroupNames.value.includes(g)) return t("components.networkDialog.errors.notProxyPlatform", { name: `"${g}"` })
  }
  return null
})

const formValid = computed(() =>
  nameValid.value
  && lobbyGroup.value.length > 0
  && !lobbyError.value
  && !fallbackError.value
  && !proxyError.value
  && (kickMessage.value.length <= 256)
  && (description.value.length <= 256),
)

function reset() {
  if (props.network) {
    name.value = props.network.name
    description.value = props.network.description ?? ""
    lobbyGroup.value = props.network.lobbyGroup
    fallbackGroups.value = [...(props.network.fallbackGroups ?? [])]
    memberGroups.value = [...(props.network.memberGroups ?? [])]
    proxyGroups.value = [...(props.network.proxyGroups ?? [])]
    kickMessage.value = props.network.kickMessage ?? ""
  } else {
    name.value = ""
    description.value = ""
    lobbyGroup.value = ""
    fallbackGroups.value = []
    memberGroups.value = []
    proxyGroups.value = []
    kickMessage.value = ""
  }
}

watch(open, (v) => { if (v) { reset(); groupsStore.fetchGroups() } })
watch(() => props.network, () => { if (open.value) reset() })

async function submit() {
  if (!formValid.value) return
  loading.value = true
  const body: NetworkComposition = {
    name: name.value.trim(),
    description: description.value.trim(),
    lobbyGroup: lobbyGroup.value.trim(),
    fallbackGroups: [...fallbackGroups.value],
    memberGroups: [...memberGroups.value],
    proxyGroups: [...proxyGroups.value],
    kickMessage: kickMessage.value.trim(),
  }
  try {
    if (isEdit.value) await store.updateNetwork(props.network!.name, body)
    else await store.createNetwork(body)
    open.value = false
  } catch {
    toast.error(isEdit.value ? t("toast.networks.updateFailed") : t("toast.networks.createFailed"))
  } finally {
    loading.value = false
  }
}

// ── Fallback chain editor (ordered) ─────────────
const fallbackPicker = ref("")
const fallbackPickerOptions = computed(() =>
  allServerGroupNames.value
    .filter(g => g !== lobbyGroup.value && !fallbackGroups.value.includes(g)),
)
function addFallback() {
  const v = fallbackPicker.value.trim()
  if (!v) return
  if (!allServerGroupNames.value.includes(v)) { toast.error(t("toast.networks.notBackendGroup", { name: `"${v}"` })); return }
  if (v === lobbyGroup.value) { toast.error(t("toast.networks.lobbyInFallback")); return }
  if (fallbackGroups.value.includes(v)) return
  fallbackGroups.value = [...fallbackGroups.value, v]
  fallbackPicker.value = ""
}
function removeFallback(i: number) {
  fallbackGroups.value = fallbackGroups.value.filter((_, idx) => idx !== i)
}
function moveFallback(i: number, dir: -1 | 1) {
  const j = i + dir
  if (j < 0 || j >= fallbackGroups.value.length) return
  const next = [...fallbackGroups.value]
  ;[next[i], next[j]] = [next[j]!, next[i]!]
  fallbackGroups.value = next
}

// ── Member / proxy chip editors ─────────────────
const memberPicker = ref("")
const memberPickerOptions = computed(() => allServerGroupNames.value.filter(g => !memberGroups.value.includes(g)))
function addMember() {
  const v = memberPicker.value.trim()
  if (!v) return
  if (memberGroups.value.includes(v)) return
  memberGroups.value = [...memberGroups.value, v]
  memberPicker.value = ""
}
function removeMember(g: string) { memberGroups.value = memberGroups.value.filter(x => x !== g) }

const proxyPicker = ref("")
const proxyPickerOptions = computed(() => allProxyGroupNames.value.filter(g => !proxyGroups.value.includes(g)))
function addProxy() {
  const v = proxyPicker.value.trim()
  if (!v) return
  if (!allProxyGroupNames.value.includes(v)) { toast.error(t("toast.networks.notProxyGroup", { name: `"${v}"` })); return }
  if (proxyGroups.value.includes(v)) return
  proxyGroups.value = [...proxyGroups.value, v]
  proxyPicker.value = ""
}
function removeProxy(g: string) { proxyGroups.value = proxyGroups.value.filter(x => x !== g) }
</script>

<template>
  <Dialog v-model:open="open">
    <DialogTrigger v-if="!isEdit && props.open === undefined" as-child>
      <Button class="bg-primary hover:bg-primary/90 text-primary-foreground">
        <Plus class="size-5 mr-2" />
        {{ t('components.networkDialog.newNetwork') }}
      </Button>
    </DialogTrigger>
    <DialogContent class="bg-popover backdrop-blur-xl border-glass-border rounded-2xl sm:max-w-2xl [&>button:last-child]:hidden overflow-hidden p-0">
      <!-- Hero -->
      <div class="relative h-28 bg-glass/40 overflow-hidden">
        <div class="absolute inset-0 bg-dot-pattern" />
        <div class="absolute inset-0 bg-gradient-to-b from-transparent via-transparent to-popover" />
        <div class="absolute inset-0 flex flex-col items-center justify-center gap-1.5">
          <div class="size-11 rounded-2xl bg-primary/10 border border-primary/20 flex items-center justify-center">
            <NetworkIcon class="size-5 text-primary" />
          </div>
          <DialogTitle class="text-base font-bold text-foreground">{{ isEdit ? t('components.networkDialog.editTitle', { name: props.network?.name }) : t('components.networkDialog.createTitle') }}</DialogTitle>
          <DialogDescription class="text-xs text-muted-foreground">
            {{ t('components.networkDialog.description') }}
          </DialogDescription>
        </div>
      </div>

      <div class="px-6 pb-7 pt-5 max-h-[70vh] overflow-y-auto styled-scrollbar flex flex-col gap-5">
        <!-- Identity -->
        <div class="grid grid-cols-2 gap-4">
          <div class="flex flex-col gap-1.5">
            <Label for="net-name">{{ t('components.networkDialog.nameLabel') }}</Label>
            <Input
              id="net-name"
              v-model="name"
              placeholder="main"
              autocomplete="off"
              class="bg-glass border-glass-border"
              :disabled="isEdit"
            />
            <p v-if="nameError" class="text-xs text-destructive">{{ nameError }}</p>
          </div>
          <div class="flex flex-col gap-1.5">
            <Label for="net-lobby">{{ t('components.networkDialog.lobbyGroupLabel') }}</Label>
            <Input
              id="net-lobby"
              v-model="lobbyGroup"
              list="lobby-options"
              placeholder="lobby"
              autocomplete="off"
              class="bg-glass border-glass-border"
            />
            <datalist id="lobby-options">
              <option v-for="g in allServerGroupNames" :key="g" :value="g" />
            </datalist>
            <p v-if="lobbyError" class="text-xs text-destructive">{{ lobbyError }}</p>
          </div>
        </div>

        <div class="flex flex-col gap-1.5">
          <Label for="net-desc">{{ t('components.networkDialog.descriptionLabel') }} <span class="text-muted-foreground font-normal">{{ t('components.networkDialog.descriptionHint') }}</span></Label>
          <Input
            id="net-desc"
            v-model="description"
            :placeholder="t('components.networkDialog.descriptionPlaceholder')"
            autocomplete="off"
            class="bg-glass border-glass-border"
          />
        </div>

        <!-- Fallback chain (ordered) -->
        <div class="flex flex-col gap-2">
          <div class="flex items-center justify-between">
            <Label class="flex items-center gap-1.5"><GitBranch class="size-3.5 text-muted-foreground" /> {{ t('components.networkDialog.fallbackChainLabel') }}</Label>
            <span class="text-[11px] text-muted-foreground">{{ t('components.networkDialog.fallbackChainHint') }}</span>
          </div>
          <div class="flex items-center gap-2">
            <Input
              v-model="fallbackPicker"
              list="fallback-options"
              :placeholder="t('components.networkDialog.addBackendGroupPlaceholder')"
              autocomplete="off"
              class="bg-glass border-glass-border flex-1"
              @keydown.enter.prevent="addFallback"
            />
            <datalist id="fallback-options">
              <option v-for="g in fallbackPickerOptions" :key="g" :value="g" />
            </datalist>
            <Button variant="outline" class="border-glass-border" :disabled="!fallbackPicker.trim()" @click="addFallback">{{ t('components.networkDialog.add') }}</Button>
          </div>
          <p v-if="fallbackError" class="text-xs text-destructive">{{ fallbackError }}</p>
          <div v-if="fallbackGroups.length === 0" class="text-xs text-muted-foreground py-2 px-3 rounded-lg bg-glass/30 border border-dashed border-glass-border/50">{{ t('components.networkDialog.noFallbackGroups') }}</div>
          <ol v-else class="flex flex-col gap-1.5">
            <li v-for="(g, i) in fallbackGroups" :key="g" class="flex items-center gap-2 px-3 py-1.5 rounded-lg bg-glass/40 border border-glass-border/60">
              <span class="text-xs tabular-nums text-muted-foreground w-5 text-right">{{ i + 1 }}.</span>
              <span class="text-sm text-foreground flex-1">{{ g }}</span>
              <button type="button" :aria-label="t('common.a11y.moveUp')" class="size-6 rounded-md flex items-center justify-center text-muted-foreground hover:text-foreground hover:bg-glass-hover disabled:opacity-30 disabled:hover:bg-transparent" :disabled="i === 0" @click="moveFallback(i, -1)"><ArrowUp class="size-3.5" /></button>
              <button type="button" :aria-label="t('common.a11y.moveDown')" class="size-6 rounded-md flex items-center justify-center text-muted-foreground hover:text-foreground hover:bg-glass-hover disabled:opacity-30 disabled:hover:bg-transparent" :disabled="i === fallbackGroups.length - 1" @click="moveFallback(i, 1)"><ArrowDown class="size-3.5" /></button>
              <button type="button" :aria-label="t('common.a11y.remove')" class="size-6 rounded-md flex items-center justify-center text-muted-foreground hover:text-destructive hover:bg-destructive/10" @click="removeFallback(i)"><X class="size-3.5" /></button>
            </li>
          </ol>
        </div>

        <!-- Member groups -->
        <div class="flex flex-col gap-2">
          <div class="flex items-center justify-between">
            <Label class="flex items-center gap-1.5"><Server class="size-3.5 text-muted-foreground" /> {{ t('components.networkDialog.memberGroupsLabel') }}</Label>
            <span class="text-[11px] text-muted-foreground">{{ t('components.networkDialog.memberGroupsHint') }}</span>
          </div>
          <div class="flex items-center gap-2">
            <Input
              v-model="memberPicker"
              list="member-options"
              :placeholder="t('components.networkDialog.addBackendGroupPlaceholder')"
              autocomplete="off"
              class="bg-glass border-glass-border flex-1"
              @keydown.enter.prevent="addMember"
            />
            <datalist id="member-options">
              <option v-for="g in memberPickerOptions" :key="g" :value="g" />
            </datalist>
            <Button variant="outline" class="border-glass-border" :disabled="!memberPicker.trim()" @click="addMember">{{ t('components.networkDialog.add') }}</Button>
          </div>
          <div v-if="memberGroups.length" class="flex flex-wrap gap-1.5">
            <Badge v-for="g in memberGroups" :key="g" variant="outline" class="text-xs pl-2.5 pr-1 py-0 h-6 gap-1.5 border-glass-border bg-glass/40">
              {{ g }}
              <button type="button" :aria-label="t('components.networkDialog.removeMemberGroup')" class="size-4 rounded flex items-center justify-center hover:bg-destructive/20 hover:text-destructive" @click="removeMember(g)"><X class="size-3" /></button>
            </Badge>
          </div>
        </div>

        <!-- Proxy groups -->
        <div class="flex flex-col gap-2">
          <div class="flex items-center justify-between">
            <Label class="flex items-center gap-1.5"><NetworkIcon class="size-3.5 text-muted-foreground" /> {{ t('components.networkDialog.proxyGroupsLabel') }}</Label>
            <span class="text-[11px] text-muted-foreground">{{ t('components.networkDialog.proxyGroupsHint') }}</span>
          </div>
          <div class="flex items-center gap-2">
            <Input
              v-model="proxyPicker"
              list="proxy-options"
              :placeholder="t('components.networkDialog.addProxyGroupPlaceholder')"
              autocomplete="off"
              class="bg-glass border-glass-border flex-1"
              @keydown.enter.prevent="addProxy"
            />
            <datalist id="proxy-options">
              <option v-for="g in proxyPickerOptions" :key="g" :value="g" />
            </datalist>
            <Button variant="outline" class="border-glass-border" :disabled="!proxyPicker.trim()" @click="addProxy">{{ t('components.networkDialog.add') }}</Button>
          </div>
          <p v-if="proxyError" class="text-xs text-destructive">{{ proxyError }}</p>
          <div v-if="proxyGroups.length" class="flex flex-wrap gap-1.5">
            <Badge v-for="g in proxyGroups" :key="g" variant="outline" class="text-xs pl-2.5 pr-1 py-0 h-6 gap-1.5 border-glass-border bg-glass/40">
              {{ g }}
              <button type="button" :aria-label="t('components.networkDialog.removeProxyGroup')" class="size-4 rounded flex items-center justify-center hover:bg-destructive/20 hover:text-destructive" @click="removeProxy(g)"><X class="size-3" /></button>
            </Badge>
          </div>
        </div>

        <!-- Kick message -->
        <div class="flex flex-col gap-1.5">
          <Label for="net-kick">{{ t('components.networkDialog.kickMessageLabel') }} <span class="text-muted-foreground font-normal">{{ t('components.networkDialog.kickMessageHint') }}</span></Label>
          <Input
            id="net-kick"
            v-model="kickMessage"
            :placeholder="t('components.networkDialog.kickMessagePlaceholder')"
            autocomplete="off"
            class="bg-glass border-glass-border"
          />
        </div>

        <DialogFooter class="!flex-row gap-2 mt-2 pt-5 border-t border-glass-border">
          <Button variant="outline" class="border-glass-border" :disabled="loading" @click="open = false">{{ t('components.networkDialog.cancel') }}</Button>
          <div class="flex-1" />
          <Button
            class="bg-primary hover:bg-primary/90 text-primary-foreground"
            :disabled="!formValid || loading"
            @click="submit"
          >
            <Loader2 v-if="loading" class="size-4 mr-1.5 animate-spin" />
            {{ loading ? (isEdit ? t('components.networkDialog.saving') : t('components.networkDialog.creating')) : (isEdit ? t('components.networkDialog.saveChanges') : t('components.networkDialog.createNetwork')) }}
          </Button>
        </DialogFooter>
      </div>
    </DialogContent>
  </Dialog>
</template>
