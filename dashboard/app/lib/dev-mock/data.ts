/**
 * Canned response data for the dev-mock layer. Used only when
 * `auth_token === DEV_MOCK_TOKEN` and the environment is `import.meta.env.DEV`.
 *
 * Shape-loose by design — these are stubs to make pages render. Anything that
 * blows up on a missing/null field needs to be fixed in the page first; we do
 * not encode strict DTO contracts here.
 */

export const DEV_MOCK_TOKEN = "dev-mock-token-v1"

export const mockUser = {
  username: "dev",
  email: "dev@local",
  role: "ADMIN",
  permissions: [
    "audit.view", "networks.view", "networks.create", "networks.edit", "networks.delete",
    "modules.view", "modules.install", "modules.uninstall",
    "templates.view", "templates.edit",
    "groups.view", "groups.edit", "groups.delete",
    "nodes.view", "nodes.edit", "nodes.delete",
    "instances.view", "instances.stop", "instances.delete",
    "crashes.view",
    "users.view", "users.edit",
    "*",
  ],
  createdAt: "2026-01-01T00:00:00Z",
  minecraftUuid: null,
  minecraftUsername: null,
}

export const mockOverview = {
  nodeCount: 7,
  playerCount: 412,
  instanceCount: 28,
  groupCount: 9,
  cpuUsage: 0.41,
  memoryUsage: 0.47,
  uptime: 5 * 24 * 3600 * 1000 + 14 * 3600 * 1000,
}

export const mockNodes = [
  {
    id: "node-1",
    type: "CONNECTED",
    status: "ONLINE",
    address: "10.0.1.21:8443",
    cpuUsage: 0.42,
    totalMemoryMb: 32_768,
    usedMemoryMb: 12_500,
    freeDiskMb: 480_000,
    totalDiskMb: 1_000_000,
    instanceCount: 3,
    connectedSince: new Date(Date.now() - 28 * 3600 * 1000).toISOString(),
    lastHeartbeat: new Date(Date.now() - 4_000).toISOString(),
    labels: { zone: "eu-central-1a", tier: "primary" },
    hostInfo: {
      osName: "Linux",
      osVersion: "6.6.30",
      arch: "x86_64",
      cpuModel: "AMD EPYC 7763 64-Core Processor",
      cpuPhysicalCores: 16,
      cpuLogicalCores: 32,
      cpuMaxFreqHz: 3_500_000_000,
      javaVersion: "Temurin 21.0.4",
      javaVendor: "Eclipse Adoptium",
      javaRuntime: "OpenJDK 64-Bit Server VM",
      javaGc: "G1",
    },
  },
  {
    id: "node-2",
    type: "CONNECTED",
    status: "DRAINING",
    address: "10.0.1.22:8443",
    cpuUsage: 0.18,
    totalMemoryMb: 32_768,
    usedMemoryMb: 4_200,
    freeDiskMb: 510_000,
    totalDiskMb: 1_000_000,
    instanceCount: 1,
    connectedSince: new Date(Date.now() - 50 * 3600 * 1000).toISOString(),
    lastHeartbeat: new Date(Date.now() - 1_800).toISOString(),
    labels: { zone: "eu-central-1b", tier: "primary" },
    hostInfo: {
      osName: "Linux",
      osVersion: "6.6.30",
      arch: "x86_64",
      cpuModel: "AMD EPYC 7763 64-Core Processor",
      cpuPhysicalCores: 16,
      cpuLogicalCores: 32,
      cpuMaxFreqHz: 3_500_000_000,
      javaVersion: "Temurin 21.0.4",
      javaVendor: "Eclipse Adoptium",
      javaRuntime: "OpenJDK 64-Bit Server VM",
      javaGc: "G1",
    },
  },
  {
    id: "node-3",
    type: "CONNECTED",
    status: "ONLINE",
    address: "10.0.1.23:8443",
    cpuUsage: 0.27,
    totalMemoryMb: 32_768,
    usedMemoryMb: 8_900,
    freeDiskMb: 460_000,
    totalDiskMb: 1_000_000,
    instanceCount: 4,
    connectedSince: new Date(Date.now() - 72 * 3600 * 1000).toISOString(),
    lastHeartbeat: new Date(Date.now() - 2_500).toISOString(),
    labels: { zone: "eu-central-1c", tier: "primary" },
    hostInfo: {
      osName: "Linux",
      osVersion: "6.6.30",
      arch: "x86_64",
      cpuModel: "AMD EPYC 7763 64-Core Processor",
      cpuPhysicalCores: 16,
      cpuLogicalCores: 32,
      cpuMaxFreqHz: 3_500_000_000,
      javaVersion: "Temurin 21.0.4",
      javaVendor: "Eclipse Adoptium",
      javaRuntime: "OpenJDK 64-Bit Server VM",
      javaGc: "G1",
    },
  },
  {
    id: "node-4",
    type: "CONNECTED",
    status: "ONLINE",
    address: "10.0.1.24:8443",
    cpuUsage: 0.61,
    totalMemoryMb: 65_536,
    usedMemoryMb: 38_400,
    freeDiskMb: 720_000,
    totalDiskMb: 2_000_000,
    instanceCount: 7,
    connectedSince: new Date(Date.now() - 96 * 3600 * 1000).toISOString(),
    lastHeartbeat: new Date(Date.now() - 1_200).toISOString(),
    labels: { zone: "eu-central-1a", tier: "burst" },
    hostInfo: {
      osName: "Linux", osVersion: "6.6.34", arch: "x86_64",
      cpuModel: "AMD EPYC 9354 32-Core Processor",
      cpuPhysicalCores: 32, cpuLogicalCores: 64, cpuMaxFreqHz: 3_800_000_000,
      javaVersion: "Temurin 21.0.5",
      javaVendor: "Eclipse Adoptium",
      javaRuntime: "OpenJDK 64-Bit Server VM",
      javaGc: "ZGC",
    },
  },
  {
    id: "node-5",
    type: "CONNECTED",
    status: "CORDONED",
    address: "10.0.2.31:8443",
    cpuUsage: 0.08,
    totalMemoryMb: 16_384,
    usedMemoryMb: 1_900,
    freeDiskMb: 220_000,
    totalDiskMb: 500_000,
    instanceCount: 1,
    connectedSince: new Date(Date.now() - 5 * 24 * 3600 * 1000).toISOString(),
    lastHeartbeat: new Date(Date.now() - 6_000).toISOString(),
    labels: { zone: "eu-west-1", tier: "secondary" },
    hostInfo: {
      osName: "Linux", osVersion: "6.6.30", arch: "x86_64",
      cpuModel: "Intel Xeon Gold 6248",
      cpuPhysicalCores: 8, cpuLogicalCores: 16, cpuMaxFreqHz: 2_500_000_000,
      javaVersion: "Temurin 21.0.4",
      javaVendor: "GraalVM Community",
      javaRuntime: "GraalVM CE",
      javaGc: "Parallel",
    },
  },
  {
    id: "node-6",
    type: "DISCONNECTED",
    status: "OFFLINE",
    firstSeen: new Date(Date.now() - 14 * 24 * 3600 * 1000).toISOString(),
    lastSeen: new Date(Date.now() - 38 * 60 * 60 * 1000).toISOString(),
  },
  {
    id: "node-pending-east-1",
    type: "PENDING",
    status: "PENDING",
    tokenId: "tok-a1b2c3",
    joinToken: "dev-mock-join-token-east-1",
    expiresAt: new Date(Date.now() + 86_400_000).toISOString(),
  },
]

