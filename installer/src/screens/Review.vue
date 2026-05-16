<script setup lang="ts">
import { computed, ref } from 'vue';
import { useWizardStore } from '@/stores/wizard';
import { yamlFor, type YamlFilename } from '@/lib/yaml';
import { highlightYaml } from '@/lib/highlight';
import NavBar from '@/components/NavBar.vue';
import InstallConsole from '@/components/InstallConsole.vue';

const wiz = useWizardStore();

const files = computed<YamlFilename[]>(() => wiz.filesForMode);
const active = computed<YamlFilename>(() => {
  const f = files.value;
  return (f.includes(wiz.activeReviewTab as YamlFilename) ? wiz.activeReviewTab : f[0]) as YamlFilename;
});
const yaml = computed(() => yamlFor(wiz.$state, active.value));
const lines = computed(() => yaml.value.split('\n').length);
const highlighted = computed(() => highlightYaml(yaml.value));

const isNative = computed(() => wiz.installMode === 'native');
const showConsole = computed(() => wiz.busy || wiz.installPhases.length > 0 || wiz.installDone);

const subtitle = computed(() =>
  wiz.installDone
    ? 'Install complete. Open the dashboard and finish onboarding.'
    : 'Copy + paste the YAML below into your install dir, or hit Install to have the wizard write it for you.',
);

function selectTab(f: YamlFilename) {
  wiz.activeReviewTab = f;
}

const copyState = ref<{ file: YamlFilename | null }>({ file: null });
async function doCopy() {
  try {
    await navigator.clipboard.writeText(yaml.value);
    copyState.value = { file: active.value };
    window.setTimeout(() => (copyState.value = { file: null }), 1500);
  } catch {
    // clipboard.writeText may reject in non-secure contexts — silently skip.
  }
}

