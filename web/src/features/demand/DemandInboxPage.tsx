import { useState } from 'react';
import { Link } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { demand, demandKeys } from '@/api/demand';
import { describe } from '@/lib/problem';
import { Alert, EmptyState, LocalDate, PageHeader, Skeleton, StatusBadge, Time } from '@/ui';

const FILTERS = [
  'OPEN',
  'NEEDS_REVIEW',
  'ACTIONABLE',
  'CONVERTED',
  'DISMISSED',
  'WITHDRAWN',
] as const;

export function DemandInboxPage() {
  const [filter, setFilter] = useState<(typeof FILTERS)[number]>('OPEN');
  const status = filter === 'OPEN' ? undefined : filter;
  const q = useQuery({
    queryKey: demandKeys.list(status),
    queryFn: () => demand.list(status),
    refetchInterval: 30_000,
  });
  const rows = (q.data ?? []).filter(
    (c) => filter !== 'OPEN' || c.status === 'NEEDS_REVIEW' || c.status === 'ACTIONABLE',
  );
  return (
    <>
      <PageHeader
        title="Demand inbox"
        lead="Travel the platform noticed from your calendar, CRM and expense connectors (all simulated here). Nothing is booked until a person converts a candidate."
      />
      <div className="row" style={{ marginBottom: 12 }}>
        <label htmlFor="dfilter">Show</label>
        <select
          id="dfilter"
          value={filter}
          onChange={(e) => setFilter(e.target.value as (typeof FILTERS)[number])}
        >
          {FILTERS.map((f) => (
            <option key={f} value={f}>
              {f === 'OPEN'
                ? 'Open (needs review or actionable)'
                : f.replaceAll('_', ' ').toLowerCase()}
            </option>
          ))}
        </select>
      </div>
      {q.isPending && <Skeleton lines={4} />}
      {q.isError && <Alert tone="danger">{describe(q.error)}</Alert>}
      {q.data && rows.length === 0 && (
        <EmptyState title="Nothing detected">
          Connectors feed this inbox after a sync. A travel admin manages connectors.
        </EmptyState>
      )}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table className="t-demand">
            <thead>
              <tr>
                <th>Purpose</th>
                <th>Traveler</th>
                <th>Where</th>
                <th>When</th>
                <th>Status</th>
                <th>Missing</th>
                <th>Updated</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((c) => (
                <tr key={c.candidateId}>
                  <td>
                    <Link to={`/demand/${c.candidateId}`}>{c.purpose || '(no title)'}</Link>
                  </td>
                  <td>{c.travelerId}</td>
                  <td>
                    {c.origin ?? '?'} → {c.destination ?? '?'}
                  </td>
                  <td>
                    <LocalDate date={c.startDate} /> – <LocalDate date={c.endDate} />
                  </td>
                  <td>
                    <StatusBadge status={c.status} />
                  </td>
                  <td>{c.missing.length ? c.missing.join(', ') : '—'}</td>
                  <td>
                    <Time iso={c.updatedAt} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
