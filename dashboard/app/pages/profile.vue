<script setup lang="ts">
import { User, Shield, KeyRound, LogOut, Loader2, Eye, EyeOff, Gamepad2, Link, Unlink } from "lucide-vue-next"
import { Badge } from "~/components/ui/badge"
import { Button } from "~/components/ui/button"
import { Input } from "~/components/ui/input"
import { Label } from "~/components/ui/label"
import { Separator } from "~/components/ui/separator"
import { toast } from "vue-sonner"

const { t } = useI18n()
const router = useRouter()
const auth = useAuthStore()

// Minecraft linking
const mcUsername = ref("")
const mcLinking = ref(false)
const mcUnlinking = ref(false)

async function linkMinecraft() {
  const name = mcUsername.value.trim()
  if (!name) return
  mcLinking.value = true
  try {
    // Resolve username to UUID via Mojang API
    const mojangRes = await $fetch<{ id: string; name: string }>(`https://api.mojang.com/users/profiles/minecraft/${encodeURIComponent(name)}`)
    if (!mojangRes?.id) {
      toast.error(t('pages.profile.toast.linkNotFoundTitle'), { description: t('pages.profile.toast.linkNotFoundDesc', { name }) })
      return
    }

    // Format UUID with dashes
    const uuid = mojangRes.id.replace(/(\w{8})(\w{4})(\w{4})(\w{4})(\w{12})/, "$1-$2-$3-$4-$5")

    await useApiClient().PUT('/api/v1/users/{username}/minecraft', { params: { path: { username: auth.user!.username } }, body: { uuid, name: mojangRes.name } })

    // Update local auth state
    auth.user!.minecraftUuid = uuid
    auth.user!.minecraftName = mojangRes.name

    mcUsername.value = ""
    toast.success(t('pages.profile.toast.linkedTitle'), { description: t('pages.profile.toast.linkedDesc', { name: mojangRes.name }) })
  } catch {
    toast.error(t('pages.profile.toast.linkFailedTitle'), { description: t('pages.profile.toast.linkFailedDesc') })
  } finally {
    mcLinking.value = false
  }
}

async function unlinkMinecraft() {
  mcUnlinking.value = true
  try {
    await useApiClient().DELETE('/api/v1/users/{username}/minecraft', { params: { path: { username: auth.user!.username } } })
    auth.user!.minecraftUuid = null
    auth.user!.minecraftName = null
    toast.success(t('pages.profile.toast.unlinkedTitle'))
  } catch {
    toast.error(t('pages.profile.toast.unlinkFailedTitle'), { description: t('pages.profile.toast.unlinkFailedDesc') })
  } finally {
    mcUnlinking.value = false
  }
}

// Password change
const currentPassword = ref("")
const newPassword = ref("")
const confirmPassword = ref("")
const passwordLoading = ref(false)
const showCurrent = ref(false)
const showNew = ref(false)

const passwordValid = computed(() =>
  currentPassword.value.length > 0
  && newPassword.value.length >= 8
  && newPassword.value === confirmPassword.value,
)

async function changePassword() {
  if (!passwordValid.value) return
  passwordLoading.value = true
  try {
    await auth.changePassword(currentPassword.value, newPassword.value)
    currentPassword.value = ""
    newPassword.value = ""
    confirmPassword.value = ""
  }
  catch {
    toast.error(t('pages.profile.toast.passwordFailedTitle'), { description: t('pages.profile.toast.passwordFailedDesc') })
  }
  finally {
    passwordLoading.value = false
  }
}

function handleLogout() {
  auth.logout()
  router.push("/login")
}
</script>

