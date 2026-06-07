import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import MotdEditor from '../groups/MotdEditor.vue'
import type { ServerGroup } from '~/types/api'

const { patchMock, toastSuccess, toastError } = vi.hoisted(() => ({
  patchMock: vi.fn(),
  toastSuccess: vi.fn(),
  toastError: vi.fn(),
}))

vi.mock('vue-sonner', () => ({ toast: { success: toastSuccess, error: toastError } }))
vi.mock('~/composables/useApiClient', () => ({ useApiClient: () => ({ PATCH: patchMock }) }))

function group(overrides: Partial<ServerGroup> = {}): ServerGroup {
  return {
    name: 'lobby', platform: 'paper', platformVersion: '1.21.1',
    motds: ['<gold>Hello'], motdMode: 'STATIC', motdIntervalSeconds: 30,
    ...overrides,
  } as ServerGroup
}

beforeEach(() => {
  patchMock.mockReset().mockResolvedValue({})
  toastSuccess.mockReset()
  toastError.mockReset()
})

describe('MotdEditor', () => {
  it('seeds the message inputs from group.motds', async () => {
    const wrapper = await mountSuspended(MotdEditor, { props: { group: group({ motds: ['a', 'b'] }) } })
    const inputs = wrapper.findAll('input[type="text"]')
    expect(inputs).toHaveLength(2)
    expect((inputs[0]!.element as HTMLInputElement).value).toBe('a')
  })

  it('falls back to a single empty input when the group has no motds', async () => {
    const wrapper = await mountSuspended(MotdEditor, { props: { group: group({ motds: [] }) } })
    expect(wrapper.findAll('input[type="text"]')).toHaveLength(1)
  })

  it('highlights the active mode and switches it on click', async () => {
    const wrapper = await mountSuspended(MotdEditor, { props: { group: group({ motdMode: 'STATIC' }) } })
    const seq = wrapper.findAll('button').find(b => b.text().includes('Sequential'))!
    expect(seq.classes()).not.toContain('border-primary')
    await seq.trigger('click')
    expect(seq.classes()).toContain('border-primary')
  })

  it('reveals the interval input only in SEQUENTIAL mode', async () => {
    const wrapper = await mountSuspended(MotdEditor, { props: { group: group({ motdMode: 'STATIC' }) } })
    expect(wrapper.find('input[type="number"]').exists()).toBe(false)
    await wrapper.findAll('button').find(b => b.text().includes('Sequential'))!.trigger('click')
    expect(wrapper.find('input[type="number"]').exists()).toBe(true)
  })

  it('adds and removes message entries', async () => {
    const wrapper = await mountSuspended(MotdEditor, { props: { group: group({ motds: ['a'] }) } })
    await wrapper.findAll('button').find(b => b.text().includes('Add'))!.trigger('click')
    expect(wrapper.findAll('input[type="text"]')).toHaveLength(2)
  })

  it('keeps one empty input after removing the last entry', async () => {
    const wrapper = await mountSuspended(MotdEditor, { props: { group: group({ motds: ['only'] }) } })
    // the trash button is the icon-only button inside the entry row
    const trash = wrapper.findAll('button').find(b => b.text() === '')!
    await trash.trigger('click')
    expect(wrapper.findAll('input[type="text"]')).toHaveLength(1)
  })

  it('renders a MiniMessage preview for non-empty entries', async () => {
    const wrapper = await mountSuspended(MotdEditor, { props: { group: group({ motds: ['<gold>Hi'] }) } })
    expect(wrapper.find('.font-minecraft').exists()).toBe(true)
  })

  it('saves the filtered motds, mode and interval, toasts success and emits saved', async () => {
    const wrapper = await mountSuspended(MotdEditor, {
      props: { group: group({ motds: ['keep', '  '], motdMode: 'RANDOM', motdIntervalSeconds: 45 }) },
    })
    await wrapper.findAll('button').find(b => b.text().includes('Save MOTD'))!.trigger('click')
    await new Promise(r => setTimeout(r))
    expect(patchMock).toHaveBeenCalledWith('/api/v1/groups/{name}', {
      params: { path: { name: 'lobby' } },
      body: { motds: ['keep'], motdMode: 'RANDOM', motdIntervalSeconds: 45 },
    })
    expect(toastSuccess).toHaveBeenCalledWith('MOTD saved')
    expect(wrapper.emitted('saved')).toHaveLength(1)
  })

  it('toasts an error when the save request rejects', async () => {
    patchMock.mockRejectedValueOnce(new Error('boom'))
    const wrapper = await mountSuspended(MotdEditor, { props: { group: group() } })
    await wrapper.findAll('button').find(b => b.text().includes('Save MOTD'))!.trigger('click')
    await new Promise(r => setTimeout(r))
    expect(toastError).toHaveBeenCalledWith('Save failed', expect.any(Object))
  })
})
