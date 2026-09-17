import { apiFetch, query } from './http';
import type { ExposureListItem, ExposureView, OrderResponse } from './types';

export const orderKeys = {
  byTrip: (tripId: string) => ['orders', 'trip', tripId] as const,
  one: (orderId: string) => ['orders', 'one', orderId] as const,
  exposures: (status: string) => ['orders', 'exposures', status] as const,
};

export const orders = {
  byTrip: (tripId: string) => apiFetch<OrderResponse[]>(`/api/v1/orders${query({ tripId })}`),
  get: (orderId: string) =>
    apiFetch<OrderResponse>(`/api/v1/orders/${encodeURIComponent(orderId)}`),
  exposures: (status: 'OPEN' | 'RESOLVED' = 'OPEN') =>
    apiFetch<ExposureListItem[]>(`/api/v1/orders/exposures${query({ status })}`),
  resolveExposure: (
    orderId: string,
    exposureId: string,
    resolution: string,
    idempotencyKey: string,
  ) =>
    apiFetch<ExposureView>(
      `/api/v1/orders/${encodeURIComponent(orderId)}/exposures/${encodeURIComponent(exposureId)}/resolution`,
      {
        method: 'POST',
        body: { resolution },
        idempotencyKey,
      },
    ),
};
