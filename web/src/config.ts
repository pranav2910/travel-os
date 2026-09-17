/**
 * Public runtime configuration, served by the edge as /config.json (nginx writes it from
 * environment variables at container start; the Vite dev server serves public/config.json).
 * Nothing here is secret: the OIDC client is public (PKCE), the issuer is the users' Keycloak.
 */
export interface RuntimeConfig {
  oidcAuthority: string;
  oidcClientId: string;
  /** SANDBOX | LIVE: the deployment's evidence class; the banner and connector labels use it. */
  deploymentClass: 'SANDBOX' | 'LIVE';
  environmentLabel: string;
}

let loaded: RuntimeConfig | null = null;

export async function loadConfig(): Promise<RuntimeConfig> {
  if (loaded) return loaded;
  const response = await fetch('/config.json', { cache: 'no-store' });
  if (!response.ok) throw new Error(`config.json: HTTP ${response.status}`);
  const raw = (await response.json()) as Partial<RuntimeConfig>;
  if (!raw.oidcAuthority || !raw.oidcClientId) {
    throw new Error('config.json must name oidcAuthority and oidcClientId');
  }
  loaded = {
    oidcAuthority: raw.oidcAuthority,
    oidcClientId: raw.oidcClientId,
    deploymentClass: raw.deploymentClass === 'LIVE' ? 'LIVE' : 'SANDBOX',
    environmentLabel: raw.environmentLabel ?? 'local',
  };
  return loaded;
}

export function config(): RuntimeConfig {
  if (!loaded) throw new Error('runtime config not loaded yet');
  return loaded;
}
