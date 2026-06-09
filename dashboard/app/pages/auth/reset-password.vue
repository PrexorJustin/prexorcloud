<script setup lang="ts">
import { Loader2, ArrowLeft, ShieldCheck, AlertCircle } from 'lucide-vue-next'
import { useForm } from 'vee-validate'
import { toTypedSchema } from '@vee-validate/zod'
import { z } from 'zod'
import { toast } from 'vue-sonner'
import { Input } from '~/components/ui/input'
import { Label } from '~/components/ui/label'
import { Button } from '~/components/ui/button'
import { Callout, CalloutTitle } from '~/components/ui/callout'

definePageMeta({ layout: 'auth' })

const apiBase = useRuntimeConfig().public.apiBase as string
const route = useRoute()
const { t } = useI18n()
const submitting = ref(false)
const completed = ref(false)
const errorMessage = ref<string | null>(null)

const token = computed(() => (route.query.token as string | undefined) ?? '')

const schema = toTypedSchema(
  z.object({
    newPassword: z.string().min(8, t('auth.reset.minChars')),
    confirmPassword: z.string().min(1, t('auth.reset.reenter')),
  }).refine((d) => d.newPassword === d.confirmPassword, {
    path: ['confirmPassword'],
    message: t('auth.reset.mismatch'),
  }),
)

const { handleSubmit, defineField, errors } = useForm({ validationSchema: schema })
const [newPassword, newPasswordAttrs] = defineField('newPassword')
const [confirmPassword, confirmPasswordAttrs] = defineField('confirmPassword')

const onSubmit = handleSubmit(async (values) => {
  if (!token.value) {
    errorMessage.value = t('auth.reset.missingToken')
    return
  }
  submitting.value = true
  errorMessage.value = null
  try {
    const res = await fetch(`${apiBase}/api/v1/auth/password-reset/complete`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token: token.value, newPassword: values.newPassword }),
    })
    if (res.ok) {
      completed.value = true
      toast.success(t('auth.reset.successToast'))
    } else {
      const body = await res.json().catch(() => ({})) as { error?: { code?: string, message?: string } }
      const code = body?.error?.code
      if (code === 'INVALID_TOKEN') {
        errorMessage.value = t('auth.reset.invalidToken')
      } else {
        errorMessage.value = body?.error?.message ?? t('auth.reset.genericError')
      }
    }
  } catch {
    errorMessage.value = t('auth.reset.networkError')
  } finally {
    submitting.value = false
  }
})
</script>

<template>
  <div class="flex flex-col items-center">
    <div class="mb-6 text-center">
      <h1 class="text-xl font-semibold tracking-tight">{{ t('auth.reset.title') }}</h1>
      <p class="mt-1 text-sm text-muted-foreground">{{ t('auth.reset.subtitle') }}</p>
    </div>

    <div class="w-full overflow-hidden rounded-2xl border border-glass-border bg-glass/40 backdrop-blur-2xl">
      <div class="h-px bg-gradient-to-r from-transparent via-primary/50 to-transparent" />

      <div v-if="!completed && token" class="p-6">
        <form class="flex flex-col gap-5" @submit="onSubmit">
          <div class="flex flex-col gap-2">
            <Label for="newPassword" class="eyebrow">{{ t('auth.reset.newPassword') }}</Label>
            <Input
              id="newPassword"
              v-model="newPassword"
              v-bind="newPasswordAttrs"
              type="password"
              :placeholder="t('auth.reset.newPasswordPlaceholder')"
              autocomplete="new-password"
              :disabled="submitting"
              class="h-11 rounded-xl border-glass-border bg-glass/60 focus:border-primary/60 focus:ring-2 focus:ring-primary/20"
            />
            <p v-if="errors.newPassword" class="pl-1 text-xs text-destructive">{{ errors.newPassword }}</p>
          </div>

          <div class="flex flex-col gap-2">
            <Label for="confirmPassword" class="eyebrow">{{ t('auth.reset.confirmPassword') }}</Label>
            <Input
              id="confirmPassword"
              v-model="confirmPassword"
              v-bind="confirmPasswordAttrs"
              type="password"
              :placeholder="t('auth.reset.confirmPasswordPlaceholder')"
              autocomplete="new-password"
              :disabled="submitting"
              class="h-11 rounded-xl border-glass-border bg-glass/60 focus:border-primary/60 focus:ring-2 focus:ring-primary/20"
            />
            <p v-if="errors.confirmPassword" class="pl-1 text-xs text-destructive">{{ errors.confirmPassword }}</p>
          </div>

          <Callout v-if="errorMessage" variant="error">
            <CalloutTitle>{{ errorMessage }}</CalloutTitle>
          </Callout>

          <Button type="submit" :disabled="submitting" class="h-11 w-full rounded-xl text-sm font-medium">
            <Loader2 v-if="submitting" class="mr-2 size-4 animate-spin" />
            <template v-if="!submitting">{{ t('auth.reset.updatePassword') }}</template>
            <template v-else>{{ t('auth.reset.updating') }}</template>
          </Button>
        </form>
      </div>

      <div v-else-if="completed" class="flex flex-col items-center gap-4 p-6">
        <div class="flex size-12 items-center justify-center rounded-full bg-primary/10">
          <ShieldCheck class="size-6 text-primary" />
        </div>
        <p class="text-center text-sm text-muted-foreground">
          {{ t('auth.reset.completedMessage') }}
        </p>
        <NuxtLink to="/login" class="inline-flex items-center gap-2 text-sm text-primary transition-colors hover:text-primary/80">
          <ArrowLeft class="size-4" />
          {{ t('auth.reset.continueToSignIn') }}
        </NuxtLink>
      </div>

      <div v-else class="flex flex-col items-center gap-4 p-6">
        <div class="flex size-12 items-center justify-center rounded-full bg-destructive/10">
          <AlertCircle class="size-6 text-destructive" />
        </div>
        <p class="text-center text-sm text-muted-foreground">
          {{ t('auth.reset.missingTokenMessage') }}
        </p>
        <NuxtLink to="/auth/forgot-password" class="inline-flex items-center gap-2 text-sm text-primary transition-colors hover:text-primary/80">
          {{ t('auth.reset.requestNewLink') }}
        </NuxtLink>
      </div>
    </div>
  </div>
</template>
