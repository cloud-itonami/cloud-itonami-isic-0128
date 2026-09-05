(ns spicecrop.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo: it previously had NO
  demo page and no generator at all (`docs/` held only
  `business-model.md`).

  This namespace drives the REAL actor stack --
  `spicecrop.operation/run-operation` -> `spicecrop.governor/check` ->
  `spicecrop.store` -- and renders only what that stack actually
  returned. Every disposition, hold reason, violation detail string,
  confidence value and gate description on the page is read back out of
  a real verdict, a real audit fact, or a real `spicecrop.governor` /
  `spicecrop.phase` var. Nothing on the page is hand-typed HTML data.

  WHY THE SCENARIO IS AUTHORED HERE RATHER THAN COPIED FROM `sim`:
  this repo's own demo driver `spicecrop.sim` (`clojure -M:dev:run`) is
  a stub -- it prints
  \"simulation: not yet implemented / TODO: integrate langgraph-clj\"
  and drives nothing. There is likewise no `store/seed-db`: the store
  is plain data (`{:farm-lots {..} :facts [..]}`) with no fixture. So
  the farm-lots below are authored from the ONLY farm-lot vocabulary
  this repo actually defines:

    - crop-category ids + Japanese names ....... `spicecrop.facts/crop-categories`
    - jurisdiction ids + names + evidence ...... `spicecrop.facts/jurisdictions`
    - farm-lot shape, `field-42` / `field-77`,
      100kg reported / 150kg licensed quota,
      the `ten-days-ago` / `hundred-days-ago` /
      `ten-days-from-now` date idiom ........... `spicecrop.governor-test`
    - `lot-001`..`lot-008`, `lot-999`, the
      `op-1` operator id ...................... `spicecrop.operation-test`
    - every proposal body (`:cites`,
      `:rationale`, `:confidence`, `:cost-usd`) . `spicecrop.advisor/default-mock-proposals`

  No operator name, company, price or figure is introduced that is not
  already in one of those namespaces.

  THE MISSING COMMIT PATH (reported, not papered over): `run-operation`
  returns `{:ok? true :facts []}` on a clean verdict -- it never writes
  to the store, and nothing in this repo ever calls
  `store/log-harvest-record`, `store/mark-scheduled` or
  `store/append-fact`. The commit / human-sign-off glue therefore lives
  in `apply-step` below, and it calls those real store functions rather
  than keeping a shadow ledger. The audit trail rendered on the page IS
  `store/audit-trail`.

  Consequently there is also no approver field anywhere on a farm-lot
  record. The approver is carried ONLY on the audit fact appended via
  `store/append-fact`, and the page says so explicitly instead of
  implying the farm-lot record holds it.

  DETERMINISM: `governor/check` calls the host clock internally (to test
  cultivation-license expiry and quota-tracking freshness), so the seed
  dates are day-scale offsets from a single `now` captured per run --
  exactly the idiom `spicecrop.governor-test` uses. No date, timestamp,
  clock reading or derived-from-`now` number is ever rendered; only the
  qualitative status the registry predicates derive from them. Farm-lots
  are rendered in sorted-key order and phases/ops in the order the
  `spicecrop.phase` / `spicecrop.governor` vars declare, so no map
  iteration order reaches the page. Two consecutive runs are
  byte-identical.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [spicecrop.advisor :as advisor]
            [spicecrop.facts :as facts]
            [spicecrop.governor :as governor]
            [spicecrop.operation :as operation]
            [spicecrop.phase :as phase]
            [spicecrop.registry :as registry]
            [spicecrop.store :as store]))

;; ───────────────────────────── context ─────────────────────────────

(def ^:private operator
  "The operation actor's context. `op-1` is the only operator identifier
  this repo defines -- it is the `:actor-id` used throughout
  `spicecrop.operation-test`. `:hold-fact-fn` is the real
  `governor/hold-fact`, as the tests wire it."
  {:actor-id "op-1"
   :hold-fact-fn governor/hold-fact})

