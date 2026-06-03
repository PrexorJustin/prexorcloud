<script setup lang="ts">
/*
 * Cluster overview — Reef direction.
 *
 * Wires three existing stores (no new endpoints): overview (stats +
 * pre-seeded history), instances (real instance list), activity (recent
 * cluster events). The 4 stat cards and the players sparkline read from
 * overview.stats/history; the instance preview reads from useInstancesStore;
 * the events list reads from useActivityStore.
 */
import { ArrowRight, Box, Plus } from 'lucide-vue-next'
import type { ActivityEvent } from '~/stores/activity'

// The instances store types its rows via the SDK's generated `Schema` type,
// which currently has `InstanceDto` outside its key union → the store rows
// surface as `unknown`. Narrow with the minimal shape the overview needs.
interface InstanceRow {
  id: string
  group: string
  node: string
  state: 'SCHEDULED' | 'STARTING' | 'RUNNING' | 'STOPPING' | 'STOPPED' | 'CRASHED' | 'DRAINING'
  playerCount: number
  uptimeMs: number
}

const overview = useOverviewStore()
const instancesStore = useInstancesStore()
const activity = useActivityStore()
const { t } = useI18n()

onMounted(() => {
  overview.fetchOverview()
  overview.connectSse()
  instancesStore.fetchInstances()
  activity.fetchEvents()
})

onUnmounted(() => {
  overview.disconnectSse()
})

interface StatCard {
  key: 'nodeCount' | 'playerCount' | 'instanceCount' | 'groupCount'
  label: string
  sub: string
  link?: string
}

const stats = computed<StatCard[]>(() => [
  { key: 'instanceCount', label: t('pages.overview.stats.instances'), sub: t('pages.overview.stats.instancesSubtitle'), link: '/instances' },
  { key: 'playerCount',   label: t('pages.overview.stats.players'),   sub: t('pages.overview.stats.playersSubtitle') },
  { key: 'nodeCount',     label: t('pages.overview.stats.nodes'),     sub: t('pages.overview.stats.nodesSubtitle'),    link: '/nodes' },
  { key: 'groupCount',    label: t('pages.overview.stats.groups'),    sub: t('pages.overview.stats.groupsSubtitle'),   link: '/groups' },
])

function valueFor(key: StatCard['key']): number {
  return overview.stats?.[key] ?? 0
}

// ─── Instance list (top 7) ────────────────────────────────────────────────
type StatePillKey = 'running' | 'starting' | 'draining' | 'crashed' | 'stopped'
function statePill(state: string | undefined): { key: StatePillKey; label: string } {
  switch (state) {
    case 'RUNNING':   return { key: 'running',  label: t('pages.overview.state.running') }
    case 'SCHEDULED':
    case 'STARTING':  return { key: 'starting', label: t('pages.overview.state.starting') }
    case 'STOPPING':  return { key: 'draining', label: t('pages.overview.state.stopping') }
    case 'DRAINING':  return { key: 'draining', label: t('pages.overview.state.draining') }
    case 'CRASHED':   return { key: 'crashed',  label: t('pages.overview.state.crashed') }
    case 'STOPPED':   return { key: 'stopped',  label: t('pages.overview.state.stopped') }
    default:          return { key: 'stopped',  label: state ?? t('pages.overview.state.unknown') }
  }
}

function formatUptime(ms: number): string {
  if (!ms || ms < 0) return '—'
  const s = Math.floor(ms / 1000)
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m}m`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h}h`
  return `${Math.floor(h / 24)}d`
}

const allInstances = computed(() => instancesStore.instances as unknown as InstanceRow[])
const previewInstances = computed(() => allInstances.value.slice(0, 7))
const instanceCountByState = computed(() => {
  const counts: Record<string, number> = { RUNNING: 0, CRASHED: 0, STOPPED: 0 }
  for (const inst of allInstances.value) {
    const state = inst.state ?? 'UNKNOWN'
    counts[state] = (counts[state] ?? 0) + 1
  }
  return counts
})
const totalInstances = computed(() => allInstances.value.length)
const activeFilter = ref<'all' | 'running' | 'crashed' | 'stopped'>('all')

