import { describe, it, expect, afterEach } from "vitest"
import { firstFocusable, focusFirst } from "../focus"

function mount(html: string): HTMLElement {
  const root = document.createElement("div")
  root.innerHTML = html
  document.body.appendChild(root)
  return root
}

afterEach(() => {
  document.body.innerHTML = ""
})

describe("firstFocusable", () => {
  it("returns null for a nullish root", () => {
    expect(firstFocusable(null)).toBeNull()
    expect(firstFocusable(undefined)).toBeNull()
  })

  it("returns the first focusable in DOM order", () => {
    const root = mount(`<p>text</p><input id="a"><button id="b">go</button>`)
    expect(firstFocusable(root)!.id).toBe("a")
  })

  it("skips disabled controls", () => {
    const root = mount(`<input id="a" disabled><button id="b">go</button>`)
    expect(firstFocusable(root)!.id).toBe("b")
  })

  it("skips tabindex=-1 and picks a positive/zero tabindex", () => {
    const root = mount(`<div id="x" tabindex="-1">x</div><div id="y" tabindex="0">y</div>`)
    expect(firstFocusable(root)!.id).toBe("y")
  })

  it("skips hidden / aria-hidden / display:none subtrees", () => {
    const root = mount(`
      <button id="h" hidden>h</button>
      <div aria-hidden="true"><button id="ah">ah</button></div>
      <div style="display:none"><button id="dn">dn</button></div>
      <button id="ok">ok</button>
    `)
    expect(firstFocusable(root)!.id).toBe("ok")
  })

  it("returns null when nothing is focusable", () => {
    expect(firstFocusable(mount(`<p>only text</p>`))).toBeNull()
  })
})

describe("focusFirst", () => {
  it("focuses the first focusable control", () => {
    const root = mount(`<input id="a"><button>go</button>`)
    const focused = focusFirst(root)
    expect(focused!.id).toBe("a")
    expect(document.activeElement).toBe(focused)
  })

  it("falls back to focusing the container when it has no focusable child", () => {
    const root = mount(`<p>nothing focusable</p>`)
    const focused = focusFirst(root)
    expect(focused).toBe(root)
    expect(root.tabIndex).toBe(-1)
    expect(document.activeElement).toBe(root)
  })

  it("returns null for a nullish root", () => {
    expect(focusFirst(null)).toBeNull()
  })
})