(def ^:private day-ms (* 24 60 60 1000))

;; ───────────────────────────── the seed ────────────────────────────

(defn- evidence-for
  "The jurisdiction's OWN `:required-evidence` list, read from
  `spicecrop.facts`. A `complete` checklist is literally that list; an
  incomplete one is that list minus `drop-n` trailing items. Nothing is
  hand-typed."
  ([jurisdiction-id] (evidence-for jurisdiction-id 0))
  ([jurisdiction-id drop-n]
   (let [required (vec (:required-evidence (facts/jurisdiction-by-id jurisdiction-id)))]
     (subvec required 0 (- (count required) drop-n)))))

(defn- seed-db
  "Farm-lots for the scenario, all built from this repo's own crop-
  category / jurisdiction / test-fixture vocabulary. `now` is threaded
  in so every date is a day-scale offset (never rendered)."
  [now]
  {:farm-lots
   (sorted-map
    ;; clean controlled-substance lot -- runs the full clean lifecycle
    "lot-001" {:crop-category :pharma/licensed-opium-poppy
               :jurisdiction :jp/maff
               :field-id "field-42"
               :cultivation-license-expiry-date (+ now (* 10 day-ms))
               :quota-last-reconciliation-date (- now (* 10 day-ms))
               :reported-harvest-kg 100
               :licensed-quota-kg 150
               :evidence-checklist (evidence-for :jp/maff)}

    ;; same shape, but the cultivation license has lapsed
    "lot-002" {:crop-category :pharma/licensed-opium-poppy
               :jurisdiction :jp/maff
               :field-id "field-43"
               :cultivation-license-expiry-date (- now (* 100 day-ms))
               :quota-last-reconciliation-date (- now (* 10 day-ms))
               :reported-harvest-kg 100
               :licensed-quota-kg 150
               :evidence-checklist (evidence-for :jp/maff)}

    ;; quota-tracking reconciliation stale AND harvest over the ceiling
    "lot-003" {:crop-category :pharma/licensed-coca-leaf
               :jurisdiction :eu/reg1307
               :field-id "field-44"
               :cultivation-license-expiry-date (+ now (* 10 day-ms))
               :quota-last-reconciliation-date (- now (* 100 day-ms))
               :reported-harvest-kg 180
               :licensed-quota-kg 150
               :evidence-checklist (evidence-for :eu/reg1307)}

    ;; non-controlled crop category with an OPEN compliance concern
    "lot-004" {:crop-category :spice/black-pepper
               :jurisdiction :jp/maff
               :field-id "field-77"
               :compliance-concern-raised? true
               :compliance-concern-resolved? false
               :evidence-checklist (evidence-for :jp/maff)}

    ;; non-controlled crop category, evidence checklist short two items
    "lot-005" {:crop-category :spice/vanilla
               :jurisdiction :us/dea-usda
               :field-id "field-45"
               :evidence-checklist (evidence-for :us/dea-usda 2)}

    ;; clean controlled lot used to probe the two permanent scope blocks
    "lot-006" {:crop-category :pharma/licensed-medicinal-cannabis
               :jurisdiction :jp/maff
               :field-id "field-46"
               :cultivation-license-expiry-date (+ now (* 10 day-ms))
               :quota-last-reconciliation-date (- now (* 10 day-ms))
               :reported-harvest-kg 100
               :licensed-quota-kg 150
               :evidence-checklist (evidence-for :jp/maff)}

    ;; non-controlled lot used for the proposal-shape / soft-gate probes
    "lot-007" {:crop-category :aromatic/peppermint
               :jurisdiction :jp/maff
               :field-id "field-47"
               :evidence-checklist (evidence-for :jp/maff)})
   :facts []})

;; ─────────────────────────── the scenario ──────────────────────────

