import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/auth/AuthContext';
import { hasRole } from '@/auth/session';
import { connectors, connectorKeys } from '@/api/demand';
import type { ConnectorView } from '@/api/types';
import { keyFor, finish } from '@/lib/idempotency';
import { describe } from '@/lib/problem';
import {
  Alert,
  Badge,
  Button,
  Card,
  EmptyState,
  Field,
  PageHeader,
  Skeleton,
  StatusBadge,
  Time,
} from '@/ui';

const KINDS = ['CALENDAR', 'CRM', 'HRIS', 'EXPENSE'] as const;
const PROVIDERS: Record<(typeof KINDS)[number], string> = {
  CALENDAR: 'sandbox-calendar',
  CRM: 'sandbox-crm',
  HRIS: 'sandbox-hris',
  EXPENSE: 'sandbox-expense',
};

export function ConnectorsPage() {
  const { session } = useAuth();
  const admin = hasRole(session, 'TRAVEL_ADMIN');
  const qc = useQueryClient();
  const q = useQuery({
    queryKey: connectorKeys.list(),
    queryFn: () => connectors.list(),
    refetchInterval: 20_000,
  });
  const [kind, setKind] = useState<(typeof KINDS)[number]>('HRIS');
  const [scheduled, setScheduled] = useState(false);
  const create = useMutation({
    mutationFn: () => {
      const body = { kind, provider: PROVIDERS[kind], config: { scheduled } };
      return connectors.create(body, keyFor('connector-create', body));
    },
    onSuccess: () => {
      finish('connector-create', {});
      void qc.invalidateQueries({ queryKey: connectorKeys.list() });
    },
  });
  return (
    <>
      <PageHeader
        title="Connectors"
        lead="The enterprise systems the platform reads travel demand from. Every provider here is SIMULATED: a sandbox table with the same paging contract a live provider would have. No live calendar, CRM, HRIS or expense system is connected."
      />
      <Alert tone="warn" title="Simulated providers only.">
        There is no live OAuth connection to offer; the four sandbox providers are the only
        adapters. Live providers are deferred.
      </Alert>
      {admin && (
        <Card title="Add a sandbox connector" className="stack-item">
          <form
            className="row"
            onSubmit={(e) => {
              e.preventDefault();
              create.mutate();
            }}
          >
            <Field label="Kind">
              {(p) => (
                <select
                  {...p}
                  value={kind}
                  onChange={(e) => setKind(e.target.value as (typeof KINDS)[number])}
                >
                  {KINDS.map((k) => (
                    <option key={k} value={k}>
                      {k} ({PROVIDERS[k]})
                    </option>
                  ))}
                </select>
              )}
            </Field>
            <label>
              <input
                type="checkbox"
                checked={scheduled}
                onChange={(e) => setScheduled(e.target.checked)}
              />{' '}
              Scheduled sync (otherwise only on request or webhook)
            </label>
            <Button type="submit" variant="primary" busy={create.isPending}>
              Create
            </Button>
          </form>
          {create.error && <Alert tone="danger">{describe(create.error)}</Alert>}
        </Card>
      )}
      {q.isPending && <Skeleton lines={4} />}
      {q.isError && <Alert tone="danger">{describe(q.error)}</Alert>}
      {q.data && q.data.length === 0 && (
        <EmptyState title="No connectors">
          A travel admin adds the sandbox connectors; the HRIS one provides the verified employee
          directory the others rely on.
        </EmptyState>
      )}
      <div className="grid-2">
        {q.data?.map((c) => (
          <ConnectorCard key={c.connectorId} c={c} admin={admin} />
        ))}
      </div>
    </>
  );
}

