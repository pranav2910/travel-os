import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { useAuth } from '@/auth/AuthContext';
import { hasRole } from '@/auth/session';
import { audit, auditKeys } from '@/api/audit';
import { disruptions, disruptionKeys } from '@/api/disruptions';
import { orders, orderKeys } from '@/api/orders';
import { policy, policyKeys } from '@/api/policy';
import { trips, tripKeys } from '@/api/trips';
import type {
  DecisionLedger,
  FlightView,
  ItemView,
  OrderResponse,
  TripResponse,
} from '@/api/types';
import { crossesMidnight, formatTime, formatDateInZone } from '@/lib/dates';
import { ApiError, describe } from '@/lib/problem';
import {
  Alert,
  Button,
  Card,
  Dialog,
  Field,
  KeyValue,
  LocalDate,
  MoneyText,
  PageHeader,
  Skeleton,
  StatusBadge,
  Time,
} from '@/ui';
import { NotFound } from '@/shell/guards';
import { useCancelTrip, useCompleteTrip, useDecideTrip, useFollowTrip, useTrip } from './hooks';
import { STEPS, explainFailure, isTerminal, stepState } from './status';
import { route } from './OverviewPage';
import { FeedbackPanel } from './FeedbackPanel';

export function TripDetailPage() {
  const { tripId = '' } = useParams();
  const { session } = useAuth();
  const trip = useTrip(tripId);
  useFollowTrip(trip.data);
  if (trip.isPending) return <Skeleton lines={5} />;
  if (trip.isError) {
    if (trip.error instanceof ApiError && trip.error.is(404)) return <NotFound what="trip" />;
    return (
      <Alert tone="danger" title="The trip could not be loaded.">
        {describe(trip.error)}
      </Alert>
    );
  }
  const t = trip.data;
  const mine = t.travelerId === session?.employeeId;
  return (
    <>
      <PageHeader
        title={
          <>
            {t.intent?.purpose || 'Trip'} <StatusBadge status={t.status} />
          </>
        }
        lead={
          <>
            {route(t)} · <span className="mono">{t.tripId}</span>
            {!mine && (
              <>
                {' '}
                · traveler {t.traveler.givenName} {t.traveler.familyName}
              </>
            )}
          </>
        }
        actions={<TripActions trip={t} mine={mine} />}
      />
      <Progress trip={t} />
      <div className="grid-2">
        <Card title="Request">
          <RequestSummary trip={t} />
        </Card>
        <Card title="Outcome">
          <KeyValue
            items={[
              ['Total', <MoneyText key="t" money={t.total} />],
              [
                'Approval',
                t.approval ? (
                  <>
                    <StatusBadge status={t.approval.status} />{' '}
                    {t.approval.decidedBy ? (
                      <>by {t.approval.decidedBy}</>
                    ) : (
                      <>needs {t.approval.requiredRole}</>
                    )}
                  </>
                ) : (
                  <span className="muted">not required</span>
                ),
              ],
              [
                'Order',
                t.evidence.orderId ? <span className="mono">{t.evidence.orderId}</span> : null,
              ],
              [
                'Policy decision',
                t.evidence.policyDecisionId ? (
                  <span className="mono">{t.evidence.policyDecisionId}</span>
                ) : null,
              ],
              ['Updated', <Time key="u" iso={t.updatedAt} />],
            ]}
          />
          {t.status === 'FAILED' && (
            <Alert tone="danger" title="Not booked.">
              {explainFailure(t)}
            </Alert>
          )}
          {t.explanation && (
            <p style={{ marginTop: 10 }}>
              <em>{t.explanation}</em>
            </p>
          )}
        </Card>
      </div>
      <Components trip={t} />
      <Orders tripId={t.tripId} />
      <Disruptions tripId={t.tripId} />
      <Decision tripId={t.tripId} />
      <Timeline tripId={t.tripId} />
      {(mine || hasRole(session, 'TRAVEL_ADMIN')) &&
        (t.status === 'BOOKED' || t.status === 'COMPLETED') && (
          <FeedbackPanel trip={t} mine={mine} />
        )}
    </>
  );
}

