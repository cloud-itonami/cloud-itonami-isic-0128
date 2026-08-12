(ns spicecrop.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2608090800,
  Wave 7) for this repo: it previously had NO demo page and no generator.
  This namespace drives the REAL actor stack --
  `spicecrop.operation/run-operation` -> `spicecrop.governor/check` ->
  `spicecrop.store` -- and renders whatever that path actually produced.
  Nothing on the page is hand-typed: every disposition, violation rule
  and violation detail string is the governor's own output, every crop-
  category / jurisdiction display name is read from `spicecrop.facts`,
  and the gate table is computed from `spicecrop.governor`'s own vars
  (`allowed-ops`, `always-escalate-ops`, `high-stakes`, `phase-auto-ops`,
  `confidence-floor`, `supply-order-cost-threshold-usd`) across
  `spicecrop.phase/all-phases`, so it cannot drift from the code.

  WHERE THE FARM-LOTS COME FROM. Unlike sibling actors in this family,
  this repo ships NO seed data: `spicecrop.store` has no `seed-db` /
  `demo-data`, and `spicecrop.sim` is still a stub (`clojure -M:dev:run`
  prints \"not yet implemented\", verified before this file was written).
  So the scenario below constructs its farm-lots here and registers them
  through the real store. The lot metadata is therefore scenario INPUT --
  it is deliberately labelled as such on the page -- while every
  disposition the page reports about those lots is real code OUTPUT.

  WHAT THE SCAFFOLD DOES NOT EMIT. `spicecrop.operation/run-operation`
  returns `:facts []` on the clean path -- this repo's scaffold has no
  commit-fact and no approval-fact function at all, and it emits the
  same `:t :governor-hold` fact for a soft escalation as for a hard
  refusal (with an empty `:basis`, which is how the two are told apart).
  The `:approval-granted` / `:committed` facts on the page are therefore
  appended by THIS namespace through the real `store/append-fact`, and
  the page says so rather than implying the actor wrote them. There is
  likewise no approver identity to record -- no approval path, no store
  field to carry one, no seeded roster -- so those facts carry
  `:actor nil` and the page prints \"not on record\". A plausible-looking
  approver name is never hand-typed.

  DETERMINISM. No timestamps, dates or random values reach the page.
  `spicecrop.governor/check` does read the wall clock (for cultivation-
  license expiry and quota-tracking freshness), so the scenario pins the
  expired/lapsed lot to fixed past epochs and derives the compliant lot's
  reconciliation date from `now` -- neither epoch is ever printed, only
  the resulting boolean status, so two consecutive runs are byte-
  identical.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jp-go-dds.skin]
            [spicecrop.advisor :as advisor]
            [spicecrop.facts :as facts]
            [spicecrop.governor :as gov]
            [spicecrop.operation :as op]
            [spicecrop.phase :as phase]
            [spicecrop.store :as store]))

;; ─────────────────────────── scenario input ───────────────────────────
;; Farm-lot metadata below is scenario INPUT (this repo ships no seed
;; data -- see ns docstring). It is fed through the real store; every
;; disposition reported about it is real governor output.

