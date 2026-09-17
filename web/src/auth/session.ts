import type { User } from 'oidc-client-ts';

export type Role = 'TRAVELER' | 'MANAGER' | 'TRAVEL_ADMIN' | 'FINANCE';

/** The verified identity the backend will see: claims of the access token, nothing typed by hand. */
export interface Session {
  username: string;
  displayName: string;
  email: string | null;
  tenantId: string;
  employeeId: string | null;
  roles: ReadonlySet<Role>;
  /** Opaque bearer token; only the API client reads it. */
  accessToken: string;
  expiresAt: number | null;
}

interface Claims {
  preferred_username?: string;
  given_name?: string;
  family_name?: string;
  email?: string;
  tenant_id?: string;
  employee_id?: string;
  roles?: string[];
}

const KNOWN: Role[] = ['TRAVELER', 'MANAGER', 'TRAVEL_ADMIN', 'FINANCE'];

export function sessionFrom(user: User): Session | null {
  const claims = user.profile as Claims;
  if (!claims.tenant_id) return null;
  const roles = new Set<Role>();
  for (const r of claims.roles ?? []) {
    if ((KNOWN as string[]).includes(r)) roles.add(r as Role);
  }
  const name = [claims.given_name, claims.family_name].filter(Boolean).join(' ');
  return {
    username: claims.preferred_username ?? 'user',
    displayName: name || claims.preferred_username || 'user',
    email: claims.email ?? null,
    tenantId: claims.tenant_id,
    employeeId: claims.employee_id ?? null,
    roles,
    accessToken: user.access_token,
    expiresAt: user.expires_at ?? null,
  };
}

export function hasRole(s: Session | null, ...roles: Role[]): boolean {
  if (!s) return false;
  return roles.some((r) => s.roles.has(r));
}

/** What the navigation shows; the backend remains the authority on every request. */
export const capabilities = {
  approvals: (s: Session | null) => hasRole(s, 'MANAGER', 'TRAVEL_ADMIN'),
  operations: (s: Session | null) => hasRole(s, 'MANAGER', 'TRAVEL_ADMIN', 'FINANCE'),
  finance: (s: Session | null) => hasRole(s, 'FINANCE', 'TRAVEL_ADMIN'),
  connectors: (s: Session | null) => hasRole(s, 'TRAVEL_ADMIN', 'FINANCE'),
  learning: (s: Session | null) => hasRole(s, 'TRAVEL_ADMIN', 'FINANCE'),
  learningAdmin: (s: Session | null) => hasRole(s, 'TRAVEL_ADMIN'),
  arrangeForOthers: (s: Session | null) => hasRole(s, 'MANAGER', 'TRAVEL_ADMIN'),
};