(defn- proposal-for
  "Start from the advisor's OWN default proposal for `op` (real
  `:cites` / `:rationale` / `:confidence` / `:cost-usd`), point its
  `:value` `:jurisdiction` at the farm-lot's actual jurisdiction, then
  apply the step's explicit overrides. `base-op` lets a step file a
  disguised request op against a legitimate advisor proposal body."
  [db {:keys [op subject base-op overrides]}]
  (let [base (get advisor/default-mock-proposals (or base-op op))
        jurisdiction (:jurisdiction (store/farm-lot db subject))
        base (cond-> base
               jurisdiction (assoc-in [:value :jurisdiction] jurisdiction)
               true (assoc :op op))]
    (reduce (fn [p [path v]] (assoc-in p path v)) base overrides)))

(def ^:private steps
  "The scenario, in order. Every step is really executed; nothing here
  is a rendering hint. `:approve?` means a human signs the escalation
  off -- `apply-step` REFUSES to honour it on a hard hold, which is what
  makes \"a HARD hold can never be released by human approval\" a
  build-time invariant rather than a comment.

  `:commit` names the real `spicecrop.store` mutation a commit applies.
  `:coordinate-supply-order` and `:flag-compliance-concern` have no
  store mutation in this repo at all -- only the audit fact is written,
  and the page says so."
  [;; ---- lot-001: one full clean lifecycle -------------------------
   {:phase :advise :op :schedule-farm-operation :subject "lot-001" :commit :scheduled
    :note "clean and auto-eligible at :advise"}
   {:phase :audit :op :coordinate-supply-order :subject "lot-001"
    :note "advisor's own $500 order, under the threshold, auto-eligible at :audit"}
   {:phase :record :op :log-harvest-record :subject "lot-001" :approve? true :commit :logged
    :note "high-stakes actuation -- always escalates, never auto at any phase"}
   {:phase :record :op :log-harvest-record :subject "lot-001"
    :note "double-commit guard, off the store's own :logged? flag"}

   ;; ---- independent compliance verification -----------------------
   {:phase :record :op :log-harvest-record :subject "lot-002"
    :note "cultivation license lapsed"}
   {:phase :record :op :log-harvest-record :subject "lot-003"
    :note "quota reconciliation stale AND harvest above the licensed ceiling"}
   {:phase :survey :op :flag-compliance-concern :subject "lot-004" :approve? true
    :note "never auto-resolved by confidence -- escalates at every phase"}
   {:phase :record :op :log-harvest-record :subject "lot-004"
    :note "the concern raised above is still unresolved"}
   {:phase :record :op :log-harvest-record :subject "lot-005"
    :note "jurisdiction's evidence checklist short two items"}

   ;; ---- permanent scope boundary ----------------------------------
   {:phase :advise :op :schedule-farm-operation :subject "lot-006"
    :overrides [[[:value :finalize-cultivation-license-renewal?] true]]
    :note "covert licence-renewal finalization, filed under a legitimate op"}
   {:phase :advise :op :approve-cultivation-license :subject "lot-006"
    :base-op :schedule-farm-operation
    :note "disguised op name, outside the closed allowlist"}

   ;; ---- proposal shape + soft gates -------------------------------
   {:phase :treat :op :schedule-farm-operation :subject "lot-007"
    :overrides [[[:effect] :commit]]
    :note "proposal claims direct write authority"}
   {:phase :audit :op :coordinate-supply-order :subject "lot-007"
    :overrides [[[:cites] []]]
    :note "no jurisdiction citation"}
   {:phase :audit :op :coordinate-supply-order :subject "lot-007" :approve? true
    :overrides [[[:value :cost-usd] 12000]]
    :note "above the supply-order cost threshold"}
   {:phase :advise :op :schedule-farm-operation :subject "lot-007" :approve? true
    :overrides [[[:confidence] 0.4]]
    :commit :scheduled
    :note "advisor confidence below the floor"}

   ;; ---- registration invariant ------------------------------------
   {:phase :advise :op :schedule-farm-operation :subject "lot-999"
    :note "never registered in the store"}])

