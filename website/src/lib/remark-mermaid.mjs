/**
 * remark-mermaid — turns ```mermaid fenced code blocks into a
 * `<div class="mermaid"><pre>SOURCE</pre></div>` HTML node so the
 * client-side mermaid runtime (see `src/scripts/mermaid.ts`) can render
 * them on hydration.
 *
 * The wrapping <pre> preserves whitespace if mermaid fails to load and
 * the `data-mermaid-source` attribute lets us round-trip the original
 * source for theme-toggle re-renders without re-parsing the DOM.
 *
 * Why client-side:
 * - SSR via mermaid-isomorphic + Playwright adds ~50 MB to the install
 *   and ~10s to the build. Phase 11 polish can swap to SSR if Lighthouse
 *   demands it.
 * - mermaid is lazy-loaded only when a `.mermaid` element exists on the
 *   page, so docs pages without diagrams pay zero JS cost.
 */
import { visit } from 'unist-util-visit';

export default function remarkMermaid() {
  return (tree) => {
    visit(tree, 'code', (node, index, parent) => {
      if (!parent || node.lang !== 'mermaid') return;
      // Strip inline `classDef …` rules and `:::class` annotations.
      // Pre-redesign diagrams hard-code cyan/violet fills which override
      // any mermaid theme; dropping them lets the editorial monochrome
      // theme variables (src/scripts/mermaid.ts) take effect uniformly
      // across every diagram without touching every .md source.
      const source = stripHardcodedTheme(node.value ?? '');
      const escaped = source
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');

      parent.children[index] = {
        type: 'html',
        value:
          `<div class="mermaid-wrapper" data-mermaid-source="${escapeAttr(source)}">` +
          `<pre class="mermaid">${escaped}</pre>` +
          `</div>`,
      };
    });
  };
}

function escapeAttr(s) {
  return s
    .replace(/&/g, '&amp;')
    .replace(/"/g, '&quot;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

// Removes per-node `:::className` annotations and the matching
// `classDef name fill:…,stroke:…,color:…` declarations. Idempotent.
function stripHardcodedTheme(source) {
  return source
    .split('\n')
    .filter(line => !/^\s*classDef\s+\w+\s+/i.test(line))
    .join('\n')
    .replace(/:::[a-zA-Z][\w-]*/g, '');
}