export const mockGroups = [
  {
    name: "lobby",
    platform: "Paper",
    platformVersion: "1.21.1",
    runtimeTarget: { platform: "Paper", platformVersion: "1.21.1", family: "server" },
    runningInstances: 2,
    minInstances: 2,
    maxInstances: 6,
    maxPlayers: 200,
    totalPlayers: 87,
    scalingMode: "DYNAMIC",
    scaleUpThreshold: 75,
    scaleDownAfterSeconds: 300,
    scaleCooldownSeconds: 60,
    predictiveScaling: false,
    scaleUpMargin: 0,
    burstCeiling: 0,
    routing: "LEAST_LOADED",
    drainOnShutdown: true,
    startupTimeoutSeconds: 90,
    shutdownGraceSeconds: 30,
    static: false,
    staticInstanceNames: [],
    portRangeStart: 25_565,
    portRangeEnd: 25_575,
    memoryMb: 2_048,
    jvmArgs: ["-XX:+UseG1GC"],
    env: {},
    templates: [],
    nodeAffinity: [],
    nodeAntiAffinity: [],
    spreadConstraint: "",
    dependsOn: [],
    fallbackGroup: null,
    defaultGroup: true,
    priority: 100,
    maintenance: false,
    updateStrategy: "ROLLING",
    maxLifetimeSeconds: 0,
  },
  {
    name: "survival",
    platform: "Paper",
    platformVersion: "1.21.1",
    runtimeTarget: { platform: "Paper", platformVersion: "1.21.1", family: "server" },
    runningInstances: 4,
    minInstances: 2,
    maxInstances: 8,
    maxPlayers: 800,
    totalPlayers: 60,
    scalingMode: "DYNAMIC",
    scaleUpThreshold: 70,
    scaleDownAfterSeconds: 600,
    scaleCooldownSeconds: 120,
    predictiveScaling: true,
    scaleUpMargin: 1,
    burstCeiling: 10,
    routing: "FILL",
    drainOnShutdown: true,
    startupTimeoutSeconds: 120,
    shutdownGraceSeconds: 60,
    static: false,
    staticInstanceNames: [],
    portRangeStart: 25_576,
    portRangeEnd: 25_600,
    memoryMb: 4_096,
    jvmArgs: ["-XX:+UseZGC"],
    env: {},
    templates: ["world-base"],
    nodeAffinity: ["tier=primary"],
    nodeAntiAffinity: [],
    spreadConstraint: "zone",
    dependsOn: [],
    fallbackGroup: "lobby",
    defaultGroup: false,
    priority: 80,
    maintenance: false,
    updateStrategy: "ROLLING",
    maxLifetimeSeconds: 0,
  },
  {
    name: "proxy",
    platform: "Velocity",
    platformVersion: "3.3.0",
    runtimeTarget: { platform: "Velocity", platformVersion: "3.3.0", family: "proxy" },
    runningInstances: 2,
    minInstances: 2,
    maxInstances: 4,
    maxPlayers: 2_000,
    totalPlayers: 147,
    scalingMode: "STATIC",
    scaleUpThreshold: 0,
    scaleDownAfterSeconds: 0,
    scaleCooldownSeconds: 0,
    predictiveScaling: false,
    scaleUpMargin: 0,
    burstCeiling: 0,
    routing: "ROUND_ROBIN",
    drainOnShutdown: true,
    startupTimeoutSeconds: 60,
    shutdownGraceSeconds: 30,
    static: false,
    staticInstanceNames: [],
    portRangeStart: 25_500,
    portRangeEnd: 25_510,
    memoryMb: 1_024,
    jvmArgs: [],
    env: {},
    templates: [],
    nodeAffinity: [],
    nodeAntiAffinity: [],
    spreadConstraint: "",
    dependsOn: [],
    fallbackGroup: null,
    defaultGroup: false,
    priority: 200,
    maintenance: false,
    updateStrategy: "BLUE_GREEN",
    maxLifetimeSeconds: 0,
  },
  {
    name: "minigame-skywars",
    platform: "Paper",
    platformVersion: "1.21.1",
    runtimeTarget: { platform: "Paper", platformVersion: "1.21.1", family: "server" },
    runningInstances: 0,
    minInstances: 0,
    maxInstances: 12,
    maxPlayers: 240,
    totalPlayers: 0,
    scalingMode: "DYNAMIC",
    scaleUpThreshold: 80,
    scaleDownAfterSeconds: 180,
    scaleCooldownSeconds: 30,
    predictiveScaling: false,
    scaleUpMargin: 0,
    burstCeiling: 0,
    routing: "LEAST_LOADED",
    drainOnShutdown: false,
    startupTimeoutSeconds: 60,
    shutdownGraceSeconds: 10,
    static: false,
    staticInstanceNames: [],
    portRangeStart: 25_700,
    portRangeEnd: 25_799,
    memoryMb: 1_536,
    jvmArgs: [],
    env: {},
    templates: [],
    nodeAffinity: [],
    nodeAntiAffinity: [],
    spreadConstraint: "",
    dependsOn: [],
    fallbackGroup: null,
    defaultGroup: false,
    priority: 50,
    maintenance: true,
    updateStrategy: "RECREATE",
    maxLifetimeSeconds: 1_800,
  },
  {
    name: "bedwars",
    platform: "Paper",
    platformVersion: "1.21.1",
    runtimeTarget: { platform: "Paper", platformVersion: "1.21.1", family: "server" },
    runningInstances: 6, minInstances: 4, maxInstances: 12, maxPlayers: 480, totalPlayers: 142,
    scalingMode: "DYNAMIC", scaleUpThreshold: 70, scaleDownAfterSeconds: 240, scaleCooldownSeconds: 60,
    predictiveScaling: true, scaleUpMargin: 1, burstCeiling: 16,
    routing: "LEAST_LOADED", drainOnShutdown: true,
    startupTimeoutSeconds: 60, shutdownGraceSeconds: 20,
    static: false, staticInstanceNames: [], portRangeStart: 25_800, portRangeEnd: 25_899,
    memoryMb: 1_536, jvmArgs: ["-XX:+UseG1GC"], env: {},
    templates: ["bedwars-base"], nodeAffinity: ["tier=primary"], nodeAntiAffinity: [],
    spreadConstraint: "zone", dependsOn: [], fallbackGroup: "lobby", defaultGroup: false,
    priority: 90, maintenance: false, updateStrategy: "ROLLING", maxLifetimeSeconds: 0,
  },
  {
    name: "creative",
    platform: "Paper",
    platformVersion: "1.21.1",
    runtimeTarget: { platform: "Paper", platformVersion: "1.21.1", family: "server" },
    runningInstances: 2, minInstances: 2, maxInstances: 2, maxPlayers: 100, totalPlayers: 31,
    scalingMode: "STATIC", scaleUpThreshold: 0, scaleDownAfterSeconds: 0, scaleCooldownSeconds: 0,
    predictiveScaling: false, scaleUpMargin: 0, burstCeiling: 0,
    routing: "FIXED", drainOnShutdown: true,
    startupTimeoutSeconds: 90, shutdownGraceSeconds: 60,
    static: true, staticInstanceNames: ["creative-01", "creative-02"], portRangeStart: 25_900, portRangeEnd: 25_910,
    memoryMb: 4_096, jvmArgs: ["-Xmx4G"], env: {},
    templates: ["creative-base"], nodeAffinity: [], nodeAntiAffinity: [],
    spreadConstraint: "", dependsOn: [], fallbackGroup: null, defaultGroup: false,
    priority: 60, maintenance: false, updateStrategy: "BLUE_GREEN", maxLifetimeSeconds: 0,
  },
  {
    name: "arena-pvp",
    platform: "Paper",
    platformVersion: "1.21.1",
    runtimeTarget: { platform: "Paper", platformVersion: "1.21.1", family: "server" },
    runningInstances: 5, minInstances: 0, maxInstances: 20, maxPlayers: 600, totalPlayers: 89,
    scalingMode: "DYNAMIC", scaleUpThreshold: 80, scaleDownAfterSeconds: 120, scaleCooldownSeconds: 30,
    predictiveScaling: false, scaleUpMargin: 0, burstCeiling: 0,
    routing: "LEAST_LOADED", drainOnShutdown: false,
    startupTimeoutSeconds: 45, shutdownGraceSeconds: 5,
    static: false, staticInstanceNames: [], portRangeStart: 26_000, portRangeEnd: 26_099,
    memoryMb: 1_024, jvmArgs: [], env: {},
    templates: [], nodeAffinity: [], nodeAntiAffinity: ["tier=secondary"],
    spreadConstraint: "", dependsOn: [], fallbackGroup: null, defaultGroup: false,
    priority: 70, maintenance: false, updateStrategy: "ROLLING", maxLifetimeSeconds: 600,
  },
  {
    name: "dev-staging",
    platform: "Paper",
    platformVersion: "1.21.1",
    runtimeTarget: { platform: "Paper", platformVersion: "1.21.1", family: "server" },
    runningInstances: 1, minInstances: 0, maxInstances: 1, maxPlayers: 20, totalPlayers: 0,
    scalingMode: "MANUAL", scaleUpThreshold: 0, scaleDownAfterSeconds: 0, scaleCooldownSeconds: 0,
    predictiveScaling: false, scaleUpMargin: 0, burstCeiling: 0,
    routing: "FIXED", drainOnShutdown: false,
    startupTimeoutSeconds: 120, shutdownGraceSeconds: 0,
    static: false, staticInstanceNames: [], portRangeStart: 26_100, portRangeEnd: 26_109,
    memoryMb: 2_048, jvmArgs: [], env: { ENV: "staging" },
    templates: [], nodeAffinity: ["tier=secondary"], nodeAntiAffinity: [],
    spreadConstraint: "", dependsOn: [], fallbackGroup: null, defaultGroup: false,
    priority: 30, maintenance: true, updateStrategy: "RECREATE", maxLifetimeSeconds: 0,
  },
  {
    name: "edge-proxy",
    platform: "BungeeCord",
    platformVersion: "1.21",
    runtimeTarget: { platform: "BungeeCord", platformVersion: "1.21", family: "proxy" },
    runningInstances: 1, minInstances: 1, maxInstances: 2, maxPlayers: 1_000, totalPlayers: 63,
    scalingMode: "STATIC", scaleUpThreshold: 0, scaleDownAfterSeconds: 0, scaleCooldownSeconds: 0,
    predictiveScaling: false, scaleUpMargin: 0, burstCeiling: 0,
    routing: "ROUND_ROBIN", drainOnShutdown: true,
    startupTimeoutSeconds: 30, shutdownGraceSeconds: 30,
    static: false, staticInstanceNames: [], portRangeStart: 25_565, portRangeEnd: 25_565,
    memoryMb: 768, jvmArgs: [], env: {},
    templates: [], nodeAffinity: [], nodeAntiAffinity: [],
    spreadConstraint: "", dependsOn: [], fallbackGroup: null, defaultGroup: false,
    priority: 180, maintenance: false, updateStrategy: "BLUE_GREEN", maxLifetimeSeconds: 0,
  },
]

