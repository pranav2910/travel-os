"""List prices, USD per million tokens, used to attribute cost to tenants and trips."""

from __future__ import annotations

# (input, output, cache read). Cache reads are billed at 10% of input.
_PRICES: dict[str, tuple[float, float, float]] = {
    "claude-opus-5": (5.00, 25.00, 0.50),
    "claude-sonnet-5": (2.00, 10.00, 0.20),
    "claude-haiku-4-5": (1.00, 5.00, 0.10),
}


def cost_micros(model: str, input_tokens: int, output_tokens: int, cache_read_tokens: int) -> int:
    prices = _PRICES.get(model)
    if prices is None:
        return 0
    per_in, per_out, per_cache = prices
    usd = (
        input_tokens * per_in + output_tokens * per_out + cache_read_tokens * per_cache
    ) / 1_000_000
    return round(usd * 1_000_000)
