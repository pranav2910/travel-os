-- What a supplier gave back when an item was released. Recorded per item at release time so a
-- cancellation that is retried after a crash (or finished by a person) still reports the whole
-- refund in travel.order.cancelled instead of only the part the last attempt saw.
ALTER TABLE order_item
    ADD COLUMN refund_currency CHAR(3),
    ADD COLUMN refund_minor    BIGINT;

-- order_exposure.reason gains a third value: CANCELLATION_REFUSED (a person asked to cancel and
-- the supplier refused to release the component). No schema change: the column is free text.