export const mockInstances = [
  { id: "lobby-01",    group: "lobby",    node: "node-1", state: "RUNNING",   port: 25565, playerCount: 47, uptimeMs: 7 * 3600 * 1000,  startedAt: new Date(Date.now() - 7 * 3600 * 1000).toISOString(),  deploymentRevision: 12 },
  { id: "lobby-02",    group: "lobby",    node: "node-3", state: "RUNNING",   port: 25566, playerCount: 40, uptimeMs: 6 * 3600 * 1000,  startedAt: new Date(Date.now() - 6 * 3600 * 1000).toISOString(),  deploymentRevision: 12 },
  { id: "survival-01", group: "survival", node: "node-1", state: "RUNNING",   port: 25576, playerCount: 18, uptimeMs: 50 * 3600 * 1000, startedAt: new Date(Date.now() - 50 * 3600 * 1000).toISOString(), deploymentRevision: 7 },
  { id: "survival-02", group: "survival", node: "node-3", state: "RUNNING",   port: 25577, playerCount: 22, uptimeMs: 50 * 3600 * 1000, startedAt: new Date(Date.now() - 50 * 3600 * 1000).toISOString(), deploymentRevision: 7 },
  { id: "survival-03", group: "survival", node: "node-3", state: "RUNNING",   port: 25578, playerCount: 12, uptimeMs: 12 * 3600 * 1000, startedAt: new Date(Date.now() - 12 * 3600 * 1000).toISOString(), deploymentRevision: 7 },
  { id: "survival-04", group: "survival", node: "node-2", state: "DRAINING",  port: 25579, playerCount: 8,  uptimeMs: 30 * 3600 * 1000, startedAt: new Date(Date.now() - 30 * 3600 * 1000).toISOString(), deploymentRevision: 7 },
  { id: "proxy-01",    group: "proxy",    node: "node-1", state: "RUNNING",   port: 25500, playerCount: 73, uptimeMs: 72 * 3600 * 1000, startedAt: new Date(Date.now() - 72 * 3600 * 1000).toISOString(), deploymentRevision: 3 },
  { id: "proxy-02",    group: "proxy",    node: "node-3", state: "RUNNING",   port: 25501, playerCount: 74, uptimeMs: 72 * 3600 * 1000, startedAt: new Date(Date.now() - 72 * 3600 * 1000).toISOString(), deploymentRevision: 3 },

  // Bedwars — busy peak hours
  { id: "bedwars-01", group: "bedwars", node: "node-4", state: "RUNNING",  port: 25800, playerCount: 32, uptimeMs:  4 * 3600 * 1000, startedAt: new Date(Date.now() -  4 * 3600 * 1000).toISOString(), deploymentRevision: 4 },
  { id: "bedwars-02", group: "bedwars", node: "node-4", state: "RUNNING",  port: 25801, playerCount: 28, uptimeMs:  3 * 3600 * 1000, startedAt: new Date(Date.now() -  3 * 3600 * 1000).toISOString(), deploymentRevision: 4 },
  { id: "bedwars-03", group: "bedwars", node: "node-3", state: "RUNNING",  port: 25802, playerCount: 24, uptimeMs:  2 * 3600 * 1000, startedAt: new Date(Date.now() -  2 * 3600 * 1000).toISOString(), deploymentRevision: 4 },
  { id: "bedwars-04", group: "bedwars", node: "node-1", state: "RUNNING",  port: 25803, playerCount: 22, uptimeMs:  1 * 3600 * 1000, startedAt: new Date(Date.now() -  1 * 3600 * 1000).toISOString(), deploymentRevision: 4 },
  { id: "bedwars-05", group: "bedwars", node: "node-4", state: "STARTING", port: 25804, playerCount:  0, uptimeMs:    20_000,        startedAt: new Date(Date.now() -    20_000).toISOString(),       deploymentRevision: 4 },
  { id: "bedwars-06", group: "bedwars", node: "node-4", state: "RUNNING",  port: 25805, playerCount: 36, uptimeMs:  5 * 3600 * 1000, startedAt: new Date(Date.now() -  5 * 3600 * 1000).toISOString(), deploymentRevision: 4 },

  // Creative — static, two pinned instances
  { id: "creative-01", group: "creative", node: "node-4", state: "RUNNING", port: 25900, playerCount: 16, uptimeMs: 24 * 3600 * 1000, startedAt: new Date(Date.now() - 24 * 3600 * 1000).toISOString(), deploymentRevision: 2 },
  { id: "creative-02", group: "creative", node: "node-4", state: "RUNNING", port: 25901, playerCount: 15, uptimeMs: 24 * 3600 * 1000, startedAt: new Date(Date.now() - 24 * 3600 * 1000).toISOString(), deploymentRevision: 2 },

  // Arena PvP — short-lived rounds
  { id: "arena-pvp-01", group: "arena-pvp", node: "node-3", state: "RUNNING",  port: 26_000, playerCount: 18, uptimeMs:  9 * 60_000,       startedAt: new Date(Date.now() -  9 * 60_000).toISOString(),       deploymentRevision: 1 },
  { id: "arena-pvp-02", group: "arena-pvp", node: "node-4", state: "RUNNING",  port: 26_001, playerCount: 22, uptimeMs:  4 * 60_000,       startedAt: new Date(Date.now() -  4 * 60_000).toISOString(),       deploymentRevision: 1 },
  { id: "arena-pvp-03", group: "arena-pvp", node: "node-1", state: "RUNNING",  port: 26_002, playerCount: 20, uptimeMs:  6 * 60_000,       startedAt: new Date(Date.now() -  6 * 60_000).toISOString(),       deploymentRevision: 1 },
  { id: "arena-pvp-04", group: "arena-pvp", node: "node-4", state: "RUNNING",  port: 26_003, playerCount: 14, uptimeMs:  2 * 60_000,       startedAt: new Date(Date.now() -  2 * 60_000).toISOString(),       deploymentRevision: 1 },
  { id: "arena-pvp-05", group: "arena-pvp", node: "node-3", state: "STOPPING", port: 26_004, playerCount:  0, uptimeMs: 12 * 60_000,       startedAt: new Date(Date.now() - 12 * 60_000).toISOString(),       deploymentRevision: 1 },
  { id: "arena-pvp-06", group: "arena-pvp", node: "node-1", state: "CRASHED",  port: 26_005, playerCount:  0, uptimeMs:  3 * 60_000,       startedAt: new Date(Date.now() -  4 * 60_000).toISOString(),       deploymentRevision: 1 },

  // Dev staging — single, in maintenance
  { id: "dev-staging-01", group: "dev-staging", node: "node-5", state: "RUNNING", port: 26_100, playerCount: 0, uptimeMs: 5 * 24 * 3600 * 1000, startedAt: new Date(Date.now() - 5 * 24 * 3600 * 1000).toISOString(), deploymentRevision: 18 },

  // BungeeCord edge proxy
  { id: "edge-proxy-01", group: "edge-proxy", node: "node-4", state: "RUNNING", port: 25_565, playerCount: 63, uptimeMs: 96 * 3600 * 1000, startedAt: new Date(Date.now() - 96 * 3600 * 1000).toISOString(), deploymentRevision: 1 },

  // Stopped / scheduled — show non-active states in the list
  { id: "lobby-03",    group: "lobby",    node: "node-3", state: "STOPPED",   port: 25567, playerCount: 0, uptimeMs: 0, startedAt: new Date(Date.now() - 90 * 60_000).toISOString(), deploymentRevision: 12 },
  { id: "survival-05", group: "survival", node: "node-5", state: "SCHEDULED", port: 25580, playerCount: 0, uptimeMs: 0, startedAt: new Date(Date.now() -  3 * 60_000).toISOString(), deploymentRevision: 7 },
]

