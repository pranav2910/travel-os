import { useEffect } from 'react';
import { useAuth } from './AuthContext';

/** Hands the current token and the 401 handler to the API client (a module, not a component). */
export function AuthBridge({
  install,
}: {
  install: (token: () => string | null, unauthorized: () => void) => void;
}) {
  const { session, markExpired } = useAuth();
  useEffect(() => {
    install(() => session?.accessToken ?? null, markExpired);
  }, [install, session, markExpired]);
  return null;
}
