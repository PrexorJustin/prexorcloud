<script setup lang="ts">
import { computed } from 'vue';
import { useWizardStore } from '@/stores/wizard';

const wiz = useWizardStore();

const canSubmit = computed(
  () =>
    !wiz.cliLoginBusy &&
    wiz.cliLoginController.trim() !== '' &&
    wiz.cliLoginUsername.trim() !== '' &&
    wiz.cliLoginPassword !== '',
);

function back() {
  wiz.setStep('mode');
}
</script>

<template>
  <div class="body">
    <div class="content-pane">
      <template v-if="wiz.cliLoginDone">
        <div class="welcome" style="max-width: 560px; margin: 0 auto; text-align: center">
          <div class="eyebrow" style="color: var(--success)">Signed in</div>
          <h1 style="margin-top: 8px">
            You're
            <em class="accent-serif">connected.</em>
          </h1>
          <p>
            prexorctl is now logged in to
            <code>{{ wiz.cliLoginController }}</code>
            as
            <code>{{ wiz.cliLoginAs }}</code>
            . The token has been written to
            <code>~/.prexorcloud/config.yml</code>
            .
          </p>
          <p style="margin-top: 8px">You can close this tab.</p>
          <div style="margin-top: 18px; display: flex; justify-content: center; gap: 8px">
            <button class="btn primary" type="button" @click="wiz.exitWizard()">
              Close wizard
            </button>
          </div>
        </div>
      </template>

      <template v-else>
        <div class="welcome" style="max-width: 560px; margin: 0 auto">
          <div class="eyebrow">CLI login</div>
          <h1>
            Sign in to your
            <em class="accent-serif">cluster.</em>
          </h1>
          <p>
            Bind this prexorctl to an existing controller. We'll POST your credentials to its
            <code>/api/v1/auth/login</code>
            , store the returned token in
            <code>~/.prexorcloud/config.yml</code>
            , and you're done — no install, no systemd, nothing written outside that file.
          </p>
        </div>

        <div
          style="
            max-width: 560px;
            margin: 24px auto 0;
            display: flex;
            flex-direction: column;
            gap: 14px;
          "
        >
          <div>
            <label class="cli-field-label">Controller URL</label>
            <input
              v-model="wiz.cliLoginController"
              class="input prose"
              type="url"
              placeholder="http://controller.example.com:8080"
              autocomplete="url"
            />
          </div>
          <div>
            <label class="cli-field-label">Username</label>
            <input
              v-model="wiz.cliLoginUsername"
              class="input prose"
              type="text"
              autocomplete="username"
            />
          </div>
          <div>
            <label class="cli-field-label">Password</label>
            <input
              v-model="wiz.cliLoginPassword"
              class="input prose"
              type="password"
              autocomplete="current-password"
            />
          </div>
          <div
            v-if="wiz.cliLoginError"
            style="color: var(--destructive); font-size: 13px; line-height: 1.45"
          >
            {{ wiz.cliLoginError }}
          </div>
        </div>

        <div class="navbar" style="margin-top: 28px">
          <button class="btn ghost" type="button" @click="back">← Back</button>
          <span class="grow"></span>
          <button
            class="btn primary"
            type="button"
            :disabled="!canSubmit"
            @click="wiz.submitCliLogin()"
          >
            {{ wiz.cliLoginBusy ? 'Signing in…' : 'Sign in' }}
          </button>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.cli-field-label {
  display: block;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--muted-foreground);
  margin-bottom: 6px;
}
</style>
