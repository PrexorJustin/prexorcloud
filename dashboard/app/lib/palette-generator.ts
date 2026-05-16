// Generates a full theme palette from a single base color

function hexToHsl(hex: string): [number, number, number] {
  const r = parseInt(hex.slice(1, 3), 16) / 255
  const g = parseInt(hex.slice(3, 5), 16) / 255
  const b = parseInt(hex.slice(5, 7), 16) / 255
  const max = Math.max(r, g, b), min = Math.min(r, g, b)
  const l = (max + min) / 2
  if (max === min) return [0, 0, l]
  const d = max - min
  const s = l > 0.5 ? d / (2 - max - min) : d / (max + min)
  let h = 0
  if (max === r) h = ((g - b) / d + (g < b ? 6 : 0)) / 6
  else if (max === g) h = ((b - r) / d + 2) / 6
  else h = ((r - g) / d + 4) / 6
  return [h * 360, s * 100, l * 100]
}

function hslToHex(h: number, s: number, l: number): string {
  h = ((h % 360) + 360) % 360
  s = Math.max(0, Math.min(100, s)) / 100
  l = Math.max(0, Math.min(100, l)) / 100
  const a = s * Math.min(l, 1 - l)
  const f = (n: number) => {
    const k = (n + h / 30) % 12
    const color = l - a * Math.max(Math.min(k - 3, 9 - k, 1), -1)
    return Math.round(255 * Math.max(0, Math.min(1, color)))
      .toString(16)
      .padStart(2, "0")
  }
  return `#${f(0)}${f(8)}${f(4)}`
}

export function generateLightPalette(baseHex: string): Record<string, string> {
  const [h, s] = hexToHsl(baseHex)
  const sat = Math.min(s, 15) // desaturate for UI surfaces

  return {
    "--background": hslToHex(h, sat, 78),
    "--foreground": hslToHex(h, sat + 10, 12),
    "--card": hslToHex(h, sat, 95),
    "--card-foreground": hslToHex(h, sat + 10, 12),
    "--popover": hslToHex(h, sat, 93) + "ee",
    "--popover-foreground": hslToHex(h, sat + 10, 12),
    "--muted": hslToHex(h, sat, 72),
    "--muted-foreground": hslToHex(h, sat + 5, 40),
    "--accent": hslToHex(h, sat, 72),
    "--accent-foreground": hslToHex(h, sat + 10, 12),
    "--border": hslToHex(h, sat, 65),
    "--input": hslToHex(h, sat, 65),
    "--glass": hslToHex(h, sat, 90),
    "--glass-hover": hslToHex(h, sat, 84),
    "--glass-border": hslToHex(h, sat, 65),
    "--sidebar": hslToHex(h, sat, 72),
    "--sidebar-foreground": hslToHex(h, sat + 5, 28),
    "--sidebar-accent": hslToHex(h, sat, 66),
    "--sidebar-accent-foreground": hslToHex(h, sat + 10, 12),
    "--sidebar-border": hslToHex(h, sat, 58),
  }
}

export function generateDarkPalette(baseHex: string): Record<string, string> {
  const [h, s] = hexToHsl(baseHex)
  const sat = Math.min(s, 20)

  return {
    "--background": hslToHex(h, sat, 4),
    "--foreground": hslToHex(h, sat, 96),
    "--card": `#ffffff08`,
    "--card-foreground": hslToHex(h, sat, 96),
    "--popover": hslToHex(h, sat, 6) + "ee",
    "--popover-foreground": hslToHex(h, sat, 96),
    "--muted": hslToHex(h, sat, 14),
    "--muted-foreground": hslToHex(h, sat, 50),
    "--accent": `#ffffff08`,
    "--accent-foreground": hslToHex(h, sat, 96),
    "--border": `#ffffff15`,
    "--input": `#ffffff10`,
    "--glass": `#ffffff08`,
    "--glass-hover": `#ffffff12`,
    "--glass-border": `#ffffff15`,
    "--sidebar": hslToHex(h, sat, 6),
    "--sidebar-foreground": hslToHex(h, sat, 60),
    "--sidebar-accent": hslToHex(h, sat, 14),
    "--sidebar-accent-foreground": hslToHex(h, sat, 96),
    "--sidebar-border": hslToHex(h, sat, 14),
  }
}

export function getPreviewColors(vars: Record<string, string>): string[] {
  return [
    vars["--background"],
    vars["--glass"]?.replace(/[a-f0-9]{2}$/i, "") || vars["--card"],
    vars["--sidebar-border"] || vars["--border"],
    vars["--sidebar"],
  ].map(c => (c ?? "").replace(/ee$/, "")) // strip alpha suffixes for preview swatches
}