(defn- commit!
  "Apply the real store mutation named by `:commit`, if any."
  [db {:keys [commit subject]}]
  (case commit
    :scheduled (store/mark-scheduled db subject)
    ;; re-register the lot exactly as it stands and flip :logged?; nothing
    ;; about the record is invented at commit time.
    :logged (store/log-harvest-record db subject (store/farm-lot db subject))
    db))

(defn- apply-step
  "Drive ONE request through the real operation actor and fold the result
  into the store. Returns `[db run]`."
  [db {:keys [phase op subject approve? note] :as step}]
  (let [request {:op op :subject subject}
        proposal (proposal-for db step)
        result (operation/run-operation request operator proposal db governor/check)
        verdict (:verdict result)
        auto? (governor/auto-eligible-at-phase? phase op)
        hard? (boolean (:hard? verdict))
        outcome (cond
                  (and (:ok? result) auto?) :auto-commit
                  (:ok? result) :phase-gated
                  hard? :hard-hold
                  :else :escalated)]
    (when (and approve? hard?)
      (throw (ex-info (str "refusing to render a human approval over a HARD governor hold: "
                           subject " " op)
                      {:subject subject :op op
                       :basis (mapv :rule (:violations verdict))})))
    (let [;; every fact the operation actor produced goes to the real ledger
          db (reduce store/append-fact db (:facts result))
          approved? (boolean (and approve? (not hard?) (not (:ok? result))))
          committed? (or (= outcome :auto-commit) approved?)
          db (cond-> db
               approved? (store/append-fact
                          {:t :approval-granted
                           :op op
                           :actor (:actor-id operator)
                           :subject subject
                           :disposition :approved
                           :approved-by (:actor-id operator)
                           :basis []})
               (= outcome :auto-commit) (store/append-fact
                                         {:t :auto-commit
                                          :op op
                                          :actor (:actor-id operator)
                                          :subject subject
                                          :disposition :auto-commit
                                          :basis []})
               committed? (commit! step))]
      [db {:phase phase
           :op op
           :subject subject
           :note note
           :outcome (if approved? :approved outcome)
           :confidence (:confidence proposal)
           :verdict verdict
           :violations (:violations verdict)
           :committed? committed?
           :auto-eligible? auto?}])))

