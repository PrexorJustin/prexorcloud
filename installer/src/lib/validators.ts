// Pure validators ported from the legacy wizard. Each one returns a boolean
// (true == valid) so call sites can chain them inline with the `invalid`
// prop on inputs.
import type { WizardState } from '@/stores/wizard';

export const isPort = (v: number | string | null | undefined): boolean => {
  const n = typeof v === 'number' ? v : parseInt(String(v ?? ''), 10);
  return Number.isFinite(n) && n >= 1 && n <= 65535;
};

export const isMongoOrRedis = (v: string | null | undefined): boolean =>
  /^(mongodb|mongodb\+srv|redis|rediss):\/\//.test((v ?? '').trim());

export const isHttpUrl = (v: string | null | undefined): boolean => {
  try {
    const u = new URL(v ?? '');
    return u.protocol === 'http:' || u.protocol === 'https:';
  } catch {
    return false;
  }
};

// Empty is allowed — fields that need a value should also check for non-empty.
export const isEmail = (v: string | null | undefined): boolean =>
  !v || /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v.trim());

export const isCidr = (v: string | null | undefined): boolean => {
  const s = v ?? '';
  return (
    /^(\d{1,3}\.){3}\d{1,3}(\/(\d|[12]\d|3[0-2]))?$/.test(s) ||
    /^[0-9a-fA-F:]+(\/(\d{1,2}|1[01]\d|12[0-8]))?$/.test(s)
  );
};

// HTTP and gRPC ports must differ — the controller binds both on the same host.
export function portConflict(state: Pick<WizardState, 'httpPort' | 'grpcPort'>): boolean {
  return !!state.httpPort && !!state.grpcPort && String(state.httpPort) === String(state.grpcPort);
}
