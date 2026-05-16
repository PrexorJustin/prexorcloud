import { describe, it, expect, vi, beforeEach } from 'vitest'
import { reactive } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import type {
  SystemHealth,
  SystemVersion,
  SystemDiagnosticItem,
  RedisKeyspace,
  RedisSchemaEntry,
} from '~/stores/system'
import System from '../observability/system.vue'

const { systemStore } = vi.hoisted(() => ({
  systemStore: {
    health: null as SystemHealth | null,
    version: null as SystemVersion | null,
    diagnostics: [] as SystemDiagnosticItem[],
    keyspace: null as RedisKeyspace | null,
    redisSchema: [] as RedisSchemaEntry[],
    settings: {} as Record<string, unknown>,
    loading: false,
    fetchAll: vi.fn(),
  },
}))

mockNuxtImport('useSystemStore', () => () => reactive(systemStore))

async function flush() {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await new Promise(r => setTimeout(r))
  }
}

beforeEach(() => {
  systemStore.health = null
  systemStore.version = null
  systemStore.diagnostics = []
  systemStore.keyspace = null
  systemStore.redisSchema = []
  systemStore.settings = {}
  systemStore.loading = false
  systemStore.fetchAll.mockReset().mockResolvedValue(undefined)
})

describe('observability/system', () => {
  it('renders the header and fetches all system data on mount', async () => {
    const wrapper = await mountSuspended(System)
    expect(wrapper.text()).toContain('System status')
    expect(wrapper.text()).toContain('Cluster health, controller version')
    expect(systemStore.fetchAll).toHaveBeenCalledTimes(1)
  })

  it('shows the unknown status badge when no health is loaded', async () => {
    const wrapper = await mountSuspended(System)
    expect(wrapper.text()).toContain('Unknown')
  })

  it('renders the no-components message when health has no components', async () => {
    systemStore.health = { status: 'UP', components: [] }
    const wrapper = await mountSuspended(System)
    expect(wrapper.text()).toContain('UP')
    expect(wrapper.text()).toContain('No component health reported.')
  })

  it('renders a row per health component', async () => {
    systemStore.health = {
      status: 'DEGRADED',
      components: [
        { id: 'redis', status: 'UP', message: 'ok' },
        { id: 'mongo', status: 'DOWN', message: 'connection refused' },
      ],
    }
    const wrapper = await mountSuspended(System)
    expect(wrapper.text()).toContain('redis')
    expect(wrapper.text()).toContain('mongo')
    expect(wrapper.text()).toContain('connection refused')
  })

  it('renders the diagnostics section only when there are diagnostics', async () => {
    const empty = await mountSuspended(System)
    expect(empty.text()).not.toContain('Diagnostics')

    systemStore.diagnostics = [
      { id: 'd1', severity: 'warning', message: 'Disk almost full', fix: 'Free up space' },
    ]
    const wrapper = await mountSuspended(System)
    expect(wrapper.text()).toContain('Diagnostics')
    expect(wrapper.text()).toContain('Disk almost full')
    expect(wrapper.text()).toContain('Free up space')
  })

  it('renders the controller version details', async () => {
    systemStore.version = {
      version: '1.4.2',
      commit: 'abcdef1234567890',
      builtAt: '2026-01-01T00:00:00Z',
      javaVersion: '25',
    }
    const wrapper = await mountSuspended(System)
    expect(wrapper.text()).toContain('1.4.2')
    expect(wrapper.text()).toContain('abcdef123456')
    expect(wrapper.text()).toContain('25')
  })

  it('renders the redis keyspace and prefix breakdown', async () => {
    systemStore.keyspace = { keys: 1234, expires: 100, avgTtl: 60 }
    systemStore.redisSchema = [{ prefix: 'instance:', count: 42 }]
    const wrapper = await mountSuspended(System)
    expect(wrapper.text()).toContain('1,234')
    expect(wrapper.text()).toContain('instance:')
    expect(wrapper.text()).toContain('42')
  })

  it('serialises the settings as JSON in the settings section', async () => {
    systemStore.settings = { scheduler: { enabled: true } }
    const wrapper = await mountSuspended(System)
    expect(wrapper.text()).toContain('Settings')
    expect(wrapper.text()).toContain('scheduler')
    expect(wrapper.text()).toContain('enabled')
  })
})