function Progress({ trip }: { trip: TripResponse }) {
  return (
    <ol className="progress-steps" aria-label="Progress" style={{ marginBottom: 14 }}>
      {STEPS.map((s) => {
        const st = stepState(trip, s);
        return (
          <li key={s.key} data-state={st} aria-current={st === 'current' ? 'step' : undefined}>
            {st === 'done' ? '✓ ' : st === 'failed' ? '✕ ' : ''}
            {s.label}
            {st === 'skipped' ? ' (not needed)' : ''}
          </li>
        );
      })}
      {trip.status === 'CANCELLED' && <li data-state="failed">Cancelled</li>}
    </ol>
  );
}

function RequestSummary({ trip }: { trip: TripResponse }) {
  const i = trip.intent;
  if (!i)
    return (
      <p>
        {trip.request ? (
          <>Free text: “{trip.request}” — the platform is still extracting the need.</>
        ) : (
          <span className="muted">No details yet.</span>
        )}
      </p>
    );
  if (i.itinerary) {
    return (
      <>
        {trip.request && <p className="muted">From your words: “{trip.request}”</p>}
        <ul style={{ paddingLeft: 18, margin: 0 }}>
          {i.itinerary.legs.map((l, n) => (
            <li key={n}>
              Flight {l.origin} → {l.destination}, depart after <Time iso={l.earliestDeparture} />,
              arrive by <Time iso={l.arrivalDeadline} />
            </li>
          ))}
          {i.itinerary.stays?.map((s, n) => (
            <li key={`s${n}`}>
              Hotel in {s.city}, <LocalDate date={s.checkInDate} /> to{' '}
              <LocalDate date={s.checkOutDate} /> (property's local dates)
            </li>
          ))}
          {i.itinerary.transfers?.map((x, n) => (
            <li key={`x${n}`}>
              Transfer in {x.city}: {x.kind.replaceAll('_', ' ').toLowerCase()}
            </li>
          ))}
        </ul>
      </>
    );
  }
  return (
    <>
      {trip.request && <p className="muted">From your words: “{trip.request}”</p>}
      <KeyValue
        items={[
          ['Route', `${i.origin} → ${i.destination}${i.returnAfter ? ` → ${i.origin}` : ''}`],
          [
            'Outbound',
            <>
              after <Time iso={i.earliestDeparture} />, arrive by <Time iso={i.arrivalDeadline} />
            </>,
          ],
          [
            'Return',
            i.returnAfter ? (
              <>
                after <Time iso={i.returnAfter} />, latest <Time iso={i.latestReturn} />
              </>
            ) : null,
          ],
          ['Hotel', i.hotelRequired ? 'required' : 'not requested'],
        ]}
      />
    </>
  );
}

function TripActions({ trip, mine }: { trip: TripResponse; mine: boolean }) {
  const { session } = useAuth();
  const cancel = useCancelTrip(trip.tripId);
  const complete = useCompleteTrip(trip.tripId);
  const decide = useDecideTrip(trip.tripId);
  const [cancelOpen, setCancelOpen] = useState(false);
  const [reason, setReason] = useState('');
  const [comment, setComment] = useState('');
  const canCancel = (mine || hasRole(session, 'TRAVEL_ADMIN')) && !isTerminal(trip.status);
  const canCancelBooked = (mine || hasRole(session, 'TRAVEL_ADMIN')) && trip.status === 'BOOKED';
  const canAttest = trip.status === 'BOOKED' && (mine || hasRole(session, 'TRAVEL_ADMIN'));
  const isApprover = hasRole(session, 'MANAGER', 'TRAVEL_ADMIN');
  const awaiting = trip.status === 'AWAITING_APPROVAL' && trip.approval?.status === 'PENDING';
  const selfApproval = awaiting && mine;
  const decideErr = decide.error;
  const err = cancel.error ?? complete.error;
  return (
    <div className="stack" style={{ alignItems: 'flex-end' }}>
      <div className="row">
        {awaiting && isApprover && !selfApproval && (
          <>
            <Button
              variant="primary"
              busy={decide.isPending}
              onClick={() =>
                decide.mutate({
                  decision: 'APPROVE',
                  comment: comment || undefined,
                  version: trip.version,
                })
              }
            >
              Approve
            </Button>
            <Button
              variant="danger"
              busy={decide.isPending}
              onClick={() =>
                decide.mutate({
                  decision: 'REJECT',
                  comment: comment || undefined,
                  version: trip.version,
                })
              }
            >
              Reject
            </Button>
          </>
        )}
        {(canCancel || canCancelBooked) && (
          <Button onClick={() => setCancelOpen(true)}>Cancel trip</Button>
        )}
        {canAttest && (
          <Button
            busy={complete.isPending}
            onClick={() => complete.mutate()}
            title="The traveler may attest after the last arrival; a travel admin at any time"
          >
            Confirm the trip happened
          </Button>
        )}
      </div>
      {awaiting && isApprover && !selfApproval && (
        <Field label="Comment for the traveler (optional)">
          {(p) => (
            <input
              {...p}
              value={comment}
              onChange={(e) => setComment(e.target.value)}
              maxLength={2000}
              style={{ minWidth: 260 }}
            />
          )}
        </Field>
      )}
      {selfApproval && (
        <Alert tone="info">
          You cannot approve your own trip. A {trip.approval?.requiredRole ?? 'manager'} decides;
          the platform refuses self-approval on every request.
        </Alert>
      )}
      {decideErr && (
        <Alert tone="danger" title="Decision refused.">
          {describe(decideErr)}
          {decideErr instanceof ApiError && decideErr.is(409)
            ? ' The trip changed meanwhile; review the latest version above before deciding again.'
            : ''}
        </Alert>
      )}
      {err && (
        <Alert tone="danger" title="Not done.">
          {describe(err)}
        </Alert>
      )}
      <Dialog
        open={cancelOpen}
        onClose={() => setCancelOpen(false)}
        title="Cancel this trip?"
        actions={
          <>
            <Button onClick={() => setCancelOpen(false)}>Keep it</Button>
            <Button
              variant="danger"
              busy={cancel.isPending}
              disabled={reason.trim().length === 0}
              onClick={() =>
                cancel.mutate(reason.trim(), { onSuccess: () => setCancelOpen(false) })
              }
            >
              Cancel the trip
            </Button>
          </>
        }
      >
        <p>
          {trip.status === 'BOOKED'
            ? 'The booked components are cancelled with the suppliers; a refund is not assumed until Finance records it.'
            : 'The planning stops; nothing is booked.'}
        </p>
        <Field label="Reason">
          {(p) => (
            <input
              {...p}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              maxLength={500}
            />
          )}
        </Field>
      </Dialog>
    </div>
  );
}

function Components({ trip }: { trip: TripResponse }) {
  if (!trip.components || trip.components.length === 0) return null;
  return (
    <Card title="Itinerary components" className="stack-item">
      {trip.components.map((c) => (
        <div className="component" key={c.componentId}>
          <span className="component__type">{c.type}</span>
          <div>
            <div className="component__title">{c.summary ?? c.type}</div>
            <div className="component__meta">
              {c.provider ?? ''}
              {c.externalRef ? ` · ref ${c.externalRef}` : ''}
              {c.failureCode ? ` · ${c.failureCode}` : ''}
            </div>
          </div>
          <div style={{ textAlign: 'right' }}>
            <StatusBadge status={c.status} />
            <br />
            <MoneyText money={c.total} />
          </div>
        </div>
      ))}
    </Card>
  );
}

function Orders({ tripId }: { tripId: string }) {
  const q = useQuery({ queryKey: orderKeys.byTrip(tripId), queryFn: () => orders.byTrip(tripId) });
  if (q.isPending)
    return (
      <Card title="Bookings">
        <Skeleton />
      </Card>
    );
  if (q.isError)
    return (
      <Card title="Bookings">
        <Alert tone="warn">{describe(q.error)}</Alert>
      </Card>
    );
  if (q.data.length === 0) return null;
  return (
    <>
      {q.data.map((o) => (
        <OrderCard key={o.orderId} order={o} />
      ))}
    </>
  );
}

function OrderCard({ order }: { order: OrderResponse }) {
  return (
    <Card
      title={
        <>
          Booking <StatusBadge status={order.status} />
        </>
      }
      actions={<span className="mono muted">{order.orderId}</span>}
    >
      <KeyValue
        items={[
          ['Supplier', order.supplier],
          ['Supplier reference', order.externalOrderId ?? null],
          ['Total', <MoneyText key="t" money={order.total} />],
          [
            'Compensation',
            order.compensated
              ? 'completed'
              : order.status === 'PARTIALLY_FAILED'
                ? 'incomplete: a person must resolve the exposure'
                : 'not needed',
          ],
        ]}
      />
      {order.failureCode && (
        <Alert tone="danger" title={order.failureCode}>
          {order.failureMessage}
        </Alert>
      )}
      <div className="table-wrap" style={{ marginTop: 10 }}>
        <table>
          <thead>
            <tr>
              <th>Item</th>
              <th>Details</th>
              <th>Status</th>
              <th>Reference</th>
              <th className="num">Total</th>
            </tr>
          </thead>
          <tbody>
            {order.items.map((i) => (
              <tr key={i.itemId}>
                <td>
                  {i.type}
                  <br />
                  <small>{i.provider}</small>
                </td>
                <td>
                  <ItemDetails item={i} />
                </td>
                <td>
                  <StatusBadge status={i.status} />
                  {i.failureCode && (
                    <>
                      <br />
                      <small>{i.failureCode}</small>
                    </>
                  )}
                </td>
                <td className="mono">{i.recordLocator ?? i.externalRef ?? '—'}</td>
                <td className="num">
                  <MoneyText money={i.total} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {order.changes && order.changes.length > 0 && (
        <details className="disclosure" style={{ marginTop: 10 }}>
          <summary>{order.changes.length} change(s) from disruption recovery</summary>
          <ul>
            {order.changes.map((c) => (
              <li key={c.changeId}>
                <StatusBadge status={c.status} /> {c.previousBundleId.slice(0, 12)}… →{' '}
                {c.replacementBundleId.slice(0, 12)}… · incremental{' '}
                <MoneyText money={c.incrementalCost} />
                {c.disruptionId && (
                  <>
                    {' '}
                    · <Link to={`/disruptions/${c.disruptionId}`}>disruption</Link>
                  </>
                )}
              </li>
            ))}
          </ul>
        </details>
      )}
      {order.exposures && order.exposures.length > 0 && (
        <Alert
          tone={order.exposures.some((e) => e.status === 'OPEN') ? 'warn' : 'info'}
          title="Financial exposure."
        >
          {order.exposures.map((e) => (
            <div key={e.exposureId}>
              <StatusBadge status={e.status} /> {e.provider} {e.externalRef}:{' '}
              <MoneyText money={e.amount} /> — {e.reason}
              {e.detail ? ` (${e.detail})` : ''}
              {e.resolution ? ` · resolved by ${e.resolvedBy}: ${e.resolution}` : ''}
            </div>
          ))}
          {order.exposures.some((e) => e.status === 'OPEN') && (
            <p style={{ margin: '6px 0 0' }}>
              An open exposure is money the company may still owe; a travel admin or Finance closes
              it after dealing with the supplier.
            </p>
          )}
        </Alert>
      )}
    </Card>
  );
}

function ItemDetails({ item }: { item: ItemView }) {
  if (item.hotel) {
    const h = item.hotel;
    return (
      <>
        {h.name} ({h.city}) · <LocalDate date={h.checkInDate} /> →{' '}
        <LocalDate date={h.checkOutDate} />, {h.nights} night(s) · {h.timeZone}
      </>
    );
  }
  if (item.ground) {
    const g = item.ground;
    return (
      <>
        {g.vendorName} {g.vehicleClass} · {g.pickupLocation} → {g.dropoffLocation} · pickup{' '}
        <Time iso={g.pickup} zone={g.timeZone} />
      </>
    );
  }
  return (
    <>
      {item.flights.map((f, n) => (
        <div key={n}>
          <Flight f={f} />
        </div>
      ))}
    </>
  );
}

function Flight({ f }: { f: FlightView }) {
  const overnight = f.departure && f.arrival ? crossesMidnight(f.departure, f.arrival) : false;
  return (
    <span>
      {f.direction.toLowerCase()} {f.carrier} {f.flightNumber} {f.origin} → {f.destination} ·{' '}
      {f.departure ? `${formatDateInZone(f.departure)} ${formatTime(f.departure)}` : '—'} →{' '}
      {f.arrival ? formatTime(f.arrival) : '—'} UTC{overnight ? ' (+1 day)' : ''} ·{' '}
      {f.cabin.toLowerCase()}
    </span>
  );
}

function Disruptions({ tripId }: { tripId: string }) {
  const q = useQuery({
    queryKey: disruptionKeys.byTrip(tripId),
    queryFn: () => disruptions.byTrip(tripId),
  });
  if (!q.data || q.data.length === 0) return null;
  return (
    <Card title="Disruptions">
      <ul className="timeline">
        {q.data.map((d) => (
          <li key={d.disruptionId}>
            <time dateTime={d.detectedAt}>{new Date(d.detectedAt).toLocaleString()}</time>
            <span>
              <StatusBadge status={d.status} /> {d.type.replaceAll('_', ' ').toLowerCase()} by{' '}
              {d.supplier}
              {d.reason ? `: ${d.reason}` : ''} ·{' '}
              <Link to={`/disruptions/${d.disruptionId}`}>recovery details</Link>
            </span>
          </li>
        ))}
      </ul>
    </Card>
  );
}

function Decision({ tripId }: { tripId: string }) {
  const ledger = useQuery({
    queryKey: auditKeys.ledger(tripId),
    queryFn: () => audit.ledger(tripId),
  });
  const pol = useQuery({
    queryKey: policyKeys.byTrip(tripId),
    queryFn: () => policy.byTrip(tripId),
  });
  const agent = useQuery({
    queryKey: tripKeys.decisions(tripId),
    queryFn: () => trips.decisions(tripId),
  });
  return (
    <Card title="Why this option">
      {ledger.isPending && <Skeleton />}
      {ledger.isError && (
        <p className="muted">
          The decision ledger is not available yet ({describe(ledger.error)}).
        </p>
      )}
      {ledger.data && <LedgerView ledger={ledger.data} />}
      {pol.data && pol.data.length > 0 && (
        <details className="disclosure" style={{ marginTop: 10 }}>
          <summary>Policy decisions ({pol.data.length})</summary>
          <ul>
            {pol.data.map((d) => (
              <li key={d.decisionId}>
                <StatusBadge status={d.outcome} /> {d.policyId} v{d.policyVersion} ·{' '}
                {d.action ?? 'candidate'}{' '}
                {d.bundleId ? <span className="mono">{d.bundleId.slice(0, 14)}…</span> : ''} ·{' '}
                {reasonsOf(d.decision)}
              </li>
            ))}
          </ul>
        </details>
      )}
      {agent.data && agent.data.length > 0 && (
        <details className="disclosure" style={{ marginTop: 10 }}>
          <summary>What the model concluded ({agent.data.length})</summary>
          <ul>
            {agent.data.map((a) => (
              <li key={a.decisionId}>
                {a.decisionType.replaceAll('_', ' ').toLowerCase()}: {a.result}
                {a.confidence != null ? ` (confidence ${a.confidence})` : ''}
                {a.assumptions.length > 0 && <> · assumed: {a.assumptions.join('; ')}</>}
              </li>
            ))}
          </ul>
        </details>
      )}
    </Card>
  );
}

function reasonsOf(decision: Record<string, unknown>): string {
  const reasons = decision['reasons'];
  if (!Array.isArray(reasons) || reasons.length === 0) return 'no reasons recorded';
  return reasons
    .map((r) =>
      typeof r === 'object' && r
        ? `${(r as { code?: string }).code ?? ''} ${(r as { message?: string }).message ?? ''}`.trim()
        : String(r),
    )
    .join('; ');
}

function LedgerView({ ledger }: { ledger: DecisionLedger }) {
  const learning = ledger.learning;
  return (
    <>
      {ledger.narrative.length > 0 ? (
        <ol className="narrative">
          {ledger.narrative.map((n, i) => (
            <li key={i}>{n}</li>
          ))}
        </ol>
      ) : (
        <p className="muted">No decisions recorded yet.</p>
      )}
      {learning && (
        <details className="disclosure">
          <summary>
            Learning: {String(learning['mode'] ?? 'OFF')}{' '}
            {learning['applied'] ? '(applied to the ranking)' : '(not applied)'}
          </summary>
          <p className="legend">
            {learning['applied']
              ? 'Learned inputs adjusted the soft ranking within the bound shown; estimated reliability and preference, not a guarantee.'
              : learning['profileId']
                ? 'Shadow mode: the baseline ranking was executed; the learned ranking was recorded for comparison only.'
                : `Fallback: ${String(learning['fallbackReason'] ?? '')} (baseline ranking).`}
          </p>
          {Array.isArray(learning['contributions']) && learning['contributions'].length > 0 && (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Candidate</th>
                    <th>Supplier keys</th>
                    <th className="num">Baseline</th>
                    <th className="num">Adjustment</th>
                    <th className="num">Learned</th>
                    <th>Reasons</th>
                  </tr>
                </thead>
                <tbody>
                  {(learning['contributions'] as Record<string, unknown>[]).map((c, i) => (
                    <tr key={i}>
                      <td className="mono">{String(c['candidateId']).slice(0, 14)}…</td>
                      <td>
                        {Array.isArray(c['supplierKeys']) ? c['supplierKeys'].join(', ') : ''}
                      </td>
                      <td className="num">{String(c['baselineScore'])}</td>
                      <td className="num">{String(c['adjustment'])}</td>
                      <td className="num">{String(c['learnedScore'])}</td>
                      <td>{Array.isArray(c['reasons']) ? c['reasons'].join('; ') : ''}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </details>
      )}
    </>
  );
}

function Timeline({ tripId }: { tripId: string }) {
  const q = useQuery({ queryKey: tripKeys.history(tripId), queryFn: () => trips.history(tripId) });
  return (
    <Card title="Timeline">
      {q.isPending && <Skeleton />}
      {q.data && q.data.length === 0 && <p className="muted">No changes yet.</p>}
      {q.data && q.data.length > 0 && (
        <ul className="timeline">
          {q.data.map((h, i) => (
            <li key={i}>
              <time dateTime={h.occurredAt}>{new Date(h.occurredAt).toLocaleString()}</time>
              <span>
                <StatusBadge status={h.to} /> {h.reason ?? ''}{' '}
                <small className="muted">· {h.actor}</small>
              </span>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}