export const mockNetworks = [
  { name: "main", routing: "LEAST_LOADED", lobbyGroup: "lobby", fallbackGroup: "lobby", proxyGroups: ["proxy"], gameGroups: ["lobby", "survival", "bedwars", "creative", "arena-pvp"] },
  { name: "edge", routing: "ROUND_ROBIN",  lobbyGroup: "lobby", fallbackGroup: "lobby", proxyGroups: ["edge-proxy"], gameGroups: ["lobby"] },
]

export const mockTemplates = [
  { name: "base",         platform: "Paper",    platformVersion: "*",       parent: null,   filesCount: 12, totalBytes: 245_000,   versionsCount: 4, latestVersionHash: "a1b2c3d4", createdAt: "2026-01-15T00:00:00Z", updatedAt: "2026-04-22T00:00:00Z" },
  { name: "base-paper",   platform: "Paper",    platformVersion: "1.21.1",  parent: "base", filesCount: 18, totalBytes: 1_200_000, versionsCount: 7, latestVersionHash: "e5f6g7h8", createdAt: "2026-01-20T00:00:00Z", updatedAt: "2026-05-02T00:00:00Z" },
  { name: "lobby",        platform: "Paper",    platformVersion: "1.21.1",  parent: "base-paper", filesCount: 24, totalBytes: 4_800_000, versionsCount: 12, latestVersionHash: "i9j0k1l2", createdAt: "2026-01-25T00:00:00Z", updatedAt: "2026-05-08T00:00:00Z" },
  { name: "world-base",   platform: "Paper",    platformVersion: "1.21.1",  parent: "base-paper", filesCount: 84, totalBytes: 110_000_000, versionsCount: 5, latestVersionHash: "m3n4o5p6", createdAt: "2026-02-01T00:00:00Z", updatedAt: "2026-05-04T00:00:00Z" },
  { name: "base-velocity", platform: "Velocity", platformVersion: "3.3.0",   parent: "base", filesCount: 8,  totalBytes: 95_000,    versionsCount: 3, latestVersionHash: "q7r8s9t0", createdAt: "2026-02-05T00:00:00Z", updatedAt: "2026-04-12T00:00:00Z" },
  { name: "bedwars-base",  platform: "Paper",    platformVersion: "1.21.1",  parent: "base-paper", filesCount: 36, totalBytes: 6_500_000, versionsCount: 9, latestVersionHash: "u1v2w3x4", createdAt: "2026-02-12T00:00:00Z", updatedAt: "2026-05-09T00:00:00Z" },
  { name: "creative-base", platform: "Paper",    platformVersion: "1.21.1",  parent: "base-paper", filesCount: 14, totalBytes:   850_000, versionsCount: 4, latestVersionHash: "y5z6a7b8", createdAt: "2026-02-18T00:00:00Z", updatedAt: "2026-04-30T00:00:00Z" },
  { name: "pvp-arena",     platform: "Paper",    platformVersion: "1.21.1",  parent: "base-paper", filesCount: 22, totalBytes: 1_800_000, versionsCount: 6, latestVersionHash: "c9d0e1f2", createdAt: "2026-02-28T00:00:00Z", updatedAt: "2026-05-08T00:00:00Z" },
  { name: "kit-shared",    platform: "Paper",    platformVersion: "*",       parent: "base", filesCount: 9,  totalBytes:   320_000, versionsCount: 2, latestVersionHash: "g3h4i5j6", createdAt: "2026-03-04T00:00:00Z", updatedAt: "2026-04-22T00:00:00Z" },
  { name: "bungeecord-edge", platform: "BungeeCord", platformVersion: "1.21", parent: null, filesCount: 6, totalBytes: 65_000, versionsCount: 2, latestVersionHash: "k7l8m9n0", createdAt: "2026-03-10T00:00:00Z", updatedAt: "2026-05-01T00:00:00Z" },
]

export const mockCatalog = [
  {
    platform: "Paper",
    category: "SERVER",
    configFormat: "yaml",
    versions: [
      { version: "1.21.1", downloadUrl: "https://example.invalid/paper-1.21.1.jar", recommended: true },
      { version: "1.21.0", downloadUrl: "https://example.invalid/paper-1.21.0.jar", recommended: false },
      { version: "1.20.6", downloadUrl: "https://example.invalid/paper-1.20.6.jar", recommended: false },
    ],
  },
  {
    platform: "Velocity",
    category: "PROXY",
    configFormat: "toml",
    versions: [
      { version: "3.3.0", downloadUrl: "https://example.invalid/velocity-3.3.0.jar", recommended: true },
      { version: "3.2.0", downloadUrl: "https://example.invalid/velocity-3.2.0.jar", recommended: false },
    ],
  },
  {
    platform: "Fabric",
    category: "SERVER",
    configFormat: "json",
    versions: [
      { version: "1.21.1", downloadUrl: "https://example.invalid/fabric-1.21.1.jar", recommended: true },
    ],
  },
]

