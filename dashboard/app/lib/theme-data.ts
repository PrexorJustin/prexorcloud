// ── Accent Colors ───────────────────────────
export interface AccentColor {
  name: string
  value: string  // dark mode
  light: string  // light mode
}

export const accentColors: AccentColor[] = [
  { name: 'Cyan', value: '#06b6d4', light: '#0891b2' },
  { name: 'Blue', value: '#3b82f6', light: '#2563eb' },
  { name: 'Violet', value: '#8b5cf6', light: '#7c3aed' },
  { name: 'Rose', value: '#f43f5e', light: '#e11d48' },
  { name: 'Orange', value: '#f97316', light: '#ea580c' },
  { name: 'Green', value: '#10b981', light: '#059669' },
  { name: 'Yellow', value: '#eab308', light: '#ca8a04' },
  { name: 'Red', value: '#ef4444', light: '#dc2626' },
  { name: 'Pink', value: '#ec4899', light: '#db2777' },
]

export const radii = [0, 0.25, 0.5, 0.75, 1] as const

// ── Theme Palette Presets ───────────────────
export interface ThemePreset {
  name: string
  description: string
  preview: string[]
  vars: Record<string, string>
}

export const lightPresets: ThemePreset[] = [
  {
    name: "Soft Scandinavian",
    description: "Warm earthy tones, sage accents",
    preview: ["#D8D5CD", "#EEEDE8", "#C5C2B8", "#C9C6BD"],
    vars: {
      "--background": "#D8D5CD", "--foreground": "#2c3027",
      "--card": "#F5F4F0", "--card-foreground": "#2c3027",
      "--popover": "#F2F1ECee", "--popover-foreground": "#2c3027",
      "--muted": "#C5C2B8", "--muted-foreground": "#6b7265",
      "--accent": "#C5C2B8", "--accent-foreground": "#2c3027",
      "--border": "#B8B5AC", "--input": "#B8B5AC",
      "--glass": "#EEEDE8", "--glass-hover": "#E4E3DC", "--glass-border": "#B8B5AC",
      "--sidebar": "#C9C6BD", "--sidebar-foreground": "#4a4f45",
      "--sidebar-accent": "#BBB8AE", "--sidebar-accent-foreground": "#2c3027", "--sidebar-border": "#ABA8A0",
    },
  },
  {
    name: "Clean Modernist",
    description: "Cool gray, white cards, minimal",
    preview: ["#E4E6EB", "#FFFFFF", "#C4C4C8", "#D6D6DC"],
    vars: {
      "--background": "#E4E6EB", "--foreground": "#1a1a2e",
      "--card": "#FFFFFF", "--card-foreground": "#1a1a2e",
      "--popover": "#FFFFFFee", "--popover-foreground": "#1a1a2e",
      "--muted": "#D3D3D3", "--muted-foreground": "#5a5a6e",
      "--accent": "#D3D3D3", "--accent-foreground": "#1a1a2e",
      "--border": "#C4C4C8", "--input": "#C4C4C8",
      "--glass": "#F4F4F6", "--glass-hover": "#EAEAEE", "--glass-border": "#C4C4C8",
      "--sidebar": "#D6D6DC", "--sidebar-foreground": "#5a5a6e",
      "--sidebar-accent": "#C8C8CE", "--sidebar-accent-foreground": "#1a1a2e", "--sidebar-border": "#B8B8BE",
    },
  },
  {
    name: "Gentle Moonlight",
    description: "Cool lavender-blue, dreamy",
    preview: ["#D0D5E8", "#F0F4FF", "#A8B8D4", "#C2C8DE"],
    vars: {
      "--background": "#D0D5E8", "--foreground": "#1e2340",
      "--card": "#F0F4FF", "--card-foreground": "#1e2340",
      "--popover": "#edf1fcee", "--popover-foreground": "#1e2340",
      "--muted": "#B7C9E8", "--muted-foreground": "#5c6a82",
      "--accent": "#B7C9E8", "--accent-foreground": "#1e2340",
      "--border": "#A8B8D4", "--input": "#A8B8D4",
      "--glass": "#E6EAFA", "--glass-hover": "#D8DEF2", "--glass-border": "#A8B8D4",
      "--sidebar": "#C2C8DE", "--sidebar-foreground": "#4a5270",
      "--sidebar-accent": "#B4BAD0", "--sidebar-accent-foreground": "#1e2340", "--sidebar-border": "#9EAAC8",
    },
  },
  {
    name: "Arctic Frost",
    description: "Icy blue-white, crisp and cold",
    preview: ["#D0DCE6", "#F2F7FA", "#9CB2C0", "#C0CED8"],
    vars: {
      "--background": "#D0DCE6", "--foreground": "#1a2832",
      "--card": "#F2F7FA", "--card-foreground": "#1a2832",
      "--popover": "#EFF4F8ee", "--popover-foreground": "#1a2832",
      "--muted": "#B0C4D0", "--muted-foreground": "#5a6e7a",
      "--accent": "#B0C4D0", "--accent-foreground": "#1a2832",
      "--border": "#9CB2C0", "--input": "#9CB2C0",
      "--glass": "#E6EEF4", "--glass-hover": "#D8E4EC", "--glass-border": "#9CB2C0",
      "--sidebar": "#C0CED8", "--sidebar-foreground": "#3a5060",
      "--sidebar-accent": "#B0C0CC", "--sidebar-accent-foreground": "#1a2832", "--sidebar-border": "#90A8B8",
    },
  },
  {
    name: "Warm Sand",
    description: "Desert tones, golden warmth",
    preview: ["#D5CCC0", "#F5F0E8", "#B4A894", "#C8BEB0"],
    vars: {
      "--background": "#D5CCC0", "--foreground": "#302820",
      "--card": "#F5F0E8", "--card-foreground": "#302820",
      "--popover": "#F2EDE5ee", "--popover-foreground": "#302820",
      "--muted": "#C4B8A6", "--muted-foreground": "#7a6e5e",
      "--accent": "#C4B8A6", "--accent-foreground": "#302820",
      "--border": "#B4A894", "--input": "#B4A894",
      "--glass": "#EDE8E0", "--glass-hover": "#E2DCD2", "--glass-border": "#B4A894",
      "--sidebar": "#C8BEB0", "--sidebar-foreground": "#5a5040",
      "--sidebar-accent": "#BAB0A2", "--sidebar-accent-foreground": "#302820", "--sidebar-border": "#A89C8A",
    },
  },
  {
    name: "Slate Noir",
    description: "Dark-ish light theme, high contrast",
    preview: ["#B8BCC6", "#E0E2E8", "#888C9A", "#A6AAB6"],
    vars: {
      "--background": "#B8BCC6", "--foreground": "#141620",
      "--card": "#E0E2E8", "--card-foreground": "#141620",
      "--popover": "#DCDEe4ee", "--popover-foreground": "#141620",
      "--muted": "#9A9EAC", "--muted-foreground": "#484C58",
      "--accent": "#9A9EAC", "--accent-foreground": "#141620",
      "--border": "#888C9A", "--input": "#888C9A",
      "--glass": "#D2D4DA", "--glass-hover": "#C6C8D0", "--glass-border": "#888C9A",
      "--sidebar": "#A6AAB6", "--sidebar-foreground": "#2E3040",
      "--sidebar-accent": "#989CA8", "--sidebar-accent-foreground": "#141620", "--sidebar-border": "#7E8290",
    },
  },
  {
    name: "Rose Quartz",
    description: "Soft pink-gray, elegant warmth",
    preview: ["#D2C8CE", "#F4EFF1", "#B4A8AE", "#C6BCC2"],
    vars: {
      "--background": "#D2C8CE", "--foreground": "#2C2228",
      "--card": "#F4EFF1", "--card-foreground": "#2C2228",
      "--popover": "#F0EBEDee", "--popover-foreground": "#2C2228",
      "--muted": "#C4B8BC", "--muted-foreground": "#7A6E72",
      "--accent": "#C4B8BC", "--accent-foreground": "#2C2228",
      "--border": "#B4A8AE", "--input": "#B4A8AE",
      "--glass": "#EBE4E8", "--glass-hover": "#E0D8DC", "--glass-border": "#B4A8AE",
      "--sidebar": "#C6BCC2", "--sidebar-foreground": "#504448",
      "--sidebar-accent": "#B8AEB4", "--sidebar-accent-foreground": "#2C2228", "--sidebar-border": "#A69CA2",
    },
  },
]

