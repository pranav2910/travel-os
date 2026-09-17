import { InMemoryWebStorage, UserManager, WebStorageStateStore } from 'oidc-client-ts';
import type { RuntimeConfig } from '@/config';

/**
 * Authorization Code + PKCE against the realm's public client. Tokens live in memory only: a
 * reload restores the session through Keycloak's SSO cookie (silent sign-in in an iframe), never
 * from local or session storage. Interaction state (the PKCE verifier between redirect and
 * callback) has to survive the redirect, so it uses sessionStorage and is cleared on callback.
 */
export function createUserManager(cfg: RuntimeConfig): UserManager {
  const origin = window.location.origin;
  return new UserManager({
    authority: cfg.oidcAuthority,
    client_id: cfg.oidcClientId,
    redirect_uri: `${origin}/auth/callback`,
    post_logout_redirect_uri: `${origin}/`,
    silent_redirect_uri: `${origin}/silent-renew.html`,
    response_type: 'code',
    scope: 'openid', // the client's default scopes (profile, email, roles, travelos) are applied by the realm
    automaticSilentRenew: true,
    silentRequestTimeoutInSeconds: 10,
    // access tokens expire after 15 minutes at the realm; renew a minute early
    accessTokenExpiringNotificationTimeInSeconds: 60,
    loadUserInfo: false,
    monitorSession: false,
    revokeTokensOnSignout: true,
    userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
    stateStore: new WebStorageStateStore({ store: window.sessionStorage }),
  });
}
