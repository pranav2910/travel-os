package io.travelos.travelcore.trip;

import io.travelos.common.ids.IdPrefix;
import io.travelos.common.ids.Ids;
import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 3: conversations persist the turns of a request; each planning turn is a trip created
 * through {@link TripService#create} like any other, so authorization, idempotency, allocation and
 * purchase rules are the same whichever door a request comes through.
 */
@Service
public class ConversationService {
  private final ConversationRepository conversations;
  private final TripService trips;
  private final Clock clock;

  public ConversationService(ConversationRepository conversations, TripService trips, Clock clock) {
    this.conversations = conversations;
    this.trips = trips;
    this.clock = clock;
  }

  @Transactional
  public Conversation start(
      RequestPrincipal me,
      String text,
      @Nullable String purchaseMode,
      @Nullable String projectId,
      String idempotencyKey) {
    Optional<Conversation> replay = conversations.findByIdempotencyKey(me.tenant(), idempotencyKey);
    if (replay.isPresent()) {
      return replay.get();
    }
    Instant now = clock.instant();
    String id = Ids.newId(IdPrefix.CONVERSATION);
    Conversation c =
        new Conversation(
            id,
            me.tenant(),
            me.employeeIdOrThrow(),
            me.principal(),
            Conversation.Status.OPEN,
            null,
            idempotencyKey,
            now,
            now);
    if (conversations.insert(c) == 0) {
      return conversations.findByIdempotencyKey(me.tenant(), idempotencyKey).orElseThrow();
    }
    conversations.append(
        me.tenant(),
        id,
        Ids.newId(IdPrefix.MESSAGE),
        Conversation.Role.USER,
        text,
        null,
        "REQUEST",
        idempotencyKey,
        now);
    Trip trip = plan(me, c, text, purchaseMode, projectId, idempotencyKey + ":turn:1");
    conversations.update(me.tenant(), id, Conversation.Status.OPEN, trip.tripId(), now);
    return conversations.find(me.tenant(), id).orElseThrow();
  }

  /**
   * The next user turn. The whole transcript of user turns is the request text of a new trip, so
   * the understanding sees the original ask and every clarification together; the earlier turn's
   * trip stays as it was (a failed clarification, a superseded plan).
   */
  @Transactional
  public Conversation reply(
      RequestPrincipal me, String conversationId, String text, String idempotencyKey) {
    Conversation c = get(me, conversationId);
    if (c.status() == Conversation.Status.CLOSED) {
      throw new ApiException.Conflict("CONVERSATION_CLOSED", "this conversation is closed");
    }
    List<Conversation.Message> before = conversations.messages(me.tenant(), conversationId);
    boolean duplicate =
        before.stream()
            .anyMatch(
                m ->
                    m.role() == Conversation.Role.USER
                        && idempotencyKey.equals(m.idempotencyKey()));
    if (duplicate) {
      return c;
    }
    Instant now = clock.instant();
    String kind = c.status() == Conversation.Status.AWAITING_USER ? "CLARIFICATION" : "REQUEST";
    conversations.append(
        me.tenant(),
        conversationId,
        Ids.newId(IdPrefix.MESSAGE),
        Conversation.Role.USER,
        text,
        null,
        kind,
        idempotencyKey,
        now);
    if (c.currentTripId() != null) {
      Trip current = trips.getInternal(me.tenant(), c.currentTripId());
      if (current.status() == TripStatus.QUOTED
          || current.status() == TripStatus.AWAITING_APPROVAL
          || current.status() == TripStatus.APPROVED
          || current.status() == TripStatus.PLANNING
          || current.status() == TripStatus.SUBMITTED) {
        // a new ask replaces a plan nobody bought yet; a booked trip stays booked
        trips.cancel(me, current.tripId(), "replaced by a later turn of the conversation");
      }
    }
    String transcript =
        conversations.messages(me.tenant(), conversationId).stream()
            .filter(m -> m.role() == Conversation.Role.USER)
            .map(Conversation.Message::text)
            .collect(Collectors.joining("\n\nClarification: "));
    Trip trip = plan(me, c, transcript, null, null, idempotencyKey);
    conversations.update(me.tenant(), conversationId, Conversation.Status.OPEN, trip.tripId(), now);
    return conversations.find(me.tenant(), conversationId).orElseThrow();
  }

  private Trip plan(
      RequestPrincipal me,
      Conversation c,
      String text,
      @Nullable String purchaseMode,
      @Nullable String projectId,
      String idempotencyKey) {
    Trip trip =
        trips.create(
            me,
            new TripService.CreateTrip(
                c.travelerId(),
                TripSource.CONVERSATION,
                text,
                null,
                null,
                projectId,
                purchaseMode,
                false),
            idempotencyKey,
            c.conversationId());
    conversations.append(
        me.tenant(),
        c.conversationId(),
        Ids.newId(IdPrefix.MESSAGE),
        Conversation.Role.ASSISTANT,
        "Planning trip " + trip.tripId() + ".",
        trip.tripId(),
        "STATUS",
        null,
        clock.instant());
    return trip;
  }

  @Transactional(readOnly = true)
  public Conversation get(RequestPrincipal me, String conversationId) {
    return conversations
        .find(me.tenant(), conversationId)
        .filter(
            c ->
                c.travelerId().equals(me.employeeId())
                    || c.createdBy().id().equals(me.principal().id())
                    || me.hasRole("TRAVEL_ADMIN"))
        .orElseThrow(() -> new ApiException.NotFound("conversation", conversationId));
  }

  @Transactional(readOnly = true)
  public List<Conversation.Message> messages(RequestPrincipal me, String conversationId) {
    get(me, conversationId);
    return conversations.messages(me.tenant(), conversationId);
  }

  @Transactional(readOnly = true)
  public List<Conversation> listMine(RequestPrincipal me, int limit) {
    return conversations.listForTraveler(me.tenant(), me.employeeIdOrThrow(), limit);
  }
}
