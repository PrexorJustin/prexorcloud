import { describe, it, expect } from 'vitest'
import { navigation } from '../navigation'
import { ALL_PERMISSIONS } from '../constants'

describe('lib/navigation', () => {
  it('exposes the eight sidebar groups in IA order', () => {
    expect(navigation.map((g) => g.label)).toEqual([
      'Overview', 'Workloads', 'Cluster', 'Configuration', 'Observability',
      'Identity', 'Operations', 'Settings',
    ])
  })

  it('group ids are unique', () => {
    const ids = navigation.map((g) => g.id)
    expect(new Set(ids).size).toBe(ids.length)
  })

  it('sortOrder is monotonically increasing across the group list', () => {
    for (let i = 1; i < navigation.length; i++) {
      expect(navigation[i]!.sortOrder).toBeGreaterThan(navigation[i - 1]!.sortOrder)
    }
  })

  it('every nav item has a title, URL, and icon', () => {
    for (const group of navigation) {
      for (const item of group.items) {
        expect(item.title.length, item.title).toBeGreaterThan(0)
        expect(item.url, item.title).toMatch(/^\//)
        expect(item.icon, item.title).toBeDefined()
      }
    }
  })

  it('item URLs are globally unique', () => {
    const urls = navigation.flatMap((g) => g.items.map((i) => i.url))
    expect(new Set(urls).size).toBe(urls.length)
  })

  it('every permission attribute matches the `<group>.<verb>` convention', () => {
    // Not every nav permission lives in PERMISSION_GROUPS — some feature
    // gates (backups, credentials, maintenance) are UI-only. Just enforce
    // the shape here; the `networks` group below is the one reconciled gate.
    for (const group of navigation) {
      for (const item of group.items) {
        if (item.permission) {
          expect(item.permission, `${item.title}: ${item.permission}`).toMatch(/^[a-z]+\.[a-z-]+$/)
        }
      }
    }
  })

  it('the Networks nav gate resolves to a real permission in PERMISSION_GROUPS', () => {
    const networksItem = navigation
      .flatMap((g) => g.items)
      .find((i) => i.permission === 'networks.view')
    expect(networksItem, 'expected a nav item gated on networks.view').toBeDefined()
    expect(ALL_PERMISSIONS).toContain('networks.view')
  })

  it('Overview group is permission-free (always visible)', () => {
    const overview = navigation.find((g) => g.label === 'Overview')!
    for (const item of overview.items) {
      expect(item.permission).toBeUndefined()
    }
  })

  it('Identity group items all carry a permission gate', () => {
    const identity = navigation.find((g) => g.label === 'Identity')!
    for (const item of identity.items) {
      expect(item.permission, item.title).toBeDefined()
    }
  })
})
