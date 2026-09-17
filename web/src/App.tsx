import { createBrowserRouter, RouterProvider, useRouteError } from 'react-router';
import { AppLayout } from '@/shell/AppLayout';
import { ErrorPage, NotFound, RequireAuth, RequireRole } from '@/shell/guards';
import { capabilities } from '@/auth/session';
import { OverviewPage } from '@/features/trips/OverviewPage';
import { TripsPage } from '@/features/trips/TripsPage';
import { NewTripPage } from '@/features/trips/NewTripPage';
import { TripDetailPage } from '@/features/trips/TripDetailPage';
import { ApprovalsPage } from '@/features/approvals/ApprovalsPage';
import { OperationsPage } from '@/features/approvals/OperationsPage';
import { DisruptionPage } from '@/features/approvals/DisruptionPage';
import { FinancePage } from '@/features/finance/FinancePage';
import { DemandInboxPage } from '@/features/demand/DemandInboxPage';
import { DemandDetailPage } from '@/features/demand/DemandDetailPage';
import { ConnectorsPage } from '@/features/connectors/ConnectorsPage';
import { LearningPage } from '@/features/learning/LearningPage';
import { ProfilePage } from '@/features/learning/ProfilePage';

function RouteError() {
  const error = useRouteError();
  return <ErrorPage error={error} />;
}

const router = createBrowserRouter([
  {
    path: '/',
    element: (
      <RequireAuth>
        <AppLayout />
      </RequireAuth>
    ),
    errorElement: <RouteError />,
    children: [
      { index: true, element: <OverviewPage /> },
      { path: 'trips', element: <TripsPage /> },
      { path: 'trips/new', element: <NewTripPage /> },
      { path: 'trips/:tripId', element: <TripDetailPage /> },
      {
        path: 'approvals',
        element: (
          <RequireRole allow={capabilities.approvals}>
            <ApprovalsPage />
          </RequireRole>
        ),
      },
      {
        path: 'operations',
        element: (
          <RequireRole allow={capabilities.operations}>
            <OperationsPage />
          </RequireRole>
        ),
      },
      { path: 'disruptions/:disruptionId', element: <DisruptionPage /> },
      {
        path: 'finance',
        element: (
          <RequireRole allow={capabilities.finance}>
            <FinancePage />
          </RequireRole>
        ),
      },
      { path: 'demand', element: <DemandInboxPage /> },
      { path: 'demand/:candidateId', element: <DemandDetailPage /> },
      {
        path: 'connectors',
        element: (
          <RequireRole allow={capabilities.connectors}>
            <ConnectorsPage />
          </RequireRole>
        ),
      },
      {
        path: 'learning',
        element: (
          <RequireRole allow={capabilities.learning}>
            <LearningPage />
          </RequireRole>
        ),
      },
      {
        path: 'learning/profiles/:profileId',
        element: (
          <RequireRole allow={capabilities.learning}>
            <ProfilePage />
          </RequireRole>
        ),
      },
      { path: 'auth/callback', element: <OverviewPage /> },
      { path: '*', element: <NotFound /> },
    ],
  },
]);

export function App() {
  return <RouterProvider router={router} />;
}
