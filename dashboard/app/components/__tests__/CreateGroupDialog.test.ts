import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { reactive, nextTick } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import CreateGroupDialog from '../groups/CreateGroupDialog.vue'

const {
  createGroup, fetchCatalog, createTemplate, fetchTemplates, fetchNodes, toastError,
  groupsStore, catalogStore, templatesStore, nodesStore,
} = vi.hoisted(() => ({
  createGroup: vi.fn(),
  fetchCatalog: vi.fn(),
  createTemplate: vi.fn(),
  fetchTemplates: vi.fn(),
  fetchNodes: vi.fn(),
  toastError: vi.fn(),
  groupsStore: { groups: [] as { name: string }[] },
  catalogStore: { entries: [] as { platform: string; category: string; versions: { version: string; recommended?: boolean }[] }[] },
  templatesStore: { templates: [] as { name: string }[] },
  nodesStore: { nodes: [] as { id: string; type: string }[] },
}))

vi.mock('vue-sonner', () => ({ toast: { error: toastError, success: vi.fn() } }))
vi.mock('~/stores/groups', () => ({
  useGroupsStore: () => reactive(Object.assign(groupsStore, { createGroup })),
}))
vi.mock('~/stores/catalog', () => ({
  useCatalogStore: () => reactive(Object.assign(catalogStore, { fetchCatalog })),
}))
vi.mock('~/stores/templates', () => ({
  useTemplatesStore: () => reactive(Object.assign(templatesStore, { createTemplate, fetchTemplates })),
}))
vi.mock('~/stores/nodes', () => ({
  useNodesStore: () => reactive(Object.assign(nodesStore, { fetchNodes })),
}))

let active: { unmount: () => void } | null = null
afterEach(() => {
  active?.unmount()
  active = null
  document.body.innerHTML = ''
})

beforeEach(() => {
  createGroup.mockReset().mockResolvedValue(undefined)
  fetchCatalog.mockReset()
  createTemplate.mockReset().mockResolvedValue(undefined)
  fetchTemplates.mockReset()
  fetchNodes.mockReset()
  toastError.mockReset()
  groupsStore.groups = []
  catalogStore.entries = [{ platform: 'paper', category: 'SERVER', versions: [{ version: '1.21.1', recommended: true }] }]
  templatesStore.templates = []
  nodesStore.nodes = []
})

function bodyInput(id: string) {
  return document.body.querySelector(`#${id}`) as HTMLInputElement
}
async function setInput(id: string, value: string) {
  const el = bodyInput(id)
  el.value = value
  el.dispatchEvent(new Event('input'))
  await nextTick()
}
function findButton(text: string) {
  return Array.from(document.body.querySelectorAll('button')).find((b) => b.textContent?.trim().startsWith(text))
}
async function advance() {
  findButton('Continue')!.click()
  await nextTick()
  await new Promise((r) => setTimeout(r, 5))
  await nextTick()
}

async function mountOpen() {
  const wrapper = await mountSuspended(CreateGroupDialog)
  active = wrapper
  await wrapper.find('button').trigger('click')
  await nextTick()
  return wrapper
}

describe('CreateGroupDialog', () => {
  it('renders the New Group trigger', async () => {
    const wrapper = await mountSuspended(CreateGroupDialog)
    active = wrapper
    expect(wrapper.text()).toContain('New Group')
  })

  it('opening fetches catalog/templates/nodes and shows the identity step', async () => {
    await mountOpen()
    expect(fetchCatalog).toHaveBeenCalled()
    expect(fetchTemplates).toHaveBeenCalled()
    expect(fetchNodes).toHaveBeenCalled()
    expect(bodyInput('g-name')).not.toBeNull()
    expect(document.body.textContent).toContain('1 / 6')
  })

  it('shows a name validation error for an invalid name', async () => {
    await mountOpen()
    await setInput('g-name', 'Bad Name')
    expect(document.body.textContent).toContain('Lowercase, numbers, underscore, hyphen')
  })

  it('keeps Continue disabled until the name is valid', async () => {
    await mountOpen()
    expect(findButton('Continue')!.getAttribute('disabled')).not.toBeNull()
    await setInput('g-name', 'lobby')
    expect(findButton('Continue')!.getAttribute('disabled')).toBeNull()
  })

  it('selecting a platform reveals its presets', async () => {
    await mountOpen()
    ;(Array.from(document.body.querySelectorAll('button')).find((b) => b.textContent?.toLowerCase().includes('paper')) as HTMLElement).click()
    await nextTick()
    expect(document.body.textContent).toContain('Lobby')
    expect(document.body.textContent).toContain('Game')
  })

  it('walks through all steps and submits a composed createGroup payload', async () => {
    await mountOpen()
    await setInput('g-name', 'lobby')
    ;(Array.from(document.body.querySelectorAll('button')).find((b) => b.textContent?.toLowerCase().includes('paper')) as HTMLElement).click()
    await nextTick()
    for (let i = 0; i < 5; i++) await advance()
    expect(document.body.textContent).toContain('6 / 6')
    findButton('Create Group')!.click()
    await new Promise((r) => setTimeout(r))
    expect(createGroup).toHaveBeenCalledWith(expect.objectContaining({
      name: 'lobby',
      platform: 'PAPER',
      platformVersion: '1.21.1',
    }))
  })

  it('creates the mandatory templates that do not yet exist', async () => {
    await mountOpen()
    await setInput('g-name', 'lobby')
    ;(Array.from(document.body.querySelectorAll('button')).find((b) => b.textContent?.toLowerCase().includes('paper')) as HTMLElement).click()
    await nextTick()
    for (let i = 0; i < 5; i++) await advance()
    findButton('Create Group')!.click()
    await new Promise((r) => setTimeout(r))
    const created = createTemplate.mock.calls.map((c) => (c[0] as { name: string }).name)
    expect(created).toEqual(expect.arrayContaining(['base', 'base-paper', 'lobby']))
  })

  it('toasts an error when group creation fails', async () => {
    createGroup.mockRejectedValue(new Error('boom'))
    await mountOpen()
    await setInput('g-name', 'lobby')
    for (let i = 0; i < 5; i++) await advance()
    findButton('Create Group')!.click()
    await new Promise((r) => setTimeout(r))
    expect(toastError).toHaveBeenCalledWith('Create failed', expect.anything())
  })

  it('moves focus into the new step after advancing (WCAG 2.4.3 focus order)', async () => {
    await mountOpen()
    await setInput('g-name', 'lobby')
    await advance()
    expect(document.body.textContent).toContain('2 / 6')
    // Focus followed the step change instead of being stranded on the footer:
    // it now sits on a focusable control inside the dialog content.
    const activeEl = document.activeElement as HTMLElement | null
    expect(activeEl).not.toBe(document.body)
    expect(activeEl?.closest('.styled-scrollbar')).not.toBeNull()
  })

  it('Back returns to the previous step', async () => {
    await mountOpen()
    await setInput('g-name', 'lobby')
    await advance()
    expect(document.body.textContent).toContain('2 / 6')
    findButton('Back')!.click()
    await nextTick()
    await new Promise((r) => setTimeout(r, 5))
    expect(document.body.textContent).toContain('1 / 6')
  })
})
