(ns spicecrop.governor
  "Spice/Aromatic/Drug-and-Pharmaceutical Crop Operations Governor -- the
  independent compliance layer that earns the SpiceCropAdvisor the right
  to commit. The LLM has no notion of:
    - Whether a farm-lot has been independently verified/registered in
      the store at all, for ANY of this actor's four proposal ops
    - Whether the farm-lot's controlled-substance cultivation license is
      current (only meaningful for controlled crop categories)
    - Whether the farm-lot's chain-of-custody/quota-tracking record was
      reconciled recently enough (only meaningful for controlled crop
      categories)
    - Whether the reported harvest quantity stays within the farm-lot's
      licensed quota ceiling (only meaningful for controlled crop
      categories)
    - Whether a proposal is covertly requesting to finalize a
      controlled-substance cultivation-license approval/renewal or a
      diversion-control-compliance clearance
    - Whether a previously-raised compliance concern has been resolved
    - Whether a farm-lot has already been logged (double-commit)

  This MUST be a separate system able to *reject* a proposal and fall
  back to HOLD.

  CRITICAL SCOPE BOUNDARY: this actor coordinates FARM OPERATIONS
  LOGISTICS ONLY (planting/harvest scheduling, yield logging, input
  procurement) for spice, aromatic, drug and pharmaceutical crops (ISIC
  Rev.5 0128). It NEVER grants, renews, or finalizes a controlled-
  substance cultivation-license approval/renewal, and NEVER grants or
  finalizes a diversion-control-compliance clearance -- both are HARD,
  PERMANENT governor blocks, never overridable by human approval (see
  `:cultivation-license-or-diversion-clearance-blocked` below). The
  Governor operates on farm-lot metadata: field identity, crop-category
  parameters, and compliance/safety flags -- never on the license or
  clearance decision itself.

  CRITICAL: `:flag-compliance-concern` ALWAYS escalates to human sign-off
  at every phase, regardless of advisor confidence -- a suspected-
  diversion/license-compliance/pest-outbreak concern is never auto-
  resolved by advisor confidence alone. It is also, deliberately, never a
  member of any phase's `phase-auto-ops` set (see below).

  Hard violations (always HOLD, no override):
    1. Operation outside the closed allowlist (`:op-not-allowed`) --
       includes any proposal that would amount to finalizing a
       cultivation-license or diversion-control-clearance decision under
       a disguised op name
    2. Proposal asserting an `:effect` other than `:propose`
       (`:effect-not-propose`)
    3. Farm-lot not independently verified/registered in the store --
       applies to ALL FOUR allowed ops (`:farm-lot-not-registered`)
    4. No jurisdiction citation (`:no-spec-basis`)
    5. Evidence checklist incomplete (`:evidence-incomplete`)
    6. Cultivation license expired (`:cultivation-license-expired` --
       only when the crop category is a controlled substance crop)
    7. Quota-tracking record lapsed (`:quota-tracking-lapsed` -- only
       when the crop category is a controlled substance crop)
    8. Harvest quota exceeded (`:harvest-quota-exceeded` -- only when the
       crop category is a controlled substance crop)
    9. Proposal covertly requests to finalize a controlled-substance
       cultivation-license approval/renewal or a diversion-control-
       compliance clearance
       (`:cultivation-license-or-diversion-clearance-blocked` -- a HARD,
       PERMANENT block, never overridable by human approval, evaluated
       against every op as defense-in-depth even though such a decision
       is already outside the closed allowlist)
   10. Unresolved compliance concern (`:compliance-flag-unresolved`)
   11. Farm-lot already logged (`:already-logged`, double-commit guard)

  Soft gates (always escalate for human):
    - Low confidence
    - `:log-harvest-record` -- the one real actuation event this actor
      performs (logging completed planting/yield/harvest data into
      records)
    - `:flag-compliance-concern` -- never auto-resolved by confidence
      alone
    - `:coordinate-supply-order` above the cost threshold
      (`supply-order-cost-threshold-usd`)

  This design mirrors `perennial.governor` (ISIC 0129, growing of other
  perennial crops) in overall shape but specializes on spice/aromatic/
  drug-and-pharmaceutical-crop compliance concerns -- cultivation-license
  currency, quota-tracking reconciliation freshness, and licensed-quota
  ceilings -- for the operator's OWN spice/aromatic/pharmaceutical-
  precursor crop, never a client farm's, and never the license/clearance
  decision itself."
  (:require [spicecrop.facts :as facts]
            [spicecrop.registry :as registry]
            [spicecrop.store :as store]))

(def confidence-floor 0.6)

