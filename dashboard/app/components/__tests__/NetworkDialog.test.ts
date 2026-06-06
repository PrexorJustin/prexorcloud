import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { reactive, nextTick } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import NetworkDialog from '../networks/NetworkDialog.vue'

const { createNetwork, updateNetwork, fetchGroups, toastError, networksStore, groupsStore } = vi.hoisted(() => ({
  createNetwork: vi.fn(),
  updateNetwork: vi.fn(),
  fetchGroups: vi.fn(),
  toastError: vi.fn(),
  networksStore: { networks: [] as { name: string }[] },
  groupsStore: { groups: [] as { name: string; platform: string }[] },
}))

vi.mock('vue-sonner', () => ({ toast: { error: toastError, success: vi.fn() } }))
vi.mock('~/stores/networks', () => ({
  useNetworksStore: () => reactive(Object.assign(networksStore, { createNetwork, updateNetwork })),
}))
vi.mock('~/stores/groups', () => ({
  useGroupsStore: () => reactive(Object.assign(groupsStore, { fetchGroups })),
}))

let active: { unmount: () => void } | null = null
afterEach(() => {
  active?.unmount()
  active = null
  document.body.innerHTML = ''
})

beforeEach(() => {
  createNetwork.mockReset().mockResolvedValue(undefined)
  updateNetwork.mockReset().mockResolvedValue(undefined)
  fetchGroups.mockReset()
  toastError.mockReset()
  networksStore.networks = []
  groupsStore.groups = [
    { name: 'lobby', platform: 'paper' },
    { name: 'survival', platform: 'paper' },
    { name: 'creative', platform: 'paper' },
    { name: 'proxy-a', platform: 'velocity' },
  ]
})

function bodyInput(id: string) {
  return document.body.querySelector(`#${id}`) as HTMLInputElement
}
async function setInput(el: HTMLInputElement, value: string) {
  el.value = value
  el.dispatchEvent(new Event('input'))
  await nextTick()
}
function findButton(text: string) {
  return Array.from(document.body.querySelectorAll('button')).find((b) => b.textContent?.trim() === text)
}
// fallback + member share the placeholder; proxy has its own.
function backendPickers() {
  return Array.from(document.body.querySelectorAll<HTMLInputElement>('input[placeholder="Add backend group…"]'))
}
function addButtonFor(input: HTMLInputElement) {
  return input.parentElement!.querySelector('button') as HTMLButtonElement
}

async function mountCreateOpen() {
  const wrapper = await mountSuspended(NetworkDialog)
  active = wrapper
  await wrapper.find('button').trigger('click')
  await nextTick()
  return wrapper
}

