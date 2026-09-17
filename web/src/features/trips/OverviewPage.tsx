import { Link } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { useAuth } from '@/auth/AuthContext';
import { capabilities } from '@/auth/session';
import { demand, demandKeys } from '@/api/demand';
import { trips, tripKeys } from '@/api/trips';
import { disruptions, disruptionKeys } from '@/api/disruptions';
import {
  Alert,
  Card,
  EmptyState,
  LinkButton,
  MoneyText,
  PageHeader,
  Skeleton,
  StatusBadge,
  Time,
} from '@/ui';
import { useMyTrips } from './hooks';
import { describe } from '@/lib/problem';

export function OverviewPage() {
  const { session } = useAuth();
  const mine = useMyTrips();
  const canApprove = capabilities.approvals(session);
  const awaiting = useQuery({
    queryKey: tripKeys.tenant('AWAITING_APPROVAL'),
    queryFn: () => trips.listTenant('AWAITING_APPROVAL'),
    enabled: canApprove,
  });
  const recoveries = useQuery({
    queryKey: disruptionKeys.list('HUMAN_REQUIRED'),
    queryFn: () => disruptions.list('HUMAN_REQUIRED'),
    enabled: capabilities.operations(session),
  });
  const myDemand = useQuery({
    queryKey: demandKeys.list('ACTIONABLE'),
    queryFn: () => demand.list('ACTIONABLE'),
  });
  const active = (mine.data ?? []).filter(
    (t) => !['CANCELLED', 'FAILED', 'COMPLETED'].includes(t.status),
  );
  const recent = (mine.data ?? []).slice(0, 5);

  return (
    <>
      <PageHeader
        title={`Hello, ${session?.displayName ?? ''}`}
        lead="Your trips and what needs attention."
        actions={
          <LinkButton to="/trips/new" variant="primary">
            New trip
          </LinkButton>
        }
      />
      <div className="grid-3">
        <Card>
          <div className="stat">
            <span className="stat__value">{mine.isPending ? '…' : active.length}</span>
            <span className="stat__label">Trips in progress or booked</span>
          </div>
        </Card>
        {canApprove && (
          <Card>
            <div className="stat">
              <span className="stat__value">
                {awaiting.isPending ? '…' : (awaiting.data?.length ?? 0)}
              </span>
              <span className="stat__label">Trips awaiting approval</span>
            </div>
          </Card>
        )}
        {capabilities.operations(session) && (
          <Card>
            <div className="stat">
              <span className="stat__value">
                {recoveries.isPending ? '…' : (recoveries.data?.length ?? 0)}
              </span>
              <span className="stat__label">Recoveries needing a person</span>
            </div>
          </Card>
        )}
        <Card>
          <div className="stat">
            <span className="stat__value">
              {myDemand.isPending ? '…' : (myDemand.data?.length ?? 0)}
            </span>
            <span className="stat__label">Detected travel demand to act on</span>
          </div>
        </Card>
      </div>
      <div className="grid-2" style={{ marginTop: 14 }}>
        <Card title="Recent trips" actions={<Link to="/trips">All trips</Link>}>
          {mine.isPending && <Skeleton />}
          {mine.isError && <Alert tone="danger">{describe(mine.error)}</Alert>}
          {mine.data && recent.length === 0 && (
            <EmptyState title="No trips yet">
              Request a trip and the platform plans, governs and books it.
            </EmptyState>
          )}
          {recent.length > 0 && (
            <div className="table-wrap">
              <table className="t-overview">
                <thead>
                  <tr>
                    <th>Trip</th>
                    <th>Status</th>
                    <th>Total</th>
                    <th>Updated</th>
                  </tr>
                </thead>
                <tbody>
                  {recent.map((t) => (
                    <tr key={t.tripId}>
                      <td>
                        <Link to={`/trips/${t.tripId}`}>
                          {t.intent?.purpose ?? t.request?.slice(0, 40) ?? t.tripId}
                        </Link>
                        <br />
                        <small>{route(t)}</small>
                      </td>
                      <td>
                        <StatusBadge status={t.status} />
                      </td>
                      <td>
                        <MoneyText money={t.total} />
                      </td>
                      <td>
                        <Time iso={t.updatedAt} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
        {canApprove && (
          <Card
            title="Awaiting your decision"
            actions={<Link to="/approvals">Approval inbox</Link>}
          >
            {awaiting.isPending && <Skeleton />}
            {awaiting.data && awaiting.data.length === 0 && (
              <EmptyState title="Nothing to approve" />
            )}
            {awaiting.data && awaiting.data.length > 0 && (
              <ul className="timeline">
                {awaiting.data.slice(0, 5).map((t) => (
                  <li key={t.tripId}>
                    <time dateTime={t.updatedAt}>{new Date(t.updatedAt).toLocaleDateString()}</time>
                    <span>
                      <Link to={`/trips/${t.tripId}`}>
                        {t.traveler.givenName} {t.traveler.familyName}: {route(t)}
                      </Link>{' '}
                      · <MoneyText money={t.total} />
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </Card>
        )}
      </div>
    </>
  );
}

export function route(t: {
  intent?:
    | {
        origin?: string;
        destination?: string;
        returnAfter?: string;
        itinerary?: { legs: { origin: string; destination: string }[] };
      }
    | undefined;
  request?: string | undefined;
}): string {
  const i = t.intent;
  if (i?.itinerary?.legs?.length) {
    const legs = i.itinerary.legs;
    return [legs[0]!.origin, ...legs.map((l) => l.destination)].join(' → ');
  }
  if (i?.origin && i?.destination)
    return `${i.origin} → ${i.destination}${i.returnAfter ? ' → ' + i.origin : ''}`;
  return t.request ? 'free-text request' : '—';
}
