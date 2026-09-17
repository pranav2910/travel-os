import { useState } from 'react';
import { Link } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { disruptions, disruptionKeys } from '@/api/disruptions';
import { formatMoney } from '@/lib/money';
import { describe } from '@/lib/problem';
import { Alert, EmptyState, PageHeader, Skeleton, StatusBadge, Time } from '@/ui';

const FILTERS = [
  'ALL',
  'HUMAN_REQUIRED',
  'RESOLVED',
  'MANUAL_INTERVENTION_REQUIRED',
  'NO_ALTERNATIVE',
  'FAILED',
] as const;

export function OperationsPage() {
  const [filter, setFilter] = useState<(typeof FILTERS)[number]>('ALL');
  const q = useQuery({
    queryKey: disruptionKeys.list(filter === 'ALL' ? undefined : filter),
    queryFn: () => disruptions.list(filter === 'ALL' ? undefined : filter),
    refetchInterval: 20_000,
  });
  return (
    <>
      <PageHeader
        title="Disruptions"
        lead="Supplier notices and what the platform did about them. Pending, partially changed and unresolved states are shown as they are."
      />
      <div className="row" style={{ marginBottom: 12 }}>
        <label htmlFor="filter">Show</label>
        <select
          id="filter"
          value={filter}
          onChange={(e) => setFilter(e.target.value as (typeof FILTERS)[number])}
        >
          {FILTERS.map((f) => (
            <option key={f} value={f}>
              {f === 'ALL' ? 'All' : f.replaceAll('_', ' ').toLowerCase()}
            </option>
          ))}
        </select>
      </div>
      {q.isPending && <Skeleton lines={4} />}
      {q.isError && <Alert tone="danger">{describe(q.error)}</Alert>}
      {q.data && q.data.length === 0 && (
        <EmptyState title="No disruptions">
          When a simulated supplier cancels or changes a booked service, it appears here with its
          recovery.
        </EmptyState>
      )}
      {q.data && q.data.length > 0 && (
        <div className="table-wrap">
          <table className="t-disruptions">
            <thead>
              <tr>
                <th>Detected</th>
                <th>Event</th>
                <th>Trip</th>
                <th>Status</th>
                <th>Recovery</th>
                <th>Approval</th>
              </tr>
            </thead>
            <tbody>
              {q.data.map((d) => {
                const rec = d.recovery as {
                  incrementalCost?: { currency: string; amountMinor: string };
                  autonomyOutcome?: string;
                };
                return (
                  <tr key={d.disruptionId}>
                    <td>
                      <Time iso={d.detectedAt} />
                    </td>
                    <td>
                      <Link to={`/disruptions/${d.disruptionId}`}>
                        {d.type.replaceAll('_', ' ').toLowerCase()}
                      </Link>
                      <br />
                      <small>
                        {d.supplier} · {d.reason ?? ''}
                      </small>
                    </td>
                    <td>
                      {d.tripId ? (
                        <Link to={`/trips/${d.tripId}`}>{d.tripId.slice(0, 14)}…</Link>
                      ) : (
                        <span className="muted">not ours</span>
                      )}
                    </td>
                    <td>
                      <StatusBadge status={d.status} />
                    </td>
                    <td>
                      {rec.autonomyOutcome ? (
                        <>
                          <StatusBadge status={rec.autonomyOutcome} />{' '}
                          {rec.incrementalCost ? (
                            <span className="mono">{formatMoney(rec.incrementalCost)}</span>
                          ) : (
                            ''
                          )}
                        </>
                      ) : (
                        '—'
                      )}
                    </td>
                    <td>{d.approval ? <StatusBadge status={d.approval.status} /> : '—'}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