export const mockCrashes = [
  { id: "crash-1", instanceId: "survival-09", group: "survival", node: "node-2", classification: "OOM",     exitCode: 137, causeSummary: "OutOfMemoryError: Java heap space",                     signature: "a1b2c3d4e5f6a7b8", uptimeMs: 4 * 3600 * 1000,  crashedAt: new Date(Date.now() - 6 * 3600 * 1000).toISOString(),   logTail: "[2026-05-09 18:42:11] INFO: Heap exhausted\njava.lang.OutOfMemoryError: Java heap space\n  at net.minecraft.world.level.chunk.ChunkAccess.<init>(ChunkAccess.java:84)\n  at …" },
  { id: "crash-2", instanceId: "lobby-04",    group: "lobby",    node: "node-1", classification: "ERROR",   exitCode: 1,   causeSummary: "NullPointerException: Cannot invoke \"World.getName()\"",   signature: "b2c3d4e5f6a7b8c9", uptimeMs: 12 * 60 * 1000,  crashedAt: new Date(Date.now() - 18 * 3600 * 1000).toISOString(),  logTail: "[2026-05-09 06:21:02] ERROR: Plugin 'CustomLobby' threw\njava.lang.NullPointerException: Cannot invoke \"World.getName()\" because \"world\" is null\n  at com.example.lobby.LobbyPlugin.onJoin(LobbyPlugin.java:42)" },
  { id: "crash-3", instanceId: "survival-07", group: "survival", node: "node-3", classification: "SIGKILL", exitCode: 137, causeSummary: "Killed (SIGKILL)",                                       signature: "c3d4e5f6a7b8c9d0", uptimeMs: 24 * 3600 * 1000, crashedAt: new Date(Date.now() - 26 * 3600 * 1000).toISOString(), logTail: "[2026-05-08 14:00:00] INFO: Received SIGKILL from supervisor (oom-killer)" },
  { id: "crash-4", instanceId: "lobby-03",    group: "lobby",    node: "node-1", classification: "SIGTERM", exitCode: 143, causeSummary: "Terminated (SIGTERM)",                                   signature: "d4e5f6a7b8c9d0e1", uptimeMs: 8 * 3600 * 1000,  crashedAt: new Date(Date.now() - 30 * 3600 * 1000).toISOString(),  logTail: "[2026-05-08 10:00:00] INFO: Received SIGTERM, shutting down" },
  { id: "crash-5", instanceId: "arena-pvp-06", group: "arena-pvp", node: "node-1", classification: "ERROR",   exitCode: 1,   causeSummary: "ArrayIndexOutOfBoundsException: Index 64 out of bounds",  signature: "e5f6a7b8c9d0e1f2", uptimeMs:  3 * 60_000,        crashedAt: new Date(Date.now() -   4 * 60_000).toISOString(),     logTail: "[2026-05-10 14:32:18] ERROR: WorldGenerator threw\nat com.example.pvp.ArenaGen.generate(ArenaGen.java:128)\nCaused by: java.lang.ArrayIndexOutOfBoundsException: Index 64 out of bounds for length 64" },
  { id: "crash-6", instanceId: "bedwars-05",  group: "bedwars",  node: "node-4", classification: "OOM",     exitCode: 137, causeSummary: "OutOfMemoryError: GC overhead limit exceeded",          signature: "f6a7b8c9d0e1f2a3", uptimeMs: 18 * 60_000,         crashedAt: new Date(Date.now() -  90 * 60_000).toISOString(),     logTail: "[2026-05-10 12:48:02] WARN: GC overhead limit exceeded\njava.lang.OutOfMemoryError: GC overhead limit exceeded\n  at net.minecraft.server.…" },
  { id: "crash-7", instanceId: "creative-03", group: "creative", node: "node-4", classification: "SIGKILL", exitCode: 137, causeSummary: "Killed (SIGKILL)",                                       signature: "c3d4e5f6a7b8c9d0", uptimeMs: 36 * 3600 * 1000,    crashedAt: new Date(Date.now() -  60 * 3600 * 1000).toISOString(),logTail: "[2026-05-08 02:14:08] INFO: oom-killer chose pid 14122 (java)" },
  { id: "crash-8", instanceId: "survival-08", group: "survival", node: "node-3", classification: "ERROR",   exitCode: 1,   causeSummary: "ConcurrentModificationException",                       signature: "a7b8c9d0e1f2a3b4", uptimeMs:  4 * 3600 * 1000,    crashedAt: new Date(Date.now() -   8 * 3600 * 1000).toISOString(), logTail: "[2026-05-10 06:11:23] FATAL: ConcurrentModificationException in TickThread\nat java.util.ArrayList$Itr.checkForComodification(ArrayList.java:1043)" },
  { id: "crash-9", instanceId: "lobby-05",    group: "lobby",    node: "node-1", classification: "SIGTERM", exitCode: 143, causeSummary: "Terminated (SIGTERM)",                                   signature: "d4e5f6a7b8c9d0e1", uptimeMs:  6 * 3600 * 1000,    crashedAt: new Date(Date.now() - 100 * 3600 * 1000).toISOString(),logTail: "[2026-05-06 12:30:11] INFO: Graceful shutdown — drain initiated" },
]

export const mockCrashTrend = (() => {
  const buckets = 24
  const bucketSeconds = 3600
  const now = Date.now()
  const windowEnd = new Date(now)
  const windowStart = new Date(now - buckets * bucketSeconds * 1000)
  const counts = [0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 0, 2, 0, 0, 0, 0, 1, 0, 0, 1, 2]
  const cls = ["OOM", "ERROR", "SIGKILL", "SIGTERM"]
  return {
    windowStart: windowStart.toISOString(),
    windowEnd: windowEnd.toISOString(),
    windowSeconds: buckets * bucketSeconds,
    bucketSeconds,
    total: counts.reduce((a, b) => a + b, 0),
    totalsByClassification: { OOM: 3, ERROR: 2, SIGKILL: 2, SIGTERM: 2 } as Record<string, number>,
    buckets: counts.map((count, i) => ({
      ts: new Date(windowStart.getTime() + i * bucketSeconds * 1000).toISOString(),
      count,
      byClassification: count === 0
        ? {} as Record<string, number>
        : { [cls[i % cls.length]!]: count } as Record<string, number>,
    })),
  }
})()

