import { Link } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { disruptions, disruptionKeys } from '@/api/disruptions';
import { trips, tripKeys } from '@/api/trips';
import { formatMoney } from '@/lib/money';
import { describe } from '@/lib/problem';
import { Alert, Card, EmptyState, MoneyText, PageHeader, Skeleton, StatusBadge, Time } from '@/ui';
import { route } from '@/features/trips/OverviewPage';

export function ApprovalsPage() {
  const awaiting = useQuery({
    queryKey: tripKeys.tenant('AWAITING_APPROVAL'),
    queryFn: () => trips.listTenant('AWAITING_APPROVAL'),
    refetchInterval: 15_000,
  });
  const human = useQuery({
    queryKey: disruptionKeys.list('HUMAN_REQUIRED'),
    queryFn: () => disruptions.list('HUMAN_REQUIRED'),
    refetchInterval: 15_000,
  });
  return (
    <>
      <PageHeader
        title="Approvals"
        lead="Trips and disruption recoveries waiting for a person. Open one to see the itinerary, the cost and why policy asks."
      />
      <Card title="Trips awaiting approval">
        {awaiting.isPending && <Skeleton />}
        {awaiting.isError && <Alert tone="danger">{describe(awaiting.error)}</Alert>}
        {awaiting.data && awaiting.data.length === 0 && <EmptyState title="Nothing waiting" />}
        {awaiting.data && awaiting.data.length > 0 && (
          <div className="table-wrap">
            <table className="t-approvals">
              <thead>
                <tr>
                  <th>Traveler</th>
                  <th>Trip</th>
                  <th>Route</th>
                  <th>Total</th>
                  <th>Requires</th>
                  <th>Requested</th>
                </tr>
              </thead>
              <tbody>
                {awaiting.data.map((t) => (
                  <tr key={t.tripId}>
                    <td>
                      {t.traveler.givenName} {t.traveler.familyName}
                    </td>
                    <td>
                      <Link to={`/trips/${t.tripId}`}>{t.intent?.purpose || t.tripId}</Link>
                    </td>
                    <td className="route">{route(t)}</td>
                    <td>
                      <MoneyText money={t.total} />
                    </td>
                    <td>{t.approval?.requiredRole ?? '—'}</td>
                    <td>
                      <Time iso={t.approval?.requestedAt ?? t.updatedAt} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
      <Card title="Recoveries needing a decision" className="stack-item">
        {human.isPending && <Skeleton />}
        {human.isError && <Alert tone="danger">{describe(human.error)}</Alert>}
        {human.data && human.data.length === 0 && <EmptyState title="No recovery is waiting" />}
        {human.data && human.data.length > 0 && (
          <div className="table-wrap">
            <table className="t-recoveries">
              <thead>
                <tr>
                  <th>Disruption</th>
                  <th>Trip</th>
                  <th>Incremental cost</th>
                  <th>Status</th>
                  <th>Detected</th>
                </tr>
              </thead>
              <tbody>
                {human.data.map((d) => {
                  const rec = d.recovery as {
                    incrementalCost?: { currency: string; amountMinor: string };
                    autonomyOutcome?: string;
                  };
                  return (
                    <tr key={d.disruptionId}>
                      <td>
                        <Link to={`/disruptions/${d.disruptionId}`}>
                          {d.type.replaceAll('_', ' ').toLowerCase()} · {d.supplier}
                        </Link>
                      </td>
                      <td>
                        {d.tripId ? (
                          <Link to={`/trips/${d.tripId}`}>{d.tripId.slice(0, 16)}…</Link>
                        ) : (
                          '—'
                        )}
                      </td>
                      <td className="mono">
                        {rec.incrementalCost ? formatMoney(rec.incrementalCost) : '—'}
                      </td>
                      <td>
                        <StatusBadge status={d.status} />
                      </td>
                      <td>
                        <Time iso={d.detectedAt} />
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </>
  );
}
