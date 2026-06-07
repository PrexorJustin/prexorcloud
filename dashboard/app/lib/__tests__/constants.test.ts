import { describe, it, expect } from 'vitest'
import {
  ROLES,
  NODE_STATES,
  INSTANCE_STATES,
  CRASH_CLASSIFICATIONS,
  PERMISSION_GROUPS,
  ALL_PERMISSIONS,
  NODE_STATUS_CONFIG,
  INSTANCE_STATE_CONFIG,
  SCALING_MODE_CONFIG,
  DEPLOY_STATE_CONFIG,
  EVENT_TYPES,
} from '../constants'

describe('lib/constants', () => {
  it('ROLES exposes the three built-in role identifiers', () => {
    expect(ROLES.ADMIN).toBe('ADMIN')
    expect(ROLES.OPERATOR).toBe('OPERATOR')
    expect(ROLES.VIEWER).toBe('VIEWER')
  })

  it('NODE_STATES covers the four lifecycle states', () => {
    expect(Object.keys(NODE_STATES).sort()).toEqual(['CORDONED', 'DRAINING', 'ONLINE', 'UNREACHABLE'])
  })

  it('INSTANCE_STATES include the canonical six states', () => {
    expect(Object.keys(INSTANCE_STATES).sort()).toEqual(
      ['CRASHED', 'RUNNING', 'SCHEDULED', 'STARTING', 'STOPPED', 'STOPPING'],
    )
  })

  it('CRASH_CLASSIFICATIONS covers OOM/ERROR/SIGKILL/SIGTERM', () => {
    expect(Object.keys(CRASH_CLASSIFICATIONS).sort()).toEqual(['ERROR', 'OOM', 'SIGKILL', 'SIGTERM'])
  })

  it('ALL_PERMISSIONS is the flattened union of every permission group', () => {
    const expected = Object.values(PERMISSION_GROUPS).flatMap((g) => g.permissions)
    expect(ALL_PERMISSIONS).toEqual(expected)
    expect(ALL_PERMISSIONS.length).toBeGreaterThan(0)
  })

  it('every permission string uses the `<group>.<verb>` convention', () => {
    for (const p of ALL_PERMISSIONS) {
      expect(p, p).toMatch(/^[a-z]+\.[a-z]+$/)
    }
  })

  it('PERMISSION_GROUPS keys are unique vs. their permission group prefix', () => {
    for (const [groupKey, group] of Object.entries(PERMISSION_GROUPS)) {
      for (const perm of group.permissions) {
        expect(perm.startsWith(`${groupKey}.`), `${perm} should start with ${groupKey}.`).toBe(true)
      }
    }
  })

  it('NODE_STATUS_CONFIG has an entry for every NODE_STATES value (plus OFFLINE/PENDING)', () => {
    for (const k of Object.keys(NODE_STATES)) {
      expect(NODE_STATUS_CONFIG[k]).toBeDefined()
    }
    expect(NODE_STATUS_CONFIG.OFFLINE).toBeDefined()
    expect(NODE_STATUS_CONFIG.PENDING).toBeDefined()
  })

  it('INSTANCE_STATE_CONFIG covers every INSTANCE_STATES value plus DRAINING', () => {
    for (const k of Object.keys(INSTANCE_STATES)) {
      expect(INSTANCE_STATE_CONFIG[k]).toBeDefined()
    }
    expect(INSTANCE_STATE_CONFIG.DRAINING).toBeDefined()
  })

  it('NODE_STATUS_CONFIG entries each carry label/color/dot/bg', () => {
    for (const cfg of Object.values(NODE_STATUS_CONFIG)) {
      expect(typeof cfg.label).toBe('string')
      expect(typeof cfg.color).toBe('string')
      expect(typeof cfg.dot).toBe('string')
      expect(typeof cfg.bg).toBe('string')
    }
  })

  it('SCALING_MODE_CONFIG covers DYNAMIC / STATIC / MANUAL', () => {
    expect(Object.keys(SCALING_MODE_CONFIG).sort()).toEqual(['DYNAMIC', 'MANUAL', 'STATIC'])
  })

  it('DEPLOY_STATE_CONFIG covers every deployment lifecycle state', () => {
    expect(Object.keys(DEPLOY_STATE_CONFIG).sort()).toEqual(
      ['COMPLETED', 'FAILED', 'IN_PROGRESS', 'PAUSED', 'PENDING', 'ROLLED_BACK'],
    )
  })

  it('EVENT_TYPES use SCREAMING_SNAKE_CASE values matching their keys', () => {
    for (const [k, v] of Object.entries(EVENT_TYPES)) {
      expect(k, k).toMatch(/^[A-Z_]+$/)
      expect(v).toBe(k)
    }
  })
})
