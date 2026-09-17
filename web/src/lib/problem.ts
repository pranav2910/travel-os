/** RFC 9457 problem details as libs/spring-web emits them, plus the platform's `code`. */
export interface Problem {
  status: number;
  code: string;
  title: string;
  detail: string;
  fields?: Record<string, string>;
}

export class ApiError extends Error {
  readonly problem: Problem;
  constructor(problem: Problem) {
    super(`${problem.code}: ${problem.detail}`);
    this.name = 'ApiError';
    this.problem = problem;
  }
  get status(): number {
    return this.problem.status;
  }
  get code(): string {
    return this.problem.code;
  }
  is(status: number): boolean {
    return this.problem.status === status;
  }
}

/** The request may or may not have reached the server: the outcome must be reconciled, not retried blindly. */
export class UncertainError extends Error {
  readonly reason: unknown;
  constructor(message: string, reason?: unknown) {
    super(message);
    this.reason = reason;
    this.name = 'UncertainError';
  }
}

export function describe(error: unknown): string {
  if (error instanceof ApiError)
    return error.problem.detail || error.problem.title || error.problem.code;
  if (error instanceof UncertainError) return error.message;
  if (error instanceof Error) return error.message;
  return String(error);
}

export function codeOf(error: unknown): string | null {
  return error instanceof ApiError ? error.problem.code : null;
}
