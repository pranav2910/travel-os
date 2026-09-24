package io.travelos.assistance.notify;

import io.travelos.assistance.notify.NotificationRecords.Category;
import io.travelos.assistance.store.TripIndexRepository;
import io.travelos.common.tenant.TenantId;
import io.travelos.events.EventEnvelope;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Phase 8: which platform facts a person should hear about, in plain words. Travelers hear about
 * their own trips; approvers about what waits on them; the queues about cases. The wording never
 * carries a supplier's free text as an instruction, only as a quoted reason.
 */
@Component
public class NotificationRules {
  private final NotificationService notifications;
  private final TripIndexRepository trips;

  public NotificationRules(NotificationService notifications, TripIndexRepository trips) {
    this.notifications = notifications;
    this.trips = trips;
  }

  public void apply(EventEnvelope event) {
    TenantId tenant = TenantId.of(event.tenantId());
    Map<String, Object> d = event.data();
    String tripId = str(d.get("tripId"));
    TripIndexRepository.TripRef trip =
        tripId == null ? null : trips.find(tenant, tripId).orElse(null);
    String traveler =
        str(d.get("travelerId")) != null
            ? str(d.get("travelerId"))
            : trip == null ? null : trip.travelerId();
    String email =
        str(d.get("travelerEmail")) != null
            ? str(d.get("travelerEmail"))
            : trip == null ? null : trip.travelerEmail();
    String where = trip != null && trip.destination() != null ? " to " + trip.destination() : "";
    switch (event.eventType()) {
      case "travel.trip.created" ->
          toTraveler(
              event,
              tenant,
              traveler,
              email,
              Category.TRIP,
              "We are planning your trip" + where,
              "Your request was received and planning has started. You will hear from us when there is something to confirm.",
              tripId,
              "NORMAL");
      case "travel.trip.quoted" ->
          toTraveler(
              event,
              tenant,
              traveler,
              email,
              Category.TRIP,
              "Your options are ready" + where,
              "A plan is priced at "
                  + money(d.get("total"))
                  + ". Open the trip to confirm it, pick another option or refresh the price."
                  + (d.get("quoteExpiresAt") == null
                      ? ""
                      : " The quote holds until " + str(d.get("quoteExpiresAt")) + "."),
              tripId,
              "HIGH");
      case "travel.trip.booked" ->
          toTraveler(
              event,
              tenant,
              traveler,
              email,
              Category.TRIP,
              "Your trip is booked" + where,
              "Everything is confirmed for "
                  + money(d.get("total"))
                  + ". Your itinerary and calendar file are on the trip page.",
              tripId,
              "NORMAL");
      case "travel.trip.cancelled" ->
          toTraveler(
              event,
              tenant,
              traveler,
              email,
              Category.TRIP,
              "Your trip was cancelled",
              "Reason: "
                  + str(d.get("reason"))
                  + ". Refunds and credits, if any, appear on the trip's receipt.",
              tripId,
              "NORMAL");
      case "travel.trip.failed" ->
          toTraveler(
              event,
              tenant,
              traveler,
              email,
              Category.TRIP,
              "We could not complete your trip",
              "The trip stopped at "
                  + str(d.get("stage"))
                  + " ("
                  + str(d.get("reasonCode"))
                  + "). A person will follow up; nothing was booked that you will be charged for.",
              tripId,
              "HIGH");
      case "travel.trip.components-released" ->
          toTraveler(
              event,
              tenant,
              traveler,
              email,
              Category.TRIP,
              "Part of your trip was released",
              "The components you asked to cancel were handled; the rest of the trip stands. Details are on the trip page.",
              tripId,
              "NORMAL");
      case "travel.approval.requested" -> {
        String from = str(d.get("requestedFrom"));
        String title = "Approval needed: trip " + tripId + " (" + money(d.get("total")) + ")";
        String body =
            "Step "
                + (d.get("step") == null ? 1 : d.get("step"))
                + " of "
                + (d.get("chainLength") == null ? 1 : d.get("chainLength"))
                + " is yours to decide"
                + (d.get("expiresAt") == null ? "." : " before " + str(d.get("expiresAt")) + ".");
        if (from != null && !from.startsWith("role:")) {
          notifications.notify(
              new NotificationService.Request(
                  tenant,
                  from,
                  null,
                  null,
                  Category.APPROVAL,
                  event.eventType(),
                  title,
                  body,
                  "trip",
                  tripId,
                  tripId,
                  "notify:" + event.eventId() + ":" + from,
                  "HIGH",
                  event.eventId()));
        } else {
          String role = from == null ? str(d.get("role")) : from.substring("role:".length());
          notifications.notify(
              new NotificationService.Request(
                  tenant,
                  null,
                  role,
                  null,
                  Category.APPROVAL,
                  event.eventType(),
                  title,
                  body,
                  "trip",
                  tripId,
                  tripId,
                  "notify:" + event.eventId() + ":role",
                  "HIGH",
                  event.eventId()));
        }
      }
      case "travel.approval.approved" -> {
        boolean last = !Boolean.FALSE.equals(d.get("finalStep"));
        toTraveler(
            event,
            tenant,
            traveler,
            email,
            Category.APPROVAL,
            last ? "Your trip was approved" : "Your trip moved to the next approver",
            (last ? "Approved by " : "Step approved by ")
                + str(d.get("decidedBy"))
                + (d.get("comment") == null ? "." : ": " + str(d.get("comment"))),
            tripId,
            "NORMAL");
      }
      case "travel.approval.rejected" ->
          toTraveler(
              event,
              tenant,
              traveler,
              email,
              Category.APPROVAL,
              "Your trip was not approved",
              "Decided by "
                  + str(d.get("decidedBy"))
                  + (d.get("comment") == null ? "." : ": " + str(d.get("comment"))),
              tripId,
              "HIGH");
      case "travel.approval.escalated" ->
          notifications.notify(
              new NotificationService.Request(
                  tenant,
                  null,
                  "TRAVEL_ADMIN",
                  null,
                  Category.APPROVAL,
                  event.eventType(),
                  "An approval went unanswered: trip " + tripId,
                  str(d.get("reason")) + ". It is now yours to decide.",
                  "trip",
                  tripId,
                  tripId,
                  "notify:" + event.eventId(),
                  "HIGH",
                  event.eventId()));
      case "travel.disruption.impact-confirmed" ->
          toTraveler(
              event,
              tenant,
              traveler,
              email,
              Category.DISRUPTION,
              "Your flight "
                  + str(d.get("affected") instanceof Map<?, ?> m ? m.get("flightNumber") : "")
                  + " is affected",
              "The airline reported "
                  + str(d.get("type"))
                  + ". We are looking for alternatives now and will tell you what we find.",
              tripId,
              "CRITICAL");
      case "travel.disruption.resolved" ->
          toTraveler(
              event,
              tenant,
              traveler,
              email,
              Category.DISRUPTION,
              "Your trip is rebooked",
              "New reservation "
                  + str(d.get("recordLocator"))
                  + (d.get("incrementalCost") == null
                      ? "."
                      : " (" + money(d.get("incrementalCost")) + " more)."),
              tripId,
              "CRITICAL");
      case "travel.disruption.recovery-failed" ->
          toTraveler(
              event,
              tenant,
              traveler,
              email,
              Category.DISRUPTION,
              "We could not rebook you automatically",
              "A person is handling it now ("
                  + str(d.get("reasonCode"))
                  + "). Reply to this message or call travel support if you are at the airport.",
              tripId,
              "CRITICAL");
      case "travel.disruption.approval-required" ->
          notifications.notify(
              new NotificationService.Request(
                  tenant,
                  null,
                  str(d.get("role")),
                  null,
                  Category.DISRUPTION,
                  event.eventType(),
                  "A stranded traveler needs an approval: trip " + tripId,
                  "The recovery costs " + money(d.get("incrementalCost")) + " more; decide it now.",
                  "trip",
                  tripId,
                  tripId,
                  "notify:" + event.eventId(),
                  "CRITICAL",
                  event.eventId()));
      case "travel.finance.payment-declined" -> {
        toTraveler(
            event,
            tenant,
            traveler,
            email,
            Category.FINANCE,
            "Payment declined for your trip",
            "The instrument was declined ("
                + str(d.get("reasonCode"))
                + "). Finance has been told; nothing was booked.",
            tripId,
            "HIGH");
        notifications.notify(
            new NotificationService.Request(
                tenant,
                null,
                "FINANCE",
                null,
                Category.FINANCE,
                event.eventType(),
                "Payment declined: order " + str(d.get("orderId")),
                str(d.get("reasonCode")) + ": " + str(d.get("message")),
                "trip",
                tripId,
                tripId,
                "notify:" + event.eventId() + ":finance",
                "HIGH",
                event.eventId()));
      }
      case "travel.assistance.case-opened" -> {
        String queue = str(d.get("queue"));
        String role =
            "FINANCE".equals(queue)
                ? "FINANCE"
                : "APPROVALS".equals(queue) ? "MANAGER" : "TRAVEL_ADMIN";
        notifications.notify(
            new NotificationService.Request(
                tenant,
                null,
                role,
                null,
                Category.CASE,
                event.eventType(),
                "New " + str(d.get("priority")) + " case: " + str(d.get("title")),
                str(d.get("nextAction")) + " (due " + str(d.get("dueAt")) + ")",
                "case",
                str(d.get("caseId")),
                tripId,
                "notify:" + event.eventId(),
                str(d.get("priority")),
                event.eventId()));
      }
      case "travel.assistance.case-escalated" ->
          notifications.notify(
              new NotificationService.Request(
                  tenant,
                  null,
                  "TRAVEL_ADMIN",
                  null,
                  Category.CASE,
                  event.eventType(),
                  "Case escalated (level " + d.get("escalationLevel") + "): " + str(d.get("title")),
                  str(d.get("reason")) + ". Next: " + str(d.get("nextAction")),
                  "case",
                  str(d.get("caseId")),
                  tripId,
                  "notify:" + event.eventId(),
                  "CRITICAL",
                  event.eventId()));
      default -> {}
    }
  }

  private void toTraveler(
      EventEnvelope event,
      TenantId tenant,
      @Nullable String traveler,
      @Nullable String email,
      Category category,
      String title,
      String body,
      @Nullable String tripId,
      String priority) {
    if (traveler == null) {
      return;
    }
    notifications.notify(
        new NotificationService.Request(
            tenant,
            traveler,
            null,
            email,
            category,
            event.eventType(),
            title,
            body,
            "trip",
            tripId,
            tripId,
            "notify:" + event.eventId() + ":" + traveler,
            priority,
            event.eventId()));
  }

  private static @Nullable String str(@Nullable Object value) {
    if (value == null) {
      return null;
    }
    String s = String.valueOf(value);
    return s.isBlank() ? null : s;
  }

  private static String money(@Nullable Object value) {
    if (value instanceof Map<?, ?> m && m.get("currency") != null && m.get("amountMinor") != null) {
      long minor = ((Number) m.get("amountMinor")).longValue();
      return m.get("currency")
          + " "
          + (minor / 100)
          + "."
          + String.format("%02d", Math.abs(minor % 100));
    }
    return "an amount not yet known";
  }

  static List<String> roles() {
    return List.of("TRAVEL_ADMIN", "FINANCE", "MANAGER");
  }
}
