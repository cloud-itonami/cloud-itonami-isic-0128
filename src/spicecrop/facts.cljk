(ns spicecrop.facts
  "Reference facts for spice, aromatic, drug and pharmaceutical crop
  (ISIC Rev.5 0128) FARM OPERATIONS COORDINATION: crop-category safety/
  compliance windows (cultivation-license currency, quota-tracking
  reconciliation freshness, licensed-quota ceiling), jurisdiction
  evidence-checklist requirements. This namespace contains pure lookup
  functions for spice/aromatic/drug-and-pharmaceutical-crop compliance
  checks -- the Governor calls these to independently validate proposals;
  the advisor's confidence is never sufficient on its own.

  ISIC 0128 covers both entirely mundane spice/aromatic crops (pepper,
  vanilla, cinnamon, mint) AND licit drug/pharmaceutical-precursor crop
  cultivation (e.g. licensed opium poppy for pharmaceutical morphine
  production, licensed coca leaf for pharmaceutical/traditional use in
  the few jurisdictions where this is legal, licensed medicinal
  cannabis). This actor coordinates FARM OPERATIONS LOGISTICS ONLY
  (planting/harvest scheduling, yield logging, input procurement) for
  crops the operator ALREADY holds an independently-verified/registered
  farm-lot record for -- it NEVER grants, renews, or finalizes a
  controlled-substance cultivation license or a diversion-control-
  compliance clearance itself (see `spicecrop.governor`'s permanent,
  un-overridable `:cultivation-license-or-diversion-clearance-blocked`
  rule).

  Crop categories split into two compliance shapes:
    - Non-controlled spice/aromatic crops (black pepper, vanilla,
      cinnamon, peppermint) have NO cultivation-license or quota-
      tracking requirement at all -- `cultivation-license-required?`/
      `quota-tracking-required?` are false, and the Governor's
      corresponding checks are skipped entirely rather than fabricating
      a target.
    - Controlled drug/pharmaceutical-precursor crops (licensed opium
      poppy, licensed coca leaf, licensed medicinal cannabis) carry a
      genuine cultivation-license expiry, a quota-tracking
      reconciliation freshness requirement, and a licensed-quota ceiling
      the reported harvest must not exceed."
  (:require [clojure.set :as set]))

(def crop-categories
  "Valid spice/aromatic/drug-and-pharmaceutical crop categories and their
  compliance shape. `cultivation-license-required?`/
  `quota-tracking-required?` are false for non-controlled (ordinary
  spice/aromatic) crop categories -- the Governor's corresponding checks
  are skipped entirely for those categories rather than fabricating a
  target."
  {:spice/black-pepper
   {:id :spice/black-pepper
    :name "コショウ栽培"
    :controlled-substance-crop? false
    :cultivation-license-required? false
    :quota-tracking-required? false}

   :spice/vanilla
   {:id :spice/vanilla
    :name "バニラ栽培"
    :controlled-substance-crop? false
    :cultivation-license-required? false
    :quota-tracking-required? false}

   :aromatic/cinnamon
   {:id :aromatic/cinnamon
    :name "シナモン(ニッケイ)栽培"
    :controlled-substance-crop? false
    :cultivation-license-required? false
    :quota-tracking-required? false}

   :aromatic/peppermint
   {:id :aromatic/peppermint
    :name "ペパーミント栽培"
    :controlled-substance-crop? false
    :cultivation-license-required? false
    :quota-tracking-required? false}

   :pharma/licensed-opium-poppy
   {:id :pharma/licensed-opium-poppy
    :name "許可契約栽培ケシ(製薬用モルヒネ原料)"
    :controlled-substance-crop? true
    :cultivation-license-required? true
    :quota-tracking-required? true}

   :pharma/licensed-coca-leaf
   {:id :pharma/licensed-coca-leaf
    :name "許可コカ葉(製薬・伝統的用途、一部法域限定)"
    :controlled-substance-crop? true
    :cultivation-license-required? true
    :quota-tracking-required? true}

   :pharma/licensed-medicinal-cannabis
   {:id :pharma/licensed-medicinal-cannabis
    :name "許可医療用大麻栽培"
    :controlled-substance-crop? true
    :cultivation-license-required? true
    :quota-tracking-required? true}})