(def ^:private advisor-actor
  "The operation context's `:actor-id`, named after this repo's own
  advisor namespace (`spicecrop.advisor`, \"SpiceCropAdvisor\"). It is a
  component identifier, not a person -- this repo ships no roster and no
  human identity is invented anywhere in this file."
  "spicecrop-advisor")

;; NO APPROVER IDENTITY EXISTS IN THIS SCAFFOLD. `spicecrop.operation`
;; has no approval path at all, `spicecrop.store` has no field that
;; could carry an approver, and the repo ships no seed roster. So the
;; sign-off facts written below carry `:actor nil` and the page renders
;; "not on record" rather than hand-typing a plausible-looking name.

(def ^:private full-evidence
  "The evidence checklist every jurisdiction in `spicecrop.facts`
  requires -- read from the facts table, not re-typed here."
  (vec (:required-evidence (facts/jurisdiction-by-id :jp/maff))))

(def ^:private day-ms (* 24 60 60 1000))

(defn- now-ms [] (System/currentTimeMillis))

(def ^:private license-expired-epoch
  "A fixed epoch far in the past (2024-01-01T00:00:00Z), so the expired
  lot stays expired no matter when this page is built. Never printed."
  1704067200000)

(defn- farm-lots
  "The scenario's farm-lots. `now` is threaded in so the compliant
  controlled lot has a genuinely fresh quota reconciliation under the
  governor's live 30-day window; the value itself never reaches the
  page."
  [now]
  [["lot-mint-01"
    {:crop-category :aromatic/peppermint
     :jurisdiction :jp/maff
     :field-id "HK-N3"
     :field-area-hectares 4.2
     :phase :treat
     :evidence-checklist full-evidence}]
   ["lot-cinnamon-07"
    {:crop-category :aromatic/cinnamon
     :jurisdiction :jp/maff
     :field-id "OK-S1"
     :field-area-hectares 1.8
     :phase :advise
     :evidence-checklist full-evidence}]
   ["lot-poppy-02"
    {:crop-category :pharma/licensed-opium-poppy
     :jurisdiction :jp/maff
     :field-id "TS-W7"
     :field-area-hectares 12.5
     :phase :record
     :evidence-checklist full-evidence
     :cultivation-license-expiry-date (+ now (* 400 day-ms))
     :quota-last-reconciliation-date (- now (* 5 day-ms))
     :reported-harvest-kg 820
     :licensed-quota-kg 900}]
   ["lot-poppy-03"
    {:crop-category :pharma/licensed-opium-poppy
     :jurisdiction :us/dea-usda
     :field-id "OR-E2"
     :field-area-hectares 30.0
     :phase :record
     :evidence-checklist full-evidence
     :cultivation-license-expiry-date license-expired-epoch
     :quota-last-reconciliation-date license-expired-epoch
     :reported-harvest-kg 1450
     :licensed-quota-kg 1200}]
   ["lot-cannabis-04"
    {:crop-category :pharma/licensed-medicinal-cannabis
     :jurisdiction :eu/reg1307
     :field-id "NL-G4"
     :field-area-hectares 2.0
     :phase :record
     :evidence-checklist full-evidence
     :cultivation-license-expiry-date (+ now (* 400 day-ms))
     :quota-last-reconciliation-date (- now (* 5 day-ms))
     :reported-harvest-kg 300
     :licensed-quota-kg 500
     :compliance-concern-raised? true}]
   ["lot-vanilla-05"
    {:crop-category :spice/vanilla
     :jurisdiction :jp/maff
     :field-id "KG-V9"
     :field-area-hectares 0.9
     :phase :record
     ;; deliberately missing :quota-reconciliation-log
     :evidence-checklist (vec (remove #{:quota-reconciliation-log} full-evidence))}]
   ["lot-pepper-06"
    {:crop-category :spice/black-pepper
     :jurisdiction :jp/maff
     :field-id "IR-P2"
     :field-area-hectares 6.4
     :phase :audit
     :evidence-checklist full-evidence}]])

(def ^:private unregistered-lot-id
  "Never inserted into the store -- exercises the registration invariant
  that guards all four allowed ops."
  "lot-unregistered-99")

(def ^:private steps
  "The scenario, in order. Each entry is one proposal put through the
  real actor. Together they reach every disposition this actor can
  produce: clean auto-commit, escalation to a human (all four escalation
  causes), and hard governor refusal that never reaches a human (seven
  distinct rules)."
  [{:lot "lot-mint-01" :op :schedule-farm-operation
    :note "clean, and :schedule-farm-operation is auto-eligible at :treat"}
   {:lot "lot-cinnamon-07" :op :schedule-farm-operation :confidence 0.42
    :note "governor-clean but the advisor is below the confidence floor"}
   {:lot "lot-poppy-02" :op :log-harvest-record
    :note "clean controlled-crop harvest log -- the one real actuation event"}
   {:lot "lot-poppy-02" :op :log-harvest-record
    :note "the same lot again -- double-commit guard"}
   {:lot "lot-poppy-03" :op :log-harvest-record
    :note "expired licence, stale quota reconciliation, harvest over ceiling"}
   {:lot "lot-cannabis-04" :op :log-harvest-record
    :note "an unresolved compliance concern blocks the harvest log"}
   {:lot "lot-cannabis-04" :op :flag-compliance-concern
    :note "raising a concern always needs a human, at every phase"}
   {:lot "lot-vanilla-05" :op :log-harvest-record
    :note "jurisdiction evidence checklist incomplete"}
   {:lot unregistered-lot-id :op :schedule-farm-operation
    :note "no independently verified farm-lot record exists"}
   {:lot "lot-mint-01" :op :schedule-farm-operation
    :value-extra {:finalize-cultivation-license-renewal? true}
    :note "a licence renewal smuggled inside an ordinary scheduling proposal"}
   {:lot "lot-mint-01" :op :grant-cultivation-license
    :note "an op outside the closed allowlist"}
   {:lot "lot-pepper-06" :op :coordinate-supply-order :cost-usd 500
    :note "cost verified below threshold, auto-eligible at :audit"}
   {:lot "lot-pepper-06" :op :coordinate-supply-order :cost-usd 18000
    :note "cost above threshold"}
   {:lot "lot-pepper-06" :op :coordinate-supply-order :cost-usd :omitted
    :note "cost absent -- the gate fails safe instead of standing down"}])

;; ───────────────────────────── the run ─────────────────────────────

(defn- proposal-for
  "Builds the advisor proposal for a step. Happy-path shape comes from
  `spicecrop.advisor/default-mock-proposals`; steps that model an
  adversarial or out-of-scope request supply the deviation explicitly."
  [{:keys [op confidence value-extra cost-usd]} lot]
  (let [base (or (get advisor/default-mock-proposals op)
                 ;; no fixture exists for an op outside the allowlist --
                 ;; that is the point of the step.
                 {:op op
                  :effect :propose
                  :cites [{:spec "Out-of-scope-request"}]
                  :rationale "Requesting finalisation of a cultivation licence."
                  :value {}
                  :confidence 0.9})
        value (cond-> (:value base)
                ;; cite the lot's OWN jurisdiction rather than the
                ;; fixture's default
                (:jurisdiction lot) (assoc :jurisdiction (:jurisdiction lot))
                (number? cost-usd) (assoc :cost-usd cost-usd)
                (= :omitted cost-usd) (dissoc :cost-usd)
                (map? value-extra) (merge value-extra))]
    (cond-> (assoc base :op op :value value)
      (number? confidence) (assoc :confidence confidence))))

(defn- escalation-reason
  "Why the governor sent this proposal to a human. Derived from the
  governor's own vars, verdict and predicates -- never hand-typed. The
  supply-order cost gate is consulted through its real (private) var so
  the threshold logic is not mirrored here."
  [request proposal verdict]
  (cond
    (< (:confidence verdict) gov/confidence-floor) :low-confidence
    (contains? gov/high-stakes (:op request)) :high-stakes-actuation
    (contains? gov/always-escalate-ops (:op request)) :always-escalates
    (#'gov/high-cost-supply-order? request proposal) :supply-order-cost-unverified
    :else :phase-not-auto-eligible))

(defn- commit!
  "Applies the real store mutation for a committed op. Two of this
  actor's four ops have no mutator in `spicecrop.store` at all, so they
  commit to the ledger only -- reported as such rather than papered
  over."
  [db op lot-id]
  (case op
    :log-harvest-record [(store/log-harvest-record db lot-id (store/farm-lot db lot-id))
                         :farm-lot-record]
    :schedule-farm-operation [(store/mark-scheduled db lot-id) :farm-lot-record]
    [db :ledger-only]))

(defn- run-step
  "Puts one proposal through `operation/run-operation` and records what
  really happened."
  [{:keys [db log]} {:keys [lot op] :as step}]
  (let [lot-map (store/farm-lot db lot)
        proposal (proposal-for step lot-map)
        request {:op op :subject lot}
        context {:actor-id advisor-actor :hold-fact-fn gov/hold-fact}
        result (op/run-operation request context proposal db gov/check)
        verdict (:verdict result)
        ;; every fact the actor itself emitted goes into the ledger as-is
        db (reduce store/append-fact db (:facts result))
        hard? (boolean (:hard? verdict))
        auto? (and (:ok? result) (gov/auto-eligible-at-phase? (:phase lot-map) op))]
    (cond
      hard?
      {:db db
       :log (conj log (assoc step
                             :disposition :hard-hold
                             :confidence (:confidence proposal)
                             :violations (:violations verdict)))}

      :else
      (let [reason (if (:ok? result)
                     :phase-not-auto-eligible
                     (escalation-reason request proposal verdict))
            approved? (not auto?)
            db (cond-> db
                 approved?
                 ;; `:actor nil` on purpose -- see the comment by
                 ;; `advisor-actor`. No approver identity exists to
                 ;; record, and one is never invented.
                 (store/append-fact {:t :approval-granted
                                     :op op
                                     :actor nil
                                     :subject lot
                                     :disposition :signed-off
                                     :basis [reason]}))
            [db effect] (commit! db op lot)
            db (store/append-fact db {:t :committed
                                      :op op
                                      ;; the store mutation is applied by
                                      ;; this actor either way; only the
                                      ;; sign-off above lacks an identity
                                      :actor advisor-actor
                                      :subject lot
                                      :disposition (if approved? :signed-off-then-committed :auto-committed)
                                      :basis [effect]})]
        {:db db
         :log (conj log (assoc step
                               :disposition (if auto? :auto-commit :escalated-signed-off)
                               :confidence (:confidence proposal)
                               :reason (when-not auto? reason)
                               :effect effect))}))))

(defn run-demo!
  "Runs the whole scenario. Returns `{:db <store> :log [...]}` where
  `:db` is the real store (farm-lots + append-only audit ledger) and
  `:log` is the per-step record of what the governor decided."
  []
  (let [now (now-ms)
        db (reduce (fn [st [id lot]] (assoc-in st [:farm-lots id] lot))
                   {:farm-lots {} :facts []}
                   (farm-lots now))]
    (reduce run-step {:db db :log []} steps)))

;; ───────────────────────────── rendering ─────────────────────────────

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw [v] (if (keyword? v) (name v) (str v)))

(defn- lot-status-cell
  "Compliance status computed with `spicecrop.facts`' own positive-sense
  predicates against the same wall clock the governor uses."
  [lot now]
  (let [cc (facts/crop-category-by-id (:crop-category lot))]
    (if-not (:controlled-substance-crop? cc)
      "<span class=\"muted\">n/a &middot; not a controlled crop</span>"
      (let [lic? (facts/cultivation-license-current?
                  (:cultivation-license-expiry-date lot) now cc)
            quo? (facts/quota-tracking-current?
                  (:quota-last-reconciliation-date lot) now cc)
            within? (facts/harvest-within-quota?
                     (:reported-harvest-kg lot) (:licensed-quota-kg lot) cc)]
        (str/join " &middot; "
                  [(if lic? "<span class=\"ok\">licence current</span>"
                       "<span class=\"critical\">licence expired</span>")
                   (if quo? "<span class=\"ok\">quota reconciled</span>"
                       "<span class=\"critical\">quota tracking lapsed</span>")
                   (if within? "<span class=\"ok\">within quota</span>"
                       "<span class=\"critical\">over quota</span>")])))))

(defn- lot-row [db now [id _]]
  (let [lot (store/farm-lot db id)
        cc (facts/crop-category-by-id (:crop-category lot))
        j (facts/jurisdiction-by-id (:jurisdiction lot))]
    (format (str "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td>"
                 "<td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>")
            (esc id) (esc (:name cc)) (esc (:name j))
            (esc (kw (:phase lot)))
            (lot-status-cell lot now)
            (if (:logged? lot) "<span class=\"ok\">logged</span>"
                "<span class=\"muted\">not logged</span>")
            (if (:scheduled? lot) "<span class=\"ok\">scheduled</span>"
                "<span class=\"muted\">not scheduled</span>"))))

(defn- unregistered-row [db]
  (format (str "        <tr><td><code>%s</code></td><td colspan=\"5\" class=\"muted\">"
               "never registered in the store</td><td>%s</td></tr>")
          (esc unregistered-lot-id)
          (if (store/farm-lot-registered? db unregistered-lot-id)
            "<span class=\"critical\">registered?!</span>"
            "<span class=\"critical\">unregistered</span>")))

(defn- gate-row
  "One row per allowed op, entirely computed from the governor's own
  vars across `spicecrop.phase/all-phases`."
  [op]
  (let [auto-phases (filterv #(gov/auto-eligible-at-phase? % op) phase/all-phases)]
    (format (str "        <tr><td><code>:%s</code></td><td>%s</td><td>%s</td>"
                 "<td>%s</td></tr>")
            (esc (kw op))
            (if (seq auto-phases)
              (str "<span class=\"ok\">"
                   (str/join ", " (map #(str ":" (kw %)) auto-phases))
                   "</span>")
              "<span class=\"warn\">never auto-commits at any phase</span>")
            (if (contains? gov/always-escalate-ops op)
              "<span class=\"warn\">always</span>"
              "<span class=\"muted\">only when a gate trips</span>")
            (if (contains? gov/high-stakes op)
              "<span class=\"warn\">yes &middot; real actuation event</span>"
              "<span class=\"muted\">no</span>"))))

(defn- step-row [{:keys [lot op disposition reason confidence violations effect note]}]
  (format (str "        <tr><td><code>:%s</code></td><td><code>%s</code></td><td>%s</td>"
               "<td>%s</td><td>%s</td><td class=\"muted\">%s</td></tr>")
          (esc (kw op)) (esc lot)
          (case disposition
            :auto-commit "<span class=\"ok\">auto-commit</span>"
            :escalated-signed-off "<span class=\"warn\">escalated &rarr; signed off <span class=\"muted\">(sign-off is scenario input)</span></span>"
            :hard-hold "<span class=\"critical\">HARD hold &middot; no human override</span>")
          (cond
            (seq violations)
            (str/join "<br>"
                      (map #(str "<code>:" (esc (kw (:rule %))) "</code>") violations))
            reason (str "<code>:" (esc (kw reason)) "</code>")
            :else (str "<span class=\"muted\">confidence " (esc confidence) "</span>"))
          (cond
            (seq violations) (str/join "<br>" (map #(esc (:detail %)) violations))
            (= :ledger-only effect) "committed to the ledger only (no store mutator for this op)"
            (= :farm-lot-record effect) "farm-lot record updated"
            :else "")
          (esc note)))

(defn- ledger-row [{:keys [t op actor subject disposition basis]}]
  (format (str "        <tr><td>%s</td><td><code>:%s</code></td><td><code>%s</code></td>"
               "<td>%s</td><td>%s</td><td>%s</td></tr>")
          (case t
            :governor-hold (if (seq basis)
                             "<span class=\"critical\">governor-hold</span>"
                             "<span class=\"warn\">governor-hold</span>")
            :approval-granted "<span class=\"warn\">approval-granted</span>"
            :committed "<span class=\"ok\">committed</span>"
            (esc (kw t)))
          (esc (kw op)) (esc subject)
          ;; An approval fact has no actor: this scaffold has no approval
          ;; path and no store field that could carry an approver, so the
          ;; identity genuinely is not on record. Never hand-typed.
          (if (some? actor)
            (esc (kw actor))
            "<span class=\"muted\">not on record</span>")
          (esc (kw disposition))
          (if (seq basis)
            (str/join ", " (map #(str "<code>:" (esc (kw %)) "</code>") basis))
            "<span class=\"muted\">—</span>")))

(defn hard-holds
  "The hard governor refusals recorded in the store's own audit ledger.
  A hold fact carries a non-empty `:basis` only when the governor found
  hard violations -- a soft escalation produces the same `:t
  :governor-hold` fact with an empty basis (a real property of this
  repo's scaffold, surfaced rather than hidden)."
  [db]
  (filterv #(and (= :governor-hold (:t %)) (seq (:basis %)))
           (store/audit-trail db)))

(defn render
  "Renders the console from a store `db` that has already been driven by
  `run-demo!`, plus that run's step log."
  [{:keys [db log]}]
  (let [now (now-ms)
        ledger (store/audit-trail db)
        holds (hard-holds db)
        autos (filterv #(= :auto-commit (:disposition %)) log)
        escalated (filterv #(= :escalated-signed-off (:disposition %)) log)
        rules (sort (distinct (mapcat #(map :rule (:violations %))
                                      (filter :violations log))))]
    (str
     "<html><head><meta charset=\"utf-8\">"
     "<title>cloud-itonami-isic-0128 &middot; spice, aromatic, drug and pharmaceutical crops</title>"
     "<style>" (jp-go-dds.skin/dds+skin) "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Growing of spice, aromatic, drug and pharmaceutical crops (ISIC 0128) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · harvest logging always human-approved · cultivation-licence and diversion-control decisions permanently out of scope</span>\n"
     "</header>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>This run</h2>\n"
     "    <p class=\"muted\">Build-time generated by <code>spicecrop.render-html</code> (<code>clojure -M:dev:render-html</code>) by driving <code>spicecrop.operation/run-operation</code> &rarr; <code>spicecrop.governor/check</code> &rarr; <code>spicecrop.store</code>. Deterministic: no timestamps or random values reach this page.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Measure</th><th>Value</th></tr></thead>\n"
     "      <tbody>\n"
     (format "        <tr><td>Proposals put through the actor</td><td>%d</td></tr>\n" (count log))
     (format "        <tr><td>Auto-committed (governor clean and phase-eligible)</td><td><span class=\"ok\">%d</span></td></tr>\n" (count autos))
     (format "        <tr><td>Escalated by the governor, then signed off <span class=\"muted\">(the escalation is real output; the sign-off is scenario input)</span></td><td><span class=\"warn\">%d</span></td></tr>\n" (count escalated))
     (format "        <tr><td>HARD holds — refused by the governor, never shown to a human</td><td><span class=\"critical\">%d</span></td></tr>\n" (count holds))
     (format "        <tr><td>Distinct hard rules exercised</td><td>%s</td></tr>\n"
             (str/join ", " (map #(str "<code>:" (esc (kw %)) "</code>") rules)))
     (format "        <tr><td>Audit facts in the store ledger</td><td>%d</td></tr>\n" (count ledger))
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Farm-lots</h2>\n"
     "    <p class=\"muted\">This repo ships no seed data (<code>spicecrop.store</code> has no <code>seed-db</code>; <code>spicecrop.sim</code> is still a stub), so the lot metadata below is scenario input registered through the real store. The compliance status column is computed by <code>spicecrop.facts</code>' own predicates; the last two columns are read back out of the store after the run.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Farm-lot</th><th>Crop category</th><th>Jurisdiction</th><th>Phase</th><th>Compliance status</th><th>Harvest record</th><th>Operation</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial lot-row db now) (farm-lots now))) "\n"
     (unregistered-row db) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Action gate (Spice/Aromatic/Pharmaceutical Crop Governor)</h2>\n"
     (format (str "    <p class=\"muted\">Computed from <code>spicecrop.governor</code>'s own vars across every phase in "
                  "<code>spicecrop.phase/all-phases</code> — this table cannot drift from the code. Confidence floor "
                  "<code>%s</code>; supply orders escalate unless their cost is present, numeric and below "
                  "<code>%s</code> USD. Anything outside this closed allowlist — above all, finalising a cultivation "
                  "licence or a diversion-control clearance — is refused permanently and is not overridable by human approval.</p>\n")
             (esc gov/confidence-floor) (esc gov/supply-order-cost-threshold-usd))
     "    <table>\n"
     "      <thead><tr><th>Allowed op</th><th>Auto-commit eligible at</th><th>Needs a human</th><th>High stakes</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map gate-row (sort-by name gov/allowed-ops))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>What the governor decided</h2>\n"
     "    <p class=\"muted\">One row per proposal, in run order. Dispositions, hold rules and their detail text are the governor's own output. No human is in this build: where a row reads <em>signed off</em>, the escalation is the governor's real decision and the sign-off that follows it is scenario input, supplied so the post-commit store state and the double-commit guard can be exercised. This scaffold has no approval path and no store field that could carry an approver, so no approver identity is recorded — the ledger below prints <em>not on record</em> rather than a name.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Farm-lot</th><th>Disposition</th><th>Rule / cause</th><th>Governor detail</th><th>Scenario</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map step-row log)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">The store's append-only ledger, in order. <code>governor-hold</code> facts are emitted by <code>spicecrop.operation/run-operation</code> itself — red where the basis is non-empty (a hard refusal), amber where it is empty (the scaffold emits the same fact type for a soft escalation). <code>approval-granted</code> and <code>committed</code> facts are appended by the renderer through <code>store/append-fact</code>, because this repo's <code>operation</code> namespace returns <code>:facts []</code> on the clean path and has no commit- or approval-fact function of its own.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Farm-lot</th><th>Actor</th><th>Disposition</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map ledger-row ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "</main>\n</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db log] :as run} (run-demo!)
        holds (hard-holds db)]
    ;; Build-time invariant: a console that shows no hard refusal is not
    ;; showing this actor's range. Fail the build rather than ship one.
    (when (zero? (count holds))
      (throw (ex-info "no HARD governor holds in the run -- refusing to render a console that hides the actor's refusals"
                      {:ledger-facts (count (store/audit-trail db))
                       :steps (count log)})))
    (io/make-parents out)
    (spit out (render run))
    (println "wrote" out
             (str "(" (count (store/audit-trail db)) " ledger facts, "
                  (count log) " proposals, "
                  (count holds) " HARD holds, "
                  (count (filter #(= :auto-commit (:disposition %)) log)) " auto-commits)"))))