describe('NetworkDialog', () => {
  it('renders the New Network trigger in create mode', async () => {
    const wrapper = await mountSuspended(NetworkDialog)
    active = wrapper
    expect(wrapper.text()).toContain('New Network')
  })

  it('opening fetches groups and shows the create title', async () => {
    await mountCreateOpen()
    expect(fetchGroups).toHaveBeenCalled()
    expect(document.body.textContent).toContain('Create network')
  })

  it('shows a name validation error for an invalid name', async () => {
    await mountCreateOpen()
    await setInput(bodyInput('net-name'), 'Bad Name')
    expect(document.body.textContent).toContain('Lowercase letters, numbers, underscore, hyphen only')
  })

  it('flags a lobby group that is not a server group', async () => {
    await mountCreateOpen()
    await setInput(bodyInput('net-lobby'), 'proxy-a')
    expect(document.body.textContent).toContain('Group does not exist or is a proxy')
  })

  it('adds, reorders and removes fallback chain entries', async () => {
    await mountCreateOpen()
    const [fallbackPicker] = backendPickers()
    await setInput(fallbackPicker!, 'survival')
    addButtonFor(fallbackPicker!).click()
    await nextTick()
    await setInput(fallbackPicker!, 'creative')
    addButtonFor(fallbackPicker!).click()
    await nextTick()
    let rows = Array.from(document.body.querySelectorAll('ol li'))
    expect(rows.map((r) => r.querySelector('span.flex-1')?.textContent)).toEqual(['survival', 'creative'])
    // move 'creative' up
    ;(rows[1]!.querySelectorAll('button')[0] as HTMLElement).click()
    await nextTick()
    rows = Array.from(document.body.querySelectorAll('ol li'))
    expect(rows.map((r) => r.querySelector('span.flex-1')?.textContent)).toEqual(['creative', 'survival'])
    // remove first
    ;(rows[0]!.querySelectorAll('button')[2] as HTMLElement).click()
    await nextTick()
    rows = Array.from(document.body.querySelectorAll('ol li'))
    expect(rows).toHaveLength(1)
  })

  it('rejects a non-proxy group in the proxy picker with a toast', async () => {
    await mountCreateOpen()
    const proxyPicker = document.body.querySelector('input[placeholder="Add proxy group…"]') as HTMLInputElement
    await setInput(proxyPicker, 'survival')
    addButtonFor(proxyPicker).click()
    await nextTick()
    expect(toastError).toHaveBeenCalledWith('"survival" is not a proxy-platform group')
  })

  it('submits a create payload and closes on success', async () => {
    await mountCreateOpen()
    await setInput(bodyInput('net-name'), 'main')
    await setInput(bodyInput('net-lobby'), 'lobby')
    const [fallbackPicker] = backendPickers()
    await setInput(fallbackPicker!, 'survival')
    addButtonFor(fallbackPicker!).click()
    await nextTick()
    findButton('Create network')!.click()
    await new Promise((r) => setTimeout(r))
    expect(createNetwork).toHaveBeenCalledWith(expect.objectContaining({
      name: 'main',
      lobbyGroup: 'lobby',
      fallbackGroups: ['survival'],
    }))
  })

  it('toasts an error when network creation fails', async () => {
    createNetwork.mockRejectedValue(new Error('boom'))
    await mountCreateOpen()
    await setInput(bodyInput('net-name'), 'main')
    await setInput(bodyInput('net-lobby'), 'lobby')
    findButton('Create network')!.click()
    await new Promise((r) => setTimeout(r))
    expect(toastError).toHaveBeenCalledWith('Failed to create network')
  })

  it('includes Bedrock routing in the create payload when set', async () => {
    await mountCreateOpen()
    await setInput(bodyInput('net-name'), 'main')
    await setInput(bodyInput('net-lobby'), 'lobby')
    await setInput(bodyInput('net-bedrock-lobby'), 'creative')
    const bedrockPicker = document.body.querySelector('input[placeholder="Add Bedrock backend group…"]') as HTMLInputElement
    await setInput(bedrockPicker, 'survival')
    addButtonFor(bedrockPicker).click()
    await nextTick()
    findButton('Create network')!.click()
    await new Promise((r) => setTimeout(r))
    expect(createNetwork).toHaveBeenCalledWith(expect.objectContaining({
      bedrockLobbyGroup: 'creative',
      bedrockFallbackGroups: ['survival'],
    }))
  })

  it('rejects a Bedrock fallback equal to the Bedrock lobby with a toast', async () => {
    await mountCreateOpen()
    await setInput(bodyInput('net-bedrock-lobby'), 'creative')
    const bedrockPicker = document.body.querySelector('input[placeholder="Add Bedrock backend group…"]') as HTMLInputElement
    await setInput(bedrockPicker, 'creative')
    addButtonFor(bedrockPicker).click()
    await nextTick()
    expect(toastError).toHaveBeenCalled()
  })

  it('seeds Bedrock routing fields in edit mode', async () => {
    const network = {
      name: 'main', description: '', lobbyGroup: 'lobby',
      fallbackGroups: [], memberGroups: [], proxyGroups: [], kickMessage: '',
      bedrockLobbyGroup: 'creative', bedrockFallbackGroups: ['survival'],
    }
    const wrapper = await mountSuspended(NetworkDialog, { props: { network, open: false } })
    active = wrapper
    await wrapper.setProps({ open: true })
    await nextTick()
    expect(bodyInput('net-bedrock-lobby').value).toBe('creative')
    expect(document.body.textContent).toContain('survival')
  })

  it('seeds fields and calls updateNetwork in edit mode', async () => {
    const network = {
      name: 'main', description: 'd', lobbyGroup: 'lobby',
      fallbackGroups: ['survival'], memberGroups: [], proxyGroups: [], kickMessage: '',
    }
    const wrapper = await mountSuspended(NetworkDialog, { props: { network, open: false } })
    active = wrapper
    await wrapper.setProps({ open: true })
    await nextTick()
    expect(document.body.textContent).toContain('Edit network main')
    expect(bodyInput('net-name').value).toBe('main')
    expect(bodyInput('net-name').disabled).toBe(true)
    findButton('Save changes')!.click()
    await new Promise((r) => setTimeout(r))
    expect(updateNetwork).toHaveBeenCalledWith('main', expect.objectContaining({ name: 'main', lobbyGroup: 'lobby' }))
  })
})