export const mockAudit = [
  { id: "audit-1", actor: "dev",      action: "GROUP_UPDATED",       resource: "groups/survival",   timestamp: new Date(Date.now() - 1_200_000).toISOString(), ip: "10.0.0.5",  metadata: { field: "maxInstances", from: 6, to: 8 } },
  { id: "audit-2", actor: "dev",      action: "INSTANCE_STOPPED",    resource: "instances/lobby-04", timestamp: new Date(Date.now() - 5_400_000).toISOString(), ip: "10.0.0.5",  metadata: { reason: "manual" } },
  { id: "audit-3", actor: "deployer", action: "DEPLOYMENT_TRIGGERED",resource: "groups/survival",   timestamp: new Date(Date.now() - 21_600_000).toISOString(), ip: "10.0.0.7", metadata: { revision: 7 } },
  { id: "audit-4",  actor: "dev",      action: "NODE_DRAINED",         resource: "nodes/node-2",         timestamp: new Date(Date.now() - 28_800_000).toISOString(),    ip: "10.0.0.5",  metadata: {} },
  { id: "audit-5",  actor: "deployer", action: "TEMPLATE_UPDATED",      resource: "templates/lobby",       timestamp: new Date(Date.now() - 6 * 3600 * 1000).toISOString(), ip: "10.0.0.7",  metadata: { fromHash: "h7g6f5e4", toHash: "i9j0k1l2" } },
  { id: "audit-6",  actor: "dev",      action: "GROUP_CREATED",         resource: "groups/bedwars",        timestamp: new Date(Date.now() - 30 * 3600 * 1000).toISOString(), ip: "10.0.0.5", metadata: {} },
  { id: "audit-7",  actor: "dev",      action: "USER_CREATED",          resource: "users/operator",        timestamp: new Date(Date.now() - 5 * 24 * 3600 * 1000).toISOString(), ip: "10.0.0.5", metadata: { role: "OPERATOR" } },
  { id: "audit-8",  actor: "deployer", action: "DEPLOYMENT_TRIGGERED",  resource: "groups/bedwars",        timestamp: new Date(Date.now() - 4 * 3600 * 1000).toISOString(), ip: "10.0.0.7", metadata: { revision: 4 } },
  { id: "audit-9",  actor: "dev",      action: "MAINTENANCE_TOGGLED",   resource: "groups/minigame-skywars", timestamp: new Date(Date.now() - 35 * 60_000).toISOString(), ip: "10.0.0.5", metadata: { enabled: true } },
  { id: "audit-10", actor: "dev",      action: "TOKEN_GENERATED",       resource: "admin/tokens",          timestamp: new Date(Date.now() - 2 * 24 * 3600 * 1000).toISOString(), ip: "10.0.0.5", metadata: { nodeId: "node-pending-east-1" } },
  { id: "audit-11", actor: "scheduler", action: "INSTANCE_DELETED",     resource: "instances/lobby-99",    timestamp: new Date(Date.now() - 9 * 3600 * 1000).toISOString(), ip: "internal", metadata: { reason: "stale" } },
]

export const mockModules: unknown[] = []
export const mockExtensions: unknown[] = []
export const mockCapabilities: unknown[] = []

export const mockUsers = [
  { username: "dev",       email: "dev@local",       role: "ADMIN",  permissions: ["*"], createdAt: "2026-01-01T00:00:00Z", lastLogin: new Date(Date.now() - 600_000).toISOString(), minecraftUuid: null, minecraftName: null },
  { username: "operator",  email: "ops@local",       role: "OPERATOR", permissions: ["instances.*", "nodes.*", "groups.view"], createdAt: "2026-02-12T00:00:00Z", lastLogin: new Date(Date.now() - 7_200_000).toISOString(), minecraftUuid: null, minecraftName: null },
  { username: "deployer",  email: "deploy@local",    role: "DEPLOYER", permissions: ["groups.edit", "templates.edit", "instances.start"], createdAt: "2026-03-04T00:00:00Z", lastLogin: new Date(Date.now() - 86_400_000).toISOString(), minecraftUuid: null, minecraftName: null },
  { username: "viewer",    email: "viewer@local",    role: "VIEWER", permissions: ["*.view"], createdAt: "2026-03-30T00:00:00Z", lastLogin: null, minecraftUuid: null, minecraftName: null },
]

export const mockRoles = [
  { name: "ADMIN",    permissions: ["*"], builtIn: true,  userCount: 1 },
  { name: "OPERATOR", permissions: ["instances.view", "instances.start", "instances.stop", "nodes.view", "nodes.edit", "groups.view"], builtIn: true,  userCount: 1 },
  { name: "DEPLOYER", permissions: ["groups.edit", "templates.edit", "instances.start", "instances.view"], builtIn: false, userCount: 1 },
  { name: "VIEWER",   permissions: ["instances.view", "nodes.view", "groups.view", "templates.view", "crashes.view"], builtIn: true,  userCount: 1 },
]

export const mockAdminTokens = [
  { tokenId: "tok-a1b2c3", nodeId: "node-pending-east-1", expiresAt: new Date(Date.now() + 86_400_000).toISOString(), createdAt: new Date(Date.now() - 3_600_000).toISOString() },
  { tokenId: "tok-d4e5f6", nodeId: "node-pending-west-2", expiresAt: new Date(Date.now() + 5 * 86_400_000).toISOString(), createdAt: new Date(Date.now() - 86_400_000).toISOString() },
]

export const mockBackups = [
  { id: "bkp-2026-05-10-0300", createdAt: new Date(Date.now() -  6 * 3600 * 1000).toISOString(), sizeBytes:  142_000_000, instanceCount: 8, templateCount: 5, verifiedAt: new Date(Date.now() -  5 * 3600 * 1000).toISOString(), verifyStatus: "OK" as const,      notes: "Daily snapshot" },
  { id: "bkp-2026-05-09-0300", createdAt: new Date(Date.now() - 30 * 3600 * 1000).toISOString(), sizeBytes:  138_000_000, instanceCount: 8, templateCount: 5, verifiedAt: new Date(Date.now() - 29 * 3600 * 1000).toISOString(), verifyStatus: "OK" as const,      notes: "Daily snapshot" },
  { id: "bkp-2026-05-08-0300", createdAt: new Date(Date.now() - 54 * 3600 * 1000).toISOString(), sizeBytes:  131_000_000, instanceCount: 7, templateCount: 5, verifiedAt: null, verifyStatus: "PENDING" as const, notes: "" },
  { id: "bkp-2026-05-07-1830", createdAt: new Date(Date.now() - 96 * 3600 * 1000).toISOString(), sizeBytes:  129_000_000, instanceCount: 6, templateCount: 4, verifiedAt: new Date(Date.now() - 95 * 3600 * 1000).toISOString(), verifyStatus: "FAILED" as const,  notes: "Pre-deploy hand-rolled" },
]

export const mockMaintenance = {
  globalEnabled: false,
  globalMessage: "Cluster will be down for migration from 03:00–04:00 UTC.",
  globalBypassUsernames: ["dev"],
  groups: [
    { groupName: "minigame-skywars", enabled: true, message: "Tuning scaling thresholds.", bypassUsernames: [] as string[] },
  ],
}

export const mockHealth = {
  status: "DEGRADED" as const,
  components: [
    { id: "mongo",      status: "UP" as const },
    { id: "redis",      status: "UP" as const },
    { id: "scheduler",  status: "UP" as const },
    { id: "audit-sink", status: "DEGRADED" as const, message: "Backlog of 1.2k events — disk I/O slow on the controller host." },
  ],
}

export const mockVersion = {
  version: "1.0.4",
  commit: "a1b2c3d4e5f6",
  builtAt: "2026-05-09T14:00:00Z",
  javaVersion: "21.0.4 (Temurin)",
}

export const mockDiagnostics = {
  items: [
    { id: "audit-backlog", severity: "warning" as const, message: "Audit event sink lagging by 1.2k events.",       fix: "Free disk on the controller host, or temporarily shrink retention." },
    { id: "drift-node-2",  severity: "info" as const,    message: "node-2 has a template cache 1 version behind.", fix: "Run `prexorctl node cache refresh node-2` once the drain completes." },
  ],
}

export const mockRedisKeyspace = { keys: 4_812, expires: 1_204, avgTtl: 3600 }
export const mockRedisSchema = {
  entries: [
    { prefix: "instance:",   count: 32,    sampleKeys: ["instance:lobby-01", "instance:lobby-02"] },
    { prefix: "node:",       count: 6,     sampleKeys: ["node:node-1", "node:node-2", "node:node-3"] },
    { prefix: "group:",      count: 14,    sampleKeys: ["group:lobby", "group:survival"] },
    { prefix: "session:",    count: 4_320, sampleKeys: ["session:1a2b…"] },
    { prefix: "audit:queue", count: 1_200, sampleKeys: [] as string[] },
  ],
}

