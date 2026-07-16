(ns spicecrop.store
  "Store abstraction for spice/aromatic/drug-and-pharmaceutical crop
  farm-lots. Current implementation operates on plain data
  (`{:farm-lots {farm-lot-id lot-map} :facts [...]}`); production should
  migrate this seam to Datomic/kotoba-server (the same seam point all
  cloud-itonami actors use) while keeping the same pure-function surface.

  A farm-lot is the minimal unit of work: one spice/aromatic/drug-and-
  pharmaceutical-crop field or plot with an INDEPENDENTLY VERIFIED/
  REGISTERED farm/grower-license record already on file (this actor
  never grants or verifies that registration itself -- it only checks
  that the record exists). Representative farm-lot keys:
    - :crop-category keyword crop-category id (see
      `spicecrop.facts/crop-categories`)
    - :jurisdiction keyword jurisdiction id (see
      `spicecrop.facts/jurisdictions`)
    - :field-id the operator's own field/plot identifier
    - :field-area-hectares cultivated field area
    - :evidence-checklist evidence items present for the farm-lot
    - :cultivation-license-expiry-date epoch-ms of the farm-lot's
      controlled-substance cultivation license expiry (nil for
      non-controlled spice/aromatic crop categories)
    - :quota-last-reconciliation-date epoch-ms of the last chain-of-
      custody/quota-tracking reconciliation (nil for non-controlled crop
      categories)
    - :reported-harvest-kg reported harvest quantity for this lot (nil
      for non-controlled crop categories)
    - :licensed-quota-kg the farm-lot's licensed quota ceiling (nil for
      non-controlled crop categories)
    - :compliance-concern-raised? / :compliance-concern-resolved? open
      suspected-diversion/license-compliance/pest-outbreak concern flag
    - :logged? true once a `:log-harvest-record` proposal commits
    - :scheduled? true once a `:schedule-farm-operation` proposal
      commits

  The ledger (`:facts`) is a separate append-only vector of audit facts,
  kept alongside `:farm-lots` in the same store value.")

(defn farm-lot
  "Retrieve a farm-lot by id, or nil if it does not exist / is not yet
  registered."
  [st farm-lot-id]
  (get-in st [:farm-lots farm-lot-id]))

(defn farm-lot-registered?
  "True only if the farm-lot exists in the store -- registration is the
  HARD invariant that must be independently verified before ANY of this
  actor's four proposal ops can be made against it."
  [st farm-lot-id]
  (some? (farm-lot st farm-lot-id)))

(defn farm-lot-already-logged?
  "True only if the farm-lot exists and has already been marked logged."
  [st farm-lot-id]
  (true? (:logged? (farm-lot st farm-lot-id))))

(defn log-harvest-record
  "Register/update `lot-data` under `farm-lot-id` and mark it logged
  (one-way flag). Used once a `:log-harvest-record` proposal commits."
  [st farm-lot-id lot-data]
  (assoc-in st [:farm-lots farm-lot-id] (assoc lot-data :logged? true)))

(defn mark-scheduled
  "Mark an existing farm-lot as scheduled (one-way flag). Used once a
  `:schedule-farm-operation` proposal commits."
  [st farm-lot-id]
  (assoc-in st [:farm-lots farm-lot-id :scheduled?] true))

(defn audit-trail
  "Return the append-only audit ledger (empty vector if none yet)."
  [st]
  (get st :facts []))

(defn append-fact
  "Append `fact` to the store's audit ledger."
  [st fact]
  (update st :facts (fnil conj []) fact))