// ─── Recent events ────────────────────────────────────────────────────────
type EventTone = 'success' | 'warning' | 'destructive' | 'accent' | 'muted'
function toneForEvent(e: ActivityEvent): EventTone {
  const type = e.type.toUpperCase()
  if (type.includes('CRASH') || type.includes('FAIL') || type.includes('UNREACHABLE')) return 'destructive'
  if (type.includes('DRAIN') || type.includes('CORDON') || type.includes('WARN')) return 'warning'
  if (type.includes('STARTED') || type.includes('CONNECTED') || type.includes('HEALTHY') || type.includes('CREATED')) return 'success'
  if (type.includes('SCHEDULED') || type.includes('STARTING')) return 'accent'
  return 'muted'
}

function relativeTime(iso: string): string {
  const parsed = Date.parse(iso)
  if (Number.isNaN(parsed)) return iso
  const diff = Math.max(0, Date.now() - parsed)
  const s = Math.floor(diff / 1000)
  if (s < 60) return t('pages.overview.ago', { value: `${s}s` })
  const m = Math.floor(s / 60)
  if (m < 60) return t('pages.overview.ago', { value: `${m}m` })
  const h = Math.floor(m / 60)
  if (h < 24) return t('pages.overview.ago', { value: `${h}h` })
  return t('pages.overview.ago', { value: `${Math.floor(h / 24)}d` })
}

const recentEvents = computed(() => activity.events.slice(0, 6))

// ─── Sparkline path (Players · last 24h) ──────────────────────────────────
// Builds an SVG path from the existing overview.history.playerCount ring.
// 60 buckets × 15s ≈ 15 min for live data; the stat row uses the same
// series via the existing Sparkline component on smaller cards.
const playersPath = computed(() => {
  const points = overview.history.playerCount
  if (!points.length) return { line: '', area: '' }
  const w = 600
  const h = 110
  const min = Math.min(...points, 0)
  const max = Math.max(...points, min + 1)
  const span = max - min || 1
  const stepX = w / Math.max(points.length - 1, 1)
  const coords = points.map((v, i) => {
    const x = i * stepX
    const y = h - ((v - min) / span) * (h - 8) - 4
    return [x, y] as const
  })
  const line = coords.map(([x, y], i) => `${i === 0 ? 'M' : 'L'}${x.toFixed(1)} ${y.toFixed(1)}`).join(' ')
  const area = `${line} L${w} ${h} L0 ${h} Z`
  return { line, area }
})
</script>

