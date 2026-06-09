const STUB_ERROR =
  '@prexorcloud/module-sdk: This function is a type stub and should not be called directly. ' +
  'It is replaced at runtime by the PrexorCloud dashboard. ' +
  'If you see this error, @prexorcloud/module-sdk was not properly externalized in your Vite config — ' +
  'make sure you are using @prexorcloud/module-vite-plugin.'

export function cn(...inputs: any[]): string { throw new Error(STUB_ERROR) }
export function timeAgo(date: string | Date): string { throw new Error(STUB_ERROR) }
export function formatUptime(ms: number): string { throw new Error(STUB_ERROR) }
export function timeUntil(date: string | Date): string { throw new Error(STUB_ERROR) }
export function formatBytes(bytes: number): string { throw new Error(STUB_ERROR) }
export function formatMemory(mb: number): string { throw new Error(STUB_ERROR) }
export function getInitials(name: string): string { throw new Error(STUB_ERROR) }
