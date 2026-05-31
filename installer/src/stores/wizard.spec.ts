import { describe, expect, it, beforeEach, vi, afterEach } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { useWizardStore } from './wizard';

beforeEach(() => {
  setActivePinia(createPinia());
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('wizard navigation', () => {
  it('starts on the mode step with default mode "all"', () => {
    const wiz = useWizardStore();
    expect(wiz.step).toBe('mode');
    expect(wiz.mode).toBe('all');
  });

  it('advance() walks mode → essentials → security → review', () => {
    const wiz = useWizardStore();
    wiz.advance();
    expect(wiz.step).toBe('essentials');
    wiz.advance();
    expect(wiz.step).toBe('security');
    wiz.advance();
    expect(wiz.step).toBe('review');
    wiz.advance();
    expect(wiz.step).toBe('review'); // terminal — no further advance
  });

  it('advance() from mode jumps to cli-login when mode is "cli"', () => {
    const wiz = useWizardStore();
    wiz.setMode('cli');
    wiz.advance();
    expect(wiz.step).toBe('cli-login');
  });

  it('setStep can jump anywhere (used by stepper clicks)', () => {
    const wiz = useWizardStore();
    wiz.setStep('review');
    expect(wiz.step).toBe('review');
  });
});

describe('help + collapsible toggles', () => {
  it('toggleHelp opens, closes, and is mutually exclusive across keys', () => {
    const wiz = useWizardStore();
    expect(wiz.helpOpen).toBeNull();
    wiz.toggleHelp('security.jwtSecret');
    expect(wiz.helpOpen).toBe('security.jwtSecret');
    wiz.toggleHelp('security.jwtSecret');
    expect(wiz.helpOpen).toBeNull();
    wiz.toggleHelp('security.jwtSecret');
    wiz.toggleHelp('database.uri');
    expect(wiz.helpOpen).toBe('database.uri');
  });

  it('toggleCollapsible flips the per-id flag (jwt starts open by default)', () => {
    const wiz = useWizardStore();
    expect(wiz.collapsibles.jwt).toBe(true);
    wiz.toggleCollapsible('jwt');
    expect(wiz.collapsibles.jwt).toBe(false);
    wiz.toggleCollapsible('rate');
    expect(wiz.collapsibles.rate).toBe(true);
  });
});

describe('list helpers', () => {
  it('add/remove/set mutate corsOrigins in place', () => {
    const wiz = useWizardStore();
    expect(wiz.corsOrigins).toEqual(['http://localhost:3000']);
    wiz.addListItem('corsOrigins');
    expect(wiz.corsOrigins).toHaveLength(2);
    wiz.setListItem('corsOrigins', 1, 'https://dash.example.com');
    expect(wiz.corsOrigins[1]).toBe('https://dash.example.com');
    wiz.removeListItem('corsOrigins', 0);
    expect(wiz.corsOrigins).toEqual(['https://dash.example.com']);
  });
});

describe('reset', () => {
  it('restores defaults including a fresh uuid', () => {
    const wiz = useWizardStore();
    const originalUuid = wiz.uuid;
    wiz.httpPort = 9999;
    wiz.setMode('daemon');
    wiz.reset();
    expect(wiz.httpPort).toBe(8080);
    expect(wiz.mode).toBe('all');
    expect(wiz.uuid).not.toBe(originalUuid); // new generation on reset
  });
});

describe('loadDefaults', () => {
  it('merges /api/info defaults into the store', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({
          defaults: {
            controllerHttpPort: 18080,
            controllerGrpcPort: 19090,
            daemonControllerHost: '10.20.30.40',
            daemonControllerGrpcPort: 19090,
            dashboardInstallDir: '/srv/dashboard',
            dashboardListenPort: '8443',
          },
          features: { cliLogin: true },
        }),
      }),
    );
    const wiz = useWizardStore();
    await wiz.loadDefaults();
    expect(wiz.httpPort).toBe(18080);
    expect(wiz.grpcPort).toBe(19090);
    expect(wiz.controllerHost).toBe('10.20.30.40');
    expect(wiz.controllerGrpcPort).toBe(19090);
    expect(wiz.dashboardInstallDir).toBe('/srv/dashboard');
    expect(wiz.dashboardListenPort).toBe(8443);
    expect(wiz.cliLoginAvailable).toBe(true);
    expect(wiz.defaultsLoaded).toBe(true);
  });

  it('flips cliLoginAvailable to false when the server reports the feature off', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ features: { cliLogin: false } }),
      }),
    );
    const wiz = useWizardStore();
    await wiz.loadDefaults();
    expect(wiz.cliLoginAvailable).toBe(false);
  });

  it('is idempotent — second call short-circuits even on a different response', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({ defaults: { controllerHttpPort: 4242 } }),
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({ defaults: { controllerHttpPort: 9999 } }),
      });
    vi.stubGlobal('fetch', fetchMock);
    const wiz = useWizardStore();
    await wiz.loadDefaults();
    await wiz.loadDefaults();
    expect(wiz.httpPort).toBe(4242);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('keeps schema defaults when the request fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network down')));
    const wiz = useWizardStore();
    await wiz.loadDefaults();
    expect(wiz.httpPort).toBe(8080);
    expect(wiz.defaultsLoaded).toBe(true);
  });
});