<template>
  <div class="reef-overview">
    <!-- ── Page header ── -->
    <header class="reef-header">
      <div class="breadcrumb">
        {{ t('pages.overview.breadcrumb.cluster') }} <span class="breadcrumb__sep">/</span> <span class="breadcrumb__cur">{{ t('pages.overview.breadcrumb.overview') }}</span>
      </div>
      <div class="reef-header__row">
        <h1 class="reef-h1">
          {{ t('pages.overview.heading.lead') }} <em class="accent-serif">{{ t('pages.overview.heading.accent') }}</em>
        </h1>
        <div class="reef-header__actions">
          <button type="button" class="reef-btn reef-btn--ghost">{{ t('pages.overview.filter') }}</button>
          <NuxtLink to="/instances/new" class="reef-btn reef-btn--mono">
            <Plus class="size-3.5" />
            {{ t('pages.overview.newInstance') }}
          </NuxtLink>
        </div>
      </div>
    </header>

    <!-- ── Stat row ── -->
    <div class="reef-stats">
      <component
        :is="stat.link ? resolveComponent('NuxtLink') : 'div'"
        v-for="stat in stats"
        :key="stat.key"
        :to="stat.link || undefined"
        class="reef-stat"
        :class="stat.link ? 'reef-stat--link' : ''"
      >
        <div class="reef-stat__label">{{ stat.label }}</div>
        <div class="reef-stat__value">{{ valueFor(stat.key) }}</div>
        <div class="reef-stat__sub">{{ stat.sub }}</div>
      </component>
    </div>

    <!-- ── Instance list ── -->
    <section class="reef-card reef-instances">
      <div class="reef-instances__head">
        <div class="reef-instances__title">
          {{ t('pages.overview.instances.title') }} <em class="accent-serif" style="color: var(--muted-foreground); font-size: 0.9em">{{ t('pages.overview.instances.runningNow') }}</em>
        </div>
        <div class="reef-pills">
          <button
            class="reef-pill"
            :data-active="activeFilter === 'all'"
            @click="activeFilter = 'all'"
          >{{ t('pages.overview.filters.all') }} {{ totalInstances }}</button>
          <button
            class="reef-pill"
            :data-active="activeFilter === 'running'"
            @click="activeFilter = 'running'"
          >{{ t('pages.overview.filters.running') }} {{ instanceCountByState.RUNNING ?? 0 }}</button>
          <button
            class="reef-pill"
            :data-active="activeFilter === 'crashed'"
            @click="activeFilter = 'crashed'"
          >{{ t('pages.overview.filters.crashed') }} {{ instanceCountByState.CRASHED ?? 0 }}</button>
          <button
            class="reef-pill"
            :data-active="activeFilter === 'stopped'"
            @click="activeFilter = 'stopped'"
          >{{ t('pages.overview.filters.stopped') }} {{ instanceCountByState.STOPPED ?? 0 }}</button>
        </div>
      </div>
      <div class="reef-instances__columns">
        <div>{{ t('pages.overview.columns.instance') }}</div><div>{{ t('pages.overview.columns.status') }}</div><div>{{ t('pages.overview.columns.node') }}</div><div>{{ t('pages.overview.columns.group') }}</div><div>{{ t('pages.overview.columns.players') }}</div><div>{{ t('pages.overview.columns.uptime') }}</div><div></div>
      </div>
      <div v-if="previewInstances.length === 0" class="reef-empty">
        {{ t('pages.overview.instances.empty') }}
      </div>
      <NuxtLink
        v-for="(inst, i) in previewInstances"
        :key="inst.id"
        :to="`/instances/${inst.id}`"
        class="reef-instances__row"
        :class="i === 0 ? 'reef-instances__row--first' : ''"
      >
        <div class="reef-instances__name">
          <Box class="size-3.5" :stroke-width="1.75" />
          <span class="mono">{{ inst.id.slice(0, 12) }}</span>
        </div>
        <div class="status-pill" :data-state="statePill(inst.state).key">
          <span class="dot" />
          <span>{{ statePill(inst.state).label }}</span>
        </div>
        <div class="mono reef-instances__sub">{{ inst.node || '—' }}</div>
        <div class="reef-instances__sub">{{ inst.group }}</div>
        <div class="mono">{{ inst.playerCount }}<span class="reef-instances__faint">p</span></div>
        <div class="mono reef-instances__sub">{{ formatUptime(inst.uptimeMs) }}</div>
        <ArrowRight class="size-3.5 reef-instances__chev" />
      </NuxtLink>
    </section>

    <!-- ── Second row: sparkline + events ── -->
    <div class="reef-second-row">
      <section class="reef-card reef-spark">
        <div class="reef-spark__head">
          <div class="reef-card__title">{{ t('pages.overview.spark.title') }}</div>
          <div class="reef-pills reef-pills--sm">
            <span class="reef-pill" data-active="true">24h</span>
            <span class="reef-pill">7d</span>
            <span class="reef-pill">30d</span>
          </div>
        </div>
        <svg
          viewBox="0 0 600 110"
          preserveAspectRatio="none"
          class="reef-spark__svg"
        >
          <defs>
            <linearGradient id="reef-spark-grad" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%"   stop-color="var(--brand)" stop-opacity="0.25" />
              <stop offset="100%" stop-color="var(--brand)" stop-opacity="0"    />
            </linearGradient>
          </defs>
          <line v-for="y in [0, 27, 55, 82]" :key="y" :x1="0" :y1="y" :x2="600" :y2="y" stroke="var(--border)" stroke-width="1" />
          <path :d="playersPath.area" fill="url(#reef-spark-grad)" />
          <path :d="playersPath.line" fill="none" stroke="var(--brand)" stroke-width="1.5" />
        </svg>
        <div class="reef-spark__ticks mono">
          <span>00:00</span><span>06:00</span><span>12:00</span><span>18:00</span><span>{{ t('pages.overview.spark.now') }}</span>
        </div>
      </section>

      <section class="reef-card reef-events">
        <div class="reef-events__head">
          <div class="reef-card__title">{{ t('pages.overview.events.title') }}</div>
          <span class="reef-events__win">{{ t('pages.overview.events.window') }}</span>
        </div>
        <div v-if="recentEvents.length === 0" class="reef-empty reef-empty--inline">
          {{ t('pages.overview.events.empty') }}
        </div>
        <div v-else class="reef-events__list">
          <div v-for="e in recentEvents" :key="e.id" class="reef-events__row">
            <span
              class="reef-events__dot"
              :style="{ background:
                toneForEvent(e) === 'success'     ? 'var(--success)' :
                toneForEvent(e) === 'warning'     ? 'var(--warning)' :
                toneForEvent(e) === 'destructive' ? 'var(--destructive)' :
                toneForEvent(e) === 'accent'      ? 'var(--brand)' :
                'var(--muted-foreground)' }"
            />
            <div class="reef-events__body">
              <div class="reef-events__time mono">{{ relativeTime(e.timestamp) }}</div>
              <div class="reef-events__msg">{{ e.message }}</div>
            </div>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.reef-overview {
  display: flex;
  flex-direction: column;
  gap: 28px;
  padding: 8px 4px 24px;
}

