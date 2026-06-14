<script setup lang="ts">
import { computed } from 'vue';
import { useWizardStore, type Profile } from '@/stores/wizard';
import { genPassword, genJoinToken } from '@/lib/generators';
import { isMongoOrRedis, isHttpUrl, portConflict } from '@/lib/validators';
import Field from '@/components/Field.vue';
import TextInput from '@/components/TextInput.vue';
import NumInput from '@/components/NumInput.vue';
import ListInput from '@/components/ListInput.vue';
import ToggleInput from '@/components/ToggleInput.vue';
import NavBar from '@/components/NavBar.vue';

const wiz = useWizardStore();

const showCtrl = computed(
  () => wiz.mode === 'all' || wiz.mode === 'controller' || wiz.mode === 'controller-join',
);
const showCtrlJoin = computed(() => wiz.mode === 'controller-join');
const showDaemon = computed(() => wiz.mode === 'all' || wiz.mode === 'daemon');
const showDash = computed(() => wiz.mode === 'dashboard');
const conflict = computed(() => portConflict(wiz.$state));
const redisRequired = computed(() => wiz.profile === 'production');
const isNative = computed(() => wiz.installMode === 'native');
// "Install locally" means a sidecar container under compose, but a host
// package under native — the button/callout copy reflects whichever path.
const localLabel = computed(() =>
  isNative.value ? 'Install locally (host)' : 'Install locally (docker)',
);

function setProfile(p: Profile) {
  wiz.profile = p;
}

function detectAdvertise() {
  wiz.advertiseAddress = '10.0.0.' + (10 + Math.floor(Math.random() * 240));
}

function generatePassword() {
  wiz.initialAdminPassword = genPassword(16);
}

function generateJoinToken() {
  wiz.joinToken = genJoinToken();
}
</script>