(defn crop-category-by-id [id]
  (get crop-categories id))

(def jurisdictions
  "Spice/aromatic/drug-and-pharmaceutical crop cultivation jurisdictions
  and their evidence-checklist requirements."
  {:jp/maff
   {:id :jp/maff
    :name "日本 (麻薬及び向精神薬取締法・農林水産省)"
    :required-evidence
    [:farm-registration-record
     :field-boundary-map
     :harvest-record
     :cultivation-license-on-file
     :quota-reconciliation-log]}

   :us/dea-usda
   {:id :us/dea-usda
    :name "United States (DEA controlled-substance cultivation license / USDA farm registration)"
    :required-evidence
    [:farm-registration-record
     :field-boundary-map
     :harvest-record
     :cultivation-license-on-file
     :quota-reconciliation-log]}

   :eu/reg1307
   {:id :eu/reg1307
    :name "European Union (Reg. (EU) No 1307/2013 CAP farm registration / narcotic precursor regulation)"
    :required-evidence
    [:farm-registration-record
     :field-boundary-map
     :harvest-record
     :cultivation-license-on-file
     :quota-reconciliation-log]}})

(defn jurisdiction-by-id [id]
  (get jurisdictions id))

(defn required-evidence-satisfied?
  "Verify that every item in the jurisdiction's `:required-evidence` list
  is present in `evidence`. `jurisdiction` may be a resolved jurisdiction
  map (as returned by `jurisdiction-by-id`) or a raw jurisdiction id --
  both call conventions are in use (tests pass a resolved map; the
  Governor passes the raw id straight off farm-lot metadata)."
  [jurisdiction evidence]
  (let [j (if (map? jurisdiction) jurisdiction (jurisdiction-by-id jurisdiction))]
    (if-not j
      false
      (set/subset? (set (:required-evidence j)) (set evidence)))))

(defn cultivation-license-current?
  "Positive-sense convenience predicate: is the farm's controlled-
  substance cultivation license valid (not yet expired) as of
  `now-epoch-ms`? Returns false when the crop category has no
  cultivation-license requirement at all -- there is nothing to be
  'current' about for an ordinary spice/aromatic crop category. This
  predicate only checks the STATUS of an existing, independently-issued
  license record -- it never grants or renews one (see
  `spicecrop.governor`'s permanent block on any proposal that would
  finalize a cultivation-license approval/renewal)."
  [expiry-epoch-ms now-epoch-ms crop-category]
  (boolean
   (and (some? crop-category)
        (true? (:cultivation-license-required? crop-category))
        (some? expiry-epoch-ms)
        (>= expiry-epoch-ms now-epoch-ms))))

(defn quota-tracking-current?
  "Positive-sense convenience predicate: was the crop's chain-of-custody/
  quota-tracking record reconciled within the safety interval (30 days)
  of `now-epoch-ms`? Returns false when the crop category has no quota-
  tracking requirement at all."
  [last-reconciliation-epoch-ms now-epoch-ms crop-category]
  (boolean
   (and (some? crop-category)
        (true? (:quota-tracking-required? crop-category))
        (some? last-reconciliation-epoch-ms)
        (<= (- now-epoch-ms last-reconciliation-epoch-ms)
            (* 30 24 60 60 1000)))))

(defn harvest-within-quota?
  "Positive-sense convenience predicate: does `reported-harvest-kg` stay
  at or below `licensed-quota-kg`? Returns false when the crop category
  has no licensed-quota ceiling at all (mundane spice/aromatic crop
  category -- nothing to exceed)."
  [reported-harvest-kg licensed-quota-kg crop-category]
  (boolean
   (and (some? crop-category)
        (true? (:quota-tracking-required? crop-category))
        (some? reported-harvest-kg)
        (some? licensed-quota-kg)
        (<= reported-harvest-kg licensed-quota-kg))))
