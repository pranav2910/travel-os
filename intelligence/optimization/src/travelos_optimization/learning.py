"""Learning from outcomes (Slice 5): bounded, explainable soft adjustments.

The optimizer never learns anything itself. It receives, per attempt, the inputs the workflow
resolved from the Learning service and pinned: a profile id, its algorithm version and evidence
class, and one bounded adjustment per supplier key. An adjustment is points on the 0..100 score,
never money: feasibility (deadlines, cabins, providers, the budget) is decided before scores exist
and is untouched by anything here.

  OFF (or no inputs)  the baseline, exactly
  SHADOW              the learned ranking is computed and reported; the baseline one is executed
  ACTIVE              the learned ranking is executed; the baseline one is reported alongside

Every candidate's contribution is reported with the supplier keys it rests on and the reasons the
profile gave, so "why this one?" stays answerable without implying a guarantee.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from travelos_optimization.model import Candidate

#: No profile may move a score by more than this, whatever it asks for.
HARD_MAX_ADJUSTMENT = 25.0
DEFAULT_MAX_ADJUSTMENT = 10.0
MODES = ("OFF", "SHADOW", "ACTIVE")


@dataclass(frozen=True)
class Adjustment:
    supplier_key: str
    adjustment: float
    estimate: float = 0.0
    samples: int = 0
    source: str = "SUPPLIER_RELIABILITY"
    reason: str = ""


@dataclass(frozen=True)
class Inputs:
    mode: str = "OFF"
    profile_id: str = ""
    algorithm_version: str = ""
    evidence_class: str = ""
    # Every adjustment that names a supplier key (a supplier's reliability AND a traveler's own
    # preference may both speak about one key); the contribution is their bounded sum.
    adjustments: dict[str, tuple[Adjustment, ...]] = field(default_factory=dict)
    max_adjustment: float = DEFAULT_MAX_ADJUSTMENT
    fallback_reason: str = ""

    @property
    def usable(self) -> bool:
        """A profile that may influence a ranking (report in SHADOW, execute in ACTIVE)."""
        return (
            self.mode in ("SHADOW", "ACTIVE") and bool(self.profile_id) and not self.fallback_reason
        )

    @property
    def applies(self) -> bool:
        return self.usable and self.mode == "ACTIVE"


@dataclass(frozen=True)
class Contribution:
    candidate_id: str
    component_id: str
    supplier_keys: tuple[str, ...]
    baseline_score: float
    adjustment: float
    learned_score: float
    reasons: tuple[str, ...]


@dataclass(frozen=True)
class Evidence:
    mode: str
    profile_id: str
    algorithm_version: str
    evidence_class: str
    applied: bool
    fallback_reason: str
    baseline_selected_id: str
    learned_selected_id: str
    contributions: tuple[Contribution, ...]
    max_adjustment: float


def from_proto(p) -> Inputs | None:
    """None when the request carries no learning at all (a Slice 1-4 caller)."""
    if p is None or (not p.mode and not p.profile_id and not p.fallback_reason):
        return None
    mode = p.mode if p.mode in MODES else "OFF"
    max_adjustment = p.max_adjustment if p.max_adjustment > 0 else DEFAULT_MAX_ADJUSTMENT
    max_adjustment = min(float(max_adjustment), HARD_MAX_ADJUSTMENT)
    adjustments: dict[str, tuple[Adjustment, ...]] = {}
    for a in p.adjustments:
        if not a.supplier_key:
            continue
        value = float(a.adjustment)
        if value != value or value in (float("inf"), float("-inf")):  # NaN / infinite: ignored
            continue
        adjustments[a.supplier_key] = adjustments.get(a.supplier_key, ()) + (
            Adjustment(
                supplier_key=a.supplier_key,
                adjustment=max(-max_adjustment, min(max_adjustment, value)),
                estimate=float(a.estimate),
                samples=int(a.samples),
                source=a.source or "SUPPLIER_RELIABILITY",
                reason=a.reason or "",
            ),
        )
    return Inputs(
        mode=mode,
        profile_id=p.profile_id,
        algorithm_version=p.algorithm_version,
        evidence_class=p.evidence_class,
        adjustments=adjustments,
        max_adjustment=max_adjustment,
        fallback_reason=p.fallback_reason,
    )


def keys_of_candidate(c: Candidate) -> tuple[str, ...]:
    keys: list[str] = []
    for j in c.journeys:
        if j.segments:
            key = f"air:{j.segments[0].carrier}"
            if key not in keys:
                keys.append(key)
    for h in c.hotel_ids:
        key = f"hotel:{h}"
        if key not in keys:
            keys.append(key)
    return tuple(keys)


def keys_of_offer(o) -> tuple[str, ...]:
    """A ComponentOffer's keys (typed loosely to avoid an import cycle with itinerary.py)."""
    if o.kind == "AIR":
        return tuple(f"air:{c}" for c in sorted(o.carriers))
    if o.kind == "HOTEL":
        return (f"hotel:{o.hotel_id}",) if o.hotel_id else ()
    if o.kind == "GROUND":
        vendor = getattr(o, "vendor_id", None)
        return (f"ground:{vendor}",) if vendor else ()
    return ()


def contribution_for(keys: tuple[str, ...], inputs: Inputs) -> tuple[float, tuple[str, ...]]:
    """The bounded sum of the matching adjustments and their reasons. Unknown keys add 0."""
    total = 0.0
    reasons: list[str] = []
    for key in keys:
        for a in inputs.adjustments.get(key, ()):
            total += a.adjustment
            reasons.append(a.reason or f"{key}: {a.adjustment:+.1f}")
    total = max(-inputs.max_adjustment, min(inputs.max_adjustment, total))
    return round(total, 4), tuple(reasons)


def clamp_score(score: float) -> float:
    return max(0.0, min(100.0, score))


def off(inputs: Inputs | None) -> Evidence:
    """Nothing applied: the baseline, with the reason when a profile was expected."""
    if inputs is None:
        return Evidence("OFF", "", "", "", False, "", "", "", (), DEFAULT_MAX_ADJUSTMENT)
    reason = inputs.fallback_reason
    if not reason:
        if inputs.mode == "OFF":
            reason = "MODE_OFF"
        elif not inputs.profile_id:
            reason = "NO_ACTIVE_PROFILE"
    return Evidence(
        inputs.mode,
        inputs.profile_id,
        inputs.algorithm_version,
        inputs.evidence_class,
        False,
        reason,
        "",
        "",
        (),
        inputs.max_adjustment,
    )
