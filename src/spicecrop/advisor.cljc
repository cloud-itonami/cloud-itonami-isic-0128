(ns spicecrop.advisor
  "SpiceCropAdvisor -- the LLM/decision-maker that proposes spice/
  aromatic/drug-and-pharmaceutical-crop FARM OPERATIONS LOGISTICS
  operations (planting/harvest scheduling, yield logging, input
  procurement).

  The advisor operates purely at the proposal level; the Governor
  (`spicecrop.governor`) independently validates all proposals against
  registration/evidence/license/quota/compliance rules before any action
  is committed. CRITICAL: the advisor NEVER finalizes a controlled-
  substance cultivation-license approval/renewal or a diversion-control-
  compliance clearance -- those actions are not in the Governor's closed
  allowlist (`spicecrop.governor/allowed-ops`) at all, and any covert
  attempt (via `:finalize-cultivation-license-approval?` /
  `:finalize-cultivation-license-renewal?` /
  `:finalize-diversion-control-clearance?` boolean `:value` flags) is a
  hard, permanent Governor block regardless of which op it is nominally
  filed under.

  In production, this is driven by langgraph-clj StateGraph with LLM
  chat turns. For testing, this is a pure function layer plus a fixed
  set of DEFAULT MOCK PROPOSALS (`default-mock-proposals`) below,
  representing this actor's happy-path behavior for each of its four
  allowed ops.

  KNOWN BUG CLASS THIS FIXTURE GUARDS AGAINST: in this actor family, a
  Governor scope-exclusion term list has occasionally been phrased as a
  bare noun (e.g. \"license\", \"clearance\") rather than as the
  finalization/execution ACTION it is meant to block. A bare-noun list
  accidentally matches inside the advisor's OWN default rationale/
  disclaimer text -- which routinely and legitimately says things like
  \"this does not finalize any cultivation license or diversion-control
  clearance\" -- causing the actor to self-block on its own legitimate
  happy path. `spicecrop.governor`'s
  `:cultivation-license-or-diversion-clearance-blocked` rule is phrased
  as the finalization ACTION (explicit `:finalize-*?` boolean `:value`
  flags) and never scans `:rationale` text at all, so it is structurally
  immune to this bug class. `default-mock-proposals` below deliberately
  includes disclaimer text containing the words \"license\",
  \"clearance\", and \"diversion\" as ordinary, correct compliance
  language, and `spicecrop.governor-test` asserts none of these
  proposals ever trip the scope-exclusion rule -- keeping that invariant
  honest against regression."
  )

(def default-mock-proposals
  "Fixed happy-path proposal fixtures, one per allowed op
  (`spicecrop.governor/allowed-ops`), each carrying a realistic advisor
  `:rationale` string that -- ON PURPOSE -- contains the words
  \"license\", \"clearance\", and \"diversion\" as part of an ordinary
  compliance disclaimer. None of these may ever trip the Governor's
  `:cultivation-license-or-diversion-clearance-blocked` rule, because
  that rule only inspects explicit `:value` boolean flags, never
  `:rationale` text (see namespace docstring)."
  {:log-harvest-record
   {:op :log-harvest-record
    :effect :propose
    :cites [{:spec "Farm-Harvest-Log"}]
    :rationale (str "Logging this farm-lot's completed harvest weight "
                     "and batch data. This does not finalize any "
                     "cultivation license, diversion-control clearance, "
                     "or quota adjustment -- farm operations logistics "
                     "only.")
    :value {:jurisdiction :jp/maff}
    :confidence 0.9}

   :schedule-farm-operation
   {:op :schedule-farm-operation
    :effect :propose
    :cites [{:spec "Farm-Operation-Schedule"}]
    :rationale (str "Proposing next week's planting/labor schedule for "
                     "this farm-lot. Not a cultivation-license or "
                     "diversion-control-clearance decision.")
    :value {:jurisdiction :jp/maff}
    :confidence 0.9}

   :flag-compliance-concern
   {:op :flag-compliance-concern
    :effect :propose
    :cites [{:spec "Field-Report"}]
    :rationale (str "Surfacing a suspected yield/quota discrepancy for "
                     "human review. This flag does not itself grant, "
                     "deny, or clear any cultivation license or "
                     "diversion-control clearance -- always escalates "
                     "to a human.")
    :value {:jurisdiction :jp/maff}
    :confidence 0.9}

   :coordinate-supply-order
   {:op :coordinate-supply-order
    :effect :propose
    :cites [{:spec "Supplier-Catalog"}]
    :rationale (str "Proposing a seed/fertilizer/input procurement "
                     "order. Not a cultivation-license or diversion-"
                     "control-clearance action.")
    :value {:jurisdiction :jp/maff :cost-usd 500}
    :confidence 0.9}})

;; In production deployment, this module provides the StateGraph state
;; machine definition and LLM binding. For this blueprint,
;; `default-mock-proposals` above is the pure-function skeleton.
