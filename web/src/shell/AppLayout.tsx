import { useState } from 'react';
import { NavLink, Outlet } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { useAuth } from '@/auth/AuthContext';
import { capabilities } from '@/auth/session';
import { config } from '@/config';
import { trips, tripKeys } from '@/api/trips';
import { disruptions, disruptionKeys } from '@/api/disruptions';
import { Button } from '@/ui';

function useAttentionCounts(enabled: boolean) {
  const approvals = useQuery({
    queryKey: tripKeys.tenant('AWAITING_APPROVAL'),
    queryFn: () => trips.listTenant('AWAITING_APPROVAL'),
    enabled,
    refetchInterval: 30_000,
    staleTime: 10_000,
  });
  const recoveries = useQuery({
    queryKey: disruptionKeys.list('HUMAN_REQUIRED'),
    queryFn: () => disruptions.list('HUMAN_REQUIRED'),
    enabled,
    refetchInterval: 30_000,
    staleTime: 10_000,
  });
  const n = (approvals.data?.length ?? 0) + (recoveries.data?.length ?? 0);
  return enabled ? n : null;
}

export function AppLayout() {
  const { session, signOut } = useAuth();
  const [open, setOpen] = useState(false);
  const attention = useAttentionCounts(capabilities.approvals(session));
  const cfg = config();
  const roles = session ? [...session.roles] : [];

  const groups: {
    title: string;
    links: { to: string; label: string; count?: number | null; show: boolean }[];
  }[] = [
    {
      title: 'My travel',
      links: [
        { to: '/', label: 'Overview', show: true },
        { to: '/trips', label: 'Trips', show: true },
        { to: '/trips/new', label: 'New trip', show: true },
      ],
    },
    {
      title: 'Needs attention',
      links: [
        {
          to: '/approvals',
          label: 'Approvals',
          count: attention,
          show: capabilities.approvals(session),
        },
        { to: '/operations', label: 'Disruptions', show: capabilities.operations(session) },
        { to: '/finance', label: 'Finance', show: capabilities.finance(session) },
      ],
    },
    {
      title: 'Demand',
      links: [
        { to: '/demand', label: 'Demand inbox', show: true },
        { to: '/connectors', label: 'Connectors', show: capabilities.connectors(session) },
      ],
    },
    {
      title: 'Learning',
      links: [{ to: '/learning', label: 'Learning', show: capabilities.learning(session) }],
    },
  ];

  return (
    <div className="shell">
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <header className="shell__header">
        <Button
          className="menu-toggle"
          variant="ghost"
          aria-expanded={open}
          aria-controls="nav"
          onClick={() => setOpen((o) => !o)}
        >
          Menu
        </Button>
        <NavLink to="/" className="brand">
          travel-os
        </NavLink>
        <span className="sandbox-chip" role="status">
          <span aria-hidden="true">⚠</span>
          {cfg.deploymentClass === 'SANDBOX'
            ? 'Sandbox — simulated bookings'
            : `Live — ${cfg.environmentLabel}`}
        </span>
        <span className="spacer" />
        {session && (
          <div className="user-menu">
            <span>
              <strong>{session.displayName}</strong>{' '}
              <span className="user-menu__roles">
                · {session.tenantId} · {roles.join(', ')}
              </span>
            </span>
            <Button size="sm" onClick={() => void signOut()}>
              Sign out
            </Button>
          </div>
        )}
      </header>
      <nav id="nav" className="shell__nav" aria-label="Main" data-open={open}>
        {groups
          .filter((g) => g.links.some((l) => l.show))
          .map((g) => (
            <div className="nav-group" key={g.title}>
              <div className="nav-group__title">{g.title}</div>
              {g.links
                .filter((l) => l.show)
                .map((l) => (
                  <NavLink
                    key={l.to}
                    to={l.to}
                    end={l.to === '/' || l.to === '/trips'}
                    className="nav-link"
                    onClick={() => setOpen(false)}
                  >
                    <span>{l.label}</span>
                    {typeof l.count === 'number' && l.count > 0 && (
                      <span className="nav-count" aria-label={`${l.count} waiting`}>
                        {l.count}
                      </span>
                    )}
                  </NavLink>
                ))}
            </div>
          ))}
      </nav>
      <main id="main" className="shell__main">
        <Outlet />
      </main>
    </div>
  );
}
