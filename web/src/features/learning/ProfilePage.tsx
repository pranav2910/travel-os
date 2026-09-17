import { Link, useParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { learning, learningKeys } from '@/api/learning';
import { ApiError, describe } from '@/lib/problem';
import { Alert, Badge, Card, Json, KeyValue, PageHeader, Skeleton, StatusBadge } from '@/ui';
import { NotFound } from '@/shell/guards';
import { ProfileSummaryRow } from './LearningPage';

type Doc = Record<string, unknown>;

export function ProfilePage() {
  const { profileId = '' } = useParams();
  const q = useQuery({
    queryKey: learningKeys.profile(profileId),
    queryFn: () => learning.profile(profileId),
    refetchInterval: (query) => {
      const s = (query.state.data as { status?: string } | undefined)?.status;
      return s === 'BUILDING' || s === 'BUILT' ? 3000 : false;
    },
  });
  if (q.isPending) return <Skeleton lines={5} />;
  if (q.isError) {
    if (q.error instanceof ApiError && q.error.is(404)) return <NotFound what="profile" />;
    return <Alert tone="danger">{describe(q.error)}</Alert>;
  }
  const p = q.data;
  const ev = (p.evaluation ?? null) as Doc | null;
  const criteria =
    ev && typeof ev['criteria'] === 'object' && ev['criteria']
      ? (ev['criteria'] as Record<string, boolean>)
      : null;
  const suppliers = p.suppliers ?? {};
  return (
    <>
      <PageHeader
        title={
          <>
            Profile <StatusBadge status={p.status} />
          </>
        }
        lead={
          <>
            <span className="mono">{p.profileId}</span> · {p.algorithmVersion} ·{' '}
            <Link to="/learning">back to learning</Link>
          </>
        }
      />
      {p.synthetic && (
        <Alert tone="warn" title="Synthetic evidence.">
          This profile was built from SANDBOX outcomes of simulated suppliers. Its evaluation shows
          how the method behaves; it does not establish a benefit for live travel.
        </Alert>
      )}
      {p.status === 'FAILED' && (
        <Alert tone="danger" title={p.failureCode ?? 'FAILED'}>
          {p.failureMessage}
        </Alert>
      )}
      <div className="grid-2">
        <Card title="Build">
          <ProfileSummaryRow p={p} />
        </Card>
        <Card title="Parameters (as configured for this build)">
          <KeyValue items={Object.entries(p.parameters).map(([k, v]) => [k, String(v)])} />
          <p className="legend">
            The adjustment scale and bounds are configuration, shown as used; nothing is recomputed
            in the browser. A demo deployment may use a larger scale than the production default.
          </p>
        </Card>
      </div>
      <Card title="Supplier reliability" className="stack-item">
        {Object.keys(suppliers).length === 0 && (
          <p className="muted">No supplier reached the minimum number of outcomes.</p>
        )}
        {Object.keys(suppliers).length > 0 && (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Supplier key</th>
                  <th className="num">Successes</th>
                  <th className="num">Failures</th>
                  <th className="num">Samples</th>
                  <th className="num">Estimate</th>
                  <th className="num">Adjustment</th>
                  <th>Reason</th>
                </tr>
              </thead>
              <tbody>
                {Object.entries(suppliers).map(([k, s]) => (
                  <tr key={k}>
                    <td className="mono">{k}</td>
                    <td className="num">{s.successes}</td>
                    <td className="num">{s.failures}</td>
                    <td className="num">{s.samples}</td>
                    <td className="num">{s.estimate}</td>
                    <td className="num">{s.adjustment > 0 ? `+${s.adjustment}` : s.adjustment}</td>
                    <td>{s.reason}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <p className="legend">
          Traveler preferences ({p.travelersWithPreferences} traveler
          {p.travelersWithPreferences === 1 ? '' : 's'}) are part of the profile but shown only to
          each traveler on their own trips.
        </p>
      </Card>
      <Card title="Evaluation before activation" className="stack-item">
        {!ev && <p className="muted">Not evaluated yet.</p>}
        {ev && (
          <>
            <KeyValue
              items={[
                ['Method', String(ev['method'] ?? '')],
                [
                  'Verdict',
                  <StatusBadge key="v" status={String(ev['verdict'] ?? p.verdict ?? '')} />,
                ],
                [
                  'Decisions replayed',
                  `${String(ev['decisionsTotal'])} (holdout ${String(ev['holdoutDecisions'])}, labeled ${String(ev['decisionsLabeled'])}, missing labels ${String(ev['labelsMissing'])})`,
                ],
                [
                  'Brier score (lower is better)',
                  ev['brierCandidate'] != null
                    ? `candidate ${String(ev['brierCandidate'])} vs prior ${String(ev['brierBaseline'])}`
                    : 'not enough labels',
                ],
                [
                  'Ranking changed',
                  `${String(ev['rankingChanged'])} decisions (counts decisions where the learned pick differed; not money saved, not failures avoided)`,
                ],
                ['Hard-constraint violations', String(ev['hardConstraintViolations'])],
                [
                  'Evidence',
                  <Badge key="e" tone={ev['syntheticEvidence'] ? 'warn' : 'info'}>
                    {ev['syntheticEvidence'] ? 'synthetic' : 'live'}
                  </Badge>,
                ],
              ]}
            />
            {criteria && (
              <ul style={{ marginTop: 8 }}>
                {Object.entries(criteria).map(([k, v]) => (
                  <li key={k}>
                    {v ? '✓' : '✕'} {k.replace(/([A-Z])/g, ' $1').toLowerCase()}
                  </li>
                ))}
              </ul>
            )}
            {Array.isArray(ev['reasons']) && (
              <p>
                <strong>Reasons:</strong> {(ev['reasons'] as string[]).join('; ')}
              </p>
            )}
            {Array.isArray(ev['limits']) && (
              <ul className="legend">
                {(ev['limits'] as string[]).map((l, i) => (
                  <li key={i}>{l}</li>
                ))}
              </ul>
            )}
            <details className="disclosure">
              <summary>Full evaluation record</summary>
              <Json value={ev} />
            </details>
          </>
        )}
      </Card>
    </>
  );
}
