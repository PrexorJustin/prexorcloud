import Fuse from "fuse.js"
import { computed, ref, watch } from "vue"
import type { StatusDotTone } from "~/components/ui/status-dot"

/**
 * Cross-resource fuzzy search powering the command palette's "Resources"
 * group. Pulls from the existing Pinia stores (no extra fetches), produces a
 * normalized hit shape so the palette doesn't need per-resource rendering
 * branches.
 *
 * Each store owns its own freshness via SSE; this composable just rebuilds
 * its index when any of those refs change. Cheap — Fuse builds in O(n).
 */
export type ResourceKind = "instance" | "node" | "group" | "template" | "user"

export interface ResourceHit {
  kind: ResourceKind
  id: string
  label: string
  sublabel?: string
  route: string
  statusTone?: StatusDotTone
}

const KIND_LABEL: Record<ResourceKind, string> = {
  instance: "Instance",
  node:     "Node",
  group:    "Group",
  template: "Template",
  user:     "User",
}

function instanceTone(state: string): StatusDotTone {
  if (state === "RUNNING") return "success"
  if (state === "STARTING" || state === "SCHEDULED" || state === "PENDING") return "primary"
  if (state === "STOPPING" || state === "DRAINING") return "warning"
  if (state === "CRASHED") return "destructive"
  return "muted"
}

function nodeTone(status: string): StatusDotTone {
  if (status === "ONLINE") return "success"
  if (status === "DRAINING" || status === "CORDONED") return "warning"
  if (status === "UNREACHABLE") return "destructive"
  return "muted"
}

export function useResourceSearchIndex() {
  const instances = useInstancesStore()
  const nodes = useNodesStore()
  const groups = useGroupsStore()
  const templates = useTemplatesStore()

  const items = computed<ResourceHit[]>(() => {
    const out: ResourceHit[] = []

    for (const i of instances.instances) {
      out.push({
        kind: "instance",
        id: i.id,
        label: i.id,
        sublabel: `${i.group} · ${i.node}`,
        route: `/instances/${i.id}`,
        statusTone: instanceTone(i.state),
      })
    }
    for (const n of nodes.nodes) {
      const status = "status" in n ? n.status : ""
      out.push({
        kind: "node",
        id: n.id,
        label: n.id,
        sublabel: KIND_LABEL.node,
        route: `/nodes/${n.id}`,
        statusTone: nodeTone(status),
      })
    }
    for (const g of groups.groups) {
      out.push({
        kind: "group",
        id: g.name,
        label: g.name,
        sublabel: `${g.platform} · ${g.runningInstances ?? 0} running`,
        route: `/groups/${g.name}`,
        statusTone: g.maintenance ? "warning" : "primary",
      })
    }
    for (const t of templates.templates) {
      out.push({
        kind: "template",
        id: t.name,
        label: t.name,
        sublabel: `${KIND_LABEL.template} · ${t.platform}`,
        route: `/templates/${t.name}`,
      })
    }

    return out
  })

  // Rebuild Fuse instance only when the underlying list shape changes.
  const fuse = ref<Fuse<ResourceHit>>(new Fuse(items.value, {
    keys: ["label", "sublabel", "kind"],
    threshold: 0.35,
    ignoreLocation: true,
    minMatchCharLength: 1,
  }))

  watch(items, (next) => {
    fuse.value.setCollection(next)
  })

  function search(query: string, limit = 12): ResourceHit[] {
    const q = query.trim()
    if (!q) return []
    return fuse.value.search(q, { limit }).map(r => r.item)
  }

  return { search, items }
}
