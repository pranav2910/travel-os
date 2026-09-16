package io.travelos.common.ids;

/**
 * Every id in the platform is {@code <prefix>_<ULID>}. The prefix makes ids self-describing in
 * logs, events and support tickets: nobody has to guess whether {@code 01J...} is a trip or an
 * order.
 */
public enum IdPrefix {
  TRIP("trip"),
  ORDER("ord"),
  ORDER_ITEM("itm"),
  OFFER("off"),
  BUNDLE("bdl"),
  SEARCH_SESSION("srch"),
  POLICY_DECISION("pd"),
  OPTIMIZATION_RUN("opt"),
  APPROVAL("apr"),
  DECISION("dec"),
  EVENT("evt"),
  COMMAND("cmd"),
  WORKFLOW("wf"),
  MODEL_CALL("llm"),
  DISRUPTION("dsr"),
  ORDER_CHANGE("chg"),
  RECOVERY_DECISION("rcd"),
  RECOVERY_OUTCOME("rco"),
  /** Slice 3: an itinerary component (leg, stay, transfer) with a stable id across re-planning. */
  COMPONENT("cmp"),
  /** Slice 3: money at risk after a failed compensation, until a person resolves it. */
  EXPOSURE("exp"),
  /** Slice 4: a detected travel demand candidate. */
  DEMAND("dmd"),
  /** Slice 4: an enterprise connector (calendar, CRM, HRIS, expense) of one tenant. */
  CONNECTOR("cnx"),
  /** Slice 4: one synchronization run of a connector. */
  SYNC_RUN("syn");

  private final String prefix;

  IdPrefix(String prefix) {
    this.prefix = prefix;
  }

  public String prefix() {
    return prefix;
  }
}
