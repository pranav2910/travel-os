import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { installAuth } from '@/api/http';
import { NewTripPage } from './NewTripPage';

vi.mock('@/auth/AuthContext', () => ({
  useAuth: () => ({
    status: 'signed-in',
    session: {
      username: 'alice',
      displayName: 'Alice',
      email: null,
      tenantId: 'acme',
      employeeId: 'emp_1001',
      roles: new Set(['TRAVELER']),
      accessToken: 't',
      expiresAt: null,
    },
    error: null,
    signIn: async () => {},
    signOut: async () => {},
    markExpired: () => {},
  }),
}));

function renderPage() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/trips/new']}>
        <Routes>
          <Route path="/trips/new" element={<NewTripPage />} />
          <Route path="/trips/:id" element={<h1>trip page</h1>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('NewTripPage', () => {
  const fetchMock = vi.fn();
  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
    installAuth(
      () => 't',
      () => {},
    );
    window.sessionStorage.clear();
  });
  afterEach(() => vi.unstubAllGlobals());

  it('validates before anything leaves the browser and states that submitting books', async () => {
    const user = userEvent.setup();
    renderPage();
    expect(screen.getByText('Submitting books the trip.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Review and submit' }));
    expect(screen.getAllByRole('alert').length).toBeGreaterThan(0);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('needs an explicit confirmation, then posts once with a fixed idempotency key across a retry', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.type(screen.getByLabelText('Purpose of travel'), 'component test');
    const outbound = screen.getByRole('group', { name: 'Outbound' });
    await user.type(outbound.querySelector('input[type=date]')!, '2026-11-10');
    const ret = screen.getByRole('group', { name: 'Return' });
    await user.type(ret.querySelector('input[type=date]')!, '2026-11-12');
    await user.click(screen.getByRole('button', { name: 'Review and submit' }));
    expect(fetchMock).not.toHaveBeenCalled();
    // first attempt: the network drops the answer
    fetchMock.mockRejectedValueOnce(new TypeError('network'));
    await user.click(screen.getByRole('button', { name: 'Confirm and submit' }));
    await screen.findByText('The answer did not arrive.');
    // second attempt with the same payload: same key, and the platform answers
    fetchMock.mockResolvedValueOnce(
      new Response(
        JSON.stringify({ tripId: 'trip_01ARZ3NDEKTSV4RRFFQ69G5FAV', status: 'SUBMITTED' }),
        { status: 201 },
      ),
    );
    await user.click(screen.getByRole('button', { name: 'Confirm and submit' }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    const keys = fetchMock.mock.calls
      .map((c) => (c[1] as RequestInit).headers as Record<string, string>)
      .map((h) => h['Idempotency-Key']);
    expect(keys[0]).toBeTruthy();
    expect(keys[1]).toBe(keys[0]);
    const body = JSON.parse((fetchMock.mock.calls[0]![1] as RequestInit).body as string) as {
      intent: { origin: string; earliestDeparture: string };
      source: string;
    };
    expect(body.intent.origin).toBe('BOS');
    expect(body.intent.earliestDeparture).toBe('2026-11-10T06:00:00.000Z');
    expect(body.source).toBe('WEB');
    await screen.findByText('trip page');
  });
});
