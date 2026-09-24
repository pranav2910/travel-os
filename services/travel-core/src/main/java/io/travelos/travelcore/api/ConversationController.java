package io.travelos.travelcore.api;

import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.idempotency.IdempotencyKeyHeader;
import io.travelos.travelcore.trip.Conversation;
import io.travelos.travelcore.trip.ConversationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 3: a persisted, multi-turn way to ask for travel. Every turn that describes a trip becomes
 * a regular trip through the same service as {@code POST /api/v1/trips}; the platform's answers (a
 * clarifying question, a quote, a booking, a failure) are messages of the same conversation.
 */
@RestController
@RequestMapping(path = "/api/v1/conversations", produces = "application/json")
public class ConversationController {
  private final ConversationService conversations;

  public ConversationController(ConversationService conversations) {
    this.conversations = conversations;
  }

  public record StartRequest(
      @NotBlank @Size(max = 4000) String text,
      @Nullable @Pattern(regexp = "^(POLICY|CONFIRM)$") String purchaseMode,
      @Nullable @Size(max = 64) String projectId) {}

  public record MessageRequest(@NotBlank @Size(max = 4000) String text) {}

  public record MessageView(
      String messageId,
      int seq,
      String role,
      String text,
      @Nullable String tripId,
      @Nullable String kind,
      Instant createdAt) {
    static MessageView from(Conversation.Message m) {
      return new MessageView(
          m.messageId(), m.seq(), m.role().name(), m.text(), m.tripId(), m.kind(), m.createdAt());
    }
  }

  public record ConversationView(
      String conversationId,
      String travelerId,
      String status,
      @Nullable String currentTripId,
      List<MessageView> messages,
      Instant createdAt,
      Instant updatedAt) {
    static ConversationView from(Conversation c, List<Conversation.Message> messages) {
      return new ConversationView(
          c.conversationId(),
          c.travelerId(),
          c.status().name(),
          c.currentTripId(),
          messages.stream().map(MessageView::from).toList(),
          c.createdAt(),
          c.updatedAt());
    }
  }

  @PostMapping(consumes = "application/json")
  public ResponseEntity<ConversationView> start(
      @AuthenticationPrincipal RequestPrincipal me,
      @IdempotencyKeyHeader String idempotencyKey,
      @Valid @RequestBody StartRequest request) {
    Conversation c =
        conversations.start(
            me, request.text(), request.purchaseMode(), request.projectId(), idempotencyKey);
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/conversations/" + c.conversationId()))
        .body(ConversationView.from(c, conversations.messages(me, c.conversationId())));
  }

  @GetMapping
  public List<ConversationView> list(
      @AuthenticationPrincipal RequestPrincipal me, @RequestParam(defaultValue = "20") int limit) {
    return conversations.listMine(me, Math.max(1, Math.min(limit, 100))).stream()
        .map(c -> ConversationView.from(c, conversations.messages(me, c.conversationId())))
        .toList();
  }

  @GetMapping("/{conversationId}")
  public ConversationView get(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String conversationId) {
    Conversation c = conversations.get(me, conversationId);
    return ConversationView.from(c, conversations.messages(me, conversationId));
  }

  /**
   * The next turn: an answer to a question, a change of mind, or a new request in the same thread.
   */
  @PostMapping(path = "/{conversationId}/messages", consumes = "application/json")
  public ResponseEntity<ConversationView> reply(
      @AuthenticationPrincipal RequestPrincipal me,
      @PathVariable String conversationId,
      @IdempotencyKeyHeader String idempotencyKey,
      @Valid @RequestBody MessageRequest request) {
    Conversation c = conversations.reply(me, conversationId, request.text(), idempotencyKey);
    return ResponseEntity.accepted()
        .body(ConversationView.from(c, conversations.messages(me, conversationId)));
  }
}
