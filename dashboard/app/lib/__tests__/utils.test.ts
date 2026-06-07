import { describe, it, expect, vi, afterEach } from 'vitest'
import { ref } from 'vue'
import { cn, valueUpdater, getInitials, timeAgo, formatUptime, timeUntil, formatBytes, formatMemory } from '../utils'

afterEach(() => {
  vi.useRealTimers()
})

describe('cn', () => {
  it('merges classnames and resolves Tailwind conflicts', () => {
    expect(cn('p-2', 'p-4')).toBe('p-4')
    expect(cn('text-sm', false && 'hidden', 'font-bold')).toBe('text-sm font-bold')
  })
})

describe('valueUpdater', () => {
  it('writes a non-function value through', () => {
    const r = ref(0)
    valueUpdater(5 as never, r)
    expect(r.value).toBe(5)
  })

  it('passes the current ref value to a function updater', () => {
    const r = ref(10)
    valueUpdater(((v: number) => v + 1) as never, r)
    expect(r.value).toBe(11)
  })
})

describe('getInitials', () => {
  it('returns the first two initials in uppercase', () => {
    expect(getInitials('alice bob')).toBe('AB')
    expect(getInitials('charlie')).toBe('C')
  })

  it('caps at two characters even for many-word names', () => {
    expect(getInitials('alice bob charlie dave')).toBe('AB')
  })
})

describe('timeAgo', () => {
  it('returns the em-dash on null/undefined', () => {
    expect(timeAgo(null)).toBe('—')
    expect(timeAgo(undefined)).toBe('—')
  })

  it('reports "just now" within the last minute', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-05-14T12:00:00Z'))
    expect(timeAgo('2026-05-14T11:59:30Z')).toBe('just now')
  })

  it('formats minutes, hours w/ minutes, and days', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-05-14T12:00:00Z'))
    expect(timeAgo('2026-05-14T11:55:00Z')).toBe('5m ago')
    expect(timeAgo('2026-05-14T09:30:00Z')).toBe('2h 30m ago')
    expect(timeAgo('2026-05-14T08:00:00Z')).toBe('4h ago')
    expect(timeAgo('2026-05-12T12:00:00Z')).toBe('2d ago')
  })

  it('appends Z when neither Z nor +HH:MM is present', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-05-14T12:00:00Z'))
    expect(timeAgo('2026-05-14T11:55:00')).toBe('5m ago')
  })
})

describe('formatUptime', () => {
  it('falls through to seconds for sub-minute durations', () => {
    expect(formatUptime(45 * 1000)).toBe('45s')
  })

  it('uses minutes once over a minute', () => {
    expect(formatUptime(120 * 1000)).toBe('2m')
  })

  it('mixes hours + minutes', () => {
    expect(formatUptime((2 * 60 + 30) * 60 * 1000)).toBe('2h 30m')
  })

  it('mixes days + hours', () => {
    expect(formatUptime((3 * 24 + 5) * 60 * 60 * 1000)).toBe('3d 5h')
  })
})

describe('timeUntil', () => {
  it('returns Expired when the date is in the past', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-05-14T12:00:00Z'))
    expect(timeUntil('2026-05-14T11:00:00Z')).toBe('Expired')
  })

  it('reports minutes / hours / days remaining', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-05-14T12:00:00Z'))
    expect(timeUntil('2026-05-14T12:30:00Z')).toBe('30m left')
    expect(timeUntil('2026-05-14T15:00:00Z')).toBe('3h left')
    expect(timeUntil('2026-05-16T12:00:00Z')).toBe('2d left')
  })
})

describe('formatBytes', () => {
  it('formats 0 specially', () => {
    expect(formatBytes(0)).toBe('0 B')
  })

  it('picks the right SI suffix', () => {
    expect(formatBytes(512)).toBe('512 B')
    expect(formatBytes(2048)).toBe('2 KB')
    expect(formatBytes(5 * 1024 * 1024)).toBe('5 MB')
    expect(formatBytes(3 * 1024 ** 3)).toBe('3 GB')
  })
})

describe('formatMemory', () => {
  it('renders sub-1024 MB as MB rounded', () => {
    expect(formatMemory(512)).toBe('512 MB')
    expect(formatMemory(512.4)).toBe('512 MB')
  })

  it('switches to GB at 1024 MB', () => {
    expect(formatMemory(2048)).toBe('2.0 GB')
    expect(formatMemory(1536)).toBe('1.5 GB')
  })
})
