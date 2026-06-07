import { describe, expect, it } from 'vitest';
import { escapeHtml, highlightYaml } from './highlight';

describe('escapeHtml', () => {
  it('escapes the five HTML danger glyphs', () => {
    expect(escapeHtml(`<a href="x">'&'</a>`)).toBe(
      '&lt;a href=&quot;x&quot;&gt;&#39;&amp;&#39;&lt;/a&gt;',
    );
  });

  it('coerces numbers and nulls without throwing', () => {
    expect(escapeHtml(0)).toBe('0');
    expect(escapeHtml(null)).toBe('');
    expect(escapeHtml(undefined)).toBe('');
  });
});

describe('highlightYaml', () => {
  it('marks comments with the .yc span', () => {
    const out = highlightYaml('# a comment');
    expect(out).toBe('<span class="yc"># a comment</span>');
  });

  it('marks key + string-value with .yk / .ys spans', () => {
    const out = highlightYaml('foo: bar');
    expect(out).toContain('<span class="yk">foo</span>');
    expect(out).toContain('<span class="ys">bar</span>');
  });

  it('marks numbers and booleans with .yn / .yb', () => {
    const out = highlightYaml('port: 8080\nenabled: true');
    expect(out).toContain('<span class="yn">8080</span>');
    expect(out).toContain('<span class="yb">true</span>');
  });

  it('marks list dashes with .yd', () => {
    const out = highlightYaml('- 10.0.0.0/8');
    expect(out).toContain('<span class="yd">- </span>');
  });

  it('preserves leading whitespace (used for indentation)', () => {
    const out = highlightYaml('  nested: 1');
    expect(out.startsWith('  ')).toBe(true);
  });
});