export const mockSystemSettings = {
  controllerHost: "controller-eu-central-1.internal:8443",
  schedulerInterval: "5s",
  heartbeatInterval: "2s",
  metricsEnabled: true,
  cosignVerification: "REQUIRE_SIGNATURE",
  rekorPolicy: "REQUIRE_SET",
  defaultRoutingPolicy: "LEAST_LOADED",
}

export const mockPlayers = [
  { id: "p-1", uuid: "1a2b3c4d-5e6f-7890-abcd-1234567890ef", username: "Notch",       currentInstance: "lobby-01",    group: "lobby",    ping: 32,  connectedAt: new Date(Date.now() - 25 * 60_000).toISOString() },
  { id: "p-2", uuid: "2b3c4d5e-6f70-8901-bcde-2345678901f0", username: "jeb_",        currentInstance: "lobby-02",    group: "lobby",    ping: 58,  connectedAt: new Date(Date.now() - 12 * 60_000).toISOString() },
  { id: "p-3", uuid: "3c4d5e6f-7081-9012-cdef-3456789012a1", username: "dinnerbone",  currentInstance: "survival-01", group: "survival", ping: 145, connectedAt: new Date(Date.now() - 90 * 60_000).toISOString() },
  { id: "p-4", uuid: "4d5e6f70-8192-0123-defa-4567890123b2", username: "Grumm",       currentInstance: "survival-02", group: "survival", ping: 92,  connectedAt: new Date(Date.now() - 47 * 60_000).toISOString() },
  { id: "p-5", uuid: "5e6f7081-9203-1234-efab-5678901234c3", username: "Searge",      currentInstance: "survival-03", group: "survival", ping: 240, connectedAt: new Date(Date.now() -  3 * 60_000).toISOString() },
  { id: "p-6",  uuid: "6f708192-0314-2345-fabc-6789012345d4", username: "EvilSeph",        currentInstance: "bedwars-01",  group: "bedwars",  ping:  41, connectedAt: new Date(Date.now() -  8 * 60_000).toISOString() },
  { id: "p-7",  uuid: "70819203-1425-3456-abcd-789012345e56", username: "Mojang_Erin",     currentInstance: "bedwars-02",  group: "bedwars",  ping:  77, connectedAt: new Date(Date.now() - 15 * 60_000).toISOString() },
  { id: "p-8",  uuid: "81920314-2536-4567-bcde-89012345fa67", username: "GoldenAxe",       currentInstance: "bedwars-04",  group: "bedwars",  ping: 113, connectedAt: new Date(Date.now() -  2 * 60_000).toISOString() },
  { id: "p-9",  uuid: "92031425-3647-5678-cdef-9012345fab78", username: "BurntToast",      currentInstance: "bedwars-06",  group: "bedwars",  ping:  62, connectedAt: new Date(Date.now() -  5 * 60_000).toISOString() },
  { id: "p-10", uuid: "03142536-4758-6789-defa-012345fabc89", username: "PixelArtisan",    currentInstance: "creative-01", group: "creative", ping:  29, connectedAt: new Date(Date.now() - 35 * 60_000).toISOString() },
  { id: "p-11", uuid: "14253647-5869-789a-efab-12345fabcd9a", username: "BuildingBlue",    currentInstance: "creative-01", group: "creative", ping:  84, connectedAt: new Date(Date.now() - 22 * 60_000).toISOString() },
  { id: "p-12", uuid: "25364758-697a-89ab-fabc-2345fabcdeab", username: "RailMaster",      currentInstance: "creative-02", group: "creative", ping:  46, connectedAt: new Date(Date.now() - 60 * 60_000).toISOString() },
  { id: "p-13", uuid: "3647586a-79ab-9abc-abcd-345fabcdef0c", username: "PvPLegend99",     currentInstance: "arena-pvp-01", group: "arena-pvp", ping:  98, connectedAt: new Date(Date.now() -  9 * 60_000).toISOString() },
  { id: "p-14", uuid: "47586a78-9abc-abcd-bcde-45fabcdef01d", username: "FasterFangs",     currentInstance: "arena-pvp-02", group: "arena-pvp", ping: 168, connectedAt: new Date(Date.now() -  1 * 60_000).toISOString() },
  { id: "p-15", uuid: "586a789b-abcd-bcde-cdef-5fabcdef012e", username: "CritGoddess",     currentInstance: "arena-pvp-03", group: "arena-pvp", ping: 210, connectedAt: new Date(Date.now() -  6 * 60_000).toISOString() },
  { id: "p-16", uuid: "6a789bac-bcde-cdef-defa-fabcdef0123f", username: "Quartzite",       currentInstance: "lobby-01",    group: "lobby",    ping:  39, connectedAt: new Date(Date.now() - 10 * 60_000).toISOString() },
  { id: "p-17", uuid: "789bacbd-cdef-defa-efab-abcdef01234a", username: "RedstoneArchitect", currentInstance: "lobby-02", group: "lobby",    ping:  51, connectedAt: new Date(Date.now() -  4 * 60_000).toISOString() },
  { id: "p-18", uuid: "89bacbde-defa-efab-fabc-bcdef01234ab", username: "EnderEnvy",       currentInstance: "lobby-01",    group: "lobby",    ping:  72, connectedAt: new Date(Date.now() -  7 * 60_000).toISOString() },
  { id: "p-19", uuid: "9bacbdef-efab-fabc-abcd-cdef01234abc", username: "NetherSurfer",    currentInstance: "survival-04", group: "survival", ping:  88, connectedAt: new Date(Date.now() - 18 * 60_000).toISOString() },
  { id: "p-20", uuid: "acbdefab-fabc-abcd-bcde-def01234abcd", username: "VanillaVeteran",  currentInstance: "survival-02", group: "survival", ping: 102, connectedAt: new Date(Date.now() - 75 * 60_000).toISOString() },
]

export function mockJourney(_playerId: string) {
  const now = Date.now()
  return [
    { ts: new Date(now - 25 * 60_000).toISOString(), type: "connected"   as const, fromInstance: null,         toInstance: "lobby-01" },
    { ts: new Date(now - 18 * 60_000).toISOString(), type: "transferred" as const, fromInstance: "lobby-01",   toInstance: "survival-01" },
    { ts: new Date(now -  6 * 60_000).toISOString(), type: "transferred" as const, fromInstance: "survival-01", toInstance: "lobby-01" },
  ]
}

