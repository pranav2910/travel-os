# ADR-0020: People hear from the platform through durable notifications; safety is a platform record; the itinerary is exportable

Status: accepted (platform completion, Phase 8, 2026-09-24)

## Context

Until Phase 7 every fact the platform produced went to Kafka and to the web app's polling views;
nobody was told anything. A traveler learned of a cancelled flight by opening the app; an approver
found out about a pending step by looking; an operations queue watched a board. There was no idea
of where booked travelers were when something happened somewhere, and the itinerary lived only in
the app.

## Decision

1. **A notification is a row before it is a message.** The assistance service (already the
   consumer of every people-facing fact) keeps `notification` (one per fact and recipient, deduped
   by key, with the person's read state) and `notification_delivery` (one per channel, PENDING ->
   SENT / FAILED / SKIPPED, retried with backoff by a dispatcher outside the event transaction).
   In-app is always on; email needs an address and a provider (SendGrid, credential-gated); chat
   needs a handle and a webhook (Slack, credential-gated). Addresses are learned from the person's
   own sign-in claims, from the events (`travelerEmail`), or from their preferences, and are
   masked when shown. A failed delivery is visible to a travel admin, never silently dropped.
2. **Recipients are people or roles.** Travelers hear about their own trips, approvals,
   disruptions and safety; the manager an allocation names hears about the step that is theirs
   (`travel.approval.requested.requestedFrom`); a role hears about what its queue owns (cases,
   escalations, Finance for declined payments). Preferences choose channels per category; in-app
   cannot be switched off.
3. **Traveler safety is derived from the platform's own trip records, never from a device.** An
   advisory names places (countries and IATA cities) and a window; the affected travelers are the
   booked trips overlapping it (the assistance trip index learns destination, dates and cities from
   `travel.trip.created/booked`). Each is told; on a HIGH or CRITICAL advisory each is asked to
   check in. A traveler who asks for help, or who has not answered when the grace period ends,
   becomes a CRITICAL SAFETY case for a person. Check-ins and advisories are events
   (`travel.assistance.advisory-issued`, `checkin-recorded`).
4. **The itinerary is exportable from Travel Core.** `GET /api/v1/trips/{id}/itinerary.ics` is an
   RFC 5545 calendar (one event per leg, stay and transfer, the supplier reference on confirmed
   components, folded lines, UTC stamps); `GET /api/v1/trips/{id}/itinerary` is the same as JSON.
   Both follow the trip's own visibility rule. Neither is authoritative for the reservation.

## Consequences

- Notifications add no authority: they say what happened and where to act; the acting still goes
  through the authoritative services with the person's own credentials.
- The assistance service remains a consumer: down, nothing is lost (the outbox and the topics keep
  the facts); up again, it catches up and people are told late rather than never.
- Email and chat are provider-test-blocked here (no SendGrid key or Slack webhook in this
  repository); the recording channel in the tests proves the delivery machinery, not a provider.
- Safety knows where a trip goes, not where a person is; a traveler who changed plans outside the
  platform is not seen. Phase 9 reports the advisories and their check-in rates.