function doDownload() {
  const blob = new Blob([yaml.value], { type: 'text/yaml' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = active.value;
  a.click();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

const copyLabel = computed(() => (copyState.value.file === active.value ? '✓ Copied' : 'Copy'));
</script>

<template>
  <div class="body">
    <div class="content-pane">
      <div class="screen-head">
        <span class="screen-eyebrow"><span class="dot"></span>Step 4 of 4</span>
        <h2 class="screen-title">Your <em class="accent-serif">configuration.</em></h2>
        <p class="screen-sub">{{ subtitle }}</p>
      </div>
      <div class="screen-body">
        <div v-if="files.length > 1" class="term-tabs">
          <button
            v-for="f in files"
            :key="f"
            class="term-tab"
            :class="{ active: active === f }"
            type="button"
            @click="selectTab(f)"
          >{{ f }}</button>
        </div>
        <div class="term-card">
          <div class="term-head">
            <div class="term-dots"><span class="d1"></span><span class="d2"></span><span class="d3"></span></div>
            <span class="term-name">{{ active }} <span class="lines">· {{ lines }} lines</span></span>
            <div class="term-actions">
              <button class="btn ghost" type="button" @click="doCopy">{{ copyLabel }}</button>
              <button class="btn tinted" type="button" @click="doDownload">Download</button>
            </div>
          </div>
          <pre class="term-pre mono" v-html="highlighted" />
        </div>

        <div class="next-card">
          <div class="next-eyebrow">Next</div>
          <div v-if="isNative" class="next-cmd">
            <template v-if="wiz.mode === 'dashboard'">
              <div><span class="pr">$</span>systemctl status {{ wiz.webServer }} --no-pager</div>
              <div><span class="pr">$</span>open {{ wiz.dashboardPublicUrl || 'https://dash.example.com' }}</div>
            </template>
            <template v-else>
              <div><span class="pr">$</span>systemctl status prexorcloud-{{ wiz.mode === 'daemon' ? 'daemon' : 'controller' }} --no-pager</div>
              <div v-if="wiz.mode !== 'daemon'"><span class="pr">$</span>cat /opt/prexorcloud/controller/config/.initial-admin-password   # if auto-generated</div>
              <div><span class="pr">$</span>prexorctl login                  # CLI is auto-linked</div>
            </template>
          </div>
          <div v-else class="next-cmd">
            <template v-if="wiz.mode === 'dashboard'">
              <div><span class="pr">$</span>cd /opt/prexorcloud/dashboard</div>
              <div><span class="pr">$</span>docker compose up -d</div>
              <div><span class="pr">$</span>open {{ wiz.dashboardPublicUrl || 'https://dash.example.com' }}</div>
            </template>
            <template v-else>
              <div><span class="pr">$</span>cd /opt/prexorcloud/{{ wiz.mode === 'daemon' ? 'daemon' : 'controller' }}</div>
              <div><span class="pr">$</span>docker compose up -d</div>
              <div v-if="wiz.mode !== 'daemon'"><span class="pr">$</span>cat config/.initial-admin-password   # if you let it auto-generate</div>
              <div><span class="pr">$</span>prexorctl login                  # CLI is auto-linked after compose-up</div>
            </template>
          </div>

          <div v-if="wiz.initialAdminPassword" class="next-pwd-callout">
            <strong>SAVE THIS NOW</strong> — the admin password is not stored anywhere recoverable. <br>
            <code>{{ wiz.initialAdminPassword }}</code>
          </div>

          <div style="margin-top:14px;display:flex;gap:8px;align-items:center;">
            <span
              v-if="wiz.installDone"
              style="color:var(--success);font-weight:600;font-size:13px;"
            >✓ Installed</span>
            <button
              v-else
              class="btn primary"
              type="button"
              :disabled="wiz.busy"
              @click="wiz.doInstall()"
            >{{ wiz.busy ? 'Installing…' : 'Install now' }}</button>
            <span class="mono" style="color:var(--muted-foreground);font-size:11.5px;">
              <template v-if="isNative">→ installs host services (systemd) + starts them on this VPS</template>
              <template v-else>→ writes config + starts the compose project on this VPS</template>
            </span>
          </div>

          <InstallConsole v-if="showConsole" />
          <!--
            Install-failure block. Three layers, most-specific first:
              1. Validation errors → render each finding with field + recovery
                 hints, so the operator can fix every issue in one pass.
              2. Generic error → show message + docs link (errorCode-derived).
              3. Nothing → omitted entirely.
            The bare red text we used to render here lost the recovery steps
            the Go backend already emitted (stream.go) and made the operator
            guess the next move.
          -->
          <div v-if="wiz.installValidationErrors.length" class="install-error-block validation">
            <div class="install-error-head">
              <span class="install-error-icon" aria-hidden="true">⚠</span>
              <span class="install-error-title">
                controller.yml failed pre-flight validation
                ({{ wiz.installValidationErrors.length }}
                {{ wiz.installValidationErrors.length === 1 ? 'issue' : 'issues' }})
              </span>
            </div>
            <ul class="install-validation-list">
              <li v-for="(verr, i) in wiz.installValidationErrors" :key="i">
                <div class="install-validation-field mono">{{ verr.field }}</div>
                <div class="install-validation-msg">{{ verr.message }}</div>
                <ul v-if="verr.recovery?.length" class="install-validation-recovery">
                  <li v-for="(step, j) in verr.recovery" :key="j">{{ step }}</li>
                </ul>
              </li>
            </ul>
            <a
              v-if="wiz.installErrorDocsUrl"
              :href="wiz.installErrorDocsUrl"
              target="_blank"
              rel="noopener"
              class="install-error-docs"
            >Open the troubleshooting docs →</a>
          </div>
          <div v-else-if="wiz.installError" class="install-error-block generic">
            <div class="install-error-head">
              <span class="install-error-icon" aria-hidden="true">✗</span>
              <span class="install-error-title">Install failed</span>
              <span v-if="wiz.installErrorCode" class="install-error-code mono">{{ wiz.installErrorCode }}</span>
            </div>
            <div class="install-error-msg">{{ wiz.installError }}</div>
            <a
              v-if="wiz.installErrorDocsUrl"
              :href="wiz.installErrorDocsUrl"
              target="_blank"
              rel="noopener"
              class="install-error-docs"
            >Open the troubleshooting docs →</a>
          </div>
        </div>
      </div>
      <NavBar back="security" hide-next />
    </div>
  </div>
</template>
