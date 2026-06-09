<script setup lang="ts">
import { Loader2, ArrowRight, Eye, EyeOff, Wrench } from 'lucide-vue-next'
import { useForm } from 'vee-validate'
import { toTypedSchema } from '@vee-validate/zod'
import { z } from 'zod'
import { Input } from '~/components/ui/input'
import { Label } from '~/components/ui/label'
import { Button } from '~/components/ui/button'
import { DEV_MOCK_TOKEN, isDevMockAvailable, mockUser } from '~/lib/dev-mock'
import { setAuthToken } from '~/lib/auth-storage'
import { toast } from 'vue-sonner'

definePageMeta({ layout: 'auth' })

const auth = useAuthStore()
const route = useRoute()
const { t } = useI18n()
const submitting = ref(false)
const showPassword = ref(false)
const devAvailable = isDevMockAvailable()

async function devSignIn() {
  setAuthToken(DEV_MOCK_TOKEN)
  auth.token = DEV_MOCK_TOKEN
  auth.user = mockUser as typeof auth.user
  toast.success(t('auth.login.devToastTitle'), { description: t('auth.login.devToastDesc') })
  await navigateTo((route.query.redirect as string) || '/')
}

const schema = toTypedSchema(
  z.object({
    username: z.string().min(1, t('auth.login.usernameRequired')),
    password: z.string().min(1, t('auth.login.passwordRequired')),
  }),
)

const { handleSubmit, defineField, errors } = useForm({ validationSchema: schema })
const [username, usernameAttrs] = defineField('username')
const [password, passwordAttrs] = defineField('password')

const onSubmit = handleSubmit(async (values) => {
  submitting.value = true
  try {
    await auth.login(values)
    await navigateTo((route.query.redirect as string) || '/')
  } catch { /* toast handled by store */ }
  finally {
    submitting.value = false
  }
})
</script>

<template>
  <div class="flex flex-col items-center">
    <div class="mb-6 text-center">
      <h1 class="text-xl font-semibold tracking-tight">{{ t('auth.login.title') }}</h1>
      <p class="mt-1 text-sm text-muted-foreground">{{ t('auth.login.subtitle') }}</p>
    </div>

    <div class="w-full overflow-hidden rounded-2xl border border-glass-border bg-glass/40 backdrop-blur-2xl">
      <div class="h-px bg-gradient-to-r from-transparent via-primary/50 to-transparent" />

      <form class="flex flex-col gap-5 p-6" @submit="onSubmit">
        <div class="flex flex-col gap-2">
          <Label for="username" class="eyebrow">{{ t('auth.login.username') }}</Label>
          <Input
            id="username"
            v-model="username"
            v-bind="usernameAttrs"
            :placeholder="t('auth.login.usernamePlaceholder')"
            autocomplete="username"
            :disabled="submitting"
            class="h-11 rounded-xl border-glass-border bg-glass/60 focus:border-primary/60 focus:ring-2 focus:ring-primary/20"
          />
          <p v-if="errors.username" class="pl-1 text-xs text-destructive">{{ errors.username }}</p>
        </div>

        <div class="flex flex-col gap-2">
          <Label for="password" class="eyebrow">{{ t('auth.login.password') }}</Label>
          <div class="relative">
            <Input
              id="password"
              v-model="password"
              v-bind="passwordAttrs"
              :type="showPassword ? 'text' : 'password'"
              :placeholder="t('auth.login.passwordPlaceholder')"
              autocomplete="current-password"
              :disabled="submitting"
              class="h-11 rounded-xl border-glass-border bg-glass/60 pr-12 focus:border-primary/60 focus:ring-2 focus:ring-primary/20"
            />
            <button
              type="button"
              tabindex="-1"
              :aria-label="t('auth.login.togglePassword')"
              class="absolute right-2 top-1/2 -translate-y-1/2 flex size-8 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-glass-hover hover:text-foreground"
              @click="showPassword = !showPassword"
            >
              <EyeOff v-if="showPassword" class="size-4" />
              <Eye v-else class="size-4" />
            </button>
          </div>
          <p v-if="errors.password" class="pl-1 text-xs text-destructive">{{ errors.password }}</p>
        </div>

        <Button
          type="submit"
          :disabled="submitting"
          class="group h-11 w-full rounded-xl text-sm font-medium"
        >
          <Loader2 v-if="submitting" class="mr-2 size-4 animate-spin" />
          <template v-if="!submitting">
            {{ t('auth.login.signIn') }}
            <ArrowRight class="ml-2 size-4 transition-transform duration-200 group-hover:translate-x-0.5" />
          </template>
          <template v-else>{{ t('auth.login.signingIn') }}</template>
        </Button>

        <NuxtLink
          to="/auth/forgot-password"
          class="text-center text-xs text-muted-foreground transition-colors hover:text-foreground"
        >
          {{ t('auth.login.forgotPassword') }}
        </NuxtLink>
      </form>

      <div v-if="devAvailable" class="border-t border-glass-border bg-glass/40 p-4">
        <Button
          type="button"
          variant="outline"
          class="w-full"
          @click="devSignIn"
        >
          <Wrench class="mr-2 size-4" />
          {{ t('auth.login.devSignIn') }}
        </Button>
        <p class="mt-2 text-center text-[11px] text-muted-foreground/70">
          {{ t('auth.login.devHint') }}
        </p>
      </div>
    </div>
  </div>
</template>