function ConnectorCard({ c, admin }: { c: ConnectorView; admin: boolean }) {
  const qc = useQueryClient();
  const runs = useQuery({
    queryKey: connectorKeys.runs(c.connectorId),
    queryFn: () => connectors.runs(c.connectorId),
    refetchInterval: c.runningRunId ? 3000 : 30_000,
  });
  const invalidate = () => {
    void qc.invalidateQueries({ queryKey: ['connectors'] });
  };
  const sync = useMutation({
    mutationFn: () =>
      connectors.sync(
        c.connectorId,
        keyFor('connector-sync', { id: c.connectorId, v: c.version, run: c.lastRunId ?? '' }),
      ),
    onSuccess: () => {
      finish('connector-sync', { id: c.connectorId, v: c.version, run: c.lastRunId ?? '' });
      invalidate();
    },
  });
  const status = useMutation({
    mutationFn: (s: 'ENABLED' | 'DISABLED') =>
      connectors.setStatus(
        c.connectorId,
        s,
        keyFor('connector-status', { id: c.connectorId, s, v: c.version }),
      ),
    onSuccess: invalidate,
  });
  const config = useMutation({
    mutationFn: (scheduled: boolean) =>
      connectors.setConfig(
        c.connectorId,
        { ...c.config, scheduled },
        keyFor('connector-config', { id: c.connectorId, scheduled, v: c.version }),
      ),
    onSuccess: invalidate,
  });
  const scheduled = c.config['scheduled'] !== false;
  const err = sync.error ?? status.error ?? config.error;
  return (
    <Card
      title={
        <>
          {c.kind} <StatusBadge status={c.status} />{' '}
          {c.simulated && <Badge tone="warn">simulated</Badge>}
        </>
      }
      actions={<span className="mono muted">{c.provider}</span>}
    >
      <dl className="kv">
        <dt>Last sync</dt>
        <dd>
          <Time iso={c.lastSyncAt} />{' '}
          {c.lastErrorCode && <Badge tone="danger">{c.lastErrorCode}</Badge>}
        </dd>
        <dt>Last success</dt>
        <dd>
          <Time iso={c.lastSuccessAt} />
        </dd>
        <dt>Checkpoint</dt>
        <dd className="mono">{c.checkpoint || '—'}</dd>
        <dt>Schedule</dt>
        <dd>
          {scheduled ? 'periodic' : 'on request / webhook only'}
          {c.nextSyncAt && scheduled ? (
            <>
              {' '}
              · next <Time iso={c.nextSyncAt} />
            </>
          ) : (
            ''
          )}
        </dd>
        {c.runningRunId && (
          <>
            <dt>Running</dt>
            <dd>
              <StatusBadge status="RUNNING" /> <span className="mono">{c.runningRunId}</span>
            </dd>
          </>
        )}
      </dl>
      {admin && (
        <div className="row" style={{ marginTop: 10 }}>
          <Button
            size="sm"
            variant="primary"
            busy={sync.isPending}
            disabled={c.status !== 'ENABLED' || !!c.runningRunId}
            onClick={() => sync.mutate()}
          >
            Sync now
          </Button>
          <Button
            size="sm"
            busy={status.isPending}
            onClick={() => status.mutate(c.status === 'ENABLED' ? 'DISABLED' : 'ENABLED')}
          >
            {c.status === 'ENABLED' ? 'Disable' : 'Enable'}
          </Button>
          <Button size="sm" busy={config.isPending} onClick={() => config.mutate(!scheduled)}>
            {scheduled ? 'Stop scheduled sync' : 'Schedule sync'}
          </Button>
        </div>
      )}
      {err && <Alert tone="danger">{describe(err)}</Alert>}
      <details className="disclosure" style={{ marginTop: 10 }}>
        <summary>Runs ({runs.data?.length ?? 0})</summary>
        {runs.data && runs.data.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Started</th>
                  <th>Trigger</th>
                  <th>Status</th>
                  <th className="num">Pages</th>
                  <th className="num">Items</th>
                  <th className="num">Changed</th>
                  <th className="num">Candidates</th>
                </tr>
              </thead>
              <tbody>
                {runs.data.slice(0, 10).map((r) => (
                  <tr key={r.runId}>
                    <td>
                      <Time iso={r.startedAt} />
                    </td>
                    <td>{r.trigger.toLowerCase()}</td>
                    <td>
                      <StatusBadge status={r.status} />
                      {r.failureCode && <small> {r.failureCode}</small>}
                    </td>
                    <td className="num">{r.pages}</td>
                    <td className="num">{r.itemsSeen}</td>
                    <td className="num">{r.itemsChanged}</td>
                    <td className="num">{r.candidatesTouched}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </details>
    </Card>
  );
}
