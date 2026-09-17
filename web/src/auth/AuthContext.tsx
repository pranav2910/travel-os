import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import type { ReactNode } from 'react';
import type { User, UserManager } from 'oidc-client-ts';
import { useQueryClient } from '@tanstack/react-query';
import { sessionFrom, type Session } from './session';

export type AuthStatus = 'restoring' | 'signed-out' | 'signed-in' | 'expired' | 'error';

export interface AuthState {
  status: AuthStatus;
  session: Session | null;
  error: string | null;
  signIn: (returnTo?: string) => Promise<void>;
  signOut: () => Promise<void>;
  /** Called by the API client on a 401: drops the session so protected routes ask to sign in. */
  markExpired: () => void;
}

const AuthContext = createContext<AuthState | null>(null);

const RETURN_TO_KEY = 'travelos.returnTo';

export function AuthProvider({
  userManager,
  children,
}: {
  userManager: UserManager;
  children: ReactNode;
}) {
  const [status, setStatus] = useState<AuthStatus>('restoring');
  const [session, setSession] = useState<Session | null>(null);
  const [error, setError] = useState<string | null>(null);
  const queryClient = useQueryClient();
  const identity = useRef<string | null>(null);

  const apply = useCallback(
    (user: User | null) => {
      const next = user && !user.expired ? sessionFrom(user) : null;
      if (!next) {
        setSession(null);
        setStatus(user ? 'error' : 'signed-out');
        if (user) setError('the token carries no tenant: this account is not provisioned');
        return;
      }
      const key = `${next.tenantId}/${next.username}`;
      if (identity.current !== null && identity.current !== key) {
        // another account: nothing cached for the previous one may leak into this one
        queryClient.clear();
      }
      identity.current = key;
      setSession(next);
      setStatus('signed-in');
      setError(null);
    },
    [queryClient],
  );

  useEffect(() => {
    let cancelled = false;
    const restore = async () => {
      try {
        if (window.location.pathname === '/auth/callback') {
          const user = await userManager.signinCallback();
          if (cancelled) return;
          const returnTo = window.sessionStorage.getItem(RETURN_TO_KEY) ?? '/';
          window.sessionStorage.removeItem(RETURN_TO_KEY);
          // the router is mounted below this provider: tell it the URL changed
          window.history.replaceState({}, '', returnTo);
          window.dispatchEvent(new PopStateEvent('popstate'));
          apply(user ?? null);
          return;
        }
        const existing = await userManager.getUser();
        if (existing && !existing.expired) {
          apply(existing);
          return;
        }
        // Tokens are never persisted; a reload restores the session from Keycloak's SSO cookie.
        const renewed = await userManager.signinSilent().catch(() => null);
        if (cancelled) return;
        apply(renewed);
      } catch (e) {
        if (cancelled) return;
        setStatus('error');
        setError(e instanceof Error ? e.message : String(e));
      }
    };
    void restore();
    const onLoaded = (user: User) => apply(user);
    const onExpired = () => {
      setSession(null);
      setStatus('expired');
    };
    const onSilentError = () => {
      setSession(null);
      setStatus('expired');
    };
    userManager.events.addUserLoaded(onLoaded);
    userManager.events.addAccessTokenExpired(onExpired);
    userManager.events.addSilentRenewError(onSilentError);
    userManager.events.addUserSignedOut(onExpired);
    return () => {
      cancelled = true;
      userManager.events.removeUserLoaded(onLoaded);
      userManager.events.removeAccessTokenExpired(onExpired);
      userManager.events.removeSilentRenewError(onSilentError);
      userManager.events.removeUserSignedOut(onExpired);
    };
  }, [userManager, apply]);

  const signIn = useCallback(
    async (returnTo?: string) => {
      window.sessionStorage.setItem(
        RETURN_TO_KEY,
        returnTo ?? window.location.pathname + window.location.search,
      );
      await userManager.signinRedirect();
    },
    [userManager],
  );

  const signOut = useCallback(async () => {
    // Keycloak ends the SSO session and redirects back to "/"; the page then starts signed out.
    // Local state is dropped only if that redirect cannot happen (the user is removed either way).
    queryClient.clear();
    identity.current = null;
    try {
      await userManager.signoutRedirect();
    } catch {
      await userManager.removeUser();
      setSession(null);
      setStatus('signed-out');
    }
  }, [userManager, queryClient]);

  const markExpired = useCallback(() => {
    setSession(null);
    setStatus('expired');
    void userManager.removeUser();
  }, [userManager]);

  const value = useMemo<AuthState>(
    () => ({ status, session, error, signIn, signOut, markExpired }),
    [status, session, error, signIn, signOut, markExpired],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth outside AuthProvider');
  return ctx;
}