/* ── Page header ── */
.reef-header { display: flex; flex-direction: column; gap: 8px; }
.breadcrumb { font-size: 12px; color: var(--muted-foreground); }
.breadcrumb__sep { color: var(--faint); margin: 0 4px; }
.breadcrumb__cur { color: var(--foreground); }
.reef-header__row { display: flex; align-items: baseline; justify-content: space-between; gap: 16px; }
.reef-h1 {
  font-family: var(--font-display);
  font-weight: 600; font-size: var(--text-3xl);
  letter-spacing: var(--tracking-tight);
  line-height: 1.1;
  margin: 0;
}
.reef-header__actions { display: flex; gap: 8px; }

.reef-btn {
  display: inline-flex; align-items: center; gap: 7px;
  padding: 9px 14px;
  border-radius: var(--r-md);
  font-family: inherit; font-size: 13px; font-weight: 500;
  cursor: pointer; text-decoration: none;
}
.reef-btn--ghost { background: transparent; color: var(--foreground); border: 1px solid var(--border); }
.reef-btn--ghost:hover { border-color: var(--border-hover); background: var(--glass-hover); }
.reef-btn--mono { background: var(--foreground); color: var(--background); border: 0; }
.reef-btn--mono:hover { filter: brightness(0.95); }

/* ── Stat row ── */
.reef-stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}
.reef-stat {
  padding: 18px 22px;
  border-radius: var(--r-lg);
  background: var(--surface);
  border: 1px solid var(--border);
  text-decoration: none; color: inherit;
  display: flex; flex-direction: column;
  transition: border-color 120ms ease;
}
.reef-stat--link:hover { border-color: var(--border-hover); }
.reef-stat__label { font-size: 12.5px; color: var(--muted-foreground); }
.reef-stat__value {
  font-family: var(--font-display);
  font-size: 32px; font-weight: 600;
  letter-spacing: var(--tracking-tight);
  margin-top: 6px; line-height: 1;
  font-variant-numeric: tabular-nums;
}
.reef-stat__sub { font-size: 11.5px; color: var(--faint); margin-top: 6px; }

/* ── Cards ── */
.reef-card {
  border-radius: var(--r-xl);
  background: var(--surface);
  border: 1px solid var(--border);
  overflow: hidden;
}
.reef-card__title {
  font-family: var(--font-display);
  font-size: 15px; font-weight: 600;
  letter-spacing: var(--tracking-tight);
}

/* ── Instance list ── */
.reef-instances__head {
  padding: 16px 24px;
  display: flex; align-items: center; justify-content: space-between;
  border-bottom: 1px solid var(--border);
  gap: 16px;
}
.reef-instances__title { font-family: var(--font-display); font-size: 15px; font-weight: 600; letter-spacing: var(--tracking-tight); }
.reef-pills { display: flex; gap: 4px; font-size: 12.5px; color: var(--muted-foreground); }
.reef-pills--sm { font-size: 11.5px; }
.reef-pill {
  padding: 5px 12px;
  border-radius: var(--r-full);
  background: transparent;
  border: 0; color: inherit; cursor: pointer; font-family: inherit;
}
.reef-pill[data-active="true"] { background: var(--bg-alt); color: var(--foreground); }