export const mockActivity = [
  { id: "ev-1",  type: "INSTANCE_CRASHED",     actor: "scheduler", message: "survival-09 crashed (OOM)",                       route: "/instances/survival-09", timestamp: new Date(Date.now() -   6 * 3600 * 1000).toISOString() },
  { id: "ev-2",  type: "DEPLOYMENT_COMPLETED", actor: "deployer",  message: "Deployment completed for survival",               route: "/groups/survival",       timestamp: new Date(Date.now() -  50 * 3600 * 1000).toISOString() },
  { id: "ev-3",  type: "NODE_CONNECTED",       actor: "system",    message: "node-3 connected",                                route: "/nodes/node-3",          timestamp: new Date(Date.now() -  72 * 3600 * 1000).toISOString() },
  { id: "ev-4",  type: "NODE_DRAIN_REQUESTED", actor: "dev",       message: "Drain requested on node-2",                       route: "/nodes/node-2",          timestamp: new Date(Date.now() -   1.5 * 3600 * 1000).toISOString() },
  { id: "ev-5",  type: "GROUP_UPDATED",        actor: "dev",       message: "survival.maxInstances updated 6 → 8",             route: "/groups/survival",       timestamp: new Date(Date.now() -  20 * 60_000).toISOString() },
  { id: "ev-6",  type: "PLAYER_CONNECTED",     actor: "system",    message: "Notch joined lobby-01",                           route: "/cluster/players",       timestamp: new Date(Date.now() -  25 * 60_000).toISOString() },
  { id: "ev-7",  type: "INSTANCE_STARTED",    actor: "scheduler", message: "survival-04 started on node-2",                   route: "/instances/survival-04", timestamp: new Date(Date.now() -  30 * 3600 * 1000).toISOString() },
  { id: "ev-8",  type: "TEMPLATE_UPDATED",    actor: "deployer",  message: "lobby template bumped to i9j0k1l2",                route: "/templates/lobby",       timestamp: new Date(Date.now() -  24 * 3600 * 1000).toISOString() },
  { id: "ev-9",  type: "MAINTENANCE_UPDATED", actor: "dev",       message: "minigame-skywars maintenance enabled",             route: "/operations/maintenance",timestamp: new Date(Date.now() -  10 * 60_000).toISOString() },
  { id: "ev-10", type: "GROUP_CRASH_LOOP",    actor: "scheduler", message: "minigame-skywars crash-loop detected (5 crashes)", route: "/groups/minigame-skywars", timestamp: new Date(Date.now() -  35 * 3600 * 1000).toISOString() },
  { id: "ev-11", type: "GROUP_CREATED",       actor: "dev",       message: "Group bedwars created",                              route: "/groups/bedwars",        timestamp: new Date(Date.now() -  30 * 3600 * 1000).toISOString() },
  { id: "ev-12", type: "DEPLOYMENT_COMPLETED", actor: "deployer", message: "Deployment completed for bedwars",                  route: "/groups/bedwars",        timestamp: new Date(Date.now() -   4 * 3600 * 1000).toISOString() },
  { id: "ev-13", type: "INSTANCE_STARTED",    actor: "scheduler", message: "bedwars-05 started on node-4",                      route: "/instances/bedwars-05",  timestamp: new Date(Date.now() -    20_000).toISOString() },
  { id: "ev-14", type: "INSTANCE_CRASHED",    actor: "scheduler", message: "arena-pvp-06 crashed (ERROR)",                      route: "/instances/arena-pvp-06", timestamp: new Date(Date.now() -    4 * 60_000).toISOString() },
  { id: "ev-15", type: "INSTANCE_CRASHED",    actor: "scheduler", message: "bedwars-05 crashed (OOM, GC overhead)",             route: "/instances/bedwars-05",  timestamp: new Date(Date.now() -   90 * 60_000).toISOString() },
  { id: "ev-16", type: "PLAYER_CONNECTED",    actor: "system",    message: "EvilSeph joined bedwars-01",                        route: "/cluster/players",       timestamp: new Date(Date.now() -    8 * 60_000).toISOString() },
  { id: "ev-17", type: "PLAYER_CONNECTED",    actor: "system",    message: "PixelArtisan joined creative-01",                   route: "/cluster/players",       timestamp: new Date(Date.now() -   35 * 60_000).toISOString() },
  { id: "ev-18", type: "NODE_CONNECTED",      actor: "system",    message: "node-4 connected",                                   route: "/nodes/node-4",          timestamp: new Date(Date.now() -   96 * 3600 * 1000).toISOString() },
  { id: "ev-19", type: "NODE_DISCONNECTED",   actor: "system",    message: "node-6 lost heartbeat",                              route: "/nodes/node-6",          timestamp: new Date(Date.now() -   38 * 3600 * 1000).toISOString() },
  { id: "ev-20", type: "TEMPLATE_UPDATED",    actor: "deployer",  message: "bedwars-base template bumped to u1v2w3x4",          route: "/templates/bedwars-base", timestamp: new Date(Date.now() -    6 * 3600 * 1000).toISOString() },
  { id: "ev-21", type: "MODULE_LOADED",       actor: "system",    message: "Module \"player-management\" loaded (v1.4.2)",      route: "/modules",               timestamp: new Date(Date.now() -    2 * 24 * 3600 * 1000).toISOString() },
  { id: "ev-22", type: "DEPLOYMENT_ROLLED_BACK", actor: "dev",    message: "Rolled back creative to revision 1",                 route: "/groups/creative",       timestamp: new Date(Date.now() -    2 * 24 * 3600 * 1000 -  2 * 3600 * 1000).toISOString() },
]

export const mockRevokedCerts = [
  { nodeId: "node-old-east-1", serial: "0a1b2c3d4e5f6789", revokedAt: new Date(Date.now() -  7 * 86_400_000).toISOString(), reason: "Decommissioned" },
  { nodeId: "node-rogue-ap-2", serial: "9f8e7d6c5b4a3210", revokedAt: new Date(Date.now() -  2 * 86_400_000).toISOString(), reason: "Suspected key leak — rotating" },
]

export const mockComposition = {
  templates: [
    { name: "base",        hash: "a1b2c3d4", source: "inherited" },
    { name: "base-paper",  hash: "e5f6g7h8", source: "inherited" },
    { name: "lobby",       hash: "i9j0k1l2", source: "primary" },
  ],
  extensions: [
    { id: "viaversion@5.0.0",  module: "version-bridge",  installPath: "/plugins" },
    { id: "paperweight@1.21",  module: "paperweight",     installPath: "/plugins" },
  ],
  jvmArgs: ["-XX:+UseG1GC", "-Xms2G", "-Xmx2G"],
  env: { TZ: "UTC", PREXOR_INSTANCE_ID: "<runtime>" },
}

export const mockResolved = {
  templateChain: ["base", "base-paper", "lobby"],
  resolvedFiles: 24,
  resolvedJvmArgs: ["-XX:+UseG1GC", "-Xms2G", "-Xmx2G"],
  resolvedEnv: { TZ: "UTC", DIFFICULTY: "normal" },
  resolvedConfigPatches: 7,
}

export const mockWorkloadCredentials = [
  { tokenId: "wc-001", instanceId: "lobby-01",    group: "lobby",    node: "node-1", issuedAt: new Date(Date.now() - 7 * 3600 * 1000).toISOString(),  expiresAt: new Date(Date.now() + 17 * 3600 * 1000).toISOString(), scope: "instance" },
  { tokenId: "wc-002", instanceId: "lobby-02",    group: "lobby",    node: "node-3", issuedAt: new Date(Date.now() - 6 * 3600 * 1000).toISOString(),  expiresAt: new Date(Date.now() + 18 * 3600 * 1000).toISOString(), scope: "instance" },
  { tokenId: "wc-003", instanceId: "survival-01", group: "survival", node: "node-1", issuedAt: new Date(Date.now() - 50 * 3600 * 1000).toISOString(), expiresAt: null, scope: "instance" },
  { tokenId: "wc-004", instanceId: "proxy-01",    group: "proxy",    node: "node-1", issuedAt: new Date(Date.now() - 72 * 3600 * 1000).toISOString(), expiresAt: null, scope: "proxy" },
]

export const mockDeployments = [
  { id: "dep-1", revision: 7, state: "COMPLETED", strategy: "ROLLING", trigger: "manual", startedAt: new Date(Date.now() - 50 * 3600 * 1000).toISOString(), completedAt: new Date(Date.now() - 50 * 3600 * 1000 + 6 * 60 * 1000).toISOString(), totalInstances: 4, updatedInstances: 4 },
  { id: "dep-2", revision: 6, state: "COMPLETED", strategy: "ROLLING", trigger: "manual", startedAt: new Date(Date.now() - 96 * 3600 * 1000).toISOString(), completedAt: new Date(Date.now() - 96 * 3600 * 1000 + 8 * 60 * 1000).toISOString(), totalInstances: 4, updatedInstances: 4 },
]

export function paginated<T>(items: T[], page = 1, pageSize = 100) {
  const start = (page - 1) * pageSize
  const slice = items.slice(start, start + pageSize)
  return { data: slice, page, pageSize, total: items.length }
}