(defn run-demo!
  "Runs the scenario above against a freshly seeded store.

  `lot-001` clears a full clean lifecycle: a farm-operation schedule
  auto-commits at `:advise`, the advisor's own under-threshold supply
  order auto-commits at `:audit`, and the harvest record -- the one real
  actuation event this actor performs -- escalates (it is permanently
  high-stakes, never auto at any phase), is signed off, and commits;
  a second harvest-log attempt against the same lot is then refused by
  the store's own double-commit guard.

  Ten further requests reach every one of the Governor's eleven hard
  rules, including the two permanent scope blocks that no human
  approval can ever release, plus all four soft gates.

  Returns `{:db .. :runs [..] :now ..}` -- every field the renderer
  reads is real governor/store output."
  []
  (let [now (System/currentTimeMillis)]
    (loop [db (seed-db now), [s & more] steps, runs []]
      (if (nil? s)
        {:db db :runs runs :now now}
        (let [[db' run] (apply-step db s)]
          (recur db' more (conj runs run)))))))

;; ───────────────────────────── rendering ───────────────────────────

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw [v] (esc (if (keyword? v) (name v) v)))

(defn- kw-list [xs]
  (if (seq xs) (str/join ", " (map kw xs)) ""))

(defn- td [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- ok [s] (str "<span class=\"ok\">" s "</span>"))
(defn- warn [s] (str "<span class=\"warn\">" s "</span>"))
(defn- crit [s] (str "<span class=\"critical\">" s "</span>"))
(defn- muted [s] (str "<span class=\"muted\">" s "</span>"))

;; --- farm lots -------------------------------------------------------

(defn- licence-cell [lot cc now]
  (cond
    (not (:cultivation-license-required? cc)) (muted "not required")
    (nil? (:cultivation-license-expiry-date lot)) (muted "no record")
    (registry/cultivation-license-expired? (:cultivation-license-expiry-date lot) now)
    (crit "expired")
    :else (ok "current")))

(defn- quota-cell [lot cc now]
  (cond
    (not (:quota-tracking-required? cc)) (muted "not required")
    (nil? (:quota-last-reconciliation-date lot)) (muted "no record")
    (registry/quota-tracking-lapsed? (:quota-last-reconciliation-date lot) now)
    (crit "lapsed")
    :else (ok "reconciled")))

(defn- harvest-cell [lot cc]
  (let [{:keys [reported-harvest-kg licensed-quota-kg]} lot]
    (if-not (and (:quota-tracking-required? cc) reported-harvest-kg licensed-quota-kg)
      (muted "no ceiling")
      (let [txt (str "<span class=\"num\">" reported-harvest-kg " / " licensed-quota-kg "</span> kg")]
        (if (registry/harvest-quota-exceeded? reported-harvest-kg licensed-quota-kg)
          (crit (str txt " over"))
          (ok txt))))))

(defn- evidence-cell [lot]
  (let [required (:required-evidence (facts/jurisdiction-by-id (:jurisdiction lot)))
        have (count (:evidence-checklist lot))
        n (count required)]
    (if (facts/required-evidence-satisfied? (:jurisdiction lot) (:evidence-checklist lot))
      (ok (str "complete " have "/" n))
      (crit (str "incomplete " have "/" n)))))

(defn- lifecycle-cell [lot]
  (cond
    (:logged? lot) (ok "harvest logged")
    (:scheduled? lot) (warn "scheduled, not yet logged")
    (:compliance-concern-raised? lot) (crit "compliance concern open")
    :else (muted "registered only")))

(defn- lot-row [now [id lot]]
  (let [cc (facts/crop-category-by-id (:crop-category lot))]
    (td (str "<code>" (esc id) "</code>")
        (esc (:field-id lot))
        (esc (:name cc))
        (if (:controlled-substance-crop? cc) (warn "controlled") (muted "ordinary"))
        (esc (:name (facts/jurisdiction-by-id (:jurisdiction lot))))
        (licence-cell lot cc now)
        (quota-cell lot cc now)
        (harvest-cell lot cc)
        (evidence-cell lot)
        (lifecycle-cell lot))))

;; --- governor gate ---------------------------------------------------

(defn- gate-row [op]
  (let [auto-phases (filterv #(governor/auto-eligible-at-phase? % op) phase/all-phases)
        always? (contains? governor/always-escalate-ops op)
        stakes? (contains? governor/high-stakes op)]
    (td (str "<code>:" (kw op) "</code>")
        (cond
          stakes? (crit "ALWAYS human sign-off &middot; high-stakes actuation")
          always? (crit "ALWAYS human sign-off &middot; never auto-resolved by confidence")
          (seq auto-phases) (ok (str "auto-commit when clean at " (kw-list auto-phases)))
          :else (warn "human sign-off at every phase"))
        (if (seq auto-phases)
          (esc (kw-list auto-phases))
          (muted "none")))))

;; --- timeline --------------------------------------------------------

(defn- outcome-cell [{:keys [outcome violations]}]
  (case outcome
    :auto-commit (ok "auto-commit")
    :approved (warn "escalated &rarr; signed off &rarr; committed")
    :phase-gated (warn "clean but phase-gated")
    :escalated (warn "escalated")
    :hard-hold (crit (str "HARD hold &middot; " (esc (kw-list (map :rule violations)))))
    (muted (kw outcome))))

(defn- timeline-row [i {:keys [phase op subject confidence note] :as run}]
  (td (str "<span class=\"num\">" (inc i) "</span>")
      (str "<code>:" (kw phase) "</code>")
      (str "<code>:" (kw op) "</code>")
      (str "<code>" (esc subject) "</code>")
      (str "<span class=\"num\">" (esc confidence) "</span>")
      (outcome-cell run)
      (muted (esc note))))

;; --- hard rules observed ---------------------------------------------

(defn- hard-rule-rows
  "One row per DISTINCT hard rule the Governor actually raised in this
  run, with the Governor's own `:detail` string verbatim."
  [runs]
  (let [vs (mapcat :violations runs)
        by-rule (reduce (fn [m {:keys [rule detail]}]
                          (-> m
                              (update-in [rule :n] (fnil inc 0))
                              (assoc-in [rule :detail] (get-in m [rule :detail] detail))))
                        (sorted-map) vs)]
    (for [[rule {:keys [n detail]}] by-rule]
      (td (str "<code>:" (kw rule) "</code>")
          (str "<span class=\"num\">" n "</span>")
          (esc detail)))))

;; --- ledger ----------------------------------------------------------

(defn- fact-row [{:keys [t op subject disposition basis approved-by]}]
  (td (str "<code>:" (kw t) "</code>")
      (str "<code>:" (kw op) "</code>")
      (str "<code>" (esc subject) "</code>")
      (case disposition
        :hold (crit "hold")
        :approved (warn "approved")
        :auto-commit (ok "auto-commit")
        (muted (kw disposition)))
      (if (seq basis) (crit (kw-list basis)) (muted "&mdash;"))
      (if approved-by (esc approved-by) (muted "&mdash;"))))

(defn render
  "Renders the whole document from a completed `run-demo!` result."
  [{:keys [db runs now]}]
  (let [ledger (vec (store/audit-trail db))
        lots (seq (:farm-lots db))
        hard-runs (filterv #(= :hard-hold (:outcome %)) runs)
        holds (filterv #(= :governor-hold (:t %)) ledger)]
    (str
     "<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
     "<title>cloud-itonami-isic-0128 &middot; spice, aromatic, drug and pharmaceutical crop farm operations</title>"
     "<style>" (jp-go-dds.skin/dds+skin) "</style></head><body>\n"

     "<header class=\"bar\">\n"
     "  <h1>Growing of spice, aromatic, drug and pharmaceutical crops (ISIC Rev.5 0128) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · harvest logging &amp; compliance flags always human-approved</span>\n"
     "</header>\n"
     "<main>\n"

     "  <section class=\"banner\">\n"
     "    <p>Build-time snapshot, generated by <code>clojure -M:dev:render-html</code> "
     "(<code>spicecrop.render-html</code>) by really running "
     "<code>spicecrop.operation/run-operation</code> &rarr; <code>spicecrop.governor/check</code> &rarr; "
     "<code>spicecrop.store</code>. Every disposition, hold reason and detail string below was read back "
     "out of a real verdict or a real audit fact — none of it is hand-written page data.</p>\n"
     "    <p class=\"muted\">This actor coordinates farm operations logistics only. It never grants, renews or "
     "finalizes a controlled-substance cultivation licence or a diversion-control clearance: those are "
     "permanent Governor blocks that no human approval can release.</p>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Farm lots</h2>\n"
     "    <p class=\"muted\">Store state after the run. Licence currency, quota-tracking freshness and the "
     "harvest-vs-ceiling comparison are recomputed here by <code>spicecrop.registry</code>, never taken from "
     "a proposal. Ordinary spice/aromatic crop categories have no licence or quota requirement at all, so "
     "those checks are skipped rather than fabricated.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Farm-lot</th><th>Field</th><th>Crop category</th><th>Class</th><th>Jurisdiction</th>"
     "<th>Cultivation licence</th><th>Quota tracking</th><th>Harvest / ceiling</th><th>Evidence</th><th>Lifecycle</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial lot-row now) lots)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "    <p class=\"muted\">"
     (esc (str "lot-999 never appears above: it is not registered in the store, which is exactly why every "
               "proposal against it is refused."))
     "</p>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Action gate (Spice Crop Governor)</h2>\n"
     "    <p class=\"muted\">Derived from <code>governor/allowed-ops</code>, "
     "<code>governor/always-escalate-ops</code> and <code>governor/auto-eligible-at-phase?</code> — this table "
     "is computed, not described. The allowlist is closed: any operation outside it is a hard block. "
     "Confidence floor <span class=\"num\">" (esc governor/confidence-floor) "</span>; supply orders at or below "
     "<span class=\"num\">USD " (esc governor/supply-order-cost-threshold-usd) "</span> may auto-commit, and an "
     "absent or non-numeric cost escalates rather than silencing the gate.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th><th>Auto-eligible phases</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map gate-row (sort-by name governor/allowed-ops))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "    <p class=\"muted\">Phase sequence: "
     (esc (kw-list phase/phase-sequence)) ".</p>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Decision timeline (this run)</h2>\n"
     "    <p class=\"muted\">" (esc (count runs)) " requests, in order. Confidence is the advisor's own declared "
     "value from <code>spicecrop.advisor/default-mock-proposals</code>; the outcome is the Governor's.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>#</th><th>Phase</th><th>Op</th><th>Farm-lot</th><th>Confidence</th><th>Outcome</th><th>Why</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map-indexed timeline-row runs)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Hard blocks raised (" (esc (count hard-runs)) " of " (esc (count runs)) " requests)</h2>\n"
     "    <p class=\"muted\">Every rule below produced <code>:hard? true</code>, which means the proposal never "
     "reached a human at all — there is no approval path past it. The renderer refuses to draw a human "
     "sign-off over a hard hold, so a regression that made one overridable would fail this build rather than "
     "quietly publish a page. Detail text is the Governor's own.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Rule</th><th>Times raised</th><th>Governor detail</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (hard-rule-rows runs)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">The append-only fact log held in the store itself (<code>store/audit-trail</code>), "
     (esc (count ledger)) " facts, of which " (esc (count holds)) " are Governor holds. "
     "<code>spicecrop.operation</code> routes hard holds and soft escalations through the same "
     "<code>hold-fact-fn</code>, so an escalation also appears as <code>:governor-hold</code> — with an empty "
     "basis, since no rule was violated. The two are told apart by the verdict's <code>:hard?</code> flag, "
     "which is what the timeline above renders.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Farm-lot</th><th>Disposition</th><th>Basis</th><th>Approved by</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map fact-row ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "    <p class=\"muted\">The approver is carried on the audit fact only. A farm-lot record in "
     "<code>spicecrop.store</code> has no approver field, so this console does not claim one holds it.</p>\n"
     "  </section>\n"

     "</main>\n"
     "<footer>\n"
     "  <p>cloud-itonami-isic-0128 — regenerate with <code>clojure -M:dev:render-html</code>. "
     "Deterministic: no timestamp, clock reading or random value reaches this page, so consecutive runs are "
     "byte-identical.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs] :as result} (run-demo!)
        ledger (store/audit-trail db)
        holds (filterv #(= :governor-hold (:t %)) ledger)
        hard-runs (filterv #(= :hard-hold (:outcome %)) runs)
        html (render result)]
    ;; A console that shows no Governor hold is not a console for a
    ;; governed actor -- it is a screenshot of the happy path. Both
    ;; counts are build-time invariants: if a future change to the
    ;; scenario, the Governor or the store stops producing holds, this
    ;; build fails instead of publishing a page that quietly claims
    ;; everything passed.
    (when (zero? (count holds))
      (throw (ex-info "refusing to write an operator console with zero governor holds"
                      {:ledger-facts (count ledger) :runs (count runs)})))
    (when (zero? (count hard-runs))
      (throw (ex-info "refusing to write an operator console with zero HARD governor holds"
                      {:governor-holds (count holds) :runs (count runs)})))
    (spit out html)
    (println "wrote" out
             "(" (count runs) "requests,"
             (count ledger) "ledger facts,"
             (count holds) "governor holds,"
             (count hard-runs) "hard holds,"
             (count (distinct (map :rule (mapcat :violations runs)))) "distinct hard rules )")))
