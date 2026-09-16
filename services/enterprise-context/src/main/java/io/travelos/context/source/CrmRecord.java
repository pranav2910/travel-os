package io.travelos.context.source;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A CRM record. Only an explicitly scheduled on-site visit ({@code kind=VISIT}, {@code onSite}) can
 * mean travel; a deal's value or stage and free-text notes never do on their own.
 *
 * @param kind VISIT | DEAL | NOTE
 * @param status SCHEDULED | COMPLETED | CANCELLED
 * @param calendarEventId an explicit link to the calendar event of the same visit, when the CRM
 *     keeps one
 */
public record CrmRecord(
    String sourceId,
    String kind,
    String ownerEmail,
    @Nullable String accountName,
    @Nullable String accountCity,
    @Nullable Instant scheduledStart,
    @Nullable Instant scheduledEnd,
    @Nullable String timeZone,
    boolean onSite,
    String status,
    @Nullable String calendarEventId,
    @Nullable Long dealValueMinor,
    @Nullable String stage,
    @Nullable String notes) {}
