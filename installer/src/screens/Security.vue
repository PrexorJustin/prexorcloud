<script setup lang="ts">
import { computed } from 'vue';
import { useWizardStore } from '@/stores/wizard';
import { genSecret } from '@/lib/generators';
import { isEmail } from '@/lib/validators';
import Field from '@/components/Field.vue';
import TextInput from '@/components/TextInput.vue';
import NumInput from '@/components/NumInput.vue';
import SelectInput from '@/components/SelectInput.vue';
import ToggleInput from '@/components/ToggleInput.vue';
import Collapsible from '@/components/Collapsible.vue';
import NavBar from '@/components/NavBar.vue';

const wiz = useWizardStore();

const showCtrl = computed(() => wiz.mode === 'all' || wiz.mode === 'controller');
const showDaemon = computed(() => wiz.mode === 'all' || wiz.mode === 'daemon');

// Subline summaries shown on each collapsible header — match the legacy text.
const rateSub = computed(() => `${wiz.rateIpPerMinute}/IP · ${wiz.rateUserPerMinute}/user · per minute`);

const lockoutSub = computed(() =>
  wiz.lockoutEnabled
    ? `${wiz.lockoutMaxAttempts} attempts / ${wiz.lockoutWindowSeconds}s, lock ${wiz.lockoutLockoutSeconds}s`
    : 'Disabled',
);

const signingReqLabel = computed(() => {
  if (wiz.modulesSigningRequired === null) {
    return `auto (${wiz.profile === 'production' ? 'on' : 'off'})`;
  }
  return wiz.modulesSigningRequired ? 'on' : 'off';
});
const signingSub = computed(() => `${signingReqLabel.value} · ${wiz.modulesSigningMode}`);

const obsSub = computed(() => `${wiz.logLevel} · ${wiz.logFormat} · metrics ${wiz.metricsEnabled ? 'on' : 'off'}`);

function genJwt() {
  wiz.jwtSecret = genSecret(64);
}

// Static option arrays declared up here so SelectInput receives a stable
// reference rather than rebuilding on every keystroke.
const signingRequiredOpts: Array<[boolean | null, string]> = [
  [null, 'auto by profile'],
  [true, 'required'],
  [false, 'not required'],
];
const signingModeOpts = ['KEYED', 'COSIGN_BUNDLE'] as const;
const logLevelOpts = ['DEBUG', 'INFO', 'WARN', 'ERROR'] as const;
const logFormatOpts = ['HUMAN', 'JSON'] as const;
</script>

