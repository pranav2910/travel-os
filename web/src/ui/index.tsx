import { forwardRef, useEffect, useId, useRef } from 'react';
import type { ButtonHTMLAttributes, ReactNode } from 'react';
import { Link } from 'react-router';
import { formatMoney, type Money } from '@/lib/money';
import { formatInstant, formatLocalDate } from '@/lib/dates';

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: 'primary' | 'default' | 'danger' | 'ghost';
  size?: 'sm' | 'md';
  busy?: boolean;
};
export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  {
    variant = 'default',
    size = 'md',
    busy = false,
    className = '',
    children,
    disabled,
    type = 'button',
    ...rest
  },
  ref,
) {
  const cls = [
    'btn',
    variant !== 'default' ? `btn--${variant}` : '',
    size === 'sm' ? 'btn--sm' : '',
    className,
  ].join(' ');
  return (
    <button
      ref={ref}
      type={type}
      className={cls}
      disabled={disabled || busy}
      aria-busy={busy || undefined}
      {...rest}
    >
      {busy ? 'Working…' : children}
    </button>
  );
});

export function LinkButton({
  to,
  children,
  variant = 'default',
  className = '',
}: {
  to: string;
  children: ReactNode;
  variant?: 'primary' | 'default';
  className?: string;
}) {
  return (
    <Link
      to={to}
      className={['btn', variant === 'primary' ? 'btn--primary' : '', className].join(' ')}
    >
      {children}
    </Link>
  );
}

export type Tone = 'neutral' | 'ok' | 'warn' | 'danger' | 'info' | 'progress';
const GLYPH: Record<Tone, string> = {
  neutral: '○',
  ok: '✓',
  warn: '!',
  danger: '✕',
  info: 'i',
  progress: '◐',
};

export function Badge({
  tone = 'neutral',
  children,
  title,
}: {
  tone?: Tone;
  children: ReactNode;
  title?: string;
}) {
  return (
    <span className={`badge badge--${tone}`} title={title}>
      <span aria-hidden="true">{GLYPH[tone]}</span>
      {children}
    </span>
  );
}

const TRIP_TONE: Record<string, [Tone, string]> = {
  DRAFT: ['neutral', 'Draft'],
  SUBMITTED: ['progress', 'Submitted'],
  PLANNING: ['progress', 'Planning'],
  AWAITING_APPROVAL: ['warn', 'Awaiting approval'],
  APPROVED: ['progress', 'Approved'],
  BOOKING: ['progress', 'Booking'],
  BOOKED: ['ok', 'Booked'],
  COMPLETED: ['ok', 'Completed'],
  CANCELLED: ['neutral', 'Cancelled'],
  FAILED: ['danger', 'Failed'],
  PLANNED: ['neutral', 'Planned'],
  QUOTED: ['progress', 'Quoted'],
  REVALIDATING: ['progress', 'Revalidating'],
  CONFIRMED: ['ok', 'Confirmed'],
  CANCEL_FAILED: ['danger', 'Cancel failed'],
  CHANGED: ['info', 'Changed'],
  SKIPPED: ['neutral', 'Skipped'],
  PENDING: ['warn', 'Pending'],
  REJECTED: ['danger', 'Rejected'],
  HELD: ['progress', 'Held'],
  PARTIALLY_FAILED: ['danger', 'Partially failed'],
  COMPENSATING: ['progress', 'Compensating'],
  OPEN: ['warn', 'Open'],
  RESOLVED: ['ok', 'Resolved'],
  DETECTED: ['warn', 'Detected'],
  IMPACT_CONFIRMED: ['warn', 'Impact confirmed'],
  SEARCHING_ALTERNATIVES: ['progress', 'Searching alternatives'],
  OPTIMIZING: ['progress', 'Optimizing'],
  DECISION_READY: ['progress', 'Decision ready'],
  AUTO_ALLOWED: ['progress', 'Auto-allowed'],
  HUMAN_REQUIRED: ['warn', 'Needs a person'],
  CHANGING: ['progress', 'Changing'],
  NO_ALTERNATIVE: ['danger', 'No alternative'],
  MANUAL_INTERVENTION_REQUIRED: ['danger', 'Manual intervention'],
  NEEDS_REVIEW: ['warn', 'Needs review'],
  ACTIONABLE: ['info', 'Actionable'],
  DISMISSED: ['neutral', 'Dismissed'],
  WITHDRAWN: ['neutral', 'Withdrawn'],
  CONVERTED: ['ok', 'Converted'],
  ENABLED: ['ok', 'Enabled'],
  DISABLED: ['neutral', 'Disabled'],
  RUNNING: ['progress', 'Running'],
  BUILDING: ['progress', 'Building'],
  BUILT: ['progress', 'Built'],
  ELIGIBLE: ['ok', 'Eligible'],
  OFF: ['neutral', 'Off'],
  SHADOW: ['info', 'Shadow'],
  ACTIVE: ['ok', 'Active'],
  ALLOW: ['ok', 'Allowed'],
  ALLOW_WITH_APPROVAL: ['warn', 'Needs approval'],
  ALLOW_WITH_TRAVELER_PAYMENT: ['info', 'Traveler pays'],
  DENY: ['danger', 'Denied'],
};
export function StatusBadge({ status }: { status: string | null | undefined }) {
  if (!status) return <Badge tone="neutral">Unknown</Badge>;
  const [tone, label] = TRIP_TONE[status] ?? ['neutral', status.replaceAll('_', ' ').toLowerCase()];
  return (
    <Badge tone={tone} title={status}>
      {label}
    </Badge>
  );
}

