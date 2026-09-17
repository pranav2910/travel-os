/**
 * The suite runs against a real platform (the Docker stack or kind). Right after a deploy the
 * edge answers before Keycloak does, so wait for both once, with a bound, instead of letting the
 * first test spend its whole timeout on a sign-in page that cannot redirect yet.
 */
const BASE = process.env['E2E_BASE_URL'] ?? 'http://localhost:8080';
const KC = process.env['E2E_KEYCLOAK_URL'] ?? 'http://localhost:8180';

async function waitFor(name: string, url: string, timeoutMs: number, okStatuses: number[] = []) {
  const until = Date.now() + timeoutMs;
  let last = '';
  while (Date.now() < until) {
    try {
      const res = await fetch(url);
      if (res.ok || okStatuses.includes(res.status)) return;
      last = `HTTP ${res.status}`;
    } catch (e) {
      last = e instanceof Error ? e.message : String(e);
    }
    await new Promise((r) => setTimeout(r, 2000));
  }
  throw new Error(`${name} at ${url} is not ready after ${timeoutMs / 1000}s (last: ${last})`);
}

export default async function globalSetup(): Promise<void> {
  await waitFor('the web edge', `${BASE}/healthz`, 120_000);
  await waitFor('Keycloak', `${KC}/realms/travelos`, 240_000);
  // unauthenticated is fine: a 401 proves the edge reached Travel Core; 5xx and transport failures do not
  await waitFor('Travel Core through the edge', `${BASE}/api/v1/trips`, 120_000, [401]);
}
