import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

import { useModuleGuard } from '../useModuleGuard'
import { useModuleStore } from '~/stores/modules'

beforeEach(() => {
  setActivePinia(createPinia())
})

describe('useModuleGuard', () => {
  it('reports not installed and not enabled when both lists are empty', () => {
    const { installed, enabled } = useModuleGuard('player-journey')
    expect(installed.value).toBe(false)
    expect(enabled.value).toBe(false)
  })

  it('detects an installed enabled regular module', () => {
    const store = useModuleStore()
    // @ts-expect-error — seed Pinia state directly
    store.modules = [{ name: 'player-journey', enabled: true }]
    const { installed, enabled } = useModuleGuard('player-journey')
    expect(installed.value).toBe(true)
    expect(enabled.value).toBe(true)
  })

  it('installed=true, enabled=false when a regular module is present but disabled', () => {
    const store = useModuleStore()
    // @ts-expect-error
    store.modules = [{ name: 'player-journey', enabled: false }]
    const { installed, enabled } = useModuleGuard('player-journey')
    expect(installed.value).toBe(true)
    expect(enabled.value).toBe(false)
  })

  it('detects an active platform module by moduleId', () => {
    const store = useModuleStore()
    // @ts-expect-error
    store.platformModules = [{ moduleId: 'core-platform', state: 'ACTIVE' }]
    const { installed, enabled } = useModuleGuard('core-platform')
    expect(installed.value).toBe(true)
    expect(enabled.value).toBe(true)
  })

  it('platform module in a non-ACTIVE state counts as installed only', () => {
    const store = useModuleStore()
    // @ts-expect-error
    store.platformModules = [{ moduleId: 'core-platform', state: 'PENDING' }]
    const { installed, enabled } = useModuleGuard('core-platform')
    expect(installed.value).toBe(true)
    expect(enabled.value).toBe(false)
  })

  it('reactively flips when the underlying store changes', () => {
    const store = useModuleStore()
    const { installed, enabled } = useModuleGuard('xyz')
    expect(installed.value).toBe(false)
    // @ts-expect-error
    store.modules = [{ name: 'xyz', enabled: true }]
    expect(installed.value).toBe(true)
    expect(enabled.value).toBe(true)
  })

  it('regular and platform lookups are independent: only one needs to match', () => {
    const store = useModuleStore()
    // @ts-expect-error
    store.modules = [{ name: 'other', enabled: true }]
    // @ts-expect-error
    store.platformModules = [{ moduleId: 'targeted', state: 'ACTIVE' }]
    const guard = useModuleGuard('targeted')
    expect(guard.installed.value).toBe(true)
    expect(guard.enabled.value).toBe(true)
  })
})
