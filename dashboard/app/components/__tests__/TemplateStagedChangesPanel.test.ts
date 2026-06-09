import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import TemplateStagedChangesPanel from '../templates/TemplateStagedChangesPanel.vue'

type ChangeType = 'modified' | 'created' | 'deleted' | 'renamed'

const changeTypeStyles: Record<ChangeType, { label: string; class: string; dot: string }> = {
  modified: { label: 'M', class: 'text-warning', dot: 'bg-warning' },
  created: { label: 'A', class: 'text-success', dot: 'bg-success' },
  deleted: { label: 'D', class: 'text-destructive', dot: 'bg-destructive' },
  renamed: { label: 'R', class: 'text-primary', dot: 'bg-primary' },
}

function props(overrides: Record<string, unknown> = {}) {
  return {
    changes: [
      { type: 'modified' as ChangeType, path: 'plugins/config.yml' },
      { type: 'created' as ChangeType, path: 'server.properties' },
    ],
    changeTypeStyles,
    saving: false,
    ...overrides,
  }
}

describe('TemplateStagedChangesPanel', () => {
  it('renders one row per change showing the basename', async () => {
    const wrapper = await mountSuspended(TemplateStagedChangesPanel, { props: props() })
    expect(wrapper.text()).toContain('config.yml')
    expect(wrapper.text()).toContain('server.properties')
  })

  it('shows the change-type label for each change', async () => {
    const wrapper = await mountSuspended(TemplateStagedChangesPanel, { props: props() })
    expect(wrapper.text()).toContain('M')
    expect(wrapper.text()).toContain('A')
  })

  it('emits unstage with the full path when the unstage button is clicked', async () => {
    const wrapper = await mountSuspended(TemplateStagedChangesPanel, { props: props() })
    await wrapper.find('button[title="Unstage"]').trigger('click')
    expect(wrapper.emitted('unstage')?.[0]).toEqual(['plugins/config.yml'])
  })

  it('emits save from the Save button', async () => {
    const wrapper = await mountSuspended(TemplateStagedChangesPanel, { props: props() })
    const save = wrapper.findAll('button').find(b => b.text().includes('Save & Rehash'))!
    await save.trigger('click')
    expect(wrapper.emitted('save')).toHaveLength(1)
  })

  it('disables the Save button and shows the saving label while saving', async () => {
    const wrapper = await mountSuspended(TemplateStagedChangesPanel, { props: props({ saving: true }) })
    const save = wrapper.findAll('button').find(b => b.text().includes('Saving...'))!
    expect(save.attributes('disabled')).toBeDefined()
  })
})
