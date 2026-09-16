"""Select exactly one feasible bundle that maximizes the weighted score.

For pre-assembled bundles this is an argmax, and CP-SAT is admittedly a large hammer. It is here
because the next step is choosing one air offer AND one hotel offer under joint constraints
(budget, distance, dates), where the model earns its keep. The objective is integer-scaled and
tie-broken by cost then bundle id, so the solver's answer is reproducible.
"""

from __future__ import annotations

import time
from dataclasses import dataclass

from ortools.sat.python import cp_model

from travelos_optimization import learning
from travelos_optimization.model import (
    Breakdown,
    Candidate,
    Constraints,
    Preferences,
    Ranked,
)
from travelos_optimization.scoring import breakdowns, infeasibility_reasons, zero

SCORE_SCALE = 10_000  # 4 decimal places of score survive integerization
SOLVER_NAME = "ortools-cpsat"


@dataclass(frozen=True)
class Result:
    selected_bundle_id: str  # "" when nothing is feasible
    ranking: tuple[Ranked, ...]
    solver: str
    solve_time_ms: int
    learning: learning.Evidence | None = None


def optimize(
    candidates: list[Candidate],
    k: Constraints,
    p: Preferences,
    inputs: learning.Inputs | None = None,
) -> Result:
    started = time.perf_counter()
    reasons = {c.bundle_id: infeasibility_reasons(c, k) for c in candidates}
    feasible = [c for c in candidates if not reasons[c.bundle_id]]
    scores: dict[str, Breakdown] = breakdowns(feasible, p)
    weighted = {c.bundle_id: scores[c.bundle_id].weighted(p.weights) for c in feasible}

    baseline_selected = _select(feasible, weighted) if feasible else ""
    selected = baseline_selected
    evidence = learning.off(inputs)
    if inputs is not None and inputs.usable and feasible:
        contributions: list[learning.Contribution] = []
        learned: dict[str, float] = {}
        for c in feasible:
            keys = learning.keys_of_candidate(c)
            adjustment, why = learning.contribution_for(keys, inputs)
            learned[c.bundle_id] = learning.clamp_score(weighted[c.bundle_id] + adjustment)
            contributions.append(
                learning.Contribution(
                    c.bundle_id,
                    "",
                    keys,
                    round(weighted[c.bundle_id], 4),
                    adjustment,
                    round(learned[c.bundle_id], 4),
                    why,
                )
            )
        learned_selected = _select(feasible, learned)
        if inputs.applies:
            selected = learned_selected
            weighted = learned
        evidence = learning.Evidence(
            inputs.mode,
            inputs.profile_id,
            inputs.algorithm_version,
            inputs.evidence_class,
            inputs.applies,
            "",
            baseline_selected,
            learned_selected,
            tuple(contributions[:200]),
            inputs.max_adjustment,
        )

    ordered_feasible = sorted(
        feasible, key=lambda c: (-weighted[c.bundle_id], c.total.amount_minor, c.bundle_id)
    )
    ordered_infeasible = sorted(
        (c for c in candidates if reasons[c.bundle_id]),
        key=lambda c: (c.total.amount_minor, c.bundle_id),
    )
    ranking: list[Ranked] = []
    for rank, c in enumerate(ordered_feasible + ordered_infeasible, start=1):
        ok = not reasons[c.bundle_id]
        ranking.append(
            Ranked(
                bundle_id=c.bundle_id,
                feasible=ok,
                score=round(weighted[c.bundle_id], 4) if ok else 0.0,
                breakdown=scores[c.bundle_id] if ok else zero(),
                infeasibility_reasons=reasons[c.bundle_id],
                rank=rank,
            )
        )
    elapsed_ms = int((time.perf_counter() - started) * 1000)
    return Result(selected, tuple(ranking), _solver_name(), elapsed_ms, evidence)


def _select(feasible: list[Candidate], weighted: dict[str, float]) -> str:
    model = cp_model.CpModel()
    # Deterministic tie-breaking: cost rank, then id rank, encoded far below the score scale.
    by_cost = sorted(feasible, key=lambda c: (c.total.amount_minor, c.bundle_id))
    cost_rank = {c.bundle_id: i for i, c in enumerate(by_cost)}
    n = len(feasible)
    x = {c.bundle_id: model.NewBoolVar(f"pick_{i}") for i, c in enumerate(feasible)}
    model.AddExactlyOne(x.values())
    model.Maximize(
        sum(
            (int(round(weighted[c.bundle_id] * SCORE_SCALE)) * (n + 1) - cost_rank[c.bundle_id])
            * x[c.bundle_id]
            for c in feasible
        )
    )
    solver = cp_model.CpSolver()
    solver.parameters.num_workers = 1  # single-threaded => reproducible
    solver.parameters.random_seed = 7
    solver.parameters.max_time_in_seconds = 5.0
    status = solver.Solve(model)
    if status not in (cp_model.OPTIMAL, cp_model.FEASIBLE):
        raise RuntimeError(f"CP-SAT returned {solver.StatusName(status)}")
    for bundle_id, var in x.items():
        if solver.Value(var):
            return bundle_id
    raise RuntimeError("CP-SAT selected nothing")


def _solver_name() -> str:
    try:
        from ortools import __version__  # type: ignore[attr-defined]

        return f"{SOLVER_NAME}-{__version__}"
    except Exception:  # pragma: no cover - version attribute is best effort
        return SOLVER_NAME