export const darkPresets: ThemePreset[] = [
  {
    name: "Default Abyss",
    description: "Deep navy black — the default",
    preview: ["#0a0a12", "#0e0e1a", "#1e293b", "#ffffff15"],
    vars: {
      "--background": "#0a0a12", "--foreground": "#f8fafc",
      "--card": "#ffffff08", "--card-foreground": "#f8fafc",
      "--popover": "#0f0f1aee", "--popover-foreground": "#f8fafc",
      "--muted": "#1e293b", "--muted-foreground": "#64748b",
      "--accent": "#ffffff08", "--accent-foreground": "#f8fafc",
      "--border": "#ffffff15", "--input": "#ffffff10",
      "--glass": "#ffffff08", "--glass-hover": "#ffffff12", "--glass-border": "#ffffff15",
      "--sidebar": "#0e0e1a", "--sidebar-foreground": "#94a3b8",
      "--sidebar-accent": "#1e293b", "--sidebar-accent-foreground": "#f8fafc", "--sidebar-border": "#1e293b",
    },
  },
  {
    name: "Charcoal",
    description: "Neutral dark gray, no blue tint",
    preview: ["#141414", "#1a1a1a", "#2a2a2a", "#ffffff15"],
    vars: {
      "--background": "#141414", "--foreground": "#e8e8e8",
      "--card": "#ffffff08", "--card-foreground": "#e8e8e8",
      "--popover": "#1a1a1aee", "--popover-foreground": "#e8e8e8",
      "--muted": "#2a2a2a", "--muted-foreground": "#808080",
      "--accent": "#ffffff08", "--accent-foreground": "#e8e8e8",
      "--border": "#ffffff15", "--input": "#ffffff10",
      "--glass": "#ffffff08", "--glass-hover": "#ffffff12", "--glass-border": "#ffffff15",
      "--sidebar": "#1a1a1a", "--sidebar-foreground": "#a0a0a0",
      "--sidebar-accent": "#2a2a2a", "--sidebar-accent-foreground": "#e8e8e8", "--sidebar-border": "#2a2a2a",
    },
  },
  {
    name: "Midnight Blue",
    description: "Deep navy with blue undertones",
    preview: ["#0c1222", "#101828", "#1c2e4a", "#ffffff12"],
    vars: {
      "--background": "#0c1222", "--foreground": "#e2e8f0",
      "--card": "#ffffff08", "--card-foreground": "#e2e8f0",
      "--popover": "#101828ee", "--popover-foreground": "#e2e8f0",
      "--muted": "#1c2e4a", "--muted-foreground": "#6b82a8",
      "--accent": "#ffffff08", "--accent-foreground": "#e2e8f0",
      "--border": "#ffffff12", "--input": "#ffffff10",
      "--glass": "#ffffff08", "--glass-hover": "#ffffff12", "--glass-border": "#ffffff12",
      "--sidebar": "#101828", "--sidebar-foreground": "#8ba2c4",
      "--sidebar-accent": "#1c2e4a", "--sidebar-accent-foreground": "#e2e8f0", "--sidebar-border": "#1c2e4a",
    },
  },
  {
    name: "Obsidian",
    description: "True black, OLED-friendly",
    preview: ["#000000", "#050505", "#1a1a1a", "#ffffff12"],
    vars: {
      "--background": "#000000", "--foreground": "#f0f0f0",
      "--card": "#ffffff06", "--card-foreground": "#f0f0f0",
      "--popover": "#0a0a0aee", "--popover-foreground": "#f0f0f0",
      "--muted": "#1a1a1a", "--muted-foreground": "#707070",
      "--accent": "#ffffff06", "--accent-foreground": "#f0f0f0",
      "--border": "#ffffff12", "--input": "#ffffff0a",
      "--glass": "#ffffff06", "--glass-hover": "#ffffff0e", "--glass-border": "#ffffff12",
      "--sidebar": "#050505", "--sidebar-foreground": "#909090",
      "--sidebar-accent": "#1a1a1a", "--sidebar-accent-foreground": "#f0f0f0", "--sidebar-border": "#1a1a1a",
    },
  },
  {
    name: "Warm Ember",
    description: "Dark with warm brown-red undertones",
    preview: ["#12100e", "#161210", "#2a2220", "#ffffff12"],
    vars: {
      "--background": "#12100e", "--foreground": "#f0ece8",
      "--card": "#ffffff08", "--card-foreground": "#f0ece8",
      "--popover": "#1c1816ee", "--popover-foreground": "#f0ece8",
      "--muted": "#2a2220", "--muted-foreground": "#8a7e78",
      "--accent": "#ffffff08", "--accent-foreground": "#f0ece8",
      "--border": "#ffffff12", "--input": "#ffffff10",
      "--glass": "#ffffff08", "--glass-hover": "#ffffff12", "--glass-border": "#ffffff12",
      "--sidebar": "#161210", "--sidebar-foreground": "#a09690",
      "--sidebar-accent": "#2a2220", "--sidebar-accent-foreground": "#f0ece8", "--sidebar-border": "#2a2220",
    },
  },
  {
    name: "Forest Night",
    description: "Dark with deep green tint",
    preview: ["#0a100e", "#0e1410", "#1c2a24", "#ffffff12"],
    vars: {
      "--background": "#0a100e", "--foreground": "#e8f0ec",
      "--card": "#ffffff08", "--card-foreground": "#e8f0ec",
      "--popover": "#10181eee", "--popover-foreground": "#e8f0ec",
      "--muted": "#1c2a24", "--muted-foreground": "#6e8a7a",
      "--accent": "#ffffff08", "--accent-foreground": "#e8f0ec",
      "--border": "#ffffff12", "--input": "#ffffff10",
      "--glass": "#ffffff08", "--glass-hover": "#ffffff12", "--glass-border": "#ffffff12",
      "--sidebar": "#0e1410", "--sidebar-foreground": "#8aaa98",
      "--sidebar-accent": "#1c2a24", "--sidebar-accent-foreground": "#e8f0ec", "--sidebar-border": "#1c2a24",
    },
  },
  {
    name: "Deep Purple",
    description: "Rich dark violet undertones",
    preview: ["#0e0a14", "#120e18", "#261e32", "#ffffff12"],
    vars: {
      "--background": "#0e0a14", "--foreground": "#ece8f2",
      "--card": "#ffffff08", "--card-foreground": "#ece8f2",
      "--popover": "#16101eee", "--popover-foreground": "#ece8f2",
      "--muted": "#261e32", "--muted-foreground": "#8a78a0",
      "--accent": "#ffffff08", "--accent-foreground": "#ece8f2",
      "--border": "#ffffff12", "--input": "#ffffff10",
      "--glass": "#ffffff08", "--glass-hover": "#ffffff12", "--glass-border": "#ffffff12",
      "--sidebar": "#120e18", "--sidebar-foreground": "#a898b8",
      "--sidebar-accent": "#261e32", "--sidebar-accent-foreground": "#ece8f2", "--sidebar-border": "#261e32",
    },
  },
]

