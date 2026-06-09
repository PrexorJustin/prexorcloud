import { describe, expect, it, beforeEach, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { useWizardStore } from '@/stores/wizard';
import Mode from './Mode.vue';
import Essentials from './Essentials.vue';
import Security from './Security.vue';
import Review from './Review.vue';
import CliLogin from './CliLogin.vue';

beforeEach(() => {
  setActivePinia(createPinia());
});

describe('Mode.vue', () => {
  it('renders every mode card when cli-login is available', () => {
    const wiz = useWizardStore();
    wiz.cliLoginAvailable = true;
    const wrap = mount(Mode);
    const cards = wrap.findAll('.mode-card');
    // all + controller + controller-join + daemon + dashboard + cli
    expect(cards).toHaveLength(6);
    expect(wrap.html()).toContain('Controller + Daemon');
    expect(wrap.html()).toContain('Add controller');
    expect(wrap.html()).toContain('CLI login');
  });

  it('hides the CLI-login card when /api/info disabled the feature', () => {
    const wiz = useWizardStore();
    wiz.cliLoginAvailable = false;
    const wrap = mount(Mode);
    expect(wrap.findAll('.mode-card')).toHaveLength(5);
    expect(wrap.html()).not.toContain('CLI login');
  });

  it('clicking a card sets the store mode', async () => {
    const wiz = useWizardStore();
    const wrap = mount(Mode);
    await wrap.findAll('.mode-card')[1].trigger('click'); // "Controller only"
    expect(wiz.mode).toBe('controller');
  });

  it('continue button advances to essentials in install modes', async () => {
    const wiz = useWizardStore();
    wiz.setMode('controller');
    const wrap = mount(Mode);
    await wrap.find('.continue-bar .btn.primary').trigger('click');
    expect(wiz.step).toBe('essentials');
  });

  it('continue button jumps to cli-login when mode is cli', async () => {
    const wiz = useWizardStore();
    wiz.setMode('cli');
    const wrap = mount(Mode);
    await wrap.find('.continue-bar .btn.primary').trigger('click');
    expect(wiz.step).toBe('cli-login');
  });

  // When /api/info reports the host can't install (macOS/Windows), all
  // server-side cards are still rendered but disabled with a reason, and the
  // banner explains why. The CLI-login card stays clickable.
  it('disables non-cli cards and shows the banner when installSupported is false', async () => {
    const wiz = useWizardStore();
    wiz.installSupported = false;
    wiz.installUnsupportedReason = 'Server-side components run on Linux. This host is darwin.';
    const wrap = mount(Mode);

    expect(wrap.find('.install-blocked-banner').exists()).toBe(true);
    expect(wrap.html()).toContain('darwin');

    const cards = wrap.findAll('.mode-card');
    expect(cards).toHaveLength(6);
    const disabledIds = cards
      .filter((c) => c.attributes('disabled') !== undefined)
      .map((c) => c.attributes('data-mode'));
    expect(disabledIds.sort()).toEqual([
      'all',
      'controller',
      'controller-join',
      'daemon',
      'dashboard',
    ]);
    const cliCard = cards.find((c) => c.attributes('data-mode') === 'cli');
    expect(cliCard?.attributes('disabled')).toBeUndefined();
  });

  it('clicking a disabled card does not set the store mode', async () => {
    const wiz = useWizardStore();
    wiz.installSupported = false;
    wiz.setMode('cli');
    const wrap = mount(Mode);
    const controllerCard = wrap
      .findAll('.mode-card')
      .find((c) => c.attributes('data-mode') === 'controller');
    await controllerCard?.trigger('click');
    expect(wiz.mode).toBe('cli');
  });
});

describe('Essentials.vue', () => {
  it('renders the controller section in "all" mode and shows the prod callout in production', () => {
    const wiz = useWizardStore();
    wiz.setMode('all');
    wiz.profile = 'production';
    const wrap = mount(Essentials);
    expect(wrap.html()).toContain('Controller');
    expect(wrap.html()).toContain('Daemon');
    expect(wrap.html()).toContain('Production checklist');
  });

  it('flags the port conflict when http and grpc match', () => {
    const wiz = useWizardStore();
    wiz.httpPort = 8080;
    wiz.grpcPort = 8080;
    const wrap = mount(Essentials);
    expect(wrap.html()).toContain('HTTP and gRPC ports must differ');
  });

  it('shows the Dashboard section in "dashboard" mode and not the controller section', () => {
    const wiz = useWizardStore();
    wiz.setMode('dashboard');
    const wrap = mount(Essentials);
    const html = wrap.html();
    expect(html).toContain('Public URL');
    expect(html).not.toContain('MongoDB URI');
  });
});

describe('Security.vue', () => {
  it('mounts and renders all expected collapsibles for the all-in-one mode', () => {
    const wiz = useWizardStore();
    wiz.setMode('all');
    const wrap = mount(Security);
    const html = wrap.html();
    for (const title of [
      'JWT',
      'Rate limiting',
      'Account lockout',
      'Password reset',
      'Module signing',
      'Logging',
      'Scheduler',
      'Daemon — runtime',
      'Maintenance',
      'Paste / share',
    ]) {
      expect(html).toContain(title);
    }
  });

  it('hides the controller-only collapsibles in daemon-only mode', () => {
    const wiz = useWizardStore();
    wiz.setMode('daemon');
    const wrap = mount(Security);
    // Section titles render inside `.collapsible-head .title`. Comments in the
    // template (which Vue keeps as DOM comments around v-if placeholders) can
    // mention controller-only sections — what matters is no real heading.
    const titles = wrap.findAll('.collapsible-head .title').map((el) => el.text());
    expect(titles).not.toContain('Rate limiting');
    expect(titles).not.toContain('JWT');
    expect(titles).toContain('Daemon — runtime');
  });
});

describe('Review.vue', () => {
  it('renders the terminal card with the active filename in the heading', () => {
    const wiz = useWizardStore();
    wiz.setMode('controller');
    const wrap = mount(Review);
    expect(wrap.html()).toContain('controller.yml');
    expect(wrap.find('.term-pre').exists()).toBe(true);
  });

  it('shows the password callout only when an initial admin password is set', () => {
    const wiz = useWizardStore();
    expect(mount(Review).html()).not.toContain('SAVE THIS NOW');
    wiz.initialAdminPassword = 'p4ssword';
    expect(mount(Review).html()).toContain('SAVE THIS NOW');
  });

  it('triggers wiz.doInstall when the Install button is clicked', async () => {
    const wiz = useWizardStore();
    const spy = vi.spyOn(wiz, 'doInstall').mockResolvedValue();
    const wrap = mount(Review);
    await wrap.find('.next-card .btn.primary').trigger('click');
    expect(spy).toHaveBeenCalled();
  });

  it('shows "✓ Installed" once installDone is true', () => {
    const wiz = useWizardStore();
    wiz.installDone = true;
    const wrap = mount(Review);
    expect(wrap.html()).toContain('✓ Installed');
  });
});

describe('CliLogin.vue', () => {
  it('disables the submit button until all three fields are filled', async () => {
    const wiz = useWizardStore();
    const wrap = mount(CliLogin);
    const btn = wrap.find('.navbar .btn.primary');
    expect(btn.attributes('disabled')).toBeDefined();
    wiz.cliLoginController = 'https://ctrl';
    wiz.cliLoginUsername = 'admin';
    wiz.cliLoginPassword = 'pw';
    await wrap.vm.$nextTick();
    expect(btn.attributes('disabled')).toBeUndefined();
  });

  it('renders the success state when cliLoginDone is true', () => {
    const wiz = useWizardStore();
    wiz.cliLoginDone = true;
    wiz.cliLoginController = 'https://ctrl';
    wiz.cliLoginAs = 'admin';
    const wrap = mount(CliLogin);
    expect(wrap.html()).toContain('Signed in');
    expect(wrap.html()).toContain('admin');
  });

  it('surfaces the server error message when set', () => {
    const wiz = useWizardStore();
    wiz.cliLoginError = 'bad credentials';
    const wrap = mount(CliLogin);
    expect(wrap.html()).toContain('bad credentials');
  });
});
