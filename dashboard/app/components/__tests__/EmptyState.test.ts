import { describe, it, expect } from 'vitest'
import { h } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import EmptyState from '../EmptyState.vue'

const StubIcon = { name: 'StubIcon', render: () => h('svg', { 'data-test': 'icon' }) }

describe('EmptyState', () => {
  it('renders the icon, title, and description', async () => {
    const wrapper = await mountSuspended(EmptyState, {
      props: { icon: StubIcon, title: 'No nodes', description: 'Add a node to get started' },
    })
    expect(wrapper.find('[data-test="icon"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('No nodes')
    expect(wrapper.text()).toContain('Add a node to get started')
  })

  it('omits the description paragraph when no description is given', async () => {
    const wrapper = await mountSuspended(EmptyState, {
      props: { icon: StubIcon, title: 'Nothing here' },
    })
    // Title <p> renders; the description <p> should not.
    expect(wrapper.findAll('p')).toHaveLength(1)
  })

  it('renders default-slot content (e.g. a call-to-action) when provided', async () => {
    const wrapper = await mountSuspended(EmptyState, {
      props: { icon: StubIcon, title: 'No nodes' },
      slots: { default: () => h('button', 'Add node') },
    })
    expect(wrapper.find('button').text()).toBe('Add node')
  })

  it('does not render the slot wrapper when the default slot is empty', async () => {
    const wrapper = await mountSuspended(EmptyState, {
      props: { icon: StubIcon, title: 'No nodes' },
    })
    expect(wrapper.find('.mt-5').exists()).toBe(false)
  })
})
