-- The currency each item was booked in (from travel.order.confirmed/changed). A settled refund is
-- checked against it: the platform never converts, so a refund in another currency is refused.
ALTER TABLE order_item ADD COLUMN currency CHAR(3);
