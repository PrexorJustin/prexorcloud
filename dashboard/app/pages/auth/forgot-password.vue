<script setup lang="ts">
import { Loader2, ArrowLeft, CheckCircle2 } from 'lucide-vue-next'
import { useForm } from 'vee-validate'
import { toTypedSchema } from '@vee-validate/zod'
import { z } from 'zod'
import { Input } from '~/components/ui/input'
import { Label } from '~/components/ui/label'
import { Button } from '~/components/ui/button'

definePageMeta({ layout: 'auth' })

const apiBase = useRuntimeConfig().public.apiBase as string
const { t } = useI18n()
const submitting = ref(false)
const submitted = ref(false)

const schema = toTypedSchema(
  z.object({
    email: z.string().email(t('auth.forgot.emailInvalid')),
  }),
)

const { handleSubmit, defineField, errors } = useForm({ validationSchema: schema })
const [email, emailAttrs] = defineField('email')

const onSubmit = handleSubmit(async (values) => {
  submitting.value = true
  try {
    await fetch(`${apiBase}/api/v1/auth/password-reset/request`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: values.email }),
    })
    submitted.value = true
  } finally {
    submitting.value = false
  }
})
</script>

<template>
  <div class="flex flex-col items-center">
    <div class="mb-6 text-center">
      <h1 class="text-xl font-semibold tracking-tight">{{ t('auth.forgot.title') }}</h1>
      <p class="mt-1 text-sm text-muted-foreground">{{ t('auth.forgot.subtitle') }}</p>
    </div>

    <div class="w-full overflow-hidden rounded-2xl border border-glass-border bg-glass/40 backdrop-blur-2xl">
      <div class="h-px bg-gradient-to-r from-transparent via-primary/50 to-transparent" />

      <div v-if="!submitted" class="p-6">
        <form class="flex flex-col gap-5" @submit="onSubmit">
          <div class="flex flex-col gap-2">
            <Label for="email" class="eyebrow">{{ t('auth.forgot.email') }}</Label>
            <Input
              id="email"
              v-model="email"
              v-bind="emailAttrs"
              type="email"
              :placeholder="t('auth.forgot.emailPlaceholder')"
              autocomplete="email"
              :disabled="submitting"
              class="h-11 rounded-xl border-glass-border bg-glass/60 focus:border-primary/60 focus:ring-2 focus:ring-primary/20"
            />
            <p v-if="errors.email" class="pl-1 text-xs text-destructive">{{ errors.email }}</p>
          </div>

          <Button
            type="submit"
            :disabled="submitting"
            class="h-11 w-full rounded-xl text-sm font-medium"
          >
            <Loader2 v-if="submitting" class="mr-2 size-4 animate-spin" />
            <template v-if="!submitting">{{ t('auth.forgot.sendLink') }}</template>
            <template v-else>{{ t('auth.forgot.sending') }}</template>
          </Button>
        </form>

        <NuxtLink
          to="/login"
          class="mt-4 inline-flex w-full items-center justify-center gap-2 text-sm text-muted-foreground transition-colors hover:text-foreground"
        >
          <ArrowLeft class="size-4" />
          {{ t('auth.forgot.backToSignIn') }}
        </NuxtLink>
      </div>

      <div v-else class="flex flex-col items-center gap-4 p-6">
        <div class="flex size-12 items-center justify-center rounded-full bg-primary/10">
          <CheckCircle2 class="size-6 text-primary" />
        </div>
        <p class="text-center text-sm text-muted-foreground">
          {{ t('auth.forgot.sentMessage') }}
        </p>
        <NuxtLink to="/login" class="inline-flex items-center gap-2 text-sm text-primary transition-colors hover:text-primary/80">
          <ArrowLeft class="size-4" />
          {{ t('auth.forgot.backToSignIn') }}
        </NuxtLink>
      </div>
    </div>
  </div>
</template>
