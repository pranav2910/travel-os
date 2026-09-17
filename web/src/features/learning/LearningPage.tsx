import { useState } from 'react';
import { Link } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/auth/AuthContext';
import { capabilities } from '@/auth/session';
import { learning, learningKeys } from '@/api/learning';
import type { LearningMode, ProfileView } from '@/api/types';
import { keyFor, finish } from '@/lib/idempotency';
import { ApiError, describe } from '@/lib/problem';
import {
  Alert,
  Badge,
  Button,
  Card,
  EmptyState,
  Field,
  KeyValue,
  PageHeader,
  Skeleton,
  StatusBadge,
  Time,
} from '@/ui';

export function LearningPage() {
  const { session } = useAuth();
  const admin = capabilities.learningAdmin(session);
  const qc = useQueryClient();
  const cfg = useQuery({
    queryKey: learningKeys.config(),
    queryFn: () => learning.config(),
    refetchInterval: 15_000,
  });
  const summary = useQuery({
    queryKey: learningKeys.summary(),
    queryFn: () => learning.summary(),
    refetchInterval: 30_000,
  });
  const profiles = useQuery({
    queryKey: learningKeys.profiles(),
    queryFn: () => learning.profiles(),
    refetchInterval: (q) =>
      (q.state.data as ProfileView[] | undefined)?.some(
        (p) => p.status === 'BUILDING' || p.status === 'BUILT',
      )
        ? 3000
        : 30_000,
  });
  const history = useQuery({ queryKey: learningKeys.history(), queryFn: () => learning.history() });
  const [conflict, setConflict] = useState<string | null>(null);
  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ['learning'] });
  };
  const onError = (e: Error) => {
    if (e instanceof ApiError && e.is(409)) {
      setConflict(
        'The configuration changed since this page was loaded (someone else acted). It has been refreshed; review it and try again.',
      );
      refresh();
    }
  };
  const version = cfg.data?.version ?? 0;
  const setMode = useMutation({
    mutationFn: (mode: LearningMode) =>
      learning.setMode(mode, version, keyFor('learning-mode', { mode, version })),
    onSuccess: () => {
      setConflict(null);
      refresh();
    },
    onError,
  });
  const activate = useMutation({
    mutationFn: (profileId: string) =>
      learning.activate(profileId, version, keyFor('learning-activate', { profileId, version })),
    onSuccess: () => {
      setConflict(null);
      refresh();
    },
    onError,
  });
  const rollback = useMutation({
    mutationFn: (toBaseline: boolean) =>
      learning.rollback(version, toBaseline, keyFor('learning-rollback', { toBaseline, version })),
    onSuccess: () => {
      setConflict(null);
      refresh();
    },
    onError,
  });
  const [windowHours, setWindowHours] = useState('');
  const build = useMutation({
    mutationFn: () => {
      const body: { window?: string } = {};
      if (windowHours.trim()) body.window = `PT${Math.max(1, Math.round(Number(windowHours)))}H`;
      return learning.build(
        body,
        keyFor('learning-build', { ...body, at: Math.floor(Date.now() / 60_000) }),
      );
    },
    onSuccess: () => {
      finish('learning-build', {});
      refresh();
    },
  });
  const c = cfg.data;
  const active = profiles.data?.find((p) => p.profileId === c?.activeProfileId);
  return (
    <>
      <PageHeader
        title="Learning"
        lead="What the platform learned from verified outcomes and traveler feedback, and how much it may influence rankings. Hard policy, budgets, approvals and the $100 recovery rule are never learned."
      />
      {conflict && (
        <Alert tone="warn" title="Stale view.">
          {conflict}
        </Alert>
      )}
      <div className="grid-2">
        <Card title="Mode">
          {cfg.isPending && <Skeleton />}
          {cfg.isError && <Alert tone="danger">{describe(cfg.error)}</Alert>}
          {c && (
            <>
              <KeyValue
                items={[
                  ['Mode', <StatusBadge key="m" status={c.mode} />],
                  [
                    'Deployment evidence class',
                    <Badge key="c" tone={c.deploymentClass === 'SANDBOX' ? 'warn' : 'info'}>
                      {c.deploymentClass}
                    </Badge>,
                  ],
                  [
                    'Active profile',
                    c.activeProfileId ? (
                      <Link key="a" to={`/learning/profiles/${c.activeProfileId}`} className="mono">
                        {c.activeProfileId}
                      </Link>
                    ) : (
                      <span className="muted">none: baseline ranking</span>
                    ),
                  ],
                  [
                    'Previous (rollback target)',
                    c.previousProfileId ? (
                      <span key="p" className="mono">
                        {c.previousProfileId}
                      </span>
                    ) : null,
                  ],
                  ['Configuration version', String(c.version)],
                  [
                    'Changed',
                    <>
                      <Time iso={c.updatedAt} /> by {c.updatedBy}
                    </>,
                  ],
                ]}
              />
              <ul className="legend" style={{ paddingLeft: 18 }}>
                <li>
                  <strong>Off</strong>: the optimizer's baseline ranking, nothing learned is
                  consulted.
                </li>
                <li>
                  <strong>Shadow</strong>: the learned ranking is computed and recorded next to
                  every decision for comparison; the executed booking is unchanged.
                </li>
                <li>
                  <strong>Active</strong>: an eligible profile adjusts the soft ranking among
                  policy-permitted, feasible options within ±{summary.data?.maxAdjustment ?? '…'}{' '}
                  score points. Costs, budgets and approvals are still decided by policy.
                </li>
              </ul>
              {admin && (
                <div className="row">
                  {(['OFF', 'SHADOW', 'ACTIVE'] as LearningMode[]).map((m) => (
                    <Button
                      key={m}
                      size="sm"
                      variant={c.mode === m ? 'primary' : 'default'}
                      disabled={c.mode === m}
                      busy={setMode.isPending}
                      onClick={() => setMode.mutate(m)}
                    >
                      {m === 'OFF' ? 'Off' : m === 'SHADOW' ? 'Shadow' : 'Active'}
                    </Button>
                  ))}
                  <Button
                    size="sm"
                    disabled={!c.activeProfileId}
                    busy={rollback.isPending}
                    onClick={() => rollback.mutate(false)}
                  >
                    Roll back to previous eligible
                  </Button>
                  <Button
                    size="sm"
                    disabled={!c.activeProfileId}
                    busy={rollback.isPending}
                    onClick={() => rollback.mutate(true)}
                  >
                    Roll back to baseline
                  </Button>
                </div>
              )}
              {(setMode.error || rollback.error) && !conflict ? (
                <Alert tone="danger">{describe(setMode.error ?? rollback.error)}</Alert>
              ) : null}
            </>
          )}
        </Card>
        <Card title="Evidence">
          {summary.isPending && <Skeleton />}
          {summary.data && (
            <>
              <KeyValue
                items={[
                  ['Algorithm', summary.data.algorithmVersion],
                  ['Adjustment bound', `±${summary.data.maxAdjustment} points`],
                  ['Minimum samples per supplier', String(summary.data.minSamples)],
                  ['Maximum window', `${summary.data.windowDays} days`],
                ]}
              />
              <div className="table-wrap" style={{ marginTop: 10 }}>
                <table>
                  <thead>
                    <tr>
                      <th>Outcome kind</th>
                      <th>Class</th>
                      <th className="num">Count</th>
                    </tr>
                  </thead>
                  <tbody>
                    {summary.data.outcomes.length === 0 ? (
                      <tr>
                        <td colSpan={3} className="muted">
                          No outcomes recorded yet.
                        </td>
                      </tr>
                    ) : (
                      summary.data.outcomes.map((o) => (
                        <tr key={`${o.kind}${o.evidenceClass}`}>
                          <td>{o.kind.replaceAll('_', ' ').toLowerCase()}</td>
                          <td>
                            <Badge tone={o.evidenceClass === 'SANDBOX' ? 'warn' : 'info'}>
                              {o.evidenceClass}
                            </Badge>
                          </td>
                          <td className="num">{o.count}</td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              </div>
              <p className="legend">
                Counts of current outcome revisions. SANDBOX evidence comes from simulated suppliers
                and says nothing about live ones.
              </p>
            </>
          )}
        </Card>
      </div>
      {active && (
        <Card title="Active profile" className="stack-item">
          <ProfileSummaryRow p={active} />
        </Card>
      )}
      <Card
        title="Profiles"
        className="stack-item"
        actions={
          admin && (
            <form
              className="row"
              onSubmit={(e) => {
                e.preventDefault();
                build.mutate();
              }}
            >
              <Field
                label="Window (hours, optional)"
                hint="Evidence newer than this many hours; default is the configured maximum."
              >
                {(p) => (
                  <input
                    {...p}
                    inputMode="numeric"
                    value={windowHours}
                    onChange={(e) => setWindowHours(e.target.value)}
                    style={{ width: 120 }}
                  />
                )}
              </Field>
              <Button type="submit" variant="primary" busy={build.isPending}>
                Build a profile
              </Button>
            </form>
          )
        }
      >
        {build.error && <Alert tone="danger">{describe(build.error)}</Alert>}
        {activate.error && !conflict && (
          <Alert tone="danger" title="Activation refused.">
            {describe(activate.error)}
          </Alert>
        )}
        {profiles.isPending && <Skeleton />}
        {profiles.data && profiles.data.length === 0 && (
          <EmptyState title="No profile yet">
            A profile is built from the outcome ledger at a cutoff, evaluated on a chronological
            holdout, and becomes eligible or rejected.
          </EmptyState>
        )}
        {profiles.data && profiles.data.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Profile</th>
                  <th>Status</th>
                  <th>Class</th>
                  <th>Cutoff</th>
                  <th className="num">Hard outcomes</th>
                  <th className="num">Keys</th>
                  <th>Verdict</th>
                  <th>Action</th>
                </tr>
              </thead>
              <tbody>
                {profiles.data.map((p) => (
                  <tr key={p.profileId}>
                    <td>
                      <Link to={`/learning/profiles/${p.profileId}`} className="mono">
                        {p.profileId.slice(0, 16)}…
                      </Link>
                      {p.profileId === c?.activeProfileId && (
                        <>
                          {' '}
                          <Badge tone="ok">active</Badge>
                        </>
                      )}
                    </td>
                    <td>
                      <StatusBadge status={p.status} />
                    </td>
                    <td>
                      <Badge tone={p.evidenceClass === 'SANDBOX' ? 'warn' : 'info'}>
                        {p.evidenceClass}
                      </Badge>
                    </td>
                    <td>
                      <Time iso={p.inputCutoff} />
                    </td>
                    <td className="num">{p.hardOutcomes}</td>
                    <td className="num">{p.supplierKeys}</td>
                    <td>{p.verdict ?? p.failureCode ?? '—'}</td>
                    <td>
                      {admin &&
                        p.status === 'ELIGIBLE' &&
                        p.profileId !== c?.activeProfileId &&
                        p.evidenceClass === c?.deploymentClass && (
                          <Button
                            size="sm"
                            busy={activate.isPending}
                            onClick={() => activate.mutate(p.profileId)}
                          >
                            Activate
                          </Button>
                        )}
                      {p.status === 'ELIGIBLE' && p.evidenceClass !== c?.deploymentClass && (
                        <small className="muted">class mismatch</small>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
      <Card title="History" className="stack-item">
        {history.data && history.data.length === 0 && <p className="muted">No changes yet.</p>}
        {history.data && history.data.length > 0 && (
          <ul className="timeline">
            {history.data.slice(0, 20).map((h) => (
              <li key={h.id}>
                <time dateTime={h.occurredAt}>{new Date(h.occurredAt).toLocaleString()}</time>
                <span>
                  {h.action.replaceAll('_', ' ').toLowerCase()}{' '}
                  {h.profileId ? <span className="mono">{h.profileId.slice(0, 16)}…</span> : ''}{' '}
                  {h.action === 'MODE_CHANGED'
                    ? `${h.previousMode ?? ''} → ${h.mode}`
                    : `(mode ${h.mode})`}{' '}
                  <small className="muted">
                    · {h.actor} · v{h.configVersion}
                  </small>
                </span>
              </li>
            ))}
          </ul>
        )}
      </Card>
    </>
  );
}

export function ProfileSummaryRow({ p }: { p: ProfileView }) {
  return (
    <KeyValue
      items={[
        [
          'Profile',
          <Link key="p" to={`/learning/profiles/${p.profileId}`} className="mono">
            {p.profileId}
          </Link>,
        ],
        [
          'Status',
          <>
            <StatusBadge status={p.status} /> {p.verdict ?? ''}
          </>,
        ],
        [
          'Evidence',
          <>
            <Badge tone={p.evidenceClass === 'SANDBOX' ? 'warn' : 'info'}>{p.evidenceClass}</Badge>{' '}
            {p.hardOutcomes} hard outcomes, {p.feedbackOutcomes} feedback, {p.supplierKeys} supplier
            keys
          </>,
        ],
        [
          'Window',
          <>
            <Time iso={p.windowStart} /> → <Time iso={p.inputCutoff} />
          </>,
        ],
        [
          'Fingerprint',
          p.datasetFingerprint ? (
            <span key="f" className="mono">
              {p.datasetFingerprint.slice(0, 16)}…
            </span>
          ) : null,
        ],
      ]}
    />
  );
}
