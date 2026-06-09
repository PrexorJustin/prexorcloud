import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import TemplateFileTreePanel from '../templates/TemplateFileTreePanel.vue'
import type { TreeNode } from '../templates/FileTreeNode.vue'

function node(overrides: Partial<TreeNode> = {}): TreeNode {
  return { name: 'server.properties', path: 'server.properties', isDirectory: false, size: 512, ...overrides }
}

function props(overrides: Record<string, unknown> = {}) {
  return {
    tree: [node(), node({ name: 'plugins', path: 'plugins', isDirectory: true, size: 0 })],
    treeLoading: false,
    openFilePath: null,
    draggedPath: null,
    dropTargetPath: null,
    renamingPath: null,
    renameValue: '',
    showNewInput: null,
    newFileTargetPath: '',
    newItemName: '',
    showSearch: false,
    searchResults: [],
    searchLoading: false,
    searchQuery: '',
    getChangeType: () => null,
    ...overrides,
  }
}

describe('TemplateFileTreePanel', () => {
  it('emits newFile and newDir with the root path from the header buttons', async () => {
    const wrapper = await mountSuspended(TemplateFileTreePanel, { props: props() })
    await wrapper.find('button[title="New file"]').trigger('click')
    await wrapper.find('button[title="New folder"]').trigger('click')
    expect(wrapper.emitted('newFile')?.[0]).toEqual([''])
    expect(wrapper.emitted('newDir')?.[0]).toEqual([''])
  })

  it('emits upload and refresh from the header buttons', async () => {
    const wrapper = await mountSuspended(TemplateFileTreePanel, { props: props() })
    await wrapper.find('button[title="Upload files"]').trigger('click')
    await wrapper.find('button[title="Refresh"]').trigger('click')
    expect(wrapper.emitted('upload')).toHaveLength(1)
    expect(wrapper.emitted('refresh')).toHaveLength(1)
  })

  it('toggles the search panel via update:showSearch', async () => {
    const wrapper = await mountSuspended(TemplateFileTreePanel, { props: props({ showSearch: false }) })
    await wrapper.find('button[title="Search files"]').trigger('click')
    expect(wrapper.emitted('update:showSearch')?.[0]).toEqual([true])
  })

  it('shows a spinner and no tree nodes while the tree is loading', async () => {
    const wrapper = await mountSuspended(TemplateFileTreePanel, { props: props({ treeLoading: true }) })
    expect(wrapper.find('.animate-spin').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('server.properties')
  })

  it('renders the tree nodes when not loading', async () => {
    const wrapper = await mountSuspended(TemplateFileTreePanel, { props: props() })
    expect(wrapper.text()).toContain('server.properties')
    expect(wrapper.text()).toContain('plugins')
  })

  it('shows the root-level new-item input when showNewInput targets the root', async () => {
    const wrapper = await mountSuspended(TemplateFileTreePanel, {
      props: props({ showNewInput: 'dir', newFileTargetPath: '' }),
    })
    const input = wrapper.find('input[placeholder="folder name"]')
    expect(input.exists()).toBe(true)
  })

  it('re-emits fileClick from a child FileTreeNode', async () => {
    const wrapper = await mountSuspended(TemplateFileTreePanel, { props: props() })
    await wrapper.find('div.cursor-pointer').trigger('click')
    expect(wrapper.emitted('fileClick')).toBeDefined()
  })
})