(def supply-order-cost-threshold-usd
  "Supply orders (seed/fertilizer/input procurement) at or below this
  cost may auto-commit when the Governor is otherwise clean; orders
  above this threshold always require human sign-off, regardless of
  advisor confidence."
  5000)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Logging a completed harvest record (`:log-harvest-record`) is the one
  real-world actuation event this actor performs -- it commits
  planting/yield/harvest data (and, transitively, the compliance facts
  that accompanied it) into the permanent record."
  #{:log-harvest-record})

(def always-escalate-ops
  "Operations that always require human sign-off, even when the
  Governor's hard checks are clean and confidence is high: the high-
  stakes actuation event (`high-stakes`) plus `:flag-compliance-concern`
  -- a suspected-diversion/license-compliance/pest-outbreak concern is
  never auto-resolved by advisor confidence alone, it always needs a
  human look."
  (conj high-stakes :flag-compliance-concern))

(def allowed-ops
  "Closed allowlist of proposal operations this actor may ever make. Any
  proposal for an operation outside this set -- most importantly
  finalizing a controlled-substance cultivation-license approval/renewal
  or a diversion-control-compliance clearance -- is a hard, permanent
  block: this actor coordinates spice/aromatic/drug-and-pharmaceutical
  crop FARM OPERATIONS LOGISTICS, it does not grant or renew any
  controlled-substance cultivation license or diversion-control-
  compliance clearance."
  #{:log-harvest-record :schedule-farm-operation :flag-compliance-concern :coordinate-supply-order})

(def phase-auto-ops
  "Per-phase map of ops eligible for auto-commit (Governor clean AND not
  a mandatory-escalation op). `:flag-compliance-concern` is deliberately
  ABSENT from every phase's set -- it always escalates via
  `always-escalate-ops` and must never be auto-commit-eligible at any
  phase. `:log-harvest-record` is likewise absent from every phase's set
  -- it is a `high-stakes` actuation event that always escalates. No op
  that would finalize a cultivation-license or diversion-control-
  clearance decision appears here at all, because no such op is ever a
  member of `allowed-ops` in the first place."
  {:intake #{}
   :survey #{}
   :advise #{:schedule-farm-operation}
   :treat #{:schedule-farm-operation}
   :record #{}
   :audit #{:coordinate-supply-order}})

(defn auto-eligible-at-phase?
  "True only if `op` is in `phase-auto-ops`' set for `phase` AND `op` is
  not a member of `always-escalate-ops` -- defense-in-depth so a future
  edit to `phase-auto-ops` alone can never silently make
  `:flag-compliance-concern` or `:log-harvest-record` auto-commit-
  eligible."
  [phase op]
  (boolean
   (and (contains? (get phase-auto-ops phase #{}) op)
        (not (contains? always-escalate-ops op)))))

;; ────────────────────────── Checks ──────────────────────────

(defn- op-not-allowed-violations
  "HARD, permanent block: any proposal outside the closed operation
  allowlist (e.g. finalizing a cultivation-license or diversion-control-
  clearance decision under a disguised op name) is refused
  unconditionally -- this actor has no authority to make such a proposal
  at all, let alone commit it."
  [{:keys [op]} _proposal]
  (when-not (contains? allowed-ops op)
    [{:rule :op-not-allowed
      :detail (str op " はこのactorの許可された提案種別 (log-harvest-record/"
                  "schedule-farm-operation/flag-compliance-concern/coordinate-supply-order) "
                  "に含まれない -- 栽培許可・流用防止クリアランスの確定はこのactorに無い")}]))

(defn- effect-not-propose-violations
  "HARD invariant: this actor's proposals are always `:effect :propose` --
  it never claims direct write/actuation authority for itself. A proposal
  asserting any other effect is refused unconditionally."
  [_request proposal]
  (when-let [effect (:effect proposal)]
    (when (not= effect :propose)
      [{:rule :effect-not-propose
        :detail (str "この actor の提案は :propose 以外の :effect を持てない (got " effect ")")}])))

(defn- farm-lot-not-registered-violations
  "HARD invariant: a farm/grower-license record must be independently
  verified/registered in the store BEFORE any of this actor's four
  proposal ops can be made against it -- coordinating work for a lot this
  actor never checked in is out of scope. Evaluated across ALL FOUR
  allowed ops, not just one."
  [{:keys [op subject]} st]
  (when (contains? allowed-ops op)
    (when-not (store/farm-lot-registered? st subject)
      [{:rule :farm-lot-not-registered
        :detail (str subject " は独立に検証・登録されたfarm-lot記録が無い -- いかなる提案も進められない")}])))

(defn- spec-basis-violations
  "A proposal with no jurisdiction citation is a HARD violation -- never
  invent a jurisdiction's spice/aromatic/drug-and-pharmaceutical crop
  compliance requirements."
  [{:keys [op]} proposal]
  (when (contains?
         #{:log-harvest-record :coordinate-supply-order :flag-compliance-concern}
         op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :jurisdiction) (nil? (:jurisdiction value))))
        [{:rule :no-spec-basis
          :detail "公式仕様の引用が無い提案は法域要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:log-harvest-record`, verify the farm-lot's evidence checklist is
  complete per jurisdiction requirements."
  [{:keys [op subject]} st]
  (when (= op :log-harvest-record)
    (let [l (store/farm-lot st subject)]
      (when-not (and l
                     (facts/required-evidence-satisfied?
                      (:jurisdiction l)
                      (:evidence-checklist l)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(farm-registration-record/field-boundary-map/harvest-record/cultivation-license-on-file等)が充足していない状態での提案"}]))))

(defn- cultivation-license-expired-violations
  "For `:log-harvest-record`, INDEPENDENTLY verify the farm-lot's
  controlled-substance cultivation license has not expired via
  `registry/cultivation-license-expired?`. Only evaluated when the crop
  category actually requires a license (controlled crop categories) --
  non-controlled spice/aromatic crop categories have nothing to check
  here, never a fabricated requirement. This is a STATUS check only; it
  never renews the license itself."
  [{:keys [op subject]} st now-ms]
  (when (= op :log-harvest-record)
    (let [l (store/farm-lot st subject)
          cc (when l (facts/crop-category-by-id (:crop-category l)))]
      (when (and l cc (:cultivation-license-required? cc) (:cultivation-license-expiry-date l)
                 (registry/cultivation-license-expired? (:cultivation-license-expiry-date l) now-ms))
        [{:rule :cultivation-license-expired
          :detail (str subject " の栽培許可(cultivation license)が失効している -- 記録提案は進められない")}]))))

(defn- quota-tracking-lapsed-violations
  "For `:log-harvest-record`, INDEPENDENTLY verify the farm-lot's chain-
  of-custody/quota-tracking record was reconciled recently enough via
  `registry/quota-tracking-lapsed?`. Only evaluated when the crop
  category actually requires quota tracking (controlled crop
  categories)."
  [{:keys [op subject]} st now-ms]
  (when (= op :log-harvest-record)
    (let [l (store/farm-lot st subject)
          cc (when l (facts/crop-category-by-id (:crop-category l)))]
      (when (and l cc (:quota-tracking-required? cc) (:quota-last-reconciliation-date l)
                 (registry/quota-tracking-lapsed? (:quota-last-reconciliation-date l) now-ms))
        [{:rule :quota-tracking-lapsed
          :detail (str subject " の数量トラッキング記録の照合が期限切れ -- 記録提案は進められない")}]))))

(defn- harvest-quota-exceeded-violations
  "For `:log-harvest-record`, INDEPENDENTLY verify that the reported
  harvest quantity does not exceed the farm-lot's licensed quota ceiling
  via `registry/harvest-quota-exceeded?`. Only evaluated when the crop
  category actually has a licensed-quota ceiling (controlled crop
  categories) -- non-controlled spice/aromatic crop categories have
  nothing to check here, never a fabricated target."
  [{:keys [op subject]} st]
  (when (= op :log-harvest-record)
    (let [l (store/farm-lot st subject)
          cc (when l (facts/crop-category-by-id (:crop-category l)))]
      (when (and l cc (:quota-tracking-required? cc) (:reported-harvest-kg l) (:licensed-quota-kg l)
                 (registry/harvest-quota-exceeded?
                  (:reported-harvest-kg l)
                  (:licensed-quota-kg l)))
        [{:rule :harvest-quota-exceeded
          :detail (str subject " の報告収穫量(" (:reported-harvest-kg l)
                      "kg)が許可数量上限を超過 -- 記録提案は進められない")}]))))

(defn- cultivation-license-or-diversion-clearance-blocked-violations
  "HARD, PERMANENT block, defense-in-depth: any proposal whose `:value`
  covertly requests to finalize a controlled-substance cultivation-
  license approval/renewal (`:finalize-cultivation-license-approval?`
  true or `:finalize-cultivation-license-renewal?` true) or a diversion-
  control-compliance clearance
  (`:finalize-diversion-control-clearance?` true) is refused
  unconditionally, regardless of which op it is nominally filed under and
  regardless of advisor confidence. Never overridable by human approval
  -- this is a scope boundary, not a risk judgment. This check ONLY
  inspects explicit structural boolean `:value` flags -- it NEVER scans
  free-text `:rationale`/disclaimer strings for bare nouns like
  \"license\" or \"clearance\", which would falsely trip on this actor's
  own legitimate default proposals (whose rationale text routinely and
  correctly says things like \"this does not finalize any cultivation
  license or diversion-control clearance\") -- see
  `spicecrop.advisor/default-mock-proposals` and the dedicated regression
  test in `spicecrop.governor-test`."
  [_request proposal]
  (let [value (:value proposal)]
    (when (or (true? (:finalize-cultivation-license-approval? value))
              (true? (:finalize-cultivation-license-renewal? value))
              (true? (:finalize-diversion-control-clearance? value)))
      [{:rule :cultivation-license-or-diversion-clearance-blocked
        :detail "栽培許可の承認・更新または流用防止コンプライアンスクリアランスの確定はこのactorの範囲外 -- 恒久的にブロックされる"}])))

(defn- compliance-flag-unresolved-violations
  "An unresolved compliance flag is a HARD, un-overridable hold.
  Suspected-diversion/license-compliance/pest-outbreak concerns raised
  during cultivation must be resolved before the farm-lot can be logged.
  Evaluated UNCONDITIONALLY at `:log-harvest-record`."
  [{:keys [op subject]} st]
  (when (= op :log-harvest-record)
    (let [l (store/farm-lot st subject)]
      (when (and (true? (:compliance-concern-raised? l))
                 (not (true? (:compliance-concern-resolved? l))))
        [{:rule :compliance-flag-unresolved
          :detail (str subject " は未解決のコンプライアンス懸念フラグがある -- 記録提案は進められない")}]))))

(defn- already-logged-violations
  "For `:log-harvest-record`, refuse to log the SAME farm-lot twice, off
  a dedicated `:logged?` fact (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :log-harvest-record)
    (when (store/farm-lot-already-logged? st subject)
      [{:rule :already-logged
        :detail (str subject " は既に記録済み")}])))

(defn- now-epoch-ms
  "Current time in epoch milliseconds, portable across Clojure/
  ClojureScript. Isolated to this single call site so the rest of the
  namespace (and all of `spicecrop.registry`) stays free of host-clock
  calls."
  []
  #?(:clj (System/currentTimeMillis)
     :cljs (js/Date.now)))

(defn- high-cost-supply-order?
  "Soft-gate helper: a `:coordinate-supply-order` proposal escalates to a human
  unless its `:cost-usd` can be established to be BELOW `supply-order-cost-threshold-usd`.

  Note the direction. This gate used to read `:cost-usd` out of the
  advisor's OWN proposal and escalate only when that number exceeded
  the threshold, which made the gate's only input the very number it
  existed to doubt:

    - an advisor understating bought itself an auto-commit wherever
      `:coordinate-supply-order` was `:auto`-eligible -- no human saw it;
    - `(some-> amount (> threshold))` returned nil when the field was
      ABSENT, so omitting `:cost-usd` skipped the gate entirely.

  There is no filed catalog in this actor's store to recompute the
  figure from -- the advisor states it directly -- so a self-declared
  value cannot be verified. An unverifiable number is worthless as a
  DE-escalation signal: it may raise the alarm, it must never silence
  it. The gate now escalates whenever the value is absent, non-numeric,
  or above the threshold, and stands down only for one that is present,
  numeric and below it."
  [{:keys [op]} proposal]
  (when (= op :coordinate-supply-order)
    (let [v (get-in proposal [:value :cost-usd])]
      (or (not (number? v))
          (> v supply-order-cost-threshold-usd)))))

(defn check
  "Censors a SpiceCropAdvisor proposal against the Governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}.

  Stakes (high-stakes actuation vs. always-escalate vs. high-cost supply
  order) are read off the REQUEST's `:op` (and, for supply-order cost,
  the proposal's own declared value) -- not off the advisor's self-
  reported stake -- since the operation being proposed is what determines
  whether a human must sign off."
  [request _context proposal st]
  (let [now-ms (now-epoch-ms)
        hard (into []
                   (concat (op-not-allowed-violations request proposal)
                           (effect-not-propose-violations request proposal)
                           (farm-lot-not-registered-violations request st)
                           (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (cultivation-license-expired-violations request st now-ms)
                           (quota-tracking-lapsed-violations request st now-ms)
                           (harvest-quota-exceeded-violations request st)
                           (cultivation-license-or-diversion-clearance-blocked-violations request proposal)
                           (compliance-flag-unresolved-violations request st)
                           (already-logged-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        actuation? (boolean (high-stakes (:op request)))
        escalate-op? (or (boolean (always-escalate-ops (:op request)))
                          (boolean (high-cost-supply-order? request proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not escalate-op?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? escalate-op?))
     :high-stakes? actuation?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
