package io.travelos.context.source;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * Spend or planned spend. Enrichment and duplicate detection only: a receipt never starts a trip.
 *
 * @param kind RECEIPT | PREAPPROVAL | TRIP_REPORT
 */
public record ExpenseRecord(
    String sourceId,
    String employeeEmail,
    String kind,
    @Nullable String city,
    LocalDate startDate,
    LocalDate endDate,
    long amountMinor,
    String currency,
    @Nullable String merchant,
    @Nullable String tripId) {}