<template>
  <div class="body">
    <div class="content-pane">
      <div class="screen-head">
        <span class="screen-eyebrow">
          <span class="dot"></span>
          Step 2 of 4
        </span>
        <h2 class="screen-title">
          The
          <em class="accent-serif">essentials.</em>
        </h2>
        <p class="screen-sub">
          The minimum each component needs to start. Defaults are from the post-audit schema — every
          key shown here actually drives behaviour in the controller / daemon.
        </p>
      </div>
      <div class="screen-body">
        <!-- Install path -->
        <div class="profile-card" style="margin-bottom: 12px">
          <div class="eyebrow">Install path</div>
          <div class="segmented" role="tablist" aria-label="Install path">
            <button
              type="button"
              role="tab"
              :aria-selected="wiz.installMode === 'compose'"
              :class="wiz.installMode === 'compose' ? 'on' : ''"
              @click="wiz.setInstallMode('compose')"
            >
              Docker (compose)
            </button>
            <button
              type="button"
              role="tab"
              :aria-selected="wiz.installMode === 'native'"
              :class="wiz.installMode === 'native' ? 'on' : ''"
              :disabled="!wiz.nativeAllowed"
              :title="wiz.nativeAllowed ? '' : wiz.nativeReason"
              @click="wiz.setInstallMode('native')"
            >
              Native (systemd)
            </button>
          </div>
          <div class="hint">
            <template v-if="isNative">
              Installs onto this host via systemd + the package manager — no Docker needed.
              MongoDB/Redis/JRE are installed as host services.
            </template>
            <template v-else>
              Runs every component as a Docker container. Works anywhere Docker + Compose v2 are
              present.
            </template>
            <template v-if="!wiz.nativeAllowed">
              <br />
              <span style="opacity: 0.8">
                Native unavailable: {{ wiz.nativeReason || 'requires Linux + root.' }}
              </span>
            </template>
          </div>
        </div>

        <!-- Service lifecycle -->
        <div class="profile-card" style="margin-bottom: 12px">
          <div class="eyebrow">Service lifecycle</div>
          <div style="display: flex; flex-direction: column; gap: 10px; margin-top: 8px">
            <ToggleInput
              v-model="wiz.enableOnBoot"
              :label="
                isNative
                  ? 'Start automatically on boot (systemctl enable)'
                  : 'Start automatically on boot (restart: unless-stopped)'
              "
            />
            <ToggleInput
              v-model="wiz.startNow"
              :label="
                isNative
                  ? 'Start now when setup finishes (systemctl start)'
                  : 'Start now when setup finishes (docker compose up -d)'
              "
            />
          </div>
          <div class="hint">
            Auto-start on boot keeps the component running after a reboot. Start now brings it up as
            soon as installation completes — leave both on for a hands-off install.
          </div>
        </div>

        <!-- Profile + UUID top bar -->
        <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-bottom: 22px">
          <div class="profile-card">
            <div class="eyebrow">Runtime profile</div>
            <div class="twobtn">
              <button
                type="button"
                data-profile="development"
                :class="wiz.profile === 'development' ? 'on dev' : ''"
                @click="setProfile('development')"
              >
                development
              </button>
              <button
                type="button"
                data-profile="production"
                :class="wiz.profile === 'production' ? 'on prod' : ''"
                @click="setProfile('production')"
              >
                production
              </button>
            </div>
            <div class="hint">
              <template v-if="wiz.profile === 'production'">
                Redis required, module signing on by default, JWT secret must be 32+ bytes.
              </template>
              <template v-else>
                Redis optional, signing relaxed, in-process fallbacks for coordination.
              </template>
            </div>
          </div>
          <div class="uuid-card">
            <div class="head">
              <span class="eyebrow">Cluster UUID</span>
              <span class="badge badge-auto">auto-generated</span>
            </div>
            <div class="row">
              <code>{{ wiz.uuid }}</code>
              <button
                class="icon-btn"
                type="button"
                title="Regenerate"
                @click="wiz.regenerateUuid()"
              >
                ↻
              </button>
            </div>
          </div>
        </div>

        <!-- System requirements info -->
        <div v-if="showCtrl || showDaemon" class="system-info">
          <svg
            class="ico"
            width="18"
            height="18"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
            stroke-linecap="round"
            stroke-linejoin="round"
          >
            <circle cx="12" cy="12" r="10" />
            <line x1="12" y1="16" x2="12" y2="12" />
            <line x1="12" y1="8" x2="12.01" y2="8" />
          </svg>
          <div v-if="isNative" class="text">
            <strong>What runs on this host.</strong>
            <ul>
              <li>
                The installer is running
                <strong>as root</strong>
                and will install components as
                <strong>systemd services</strong>
                — no Docker required.
              </li>
              <li>
                A managed
                <strong>Eclipse Temurin JRE 25</strong>
                is installed to
                <code class="mono">/opt/prexorcloud/jre</code>
                if a suitable Java isn't already present.
              </li>
              <li v-if="showCtrl">
                Selecting
                <strong>Install locally</strong>
                for MongoDB or Redis installs them via your distro's package manager (MongoDB 8.0 /
                Redis) and enables them as services on
                <code class="mono">localhost</code>
                .
              </li>
              <li v-if="showDaemon">
                The daemon is registered as
                <code class="mono">prexorcloud-daemon.service</code>
                and started immediately.
              </li>
            </ul>
          </div>
          <div v-else class="text">
            <strong>What runs on this host.</strong>
            <ul>
              <li>
                <strong>Docker (24+) and Compose v2</strong>
                must already be installed — the installer uses them to start the stack.
              </li>
              <li v-if="showCtrl">
                Selecting
                <strong>Install locally</strong>
                for MongoDB or Redis spawns them as containers (
                <code class="mono">mongo:8</code>
                ,
                <code class="mono">redis:7-alpine</code>
                ) inside the same Compose project — nothing is installed on the host.
              </li>
              <li v-if="showCtrl">
                <strong>Cosign</strong>
                ships inside the controller image; you do not need it on the host to enable
                signed-module enforcement later.
              </li>
              <li v-if="showDaemon">
                The daemon runs as a container too — no additional host packages are needed.
              </li>
            </ul>
          </div>
        </div>

        <!-- Controller-join: paste the wire token. -->
        <div v-if="showCtrlJoin" class="section-card">
          <div class="section-head">
            <span class="section-title">Cluster join token</span>
            <span class="section-grow"></span>
            <span class="section-sub">paste from an existing cluster controller</span>
          </div>
          <Field
            label="Join token"
            key-path="cluster.joinToken"
            required
            help="The wire token printed by 'prexorctl cluster join-token create' on an existing cluster controller. Single-use: a token can be redeemed at most once."
          >
            <TextInput
              v-model="wiz.controllerJoinToken"
              placeholder="prexor-jt:v1:..."
              :invalid="!wiz.controllerJoinToken.trim().startsWith('prexor-jt:v1:')"
            />
            <div class="hint" style="margin-top: 6px">
              The token's HMAC is checked against the cluster seed; an unrecognised or rotated token
              is refused at first start.
            </div>
          </Field>
        </div>

        <!-- Controller -->
        <div v-if="showCtrl" class="section-card">
          <div class="section-head">
            <span class="section-title">Controller</span>
            <span class="section-grow"></span>
            <span class="section-sub">network · storage · admin</span>
          </div>
          <Field
            label="HTTP / gRPC ports"
            key-path="http.port + grpc.port"
            required
            help="HTTP port serves the REST API + dashboard SPA. gRPC port accepts daemon registrations and mTLS streams. They must differ."
          >
            <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 10px">
              <div>
                <NumInput v-model="wiz.httpPort" :invalid="conflict" />
                <div
                  style="
                    font-family: 'JetBrains Mono', monospace;
                    font-size: 10.5px;
                    color: var(--muted-foreground);
                    margin-top: 4px;
                  "
                >
                  HTTP
                </div>
              </div>
              <div>
                <NumInput v-model="wiz.grpcPort" :invalid="conflict" />
                <div
                  style="
                    font-family: 'JetBrains Mono', monospace;
                    font-size: 10.5px;
                    color: var(--muted-foreground);
                    margin-top: 4px;
                  "
                >
                  gRPC · for daemons
                </div>
              </div>
            </div>
            <div v-if="conflict" class="field-error">HTTP and gRPC ports must differ.</div>
          </Field>
          <Field
            label="MongoDB"
            key-path="database.uri"
            :required="wiz.mongoMode === 'remote'"
            help="Install locally to spawn a Mongo 8 sidecar container in the compose project (URI auto-set to mongo:27017). Use existing to point at a Mongo 6.0+ you already run."
          >
            <template #badges>
              <span class="badge badge-auto" v-if="wiz.mongoMode === 'local'">auto-installed</span>
            </template>
            <div class="segmented" role="tablist" aria-label="MongoDB source">
              <button
                type="button"
                role="tab"
                :aria-selected="wiz.mongoMode === 'local'"
                :class="wiz.mongoMode === 'local' ? 'on' : ''"
                @click="wiz.setMongoMode('local')"
              >
                {{ localLabel }}
              </button>
              <button
                type="button"
                role="tab"
                :aria-selected="wiz.mongoMode === 'remote'"
                :class="wiz.mongoMode === 'remote' ? 'on' : ''"
                @click="wiz.setMongoMode('remote')"
              >
                Use existing URI
              </button>
            </div>
            <TextInput
              v-if="wiz.mongoMode === 'remote'"
              v-model="wiz.databaseUri"
              placeholder="mongodb://localhost:27017"
              :invalid="!isMongoOrRedis(wiz.databaseUri)"
            />
            <div v-else class="service-callout">
              <div v-if="isNative">
                Installs
                <code class="mono">mongodb-org</code>
                8.0 from the official repo and enables
                <code class="mono">mongod</code>
                on
                <code class="mono">localhost:27017</code>
                . Requires a distro with a native MongoDB package (Debian/Ubuntu/RHEL family).
              </div>
              <div v-else>
                Spawns a
                <code class="mono">mongo:8</code>
                service alongside the controller. Data persists to the
                <code class="mono">mongo-data</code>
                volume.
              </div>
            </div>
          </Field>
          <Field
            label="Redis"
            key-path="redis.uri"
            :required="redisRequired && wiz.redisMode === 'remote'"
            help="Install locally to spawn a Redis 7 sidecar container in the compose project (URI auto-set to redis:6379). Use existing to point at your own Redis-compatible store. Required in production; in dev the controller falls back to in-process and logs a warning at startup."
          >
            <template #badges>
              <span class="badge badge-auto" v-if="wiz.redisMode === 'local'">auto-installed</span>
              <span class="badge badge-adv" v-else-if="!redisRequired">optional in dev</span>
            </template>
            <div class="segmented" role="tablist" aria-label="Redis source">
              <button
                type="button"
                role="tab"
                :aria-selected="wiz.redisMode === 'local'"
                :class="wiz.redisMode === 'local' ? 'on' : ''"
                @click="wiz.setRedisMode('local')"
              >
                {{ localLabel }}
              </button>
              <button
                type="button"
                role="tab"
                :aria-selected="wiz.redisMode === 'remote'"
                :class="wiz.redisMode === 'remote' ? 'on' : ''"
                @click="wiz.setRedisMode('remote')"
              >
                Use existing URI
              </button>
            </div>
            <TextInput
              v-if="wiz.redisMode === 'remote'"
              v-model="wiz.redisUri"
              placeholder="redis://localhost:6379"
              :invalid="redisRequired && !isMongoOrRedis(wiz.redisUri)"
            />
            <div v-else class="service-callout">
              <div v-if="isNative">
                Installs
                <code class="mono">redis</code>
                from your distro's package manager and enables it on
                <code class="mono">localhost:6379</code>
                .
              </div>
              <div v-else>
                Spawns a
                <code class="mono">redis:7-alpine</code>
                service alongside the controller. Data persists to the
                <code class="mono">redis-data</code>
                volume.
              </div>
            </div>
          </Field>
          <Field
            label="Initial admin password"
            key-path="security.initialAdminPassword"
            help="Set on first boot only. Leave blank to let the controller auto-generate one and write it to config/.initial-admin-password (deleted automatically once the admin changes it from the dashboard)."
          >
            <template v-if="wiz.initialAdminPassword" #badges>
              <span class="badge badge-auto">generated</span>
            </template>
            <div class="input-row">
              <TextInput
                v-model="wiz.initialAdminPassword"
                placeholder="leave blank to auto-generate"
              />
              <button class="inline-btn" type="button" @click="generatePassword">Generate</button>
            </div>
          </Field>
          <Field
            label="Dashboard CORS origins"
            key-path="http.cors.allowedOrigins"
            help="Origins (full URL with scheme) the dashboard browser tab will be served from. Locked down by DynamicCorsHandler at request time; can be edited live later via PATCH /api/v1/admin/cors/origins."
          >
            <ListInput v-model="wiz.corsOrigins" placeholder="http://localhost:3000" />
          </Field>
          <Field
            label="Allowed source subnets"
            key-path="network.allowedSubnets"
            help="Defense-in-depth: gRPC + REST traffic from IPs outside these CIDRs is rejected. Default 0.0.0.0/0 + ::/0 is wide open; tighten in production. Loopback is always allowed."
          >
            <ListInput v-model="wiz.allowedSubnets" placeholder="10.0.0.0/8" />
          </Field>
        </div>

        <!-- Daemon -->
        <div v-if="showDaemon" class="section-card">
          <div class="section-head">
            <span class="section-title">Daemon</span>
            <span class="section-grow"></span>
            <span class="section-sub">identity · controller link</span>
          </div>
          <Field
            label="Node ID"
            key-path="nodeId"
            required
            help="Stable identifier for this daemon's node. Must be unique within the cluster — appears in `prexorctl node list`, audit logs, and the dashboard."
          >
            <TextInput v-model="wiz.nodeId" placeholder="node-a" />
          </Field>
          <template v-if="wiz.mode === 'daemon'">
            <Field
              label="Controller host"
              key-path="controller.host"
              required
              help="Hostname or IP of the controller this daemon should connect to."
            >
              <TextInput v-model="wiz.controllerHost" placeholder="192.168.1.10" />
            </Field>
            <Field
              label="Controller gRPC port"
              key-path="controller.grpcPort"
              required
              help="Must match the controller side `grpc.port`. Default 9090."
            >
              <NumInput v-model="wiz.controllerGrpcPort" />
            </Field>
            <Field
              label="Join token"
              key-path="security.joinToken"
              required
              help="Single-use bootstrap token. Generate on the controller with `prexorctl token create --node <id>`. The daemon exchanges it for an mTLS certificate via /api/v1/bootstrap/exchange."
            >
              <div class="input-row">
                <TextInput v-model="wiz.joinToken" placeholder="pcjt_..." />
                <button class="inline-btn" type="button" @click="generateJoinToken">
                  Mock token
                </button>
              </div>
            </Field>
          </template>
          <Field
            label="Advertise address"
            key-path="advertiseAddress"
            help="IP the controller will use to reach back to this node for proxy traffic. Leave blank and the daemon detects from network interfaces."
          >
            <template #badges>
              <span class="badge badge-adv">auto-detect if blank</span>
            </template>
            <div class="input-row">
              <TextInput v-model="wiz.advertiseAddress" placeholder="auto" />
              <button class="inline-btn" type="button" @click="detectAdvertise">Detect</button>
            </div>
          </Field>
          <Field
            label="Max heap (MB)"
            key-path="resources.maxMemoryMb"
            help="0 = auto (80% of physical RAM). Cap explicitly when sharing the host with other JVM workloads."
          >
            <NumInput v-model="wiz.resourcesMaxMemoryMb" suffix="MB" />
          </Field>
        </div>

        <!-- Dashboard standalone -->
        <div v-if="showDash" class="section-card">
          <div class="section-head">
            <span class="section-title">Dashboard</span>
            <span class="section-grow"></span>
            <span class="section-sub">public URL · controller link · admin login</span>
          </div>
          <Field
            label="Public URL"
            key-path="dashboardPublicUrl"
            required
            help="Where operators will visit this dashboard. Registered automatically in the controller's CORS allow-list after install (live, no restart)."
          >
            <TextInput
              v-model="wiz.dashboardPublicUrl"
              placeholder="https://dash.example.com"
              :invalid="!!wiz.dashboardPublicUrl && !isHttpUrl(wiz.dashboardPublicUrl)"
            />
          </Field>
          <Field
            label="Controller base URL"
            key-path="dashboardControllerUrl"
            required
            help="REST API base of the controller. Example: https://controller.example.com:8080. Used by nginx to proxy /api/*."
          >
            <TextInput
              v-model="wiz.dashboardControllerUrl"
              placeholder="http://controller-host:8080"
              :invalid="!!wiz.dashboardControllerUrl && !isHttpUrl(wiz.dashboardControllerUrl)"
            />
          </Field>
          <Field
            label="Local host port"
            key-path="dashboardListenPort"
            :help="
              isNative
                ? 'Where the web server binds on the host (nginx). With Caddy on an https:// URL, ports 80+443 are used for ACME + HTTPS.'
                : 'Where the nginx container binds on the host. 80 / 443 / 3000 are typical.'
            "
          >
            <NumInput v-model="wiz.dashboardListenPort" />
          </Field>
          <Field
            v-if="isNative"
            label="Web server"
            key-path="webServer"
            help="Caddy auto-provisions a Let's Encrypt certificate when the public URL is https:// (no extra config). nginx serves plain HTTP and assumes TLS is terminated upstream."
          >
            <div class="segmented" role="tablist" aria-label="Web server">
              <button
                type="button"
                role="tab"
                :aria-selected="wiz.webServer === 'caddy'"
                :class="wiz.webServer === 'caddy' ? 'on' : ''"
                @click="wiz.webServer = 'caddy'"
              >
                Caddy (auto-HTTPS)
              </button>
              <button
                type="button"
                role="tab"
                :aria-selected="wiz.webServer === 'nginx'"
                :class="wiz.webServer === 'nginx' ? 'on' : ''"
                @click="wiz.webServer = 'nginx'"
              >
                nginx (plain HTTP)
              </button>
            </div>
          </Field>
          <Field
            label="Admin username"
            key-path="dashboardAdminUser"
            help="Used once to register CORS + save a local CLI context. Discarded after install."
          >
            <TextInput v-model="wiz.dashboardAdminUser" />
          </Field>
          <Field
            label="Admin password"
            key-path="dashboardAdminPassword"
            required
            help="The password set during controller install (saved in config/.initial-admin-password on the controller VPS)."
          >
            <TextInput v-model="wiz.dashboardAdminPassword" type="password" />
          </Field>
        </div>

        <!-- Production checklist -->
        <div v-if="wiz.profile === 'production'" class="prod-callout">
          <svg
            class="ico"
            width="18"
            height="18"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
          >
            <path
              d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"
            />
            <line x1="12" y1="9" x2="12" y2="13" />
            <line x1="12" y1="17" x2="12.01" y2="17" />
          </svg>
          <div class="text">
            <strong>Production checklist.</strong>
            Tighten
            <code class="mono">network.allowedSubnets</code>
            away from 0.0.0.0/0, rotate the auto-generated JWT secret, set
            <code class="mono">modules.signing.required: true</code>
            , and configure SMTP if you want password reset.
          </div>
        </div>
      </div>
      <NavBar back="mode" next="security" :disable-next="conflict" />
    </div>
  </div>
</template>