<template>
  <div class="flex flex-col gap-6 flex-1">
    <PageHeader
      :title="t('pages.profile.title')"
      :description="t('pages.profile.description')"
    />

    <!-- Account Info -->
    <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6">
      <div class="flex items-center gap-5">
        <div class="size-16 rounded-2xl bg-primary/10 border border-primary/20 flex items-center justify-center shrink-0">
          <User class="size-8 text-primary" />
        </div>
        <div class="flex-1 min-w-0">
          <h2 class="text-xl font-bold text-foreground">{{ auth.user?.username }}</h2>
          <div class="flex items-center gap-2 mt-1">
            <Badge variant="outline" class="text-xs">
              <Shield class="size-3 mr-1" />
              {{ auth.user?.role }}
            </Badge>
          </div>
        </div>
      </div>
    </div>

    <!-- Minecraft Account -->
    <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6">
      <div class="flex items-center gap-3 mb-5">
        <div class="size-10 rounded-xl bg-primary/10 flex items-center justify-center shrink-0">
          <Gamepad2 class="size-5 text-primary" />
        </div>
        <div>
          <h3 class="font-semibold text-foreground">{{ t('pages.profile.mc.title') }}</h3>
          <p class="text-sm text-muted-foreground">{{ t('pages.profile.mc.subtitle') }}</p>
        </div>
      </div>

      <!-- Linked state -->
      <div v-if="auth.user?.minecraftUuid" class="flex items-center justify-between">
        <div class="flex items-center gap-4">
          <img
            :src="`https://mc-heads.net/avatar/${auth.user.minecraftName}/48`"
            :alt="auth.user.minecraftName ?? ''"
            class="size-12 rounded-xl"
            loading="lazy"
          >
          <div>
            <p class="font-semibold text-foreground">{{ auth.user.minecraftName }}</p>
            <p class="text-xs text-muted-foreground font-mono">{{ auth.user.minecraftUuid }}</p>
          </div>
        </div>
        <Button
          variant="outline"
          class="border-destructive/50 text-destructive hover:bg-destructive/10"
          :disabled="mcUnlinking"
          @click="unlinkMinecraft"
        >
          <Loader2 v-if="mcUnlinking" class="size-4 mr-1.5 animate-spin" />
          <Unlink v-else class="size-4 mr-2" />
          {{ mcUnlinking ? t('pages.profile.mc.unlinking') : t('pages.profile.mc.unlink') }}
        </Button>
      </div>

      <!-- Unlinked state -->
      <div v-else class="flex flex-col gap-4 max-w-md">
        <div class="flex flex-col gap-1.5">
          <Label for="mc-username">{{ t('pages.profile.mc.usernameLabel') }}</Label>
          <Input
            id="mc-username"
            v-model="mcUsername"
            :placeholder="t('pages.profile.mc.usernamePlaceholder')"
            autocomplete="off"
            class="bg-glass border-glass-border"
            @keydown.enter="linkMinecraft"
          />
          <p class="text-xs text-muted-foreground">{{ t('pages.profile.mc.usernameHint') }}</p>
        </div>
        <Button
          class="self-start"
          :disabled="!mcUsername.trim() || mcLinking"
          @click="linkMinecraft"
        >
          <Loader2 v-if="mcLinking" class="size-4 mr-1.5 animate-spin" />
          <Link v-else class="size-4 mr-2" />
          {{ mcLinking ? t('pages.profile.mc.linking') : t('pages.profile.mc.linkAccount') }}
        </Button>
      </div>
    </div>

    <!-- Change Password -->
    <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6">
      <div class="flex items-center gap-3 mb-5">
        <div class="size-10 rounded-xl bg-primary/10 flex items-center justify-center shrink-0">
          <KeyRound class="size-5 text-primary" />
        </div>
        <div>
          <h3 class="font-semibold text-foreground">{{ t('pages.profile.password.title') }}</h3>
          <p class="text-sm text-muted-foreground">{{ t('pages.profile.password.subtitle') }}</p>
        </div>
      </div>

      <div class="flex flex-col gap-4 max-w-md">
        <div class="flex flex-col gap-1.5">
          <Label for="current-password">{{ t('pages.profile.password.currentLabel') }}</Label>
          <div class="relative">
            <Input
              id="current-password"
              v-model="currentPassword"
              :type="showCurrent ? 'text' : 'password'"
              :placeholder="t('pages.profile.password.currentPlaceholder')"
              autocomplete="current-password"
              class="bg-glass border-glass-border pr-10"
            />
            <button
              type="button"
              :aria-label="showCurrent ? t('common.a11y.hidePassword') : t('common.a11y.showPassword')"
              class="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground transition-colors"
              @click="showCurrent = !showCurrent"
            >
              <EyeOff v-if="showCurrent" class="size-4" />
              <Eye v-else class="size-4" />
            </button>
          </div>
        </div>

        <Separator class="my-1" />

        <div class="flex flex-col gap-1.5">
          <Label for="new-password">{{ t('pages.profile.password.newLabel') }}</Label>
          <div class="relative">
            <Input
              id="new-password"
              v-model="newPassword"
              :type="showNew ? 'text' : 'password'"
              :placeholder="t('pages.profile.password.newPlaceholder')"
              autocomplete="new-password"
              class="bg-glass border-glass-border pr-10"
            />
            <button
              type="button"
              :aria-label="showNew ? t('common.a11y.hidePassword') : t('common.a11y.showPassword')"
              class="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground transition-colors"
              @click="showNew = !showNew"
            >
              <EyeOff v-if="showNew" class="size-4" />
              <Eye v-else class="size-4" />
            </button>
          </div>
        </div>

        <div class="flex flex-col gap-1.5">
          <Label for="confirm-password">{{ t('pages.profile.password.confirmLabel') }}</Label>
          <Input
            id="confirm-password"
            v-model="confirmPassword"
            type="password"
            :placeholder="t('pages.profile.password.confirmPlaceholder')"
            autocomplete="new-password"
            class="bg-glass border-glass-border"
            @keydown.enter="changePassword"
          />
          <p v-if="confirmPassword && newPassword !== confirmPassword" class="text-xs text-destructive">
            {{ t('pages.profile.password.mismatch') }}
          </p>
        </div>

        <Button
          class="self-start mt-2"
          :disabled="!passwordValid || passwordLoading"
          @click="changePassword"
        >
          <Loader2 v-if="passwordLoading" class="size-4 mr-1.5 animate-spin" />
          {{ passwordLoading ? t('pages.profile.password.changing') : t('pages.profile.password.title') }}
        </Button>
      </div>
    </div>

    <!-- Danger Zone -->
    <div class="rounded-2xl border border-destructive/30 bg-destructive/5 p-6">
      <div class="flex items-center justify-between">
        <div class="flex items-start gap-3">
          <div class="size-10 rounded-xl bg-destructive/20 flex items-center justify-center shrink-0 mt-0.5">
            <LogOut class="size-5 text-destructive" />
          </div>
          <div>
            <h3 class="font-semibold text-foreground">{{ t('pages.profile.signOut.title') }}</h3>
            <p class="text-sm text-muted-foreground mt-1">{{ t('pages.profile.signOut.subtitle') }}</p>
          </div>
        </div>
        <Button
          variant="outline"
          class="shrink-0 ml-4 border-destructive/50 text-destructive hover:bg-destructive/10"
          @click="handleLogout"
        >
          <LogOut class="size-4 mr-2" />
          {{ t('pages.profile.signOut.title') }}
        </Button>
      </div>
    </div>
  </div>
</template>
