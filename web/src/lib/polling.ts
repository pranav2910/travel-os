/**
 * Bounded polling with backoff for workflow-driven state: refetch quickly while something is in
 * flight, slow down over time, stop entirely at a terminal state. TanStack Query calls this on
 * every settle; the interval is derived from the data, never from a wall-clock timer.
 */
export function pollingInterval(
  isTerminal: boolean,
  startedAt: number | null,
  options: { minMs?: number; maxMs?: number; giveUpAfterMs?: number } = {},
): number | false {
  if (isTerminal) return false;
  const { minMs = 1500, maxMs = 15000, giveUpAfterMs = 30 * 60_000 } = options;
  const elapsed = startedAt === null ? 0 : Date.now() - startedAt;
  if (elapsed > giveUpAfterMs) return false;
  const steps = Math.floor(elapsed / 30_000);
  return Math.min(maxMs, minMs * 2 ** steps);
}

/**
 * A resting state (a person is needed) stops polling; once that person acted, the workflow moves
 * again and the view must follow. `wake(key)` re-arms polling for a bounded while, and the wake
 * time becomes the new backoff origin so the first refetches are quick again.
 */
const woken = new Map<string, number>();
export const WAKE_WINDOW_MS = 5 * 60_000;
export function wake(key: string): void {
  woken.set(key, Date.now());
}
export function wokenAt(key: string): number | null {
  const at = woken.get(key);
  if (at === undefined) return null;
  if (Date.now() - at > WAKE_WINDOW_MS) {
    woken.delete(key);
    return null;
  }
  return at;
}

/**
 * A record reached by deep link may not exist *yet*: its id is minted before the event that
 * creates it is consumed (a supplier notice, a sync run). For a bounded while after the page was
 * opened, a 404 means "not here yet" and is asked again; after that it is a real "not found".
 */
export const ARRIVAL_WINDOW_MS = 60_000;
export function arrivalInterval(openedAt: number, notFound: boolean): number | false {
  return notFound && Date.now() - openedAt < ARRIVAL_WINDOW_MS ? 2000 : false;
}
