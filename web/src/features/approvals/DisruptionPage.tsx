import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/auth/AuthContext';
import { hasRole } from '@/auth/session';
import { disruptions, disruptionKeys } from '@/api/disruptions';
import { TERMINAL_DISRUPTION, type DisruptionView } from '@/api/types';
import { formatMoney, type Money } from '@/lib/money';
import { keyFor, finish } from '@/lib/idempotency';
import { ARRIVAL_WINDOW_MS, arrivalInterval, pollingInterval, wake, wokenAt } from '@/lib/polling';
import { ApiError, describe } from '@/lib/problem';
import {
  Alert,
  Button,
  Card,
  Field,
  KeyValue,
  PageHeader,
  Skeleton,
  StatusBadge,
  Time,
} from '@/ui';
import { NotFound } from '@/shell/guards';

type Doc = Record<string, unknown>;
const money = (m: unknown): Money | null =>
  m && typeof m === 'object' && 'currency' in m && 'amountMinor' in m ? (m as Money) : null;

const notFound = (e: unknown) => e instanceof ApiError && e.is(404);

export function DisruptionPage() {
  const { disruptionId = '' } = useParams();
  const { session } = useAuth();
  const qc = useQueryClient();
  const [openedAt] = useState(() => Date.now());
  // for a bounded while after opening, a 404 is "not here yet" (see arrivalInterval)
  const [stillArriving, setStillArriving] = useState(true);
  useEffect(() => {
    const id = window.setTimeout(() => setStillArriving(false), ARRIVAL_WINDOW_MS);
    return () => window.clearTimeout(id);
  }, []);
  const q = useQuery({
    queryKey: disruptionKeys.one(disruptionId),
    queryFn: () => disruptions.get(disruptionId),
    refetchInterval: (query) => {
      if (query.state.status === 'error')
        return arrivalInterval(openedAt, notFound(query.state.error));
      const d = query.state.data as DisruptionView | undefined;
      if (!d) return false;
      const woke = wokenAt(`disruption:${disruptionId}`);
      const resting = d.status === 'HUMAN_REQUIRED' && woke === null;
      return pollingInterval(
        TERMINAL_DISRUPTION.has(d.status) || resting,
        woke ?? new Date(d.createdAt).getTime(),
      );
    },
  });
  const [comment, setComment] = useState('');
  const decide = useMutation({
    mutationFn: ({ decision, version }: { decision: 'APPROVE' | 'REJECT'; version: number }) =>
      disruptions.decide(
        disruptionId,
        decision,
        comment || undefined,
        keyFor('decide-recovery', { disruptionId, decision, version }),
      ),
    onSuccess: () => {
      finish('decide-recovery', { disruptionId });
      wake(`disruption:${disruptionId}`); // the person acted: the recovery moves again
      void qc.invalidateQueries({ queryKey: disruptionKeys.one(disruptionId) });
      void qc.invalidateQueries({ queryKey: disruptionKeys.list('HUMAN_REQUIRED') });
    },
  });
  if (q.isPending) return <Skeleton lines={5} />;
  if (q.isError) {
    if (notFound(q.error)) {
      if (stillArriving)
        return (
          <Alert tone="info" title="Not here yet.">
            A disruption is recorded a moment after the supplier's notice arrives. Checking again.
          </Alert>
        );
      return <NotFound what="disruption" />;
    }
    return <Alert tone="danger">{describe(q.error)}</Alert>;
  }
  const d = q.data;
  const rec = d.recovery as Doc;
  const dec = (d.decision ?? {}) as Doc;
  const out = (d.outcome ?? {}) as Doc;
  const affected = d.affected as Doc;
  const pending = d.status === 'HUMAN_REQUIRED' && d.approval?.status === 'PENDING';
  const isApprover = hasRole(session, 'MANAGER', 'TRAVEL_ADMIN');
  const self = d.travelerId === session?.employeeId;
  const changes = Array.isArray(dec['componentChanges']) ? (dec['componentChanges'] as Doc[]) : [];
  const rejected = Array.isArray(dec['rejected']) ? (dec['rejected'] as Doc[]) : [];
  const incremental = money(rec['incrementalCost']);
  return (
    <>
      <PageHeader
        title={
          <>
            {d.type.replaceAll('_', ' ').toLowerCase()} <StatusBadge status={d.status} />
          </>
        }
        lead={
          <>
            {d.supplier} · {d.reason ?? ''} · <span className="mono">{d.disruptionId}</span>
          </>
        }
      />
      <div className="grid-2">
        <Card title="What happened">
          <KeyValue
            items={[
              ['Detected', <Time key="d" iso={d.detectedAt} />],
              [
                'Affected',
                affected
                  ? `${String(affected['carrier'] ?? '')} ${String(affected['flightNumber'] ?? '')} ${String(affected['origin'] ?? '')} → ${String(affected['destination'] ?? '')}`
                  : null,
              ],
              [
                'Scheduled',
                affected?.['scheduledDeparture'] ? (
                  <Time key="s" iso={String(affected['scheduledDeparture'])} />
                ) : null,
              ],
              ['Severity', d.severity],
              [
                'Trip',
                d.tripId ? (
                  <Link key="t" to={`/trips/${d.tripId}`}>
                    {d.tripId}
                  </Link>
                ) : (
                  'not one of ours'
                ),
              ],
              ['Supplier reference', d.externalOrderId],
            ]}
          />
        </Card>
        <Card title="Recovery">
          <KeyValue
            items={[
              [
                'Outcome',
                rec['autonomyOutcome'] ? (
                  <StatusBadge key="a" status={String(rec['autonomyOutcome'])} />
                ) : (
                  <span className="muted">not decided yet</span>
                ),
              ],
              [
                'Incremental cost',
                incremental ? (
                  <span key="i" className="mono">
                    {formatMoney(incremental)}
                  </span>
                ) : null,
              ],
              [
                'Replacement',
                rec['replacementBundleId'] ? (
                  <span key="r" className="mono">
                    {String(rec['replacementBundleId'])}
                  </span>
                ) : null,
              ],
              [
                'Policy decision',
                rec['policyDecisionId'] ? (
                  <span key="p" className="mono">
                    {String(rec['policyDecisionId'])}
                  </span>
                ) : null,
              ],
              [
                'Approval',
                d.approval ? (
                  <>
                    <StatusBadge status={d.approval.status} />{' '}
                    {d.approval.decidedBy
                      ? `by ${d.approval.decidedBy}`
                      : `needs ${d.approval.requiredRole}`}
                  </>
                ) : (
                  'not required'
                ),
              ],
              [
                'Learning',
                dec['learningMode']
                  ? `${String(dec['learningMode'])}${dec['learningProfileId'] ? ` · profile ${String(dec['learningProfileId'])}` : ''}${dec['learningFallback'] ? ` · ${String(dec['learningFallback'])}` : ''}`
                  : null,
              ],
            ]}
          />
          {rec['explanation'] ? (
            <p style={{ marginTop: 10 }}>
              <em>{String(rec['explanation'])}</em>
            </p>
          ) : null}
          {d.failureCode && (
            <Alert tone="danger" title={d.failureCode}>
              The recovery stopped at {d.failureStage}.{' '}
              {d.status === 'MANUAL_INTERVENTION_REQUIRED'
                ? 'A person must handle this with the supplier.'
                : ''}
            </Alert>
          )}
          {out['status'] ? (
            <p className="legend">
              Outcome: {String(out['status'])}
              {out['supplierResult'] && typeof out['supplierResult'] === 'object'
                ? ` · supplier ${String((out['supplierResult'] as Doc)['status'] ?? '')}`
                : ''}
            </p>
          ) : null}
        </Card>
      </div>
      {pending && (
        <Card title="Your decision" className="stack-item">
          <p>
            The platform found a compliant replacement but policy limits automatic rebooking (the
            $100 autonomy rule and the approval thresholds are decided by the policy service).
            Approving executes the change shown above; rejecting leaves the booking as it is for a
            person to handle.
          </p>
          {self && (
            <Alert tone="info">
              This is your own trip: you cannot approve its recovery. A manager or travel admin
              decides.
            </Alert>
          )}
          {isApprover && !self && (
            <div className="stack">
              <Field label="Comment (optional)">
                {(p) => (
                  <input
                    {...p}
                    value={comment}
                    onChange={(e) => setComment(e.target.value)}
                    maxLength={2000}
                  />
                )}
              </Field>
              <div className="row">
                <Button
                  variant="primary"
                  busy={decide.isPending}
                  onClick={() => decide.mutate({ decision: 'APPROVE', version: d.version })}
                >
                  Approve the replacement
                </Button>
                <Button
                  variant="danger"
                  busy={decide.isPending}
                  onClick={() => decide.mutate({ decision: 'REJECT', version: d.version })}
                >
                  Reject
                </Button>
              </div>
            </div>
          )}
          {!isApprover && !self && (
            <Alert tone="info">Only a manager or travel admin may decide.</Alert>
          )}
          {decide.error && (
            <Alert tone="danger" title="Refused.">
              {describe(decide.error)}
              {decide.error instanceof ApiError && decide.error.is(409)
                ? ' It was already decided or the recovery moved on; the latest state is shown above.'
                : ''}
            </Alert>
          )}
        </Card>
      )}
      {changes.length > 0 && (
        <Card title="Dependent components" className="stack-item">
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Component</th>
                  <th>Action</th>
                  <th className="num">Before</th>
                  <th className="num">After</th>
                  <th className="num">Delta</th>
                  <th>Reason</th>
                </tr>
              </thead>
              <tbody>
                {changes.map((c, i) => (
                  <tr key={i}>
                    <td>{String(c['type'])}</td>
                    <td>
                      <StatusBadge status={String(c['action'])} />
                    </td>
                    <td className="num">
                      {money(c['previousTotal']) ? formatMoney(money(c['previousTotal'])!) : '—'}
                    </td>
                    <td className="num">
                      {money(c['replacementTotal'])
                        ? formatMoney(money(c['replacementTotal'])!)
                        : '—'}
                    </td>
                    <td className="num">
                      {money(c['delta']) ? formatMoney(money(c['delta'])!) : '—'}
                    </td>
                    <td>{String(c['reason'] ?? '')}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}
      {dec['candidatesSearched'] !== undefined && (
        <Card title="The decision record" className="stack-item">
          <KeyValue
            items={[
              ['Candidates searched', String(dec['candidatesSearched'])],
              ['Permitted by policy', String(dec['candidatesPermitted'])],
              ['Feasible', String(dec['candidatesFeasible'])],
              [
                'Selected',
                dec['selected'] && typeof dec['selected'] === 'object' ? (
                  <span key="s" className="mono">
                    {String((dec['selected'] as Doc)['bundleId'])}
                  </span>
                ) : null,
              ],
            ]}
          />
          {rejected.length > 0 && (
            <details className="disclosure" style={{ marginTop: 8 }}>
              <summary>Rejected candidates ({rejected.length})</summary>
              <ul>
                {rejected.map((r, i) => (
                  <li key={i}>
                    <span className="mono">{String(r['bundleId']).slice(0, 14)}…</span> at{' '}
                    {String(r['stage'])}:{' '}
                    {Array.isArray(r['reasonCodes']) ? r['reasonCodes'].join(', ') : ''}
                  </li>
                ))}
              </ul>
            </details>
          )}
        </Card>
      )}
      <Card title="History" className="stack-item">
        <ul className="timeline">
          {d.history.map((h, i) => (
            <li key={i}>
              <time dateTime={h.occurredAt}>{new Date(h.occurredAt).toLocaleString()}</time>
              <span>
                <StatusBadge status={h.to} /> {h.reason ?? ''}
              </span>
            </li>
          ))}
        </ul>
      </Card>
    </>
  );
}