describe('doInstall', () => {
  it('POSTs to /api/install/controller + /api/install/daemon in "all" mode', async () => {
    const calls: Array<{ url: string; body: unknown }> = [];
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation(async (url: string, init: RequestInit) => {
        calls.push({ url, body: JSON.parse(init.body as string) });
        return { ok: true, json: async () => ({ ok: true, messages: ['provisioned'] }) };
      }),
    );
    const wiz = useWizardStore();
    wiz.setMode('all');
    wiz.nodeId = 'node-a';
    wiz.joinToken = 'pcjt_x';
    await wiz.doInstall();
    expect(wiz.installDone).toBe(true);
    expect(wiz.installError).toBe('');
    expect(calls.map((c) => c.url)).toEqual(['/api/install/controller', '/api/install/daemon']);
    expect(wiz.installLog.some((l) => l.msg.includes('provisioned'))).toBe(true);
  });

  it('POSTs to /api/install/controller with joinToken set in "controller-join" mode', async () => {
    const calls: Array<{ url: string; body: { joinToken?: string } }> = [];
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation(async (url: string, init: RequestInit) => {
        calls.push({ url, body: JSON.parse(init.body as string) });
        return { ok: true, json: async () => ({ ok: true, messages: ['ok'] }) };
      }),
    );
    const wiz = useWizardStore();
    wiz.setMode('controller-join');
    wiz.controllerJoinToken = 'prexor-jt:v1:somepayload.somemac';
    await wiz.doInstall();
    expect(wiz.installDone).toBe(true);
    expect(calls.map((c) => c.url)).toEqual(['/api/install/controller']);
    expect(calls[0].body.joinToken).toBe('prexor-jt:v1:somepayload.somemac');
  });

  it('captures the server error into installError', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 500,
        json: async () => ({ ok: false, error: 'mongo unreachable' }),
      }),
    );
    const wiz = useWizardStore();
    wiz.setMode('controller');
    await wiz.doInstall();
    expect(wiz.installDone).toBe(false);
    expect(wiz.installError).toBe('mongo unreachable');
    expect(wiz.installLog.some((l) => l.cls === 'err')).toBe(true);
  });

  // Builds a ReadableStream that emits the given NDJSON lines, mimicking the
  // wizard server's streaming install response.
  function ndjsonStream(events: object[]): ReadableStream<Uint8Array> {
    const enc = new TextEncoder();
    return new ReadableStream({
      start(controller) {
        for (const e of events) controller.enqueue(enc.encode(JSON.stringify(e) + '\n'));
        controller.close();
      },
    });
  }

  it('builds the phase timeline + next steps from an NDJSON stream', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        headers: { get: () => 'application/x-ndjson' },
        body: ndjsonStream([
          { type: 'step', msg: 'Ensuring required packages (cosign)…' },
          { type: 'output', data: '$ apt-get install -y redis-server\r\nSetting up redis…\r\n' },
          { type: 'step', msg: 'Installed cosign.' },
          { type: 'done', ok: true, nextSteps: ['systemctl status prexorcloud-controller'] },
        ]),
      }),
    );
    const wiz = useWizardStore();
    wiz.setMode('controller');
    await wiz.doInstall();
    expect(wiz.installDone).toBe(true);
    expect(wiz.installError).toBe('');
    // Two step events → two phases, both completed by the done event.
    expect(wiz.installPhases.map((p) => p.label)).toEqual([
      'Ensuring required packages (cosign)…',
      'Installed cosign.',
    ]);
    expect(wiz.installPhases.every((p) => p.status === 'done')).toBe(true);
    expect(wiz.installNextSteps).toContain('systemctl status prexorcloud-controller');
    // Raw output is mirrored into installLog as a text fallback.
    expect(wiz.installLog.some((l) => l.cls === 'out' && l.msg.includes('redis'))).toBe(true);
  });

  it('marks the active phase failed on a done{ok:false} stream event', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        headers: { get: () => 'application/x-ndjson' },
        body: ndjsonStream([
          { type: 'step', msg: 'Installing MongoDB…' },
          { type: 'output', data: 'E: Unable to locate package mongodb-org\r\n' },
          { type: 'done', ok: false, error: 'install MongoDB: exit status 100' },
        ]),
      }),
    );
    const wiz = useWizardStore();
    wiz.setMode('controller');
    await wiz.doInstall();
    expect(wiz.installDone).toBe(false);
    expect(wiz.installError).toBe('install MongoDB: exit status 100');
    const last = wiz.installPhases[wiz.installPhases.length - 1];
    expect(last.status).toBe('failed');
    expect(last.detail).toBe('install MongoDB: exit status 100');
  });
});