// ── Glow Blobs ──────────────────────────────
export interface GlowBlob {
  x: number
  y: number
  size: number
  blur: number
  opacity: number
  intensity: number
  color: string
  scaleX: number
  scaleY: number
  rotate: number
}

export const defaultGlowBlobs: GlowBlob[] = [
  { x: 25, y: 0, size: 500, blur: 120, opacity: 8, intensity: 100, color: "primary", scaleX: 100, scaleY: 100, rotate: 0 },
  { x: 75, y: 100, size: 500, blur: 120, opacity: 8, intensity: 100, color: "secondary", scaleX: 100, scaleY: 100, rotate: 0 },
  { x: 50, y: 50, size: 800, blur: 150, opacity: 3, intensity: 100, color: "primary", scaleX: 100, scaleY: 100, rotate: 0 },
]

export interface GlowPreset {
  name: string
  description: string
  enabled: boolean
  intensity: number
}

export const glowPresets: GlowPreset[] = [
  { name: "Off", description: "No ambient glows", enabled: false, intensity: 0 },
  { name: "Subtle", description: "Barely visible, gentle ambience", enabled: true, intensity: 40 },
  { name: "Standard", description: "Default glow intensity", enabled: true, intensity: 100 },
  { name: "Vivid", description: "Strong, vibrant glows", enabled: true, intensity: 180 },
]

// All palette CSS var keys (for cleanup)
export const paletteVarKeys = [
  "--background", "--foreground", "--card", "--card-foreground",
  "--popover", "--popover-foreground", "--muted", "--muted-foreground",
  "--accent", "--accent-foreground", "--border", "--input",
  "--glass", "--glass-hover", "--glass-border",
  "--sidebar", "--sidebar-foreground", "--sidebar-accent",
  "--sidebar-accent-foreground", "--sidebar-border",
]