<template>
  <div class="body">
    <div class="content-pane">
      <div class="screen-head">
        <span class="screen-eyebrow"><span class="dot"></span>Step 3 of 4</span>
        <h2 class="screen-title"><em class="accent-serif">Hardening.</em></h2>
        <p class="screen-sub">Optional hardening. Defaults are sane — only crack these open if you want to deviate.</p>
      </div>
      <div class="screen-body">
        <!-- JWT -->
        <Collapsible v-if="showCtrl" id="jwt" title="JWT" sub="Token signing + expiration">
          <Field
            label="JWT secret"
            key-path="security.jwtSecret"
            help="Alphanumeric secret of ≥ 32 characters (Base64-decodable). Auto-generated when the wizard loaded; click Generate to rotate, or paste your own to pin for backups / multi-controller setups."
          >
            <template #badges>
              <span class="badge badge-auto">{{ wiz.jwtSecret ? 'set' : 'missing' }}</span>
            </template>
            <div class="input-row">
              <TextInput v-model="wiz.jwtSecret" placeholder="64-char alphanumeric secret" />
              <button class="inline-btn" type="button" @click="genJwt">Generate</button>
            </div>
          </Field>
          <Field
            label="JWT lifetime (min)"
            key-path="security.jwtExpirationMinutes"
            help="How long an issued token remains valid. 60 = 1h, 1440 = 24h, 10080 = a week. Shorter = safer at the cost of more dashboard logins."
          >
            <NumInput v-model="wiz.jwtExpirationMinutes" suffix="minutes" />
          </Field>
        </Collapsible>

        <!-- Rate limiting -->
        <Collapsible v-if="showCtrl" id="rate" title="Rate limiting" :sub="rateSub">
          <Field
            label="Per-IP / min"
            key-path="security.rateLimiting.perIpPerMinute"
            help="Hard cap of requests per source IP per minute, enforced before auth so brute-force is blocked early."
          >
            <NumInput v-model="wiz.rateIpPerMinute" />
          </Field>
          <Field
            label="Per-user / min"
            key-path="security.rateLimiting.perUserPerMinute"
            help="Cap per authenticated user. Counted by JWT subject after auth."
          >
            <NumInput v-model="wiz.rateUserPerMinute" />
          </Field>
          <Field
            label="Fail-open on Redis error"
            key-path="security.rateLimiting.failOpenOnRedisError"
            help="When Redis is unreachable, allow requests through (true) or reject (false). Default false = fail-closed = safer."
          >
            <ToggleInput
              v-model="wiz.rateFailOpenOnRedisError"
              :label="wiz.rateFailOpenOnRedisError ? 'fail-open' : 'fail-closed'"
            />
          </Field>
        </Collapsible>

        <!-- Lockout -->
        <Collapsible v-if="showCtrl" id="lockout" title="Account lockout" :sub="lockoutSub">
          <Field
            label="Lockout enabled"
            key-path="security.lockout.enabled"
            help="Lock accounts after repeated failed logins. Auto-unlock after the window expires."
          >
            <ToggleInput v-model="wiz.lockoutEnabled" :label="wiz.lockoutEnabled ? 'on' : 'off'" />
          </Field>
          <template v-if="wiz.lockoutEnabled">
            <Field label="Max attempts" key-path="security.lockout.maxAttempts" help="Failed logins before lockout.">
              <NumInput v-model="wiz.lockoutMaxAttempts" />
            </Field>
            <Field label="Observation window" key-path="security.lockout.windowSeconds" help="Window in seconds to count failures.">
              <NumInput v-model="wiz.lockoutWindowSeconds" suffix="s" />
            </Field>
            <Field label="Lockout duration" key-path="security.lockout.lockoutSeconds" help="Seconds to stay locked.">
              <NumInput v-model="wiz.lockoutLockoutSeconds" suffix="s" />
            </Field>
          </template>
        </Collapsible>

        <!-- Password reset + SMTP -->
        <Collapsible
          v-if="showCtrl"
          id="pwr"
          title="Password reset & SMTP"
          :sub="wiz.passwordResetEnabled ? 'Enabled' : 'Disabled'"
        >
          <Field
            label="Password reset enabled"
            key-path="security.passwordReset.enabled"
            help="Lets users request a reset link via email. Requires SMTP below."
          >
            <ToggleInput v-model="wiz.passwordResetEnabled" :label="wiz.passwordResetEnabled ? 'on' : 'off'" />
          </Field>
          <template v-if="wiz.passwordResetEnabled">
            <Field label="Token TTL (min)" key-path="security.passwordReset.tokenTtlMinutes" help="How long a reset token stays valid.">
              <NumInput v-model="wiz.passwordResetTtlMinutes" suffix="minutes" />
            </Field>
            <Field label="Reset URL base" key-path="security.passwordReset.resetUrlBase" help="URL prefix that emails point to; usually your dashboard origin.">
              <TextInput v-model="wiz.passwordResetUrlBase" placeholder="https://dash.example.com" />
            </Field>
            <div class="subhead">SMTP</div>
            <Field label="Host" key-path="security.passwordReset.smtp.host" help="SMTP relay hostname.">
              <TextInput v-model="wiz.smtpHost" placeholder="smtp.gmail.com" />
            </Field>
            <Field label="Port" key-path="security.passwordReset.smtp.port" help="587 for STARTTLS, 465 for implicit TLS.">
              <NumInput v-model="wiz.smtpPort" />
            </Field>
            <Field label="STARTTLS" key-path="security.passwordReset.smtp.startTls" help="Upgrade to TLS after greeting (port 587).">
              <ToggleInput v-model="wiz.smtpStartTls" :label="wiz.smtpStartTls ? 'on' : 'off'" />
            </Field>
            <Field label="Implicit TLS" key-path="security.passwordReset.smtp.implicitTls" help="Connect over TLS from the start (port 465).">
              <ToggleInput v-model="wiz.smtpImplicitTls" :label="wiz.smtpImplicitTls ? 'on' : 'off'" />
            </Field>
            <Field label="Username" key-path="security.passwordReset.smtp.username" help="SMTP auth user.">
              <TextInput v-model="wiz.smtpUsername" placeholder="noreply@example.com" />
            </Field>
            <Field label="Password" key-path="security.passwordReset.smtp.password" help="SMTP auth password / app token.">
              <TextInput v-model="wiz.smtpPassword" type="password" />
            </Field>
            <Field
              label="From address"
              key-path="security.passwordReset.smtp.from"
              help="Envelope-from address. Must be valid email."
            >
              <TextInput
                v-model="wiz.smtpFrom"
                placeholder="noreply@example.com"
                :invalid="!isEmail(wiz.smtpFrom)"
              />
            </Field>
          </template>
        </Collapsible>

        <!-- Module signing -->
        <Collapsible v-if="showCtrl" id="signing" title="Module signing" :sub="signingSub">
          <Field
            label="Signature required"
            key-path="modules.signing.required"
            help="When true, platform modules must carry a valid signature against the trust root. Leave null and the controller derives it from the runtime profile (true in production)."
          >
            <template #badges>
              <span class="badge badge-new">auto on in prod</span>
            </template>
            <SelectInput v-model="wiz.modulesSigningRequired" :options="signingRequiredOpts" />
          </Field>
          <Field
            label="Verifier mode"
            key-path="modules.signing.mode"
            help="KEYED = simple pubkey trust root; COSIGN_BUNDLE = full cosign attestation incl. optional Rekor transparency log."
          >
            <SelectInput v-model="wiz.modulesSigningMode" :options="signingModeOpts as unknown as string[]" />
          </Field>
          <Field
            label="Trust root"
            key-path="modules.signing.trustRoot"
            help="Path to a PEM file (KEYED mode) or cosign trust root JSON (COSIGN_BUNDLE). Leave blank and the installer generates a cosign keypair at config/security/module-trust-root.pem (private key kept in /opt/prexorcloud/controller/secrets/cosign.key, 0600). Provide a path here to bring your own."
          >
            <template #badges>
              <span v-if="!wiz.modulesSigningTrustRoot" class="badge badge-auto">auto-provisioned</span>
            </template>
            <TextInput v-model="wiz.modulesSigningTrustRoot" placeholder="config/security/module-trust-root.pem (auto-provisioned)" />
          </Field>
          <Field
            label="Allow unsigned in dev"
            key-path="modules.signing.allowUnsignedDevelopment"
            help="Escape hatch: when required=true in development profile, lets you still install unsigned local builds. Logs a loud warning. No effect in production."
          >
            <ToggleInput
              v-model="wiz.modulesSigningAllowUnsignedDev"
              :label="wiz.modulesSigningAllowUnsignedDev ? 'on' : 'off'"
            />
          </Field>
        </Collapsible>

        <!-- Logging + metrics -->
        <Collapsible v-if="showCtrl" id="obs" title="Logging & metrics" :sub="obsSub">
          <Field label="Log level" key-path="logging.level" help="Root SLF4J level.">
            <SelectInput v-model="wiz.logLevel" :options="logLevelOpts as unknown as string[]" />
          </Field>
          <Field label="Log format" key-path="logging.format" help="HUMAN = colour-coded for tail -f. JSON = structured for log aggregators.">
            <SelectInput v-model="wiz.logFormat" :options="logFormatOpts as unknown as string[]" />
          </Field>
          <Field label="Metrics enabled" key-path="metrics.enabled" help="Exposes /metrics (Prometheus) and the in-memory time series.">
            <ToggleInput v-model="wiz.metricsEnabled" :label="wiz.metricsEnabled ? 'on' : 'off'" />
          </Field>
          <Field
            label="Retention (hours)"
            key-path="metrics.retentionHours"
            help="How far back the in-memory time series goes. Capped to ~600k samples per series to keep memory bounded."
          >
            <NumInput v-model="wiz.metricsRetentionHours" suffix="h" />
          </Field>
          <Field
            label="Sample interval"
            key-path="metrics.collectionIntervalSeconds"
            help="How often the controller snapshots cluster state into the time series."
          >
            <NumInput v-model="wiz.metricsCollectionIntervalSeconds" suffix="s" />
          </Field>
        </Collapsible>

        <!-- Scheduler -->
        <Collapsible
          v-if="showCtrl"
          id="sched"
          title="Scheduler & heartbeat"
          sub="Evaluation cadence, cooldowns, crash detection"
        >
          <Field label="Evaluation interval" key-path="scheduler.evaluationIntervalSeconds" help="Tick rate for the scheduler loop (instance start/stop, scaling, lease renewal).">
            <NumInput v-model="wiz.schedulerEvalSeconds" suffix="s" />
          </Field>
          <Field label="Scaling cooldown" key-path="scheduler.scalingCooldownSeconds" help="Minimum gap between two scaling actions for the same group.">
            <NumInput v-model="wiz.schedulerCooldownSeconds" suffix="s" />
          </Field>
          <Field label="Node timeout" key-path="scheduler.nodeTimeoutSeconds" help="How long a daemon can miss heartbeats before the scheduler evicts its instances.">
            <NumInput v-model="wiz.schedulerNodeTimeoutSeconds" suffix="s" />
          </Field>
          <Field label="Audit retention" key-path="scheduler.auditRetentionDays" help="How long audit log entries are kept before pruning.">
            <NumInput v-model="wiz.schedulerAuditRetentionDays" suffix="d" />
          </Field>
          <Field label="Heartbeat interval" key-path="heartbeat.intervalMs" help="How often daemons must heartbeat back to the controller.">
            <NumInput v-model="wiz.heartbeatIntervalMs" suffix="ms" />
          </Field>
          <Field label="Missed threshold" key-path="heartbeat.missedThreshold" help="Missed heartbeats before a daemon is considered unhealthy.">
            <NumInput v-model="wiz.heartbeatMissedThreshold" />
          </Field>
          <Field label="Crash ring buffer" key-path="crashes.ringBufferSize" help="How many recent crash records are kept in memory for forensics.">
            <NumInput v-model="wiz.crashRingBufferSize" />
          </Field>
          <Field label="Crash-loop threshold" key-path="crashes.crashLoopThreshold" help="Crashes within the window below to flag a crash loop.">
            <NumInput v-model="wiz.crashLoopThreshold" />
          </Field>
          <Field label="Crash-loop window" key-path="crashes.crashLoopWindowSeconds" help="Time window for the threshold above.">
            <NumInput v-model="wiz.crashLoopWindowSeconds" suffix="s" />
          </Field>
        </Collapsible>

        <!-- Daemon runtime -->
        <Collapsible
          v-if="showDaemon"
          id="daemonrt"
          title="Daemon — runtime"
          sub="Health, logging, reconnect, instance limits"
        >
          <Field
            label="Health endpoint"
            key-path="health.enabled"
            help="Local HTTP probe at health.bindAddress:port — used by orchestrators / load balancers."
          >
            <ToggleInput v-model="wiz.daemonHealthEnabled" :label="wiz.daemonHealthEnabled ? 'on' : 'off'" />
          </Field>
          <template v-if="wiz.daemonHealthEnabled">
            <Field label="Bind address" key-path="health.bindAddress" help="Where the daemon binds its health endpoint. 127.0.0.1 = localhost only.">
              <TextInput v-model="wiz.daemonHealthBindAddress" />
            </Field>
            <Field label="Port" key-path="health.port" help="TCP port for the health endpoint.">
              <NumInput v-model="wiz.daemonHealthPort" />
            </Field>
          </template>
          <Field label="Log level" key-path="logging.level" help="Daemon-side SLF4J level (separate from controller).">
            <SelectInput v-model="wiz.daemonLogLevel" :options="logLevelOpts as unknown as string[]" />
          </Field>
          <Field label="Log format" key-path="logging.format" help="HUMAN or JSON.">
            <SelectInput v-model="wiz.daemonLogFormat" :options="logFormatOpts as unknown as string[]" />
          </Field>
          <Field label="Reconnect initial delay" key-path="reconnect.initialDelayMs" help="First retry delay after losing the controller connection.">
            <NumInput v-model="wiz.reconnectInitialDelayMs" suffix="ms" />
          </Field>
          <Field label="Reconnect max delay" key-path="reconnect.maxDelayMs" help="Cap on the exponential backoff.">
            <NumInput v-model="wiz.reconnectMaxDelayMs" suffix="ms" />
          </Field>
          <Field label="Reconnect multiplier" key-path="reconnect.multiplier" help="Exponent for the backoff growth.">
            <NumInput v-model="wiz.reconnectMultiplier" />
          </Field>
          <Field label="Instance shutdown timeout" key-path="instances.shutdownTimeoutSeconds" help="How long the daemon waits for a graceful shutdown before SIGKILL.">
            <NumInput v-model="wiz.instancesShutdownTimeoutSeconds" suffix="s" />
          </Field>
          <Field label="Console rate cap (lines/s)" key-path="instances.maxConsoleOutputLinesPerSecond" help="Prevents a chatty server from flooding the dashboard SSE stream.">
            <NumInput v-model="wiz.instancesMaxConsoleOutputLinesPerSecond" />
          </Field>
        </Collapsible>

        <!-- Maintenance -->
        <Collapsible
          v-if="showCtrl"
          id="maint"
          title="Maintenance mode"
          :sub="wiz.maintenanceEnabled ? 'Active — non-admins kicked' : 'Off'"
        >
          <Field
            label="Maintenance mode"
            key-path="maintenance.enabled"
            help="Globally pause scheduling + reject non-admin player joins."
          >
            <ToggleInput v-model="wiz.maintenanceEnabled" :label="wiz.maintenanceEnabled ? 'on' : 'off'" />
          </Field>
          <Field
            v-if="wiz.maintenanceEnabled"
            label="Player kick message"
            key-path="maintenance.message"
            help="Shown to players kicked while maintenance is on. Supports formatting codes."
          >
            <TextInput v-model="wiz.maintenanceMessage" prose />
          </Field>
        </Collapsible>

        <!-- Share -->
        <Collapsible
          v-if="showCtrl"
          id="share"
          title="Paste / share integration"
          :sub="wiz.shareEnabled ? wiz.sharePasteUrl : 'Off'"
        >
          <Field
            label="Share enabled"
            key-path="share.enabled"
            help="Lets operators upload redacted crash reports, log tails, and diagnostics to a pastebin via the share command."
          >
            <ToggleInput v-model="wiz.shareEnabled" :label="wiz.shareEnabled ? 'on' : 'off'" />
          </Field>
          <template v-if="wiz.shareEnabled">
            <Field label="Paste URL" key-path="share.pasteUrl" help="Pastebin endpoint. pste.dev is the default.">
              <TextInput v-model="wiz.sharePasteUrl" placeholder="https://pste.dev" />
            </Field>
            <Field label="Paste token" key-path="share.pasteToken" help="Optional auth token for the pastebin.">
              <TextInput v-model="wiz.sharePasteToken" type="password" />
            </Field>
            <Field label="Default expiry" key-path="share.defaultExpiry" help="pste.dev expiry shorthand (1h, 1d, 7d, 30d).">
              <TextInput v-model="wiz.shareDefaultExpiry" placeholder="1d" />
            </Field>
            <Field label="Default private" key-path="share.defaultPrivate" help="Default visibility of shares. Override per call.">
              <ToggleInput v-model="wiz.shareDefaultPrivate" :label="wiz.shareDefaultPrivate ? 'on' : 'off'" />
            </Field>
            <Field label="End-to-end encryption" key-path="share.e2e" help="Encrypt body client-side before upload (paste service never sees plaintext).">
              <ToggleInput v-model="wiz.shareE2E" :label="wiz.shareE2E ? 'on' : 'off'" />
            </Field>
          </template>
        </Collapsible>
      </div>
      <NavBar back="essentials" next="review" />
    </div>
  </div>
</template>
