import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { demand, demandKeys } from '@/api/demand';
import { audit, auditKeys } from '@/api/audit';
import { keyFor, finish } from '@/lib/idempotency';
import { ApiError, UncertainError, describe } from '@/lib/problem';
import {
  Alert,
  Button,
  Card,
  Dialog,
  Field,
  Json,
  KeyValue,
  LocalDate,
  PageHeader,
  Skeleton,
  StatusBadge,
  Time,
} from '@/ui';
import { NotFound } from '@/shell/guards';

export function DemandDetailPage() {
  const { candidateId = '' } = useParams();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const q = useQuery({
    queryKey: demandKeys.one(candidateId),
    queryFn: () => demand.get(candidateId),
  });
  const history = useQuery({
    queryKey: demandKeys.history(candidateId),
    queryFn: () => demand.history(candidateId),
  });
  const evidence = useQuery({
    queryKey: demandKeys.evidence(candidateId),
    queryFn: () => demand.evidence(candidateId),
  });
  const trail = useQuery({
    queryKey: auditKeys.demand(candidateId),
    queryFn: () => audit.demand(candidateId),
    enabled: !!q.data?.tripId,
  });
  const [destination, setDestination] = useState('');
  const [start, setStart] = useState('');
  const [end, setEnd] = useState('');
  const [dismissOpen, setDismissOpen] = useState(false);
  const [reason, setReason] = useState('');
  const invalidate = () => {
    void qc.invalidateQueries({ queryKey: ['demand'] });
  };
  const details = useMutation({
    mutationFn: () => {
      const body = {
        destination: destination || undefined,
        startDate: start || undefined,
        endDate: end || undefined,
      };
      return demand.details(candidateId, body, keyFor('demand-details', { candidateId, ...body }));
    },
    onSuccess: invalidate,
  });
  const clearFlag = useMutation({
    mutationFn: (flag: string) =>
      demand.details(
        candidateId,
        { clearReviewFlag: flag },
        keyFor('demand-clear', { candidateId, flag }),
      ),
    onSuccess: invalidate,
  });
  const dismiss = useMutation({
    mutationFn: () =>
      demand.dismiss(candidateId, reason || undefined, keyFor('demand-dismiss', { candidateId })),
    onSuccess: () => {
      finish('demand-dismiss', { candidateId });
      setDismissOpen(false);
      invalidate();
    },
  });
  const convert = useMutation({
    // one key per candidate: every click, retry or reload converts into the same trip
    mutationFn: () => demand.convert(candidateId, keyFor('demand-convert', { candidateId })),
    onSuccess: (view) => {
      finish('demand-convert', { candidateId });
      invalidate();
      navigate(`/trips/${view.tripId}`);
    },
  });
  if (q.isPending) return <Skeleton lines={5} />;
  if (q.isError) {
    if (q.error instanceof ApiError && q.error.is(404)) return <NotFound what="demand candidate" />;
    return <Alert tone="danger">{describe(q.error)}</Alert>;
  }
  const c = q.data;
  const open = c.status === 'NEEDS_REVIEW' || c.status === 'ACTIONABLE';
  return (
    <>
      <PageHeader
        title={
          <>
            {c.purpose || 'Detected travel'} <StatusBadge status={c.status} />
          </>
        }
        lead={
          <>
            {c.origin ?? '?'} → {c.destination ?? '?'} · <LocalDate date={c.startDate} /> –{' '}
            <LocalDate date={c.endDate} /> {c.timeZone ? `(${c.timeZone})` : ''} · rules{' '}
            {c.rulesVersion}
          </>
        }
      />
      {c.tripId && (
        <Alert tone="ok" title="Converted.">
          {' '}
          This demand became <Link to={`/trips/${c.tripId}`}>trip {c.tripId}</Link>. Converting
          again is not offered: a source change after conversion is flagged for review on the trip,
          never applied silently.
        </Alert>
      )}
      <div className="grid-2">
        <Card title="What the platform saw">
          <p>{c.explanation}</p>
          <KeyValue
            items={[
              ['Traveler (verified from HRIS)', c.travelerId],
              ['Missing', c.missing.length ? c.missing.join(', ') : 'nothing'],
              ['Review flags', c.reviewReasons.length ? c.reviewReasons.join(', ') : 'none'],
              ['Sources', String(c.sources.length)],
              ['Updated', <Time key="u" iso={c.updatedAt} />],
            ]}
          />
          {c.reviewReasons.length > 0 && open && (
            <div className="row" style={{ marginTop: 8 }}>
              {c.reviewReasons.map((f) => (
                <Button
                  key={f}
                  size="sm"
                  busy={clearFlag.isPending}
                  onClick={() => clearFlag.mutate(f)}
                >
                  Clear “{f}”
                </Button>
              ))}
            </div>
          )}
        </Card>
        {open && (
          <Card title="Complete and act">
            <form
              className="stack"
              onSubmit={(e) => {
                e.preventDefault();
                details.mutate();
              }}
            >
              <div className="form-grid">
                <Field label="Destination (airport code)">
                  {(p) => (
                    <input
                      {...p}
                      value={destination}
                      maxLength={3}
                      onChange={(e) => setDestination(e.target.value.toUpperCase())}
                      placeholder={c.destination ?? 'SEA'}
                    />
                  )}
                </Field>
                <Field label="Start date">
                  {(p) => (
                    <input
                      {...p}
                      type="date"
                      value={start}
                      onChange={(e) => setStart(e.target.value)}
                    />
                  )}
                </Field>
                <Field label="End date">
                  {(p) => (
                    <input
                      {...p}
                      type="date"
                      value={end}
                      onChange={(e) => setEnd(e.target.value)}
                    />
                  )}
                </Field>
              </div>
              {details.error && <Alert tone="danger">{describe(details.error)}</Alert>}
              <div className="row">
                <Button
                  type="submit"
                  busy={details.isPending}
                  disabled={!destination && !start && !end}
                >
                  Save details
                </Button>
                <Button
                  variant="primary"
                  busy={convert.isPending}
                  disabled={c.status !== 'ACTIONABLE'}
                  title={c.status !== 'ACTIONABLE' ? 'Resolve what is missing first' : undefined}
                  onClick={() => convert.mutate()}
                >
                  Convert into a trip
                </Button>
                <Button variant="ghost" onClick={() => setDismissOpen(true)}>
                  Dismiss
                </Button>
              </div>
              {c.status !== 'ACTIONABLE' && (
                <p className="legend">
                  Conversion is offered once the candidate is actionable: the missing fields above
                  and any review flag must be resolved.
                </p>
              )}
              {convert.error && (
                <Alert
                  tone={convert.error instanceof UncertainError ? 'warn' : 'danger'}
                  title={
                    convert.error instanceof UncertainError
                      ? 'The answer did not arrive.'
                      : 'Not converted.'
                  }
                >
                  {describe(convert.error)}
                  {convert.error instanceof UncertainError
                    ? ' Converting again is safe: the same candidate always becomes the same trip.'
                    : ''}
                </Alert>
              )}
            </form>
          </Card>
        )}
      </div>
      <Card title="Evidence" className="stack-item">
        {evidence.isPending && <Skeleton />}
        {evidence.data && evidence.data.length === 0 && <p className="muted">No source items.</p>}
        {evidence.data?.map((e) => (
          <details className="disclosure" key={`${e.connectorId}/${e.sourceId}`}>
            <summary>
              {e.kind.toLowerCase()} · {e.sourceId} · revision {e.revision} ·{' '}
              {e.status.toLowerCase()} <span className="muted">(simulated source)</span>
            </summary>
            <p className="legend">
              Text from the source is shown as data; it never instructs the platform.
            </p>
            <Json value={e.item} />
          </details>
        ))}
      </Card>
      <Card title="History" className="stack-item">
        {history.data && (
          <ul className="timeline">
            {history.data.map((h, i) => (
              <li key={i}>
                <time dateTime={h.occurredAt}>{new Date(h.occurredAt).toLocaleString()}</time>
                <span>
                  <StatusBadge status={h.to} /> {h.reason}
                  {h.detail ? `: ${h.detail}` : ''} <small className="muted">· {h.actor}</small>
                </span>
              </li>
            ))}
          </ul>
        )}
        {trail.data && trail.data.events.length > 0 && (
          <p className="legend">
            {trail.data.events.length} audit events link this demand to its trip.
          </p>
        )}
      </Card>
      <Dialog
        open={dismissOpen}
        onClose={() => setDismissOpen(false)}
        title="Dismiss this demand?"
        actions={
          <>
            <Button onClick={() => setDismissOpen(false)}>Keep</Button>
            <Button variant="danger" busy={dismiss.isPending} onClick={() => dismiss.mutate()}>
              Dismiss
            </Button>
          </>
        }
      >
        <p>
          The candidate stops being offered. A later change in the source does not resurrect it.
        </p>
        <Field label="Reason (optional)">
          {(p) => (
            <input
              {...p}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              maxLength={2000}
            />
          )}
        </Field>
        {dismiss.error && <Alert tone="danger">{describe(dismiss.error)}</Alert>}
      </Dialog>
    </>
  );
}
