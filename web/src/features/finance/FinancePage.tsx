import { useState } from 'react';
import { Link } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/auth/AuthContext';
import { hasRole } from '@/auth/session';
import { learning, learningKeys } from '@/api/learning';
import { orders, orderKeys } from '@/api/orders';
import type { ExposureListItem } from '@/api/types';
import { keyFor, finish } from '@/lib/idempotency';
import { parseMajor } from '@/lib/money';
import { describe } from '@/lib/problem';
import {
  Alert,
  Button,
  Card,
  Dialog,
  EmptyState,
  Field,
  MoneyText,
  PageHeader,
  Skeleton,
  StatusBadge,
  Time,
} from '@/ui';

export function FinancePage() {
  const { session } = useAuth();
  const [status, setStatus] = useState<'OPEN' | 'RESOLVED'>('OPEN');
  const q = useQuery({
    queryKey: orderKeys.exposures(status),
    queryFn: () => orders.exposures(status),
    refetchInterval: 30_000,
  });
  const finance = hasRole(session, 'FINANCE');
  return (
    <>
      <PageHeader
        title="Finance"
        lead="Money the company may still owe (exposures), and refunds a supplier actually settled. An open exposure is a liability until a person closes it; a cancellation never implies a refund."
      />
      <Card
        title="Exposures"
        actions={
          <label>
            Show{' '}
            <select
              value={status}
              onChange={(e) => setStatus(e.target.value as 'OPEN' | 'RESOLVED')}
            >
              <option value="OPEN">Open (unresolved liabilities)</option>
              <option value="RESOLVED">Resolved</option>
            </select>
          </label>
        }
      >
        {q.isPending && <Skeleton />}
        {q.isError && <Alert tone="danger">{describe(q.error)}</Alert>}
        {q.data && q.data.length === 0 && (
          <EmptyState title={status === 'OPEN' ? 'No open exposure' : 'No resolved exposure'}>
            An exposure appears when a supplier refuses to release an item the platform had to
            compensate.
          </EmptyState>
        )}
        {q.data && q.data.length > 0 && (
          <div className="table-wrap">
            <table className="t-exposures">
              <thead>
                <tr>
                  <th>Created</th>
                  <th>Order / trip</th>
                  <th>Supplier</th>
                  <th className="num">Amount</th>
                  <th>Reason</th>
                  <th>Status</th>
                  <th>Action</th>
                </tr>
              </thead>
              <tbody>
                {q.data.map((row) => (
                  <ExposureRow key={row.exposure.exposureId} row={row} />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
      <RefundCard finance={finance} />
    </>
  );
}

function ExposureRow({ row }: { row: ExposureListItem }) {
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const [resolution, setResolution] = useState('');
  const e = row.exposure;
  const resolve = useMutation({
    mutationFn: () =>
      orders.resolveExposure(
        row.orderId,
        e.exposureId,
        resolution.trim(),
        keyFor('resolve-exposure', { exposureId: e.exposureId, resolution: resolution.trim() }),
      ),
    onSuccess: () => {
      finish('resolve-exposure', { exposureId: e.exposureId, resolution: resolution.trim() });
      setOpen(false);
      void qc.invalidateQueries({ queryKey: ['orders'] });
    },
  });
  return (
    <tr>
      <td>
        <Time iso={e.createdAt} />
      </td>
      <td>
        <span className="mono">{row.orderId.slice(0, 14)}…</span>
        <br />
        <Link to={`/trips/${row.tripId}`}>trip</Link> · {row.travelerId}
      </td>
      <td>
        {e.provider}
        <br />
        <small className="mono">{e.externalRef}</small>
      </td>
      <td className="num">
        <MoneyText money={e.amount} />
      </td>
      <td>
        {e.reason}
        {e.detail && (
          <>
            <br />
            <small>{e.detail}</small>
          </>
        )}
        {e.resolution && (
          <>
            <br />
            <small>
              resolved by {e.resolvedBy}: {e.resolution}
            </small>
          </>
        )}
      </td>
      <td>
        <StatusBadge status={e.status} />
      </td>
      <td>
        {e.status === 'OPEN' && (
          <Button size="sm" onClick={() => setOpen(true)}>
            Resolve
          </Button>
        )}
        <Dialog
          open={open}
          onClose={() => setOpen(false)}
          title="Close this exposure"
          actions={
            <>
              <Button onClick={() => setOpen(false)}>Back</Button>
              <Button
                variant="primary"
                busy={resolve.isPending}
                disabled={resolution.trim().length < 3}
                onClick={() => resolve.mutate()}
              >
                Record resolution
              </Button>
            </>
          }
        >
          <p>
            Say what happened with the supplier (cancelled by phone, loss accepted, credit
            obtained). This closes the record; it does not say a refund was received — record that
            separately below if one settled.
          </p>
          <Field label="Resolution">
            {(p) => (
              <textarea
                {...p}
                rows={3}
                maxLength={2000}
                value={resolution}
                onChange={(ev) => setResolution(ev.target.value)}
              />
            )}
          </Field>
          {resolve.error && <Alert tone="danger">{describe(resolve.error)}</Alert>}
        </Dialog>
      </td>
    </tr>
  );
}

function RefundCard({ finance }: { finance: boolean }) {
  const qc = useQueryClient();
  const [tripId, setTripId] = useState('');
  const [orderId, setOrderId] = useState('');
  const [itemId, setItemId] = useState('');
  const [amount, setAmount] = useState('');
  const [currency, setCurrency] = useState('USD');
  const [reference, setReference] = useState('');
  const [error, setError] = useState<string | null>(null);
  const record = useMutation({
    mutationFn: () => {
      const minor = parseMajor(amount, currency);
      if (minor === null || minor < 0n)
        throw new Error(
          'Enter the settled amount as a positive number with at most the currency’s decimals.',
        );
      if (minor > BigInt(Number.MAX_SAFE_INTEGER)) throw new Error('Amount too large.');
      const body = {
        tripId: tripId.trim(),
        orderId: orderId.trim(),
        itemId: itemId.trim() || undefined,
        amountMinor: Number(minor),
        currency,
        reference: reference.trim(),
      };
      return learning.recordRefund(body, keyFor('refund', body));
    },
    onSuccess: (o) => {
      finish('refund', {});
      setError(null);
      void qc.invalidateQueries({ queryKey: learningKeys.outcomes(o.tripId ?? '') });
    },
    onError: (e) => setError(describe(e)),
  });
  const outcomes = useQuery({
    queryKey: learningKeys.outcomes(tripId.trim()),
    queryFn: () => learning.outcomes(tripId.trim()),
    enabled: tripId.trim().length > 10,
  });
  return (
    <Card title="Settled refunds" className="stack-item">
      {!finance && (
        <Alert tone="info">
          Recording a settled refund needs the FINANCE role. Travel admins see the records; the
          platform refuses other roles.
        </Alert>
      )}
      <form
        className="stack"
        onSubmit={(e) => {
          e.preventDefault();
          record.mutate();
        }}
      >
        <div className="form-grid">
          <Field label="Trip id">
            {(p) => (
              <input
                {...p}
                value={tripId}
                onChange={(e) => setTripId(e.target.value)}
                placeholder="trip_…"
              />
            )}
          </Field>
          <Field label="Order id">
            {(p) => (
              <input
                {...p}
                value={orderId}
                onChange={(e) => setOrderId(e.target.value)}
                placeholder="ord_…"
              />
            )}
          </Field>
          <Field label="Item id (optional)">
            {(p) => (
              <input
                {...p}
                value={itemId}
                onChange={(e) => setItemId(e.target.value)}
                placeholder="itm_…"
              />
            )}
          </Field>
          <Field label="Amount settled" hint="In the currency’s major units, e.g. 435.00">
            {(p) => (
              <input
                {...p}
                inputMode="decimal"
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
              />
            )}
          </Field>
          <Field label="Currency">
            {(p) => (
              <input
                {...p}
                value={currency}
                maxLength={3}
                onChange={(e) => setCurrency(e.target.value.toUpperCase())}
              />
            )}
          </Field>
          <Field label="Supplier / bank reference">
            {(p) => (
              <input
                {...p}
                value={reference}
                onChange={(e) => setReference(e.target.value)}
                maxLength={200}
              />
            )}
          </Field>
        </div>
        {error && <Alert tone="danger">{error}</Alert>}
        {record.isSuccess && (
          <Alert tone="ok">
            Recorded as revision {record.data.revision} of the refund for this item. The same
            statement again is not a second refund; a corrected amount becomes revision{' '}
            {record.data.revision + 1}.
          </Alert>
        )}
        <div>
          <Button type="submit" variant="primary" busy={record.isPending} disabled={!finance}>
            Record settled refund
          </Button>
        </div>
      </form>
      {outcomes.data && (
        <details className="disclosure" style={{ marginTop: 10 }}>
          <summary>Outcome ledger for this trip ({outcomes.data.length})</summary>
          <div className="table-wrap">
            <table className="t-outcomes">
              <thead>
                <tr>
                  <th>Kind</th>
                  <th>Rev</th>
                  <th>Supplier key</th>
                  <th>Observed</th>
                  <th>Source</th>
                </tr>
              </thead>
              <tbody>
                {outcomes.data.map((o) => (
                  <tr key={o.outcomeId}>
                    <td>{o.kind.replaceAll('_', ' ').toLowerCase()}</td>
                    <td className="num">{o.revision}</td>
                    <td>{o.supplierKey ?? '—'}</td>
                    <td>
                      <Time iso={o.observedAt} />
                    </td>
                    <td>{o.source}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="legend">
            Pending refunds do not exist as records: until Finance records a settlement there is
            only a cancellation.
          </p>
        </details>
      )}
    </Card>
  );
}