.reef-instances__columns {
  display: grid;
  grid-template-columns: 1.5fr 1.1fr 1fr 1fr 1fr 1fr auto;
  padding: 10px 24px;
  font-size: 11px;
  letter-spacing: var(--tracking-widest);
  color: var(--faint);
  text-transform: uppercase;
  font-weight: 600;
  border-bottom: 1px solid var(--border);
}
.reef-instances__row {
  display: grid;
  grid-template-columns: 1.5fr 1.1fr 1fr 1fr 1fr 1fr auto;
  align-items: center;
  padding: 14px 24px;
  font-size: 13.5px;
  text-decoration: none;
  color: var(--foreground);
  border-top: 1px solid var(--border);
  transition: background 120ms ease;
}
.reef-instances__row--first { border-top: 0; }
.reef-instances__row:hover { background: var(--bg-alt); }
.reef-instances__name { display: flex; align-items: center; gap: 12px; }
.reef-instances__name .mono { font-family: var(--font-mono); font-size: 13px; }
.reef-instances__sub { color: var(--muted-foreground); font-size: 12.5px; }
.reef-instances__sub.mono { font-family: var(--font-mono); }
.reef-instances__faint { color: var(--faint); }
.reef-instances__chev { color: var(--muted-foreground); }

.reef-empty {
  padding: 32px 24px;
  font-size: 13px; color: var(--muted-foreground);
  text-align: center;
}
.reef-empty--inline { padding: 16px 0; text-align: left; }

/* ── Second row ── */
.reef-second-row {
  display: grid;
  grid-template-columns: 1.4fr 1fr;
  gap: 16px;
}
.reef-spark { padding: 20px 24px; overflow: visible; }
.reef-spark__head { display: flex; align-items: baseline; justify-content: space-between; }
.reef-spark__svg { display: block; width: 100%; height: 140px; margin-top: 18px; }
.reef-spark__ticks {
  display: flex; justify-content: space-between;
  margin-top: 10px;
  font-size: 11px; color: var(--faint);
}
.reef-spark__ticks.mono, .mono { font-family: var(--font-mono); }

.reef-events { padding: 18px 22px; }
.reef-events__head { display: flex; align-items: center; justify-content: space-between; }
.reef-events__win { font-size: 11px; color: var(--muted-foreground); letter-spacing: var(--tracking-wide); }
.reef-events__list { margin-top: 16px; display: flex; flex-direction: column; gap: 13px; }
.reef-events__row { display: flex; gap: 12px; font-size: 12.5px; line-height: 1.5; }
.reef-events__dot { width: 6px; height: 6px; border-radius: 999px; flex-shrink: 0; margin-top: 6px; }
.reef-events__body { flex: 1; min-width: 0; }
.reef-events__time { font-size: 11px; color: var(--faint); font-family: var(--font-mono); }
.reef-events__msg { color: var(--text-soft); margin-top: 1px; }

.status-pill { display: inline-flex; align-items: center; gap: 8px; font-size: 13.5px; }
.status-pill > .dot { width: 7px; height: 7px; border-radius: 999px; background: var(--muted-foreground); flex-shrink: 0; }
.status-pill[data-state="running"]  > .dot { background: var(--success); }
.status-pill[data-state="starting"] > .dot { background: var(--brand); }
.status-pill[data-state="draining"] > .dot { background: var(--warning); }
.status-pill[data-state="crashed"]  > .dot { background: var(--destructive); }
.status-pill[data-state="stopped"]  > .dot { background: var(--muted-foreground); }

@media (max-width: 980px) {
  .reef-stats { grid-template-columns: repeat(2, 1fr); }
  .reef-second-row { grid-template-columns: 1fr; }
  .reef-instances__columns,
  .reef-instances__row { grid-template-columns: 1.5fr 1.1fr 1fr auto; }
  .reef-instances__columns > div:nth-child(4),
  .reef-instances__columns > div:nth-child(5),
  .reef-instances__row > :nth-child(4),
  .reef-instances__row > :nth-child(5) { display: none; }
}
</style>
