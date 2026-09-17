import type { ReactNode } from 'react';
import { useLocation } from 'react-router';
import { useAuth } from '@/auth/AuthContext';
import type { Session } from '@/auth/session';
import { Alert, Button, Skeleton } from '@/ui';

export function SignInPage() {
  const auth = useAuth();
  const location = useLocation();
  const expired = auth.status === 'expired';
  return (
    <main className="shell__main" style={{ maxWidth: 520, margin: '48px auto' }}>
      <h1>travel-os</h1>
      <p className="muted">
        Business travel on a governed platform. Suppliers and connectors are simulated in this
        environment.
      </p>
      {expired && (
        <Alert tone="warn" title="Your session ended.">
          Sign in again to continue where you were.
        </Alert>
      )}
      {auth.status === 'error' && (
        <Alert tone="danger" title="Sign-in problem.">
          {auth.error}
        </Alert>
      )}
      <p>
        <Button
          variant="primary"
          onClick={() => void auth.signIn(location.pathname + location.search)}
        >
          Sign in
        </Button>
      </p>
      <p className="legend">
        Development identities: alice (traveler), bob (manager), carol (travel admin + Finance), dan
        (traveler), zoe (another tenant).
      </p>
    </main>
  );
}

export function RequireAuth({ children }: { children: ReactNode }) {
  const auth = useAuth();
  if (auth.status === 'restoring') {
    return (
      <main className="shell__main" style={{ maxWidth: 520, margin: '48px auto' }}>
        <Skeleton lines={3} />
      </main>
    );
  }
  if (auth.status !== 'signed-in' || !auth.session) return <SignInPage />;
  return <>{children}</>;
}

export function RequireRole({
  allow,
  children,
}: {
  allow: (s: Session | null) => boolean;
  children: ReactNode;
}) {
  const { session } = useAuth();
  if (!allow(session)) return <AccessDenied />;
  return <>{children}</>;
}

export function AccessDenied() {
  const { session } = useAuth();
  return (
    <section className="stack" aria-labelledby="denied">
      <h1 id="denied">Not available for your role</h1>
      <Alert tone="warn">
        This area needs a role your account does not carry (
        {session ? [...session.roles].join(', ') || 'no roles' : 'signed out'}). The platform
        decides access on every request; nothing on this page can grant it.
      </Alert>
    </section>
  );
}

export function NotFound({ what = 'page' }: { what?: string }) {
  return (
    <section className="stack">
      <h1>Not found</h1>
      <Alert tone="info">
        This {what} does not exist, or your account may not see it. Records of other tenants and
        other travelers are never disclosed.
      </Alert>
    </section>
  );
}

export function ErrorPage({ error }: { error: unknown }) {
  const message = error instanceof Error ? error.message : String(error);
  return (
    <section className="stack">
      <h1>Something went wrong</h1>
      <Alert tone="danger" title="The page could not be shown.">
        {message}
      </Alert>
    </section>
  );
}