describe('submitCliLogin', () => {
  it('marks cliLoginDone on a 2xx response and clears the password', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: async () => ({ ok: true, controller: 'https://ctrl', username: 'admin' }),
      }),
    );
    const wiz = useWizardStore();
    wiz.cliLoginController = 'https://ctrl';
    wiz.cliLoginUsername = 'admin';
    wiz.cliLoginPassword = 'hunter2';
    await wiz.submitCliLogin();
    expect(wiz.cliLoginDone).toBe(true);
    expect(wiz.cliLoginAs).toBe('admin');
    expect(wiz.cliLoginPassword).toBe('');
  });

  it('surfaces the server error message into cliLoginError', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 401,
        json: async () => ({ ok: false, error: 'bad credentials' }),
      }),
    );
    const wiz = useWizardStore();
    wiz.cliLoginPassword = 'wrong';
    await wiz.submitCliLogin();
    expect(wiz.cliLoginDone).toBe(false);
    expect(wiz.cliLoginError).toBe('bad credentials');
  });
});

describe('install path (compose vs native)', () => {
  it('defaults to compose with native locked until /api/info allows it', () => {
    const wiz = useWizardStore();
    expect(wiz.installMode).toBe('compose');
    expect(wiz.nativeAllowed).toBe(false);
  });

  it('setInstallMode("native") is a no-op while native is unavailable', () => {
    const wiz = useWizardStore();
    wiz.setInstallMode('native');
    expect(wiz.installMode).toBe('compose');
  });

  it('switching to native re-points local storage URIs at localhost', () => {
    const wiz = useWizardStore();
    wiz.nativeAllowed = true;
    expect(wiz.databaseUri).toBe('mongodb://mongo:27017');
    expect(wiz.redisUri).toBe('redis://redis:6379');
    wiz.setInstallMode('native');
    expect(wiz.installMode).toBe('native');
    expect(wiz.databaseUri).toBe('mongodb://localhost:27017');
    expect(wiz.redisUri).toBe('redis://localhost:6379');
    // …and back to the compose service names when reverted.
    wiz.setInstallMode('compose');
    expect(wiz.databaseUri).toBe('mongodb://mongo:27017');
    expect(wiz.redisUri).toBe('redis://redis:6379');
  });

  it('does not rewrite a remote (operator-supplied) URI on mode switch', () => {
    const wiz = useWizardStore();
    wiz.nativeAllowed = true;
    wiz.setMongoMode('remote');
    wiz.databaseUri = 'mongodb://db.internal:27017';
    wiz.setInstallMode('native');
    expect(wiz.databaseUri).toBe('mongodb://db.internal:27017');
  });

  it('setMongoMode("local") picks the address for the active install path', () => {
    const wiz = useWizardStore();
    wiz.nativeAllowed = true;
    wiz.setInstallMode('native');
    wiz.setMongoMode('remote');
    expect(wiz.databaseUri).toBe('');
    wiz.setMongoMode('local');
    expect(wiz.databaseUri).toBe('mongodb://localhost:27017');
  });

  it('loadDefaults reads the platform block into nativeAllowed/nativeReason', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ platform: { nativeAllowed: false, nativeReason: 'needs root' } }),
      }),
    );
    const wiz = useWizardStore();
    await wiz.loadDefaults();
    expect(wiz.nativeAllowed).toBe(false);
    expect(wiz.nativeReason).toBe('needs root');
  });

  // installSupported defaults to true so the UI doesn't flash a disabled
  // state before /api/info resolves; when the server flips it to false
  // (macOS/Windows host), the store flips its default mode to 'cli' so the
  // operator lands on the only card they can actually use.
  it('loadDefaults flips installSupported and snaps mode to cli when host is unsupported', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({
          platform: {
            installSupported: false,
            installUnsupportedReason: 'Server-side components run on Linux. This host is darwin.',
          },
        }),
      }),
    );
    const wiz = useWizardStore();
    expect(wiz.mode).toBe('all');
    await wiz.loadDefaults();
    expect(wiz.installSupported).toBe(false);
    expect(wiz.installUnsupportedReason).toContain('darwin');
    expect(wiz.mode).toBe('cli');
  });

  // setMode is the public store mutator the stepper and screens call into.
  // It must enforce the same constraint as the Mode screen: when
  // installSupported is false, only 'cli' is settable. Otherwise a stale
  // stepper or test bypass could put the wizard in an unreachable state.
  it('setMode refuses non-cli modes when installSupported is false', () => {
    const wiz = useWizardStore();
    wiz.installSupported = false;
    wiz.setMode('cli');
    wiz.setMode('controller');
    expect(wiz.mode).toBe('cli');
    wiz.setMode('daemon');
    expect(wiz.mode).toBe('cli');
  });
});
