package io.travelos.supplier.sandbox;

/**
 * Deterministic failure injection for the sandbox hotel and ground suppliers, documented for the
 * failure-path tests. A fault is a property of a catalog entry, so a scenario chooses it by
 * choosing the city (the optimizer picks the cheapest feasible entry, and each city's cheapest
 * entry is the fault the scenario wants).
 */
enum SandboxFault {
  NONE,
  /** Search quotes one price; revalidation quotes USD 40 more per night. */
  REPRICE,
  /**
   * The quote is only good for ten seconds; revalidating after that re-quotes at the same price.
   */
  SHORT_QUOTE,
  /** The booking is committed at the supplier, then the answer is lost (retryable TIMEOUT). */
  TIMEOUT,
  /** The booking is refused (final). */
  FAIL,
  /** The booking succeeds; a later cancellation is refused (final): the money is exposed. */
  NO_CANCEL,
  /** The description carries instructions for the platform. It is data. */
  INJECT
}