export function Card({
  title,
  actions,
  children,
  className = '',
}: {
  title?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section className={`card ${className}`}>
      {(title || actions) && (
        <div className="card__title">
          {title ? <h2>{title}</h2> : <span />}
          {actions}
        </div>
      )}
      {children}
    </section>
  );
}

export function PageHeader({
  title,
  lead,
  actions,
}: {
  title: ReactNode;
  lead?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <header className="page-header">
      <div>
        <h1>{title}</h1>
        {lead && <p>{lead}</p>}
      </div>
      {actions && <div className="row">{actions}</div>}
    </header>
  );
}

export function Alert({
  tone = 'info',
  title,
  children,
  role,
}: {
  tone?: 'info' | 'warn' | 'danger' | 'ok';
  title?: string;
  children?: ReactNode;
  role?: 'alert' | 'status';
}) {
  return (
    <div className={`alert alert--${tone}`} role={role ?? (tone === 'danger' ? 'alert' : 'status')}>
      <div>
        {title && <strong>{title} </strong>}
        {children}
      </div>
    </div>
  );
}

export function Field({
  label,
  hint,
  error,
  children,
  id: given,
}: {
  label: string;
  hint?: string;
  error?: string | undefined;
  children: (props: {
    id: string;
    'aria-describedby'?: string;
    'aria-invalid'?: boolean;
  }) => ReactNode;
  id?: string;
}) {
  const auto = useId();
  const id = given ?? auto;
  const hintId = hint ? `${id}-hint` : undefined;
  const errId = error ? `${id}-err` : undefined;
  const described = [hintId, errId].filter(Boolean).join(' ') || undefined;
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      {children({
        id,
        ...(described ? { 'aria-describedby': described } : {}),
        ...(error ? { 'aria-invalid': true } : {}),
      })}
      {hint && (
        <span id={hintId} className="field__hint">
          {hint}
        </span>
      )}
      {error && (
        <span id={errId} className="field__error" role="alert">
          {error}
        </span>
      )}
    </div>
  );
}

export function EmptyState({
  title,
  children,
  action,
}: {
  title: string;
  children?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="empty">
      <h3>{title}</h3>
      {children && <p>{children}</p>}
      {action}
    </div>
  );
}

export function Skeleton({ lines = 3 }: { lines?: number }) {
  return (
    <div className="stack" aria-busy="true" aria-live="polite">
      <span className="sr-only">Loading</span>
      {Array.from({ length: lines }, (_, i) => (
        <div key={i} className="skeleton" style={{ width: `${90 - i * 12}%` }} />
      ))}
    </div>
  );
}

export function MoneyText({ money }: { money: Money | null | undefined }) {
  if (!money) return <span className="muted">—</span>;
  return (
    <span className="mono money" title={`${money.amountMinor} minor units`}>
      {formatMoney(money)}
    </span>
  );
}

export function Time({ iso, zone = 'UTC' }: { iso: string | null | undefined; zone?: string }) {
  if (!iso) return <span className="muted">—</span>;
  return <time dateTime={iso}>{formatInstant(iso, zone)}</time>;
}

export function LocalDate({ date }: { date: string | null | undefined }) {
  if (!date) return <span className="muted">—</span>;
  return <time dateTime={date}>{formatLocalDate(date)}</time>;
}

export function KeyValue({ items }: { items: [string, ReactNode][] }) {
  return (
    <dl className="kv">
      {items.map(([k, v]) => (
        <div key={k} style={{ display: 'contents' }}>
          <dt>{k}</dt>
          <dd>{v ?? <span className="muted">—</span>}</dd>
        </div>
      ))}
    </dl>
  );
}

/** Native <dialog>: modal, focus-trapped by the browser, closed with Escape; labelled by its title. */
export function Dialog({
  open,
  onClose,
  title,
  children,
  actions,
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
  actions?: ReactNode;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    if (open && !el.open) el.showModal();
    if (!open && el.open) el.close();
  }, [open]);
  return (
    <dialog
      ref={ref}
      className="dialog"
      aria-labelledby={titleId}
      onClose={onClose}
      onCancel={(e) => {
        e.preventDefault();
        onClose();
      }}
    >
      {open && (
        <>
          <div className="dialog__body">
            <h2 id={titleId}>{title}</h2>
            {children}
          </div>
          {actions && <div className="dialog__actions">{actions}</div>}
        </>
      )}
    </dialog>
  );
}

export function Json({ value }: { value: unknown }) {
  return <pre className="json">{JSON.stringify(value, null, 2)}</pre>;
}
