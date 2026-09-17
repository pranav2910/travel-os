import { Link } from 'react-router';
import {
  Alert,
  EmptyState,
  LinkButton,
  MoneyText,
  PageHeader,
  Skeleton,
  StatusBadge,
  Time,
} from '@/ui';
import { useMyTrips } from './hooks';
import { route } from './OverviewPage';
import { describe } from '@/lib/problem';

export function TripsPage() {
  const mine = useMyTrips();
  return (
    <>
      <PageHeader
        title="Trips"
        lead="Everything you requested, newest first."
        actions={
          <LinkButton to="/trips/new" variant="primary">
            New trip
          </LinkButton>
        }
      />
      {mine.isPending && <Skeleton lines={4} />}
      {mine.isError && (
        <Alert tone="danger" title="Trips could not be loaded.">
          {describe(mine.error)}
        </Alert>
      )}
      {mine.data && mine.data.length === 0 && (
        <EmptyState
          title="No trips yet"
          action={
            <LinkButton to="/trips/new" variant="primary">
              Request your first trip
            </LinkButton>
          }
        >
          A request is planned, checked against policy, approved when needed and booked with the
          simulated suppliers.
        </EmptyState>
      )}
      {mine.data && mine.data.length > 0 && (
        <div className="table-wrap">
          <table className="t-trips">
            <thead>
              <tr>
                <th>Trip</th>
                <th>Route</th>
                <th>Status</th>
                <th>Total</th>
                <th>Requested</th>
              </tr>
            </thead>
            <tbody>
              {mine.data.map((t) => (
                <tr key={t.tripId}>
                  <td>
                    <Link to={`/trips/${t.tripId}`}>
                      {t.intent?.purpose || t.request?.slice(0, 48) || t.tripId}
                    </Link>
                    <br />
                    <small className="mono">{t.tripId}</small>
                  </td>
                  <td className="route">{route(t)}</td>
                  <td>
                    <StatusBadge status={t.status} />
                  </td>
                  <td>
                    <MoneyText money={t.total} />
                  </td>
                  <td>
                    <Time iso={t.createdAt} />
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
