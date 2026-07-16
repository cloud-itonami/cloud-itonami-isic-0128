(ns spicecrop.phase
  "Phase machine: the states a spice/aromatic/drug-and-pharmaceutical
  crop farm-lot transits through.

  State machine:
    :intake -> :survey -> :advise -> :treat -> :record -> :audit

  `:intake` is farm-lot receiving (field, plot, requested farm
  operation); `:survey` is field/crop-condition assessment; `:advise` is
  the advisor's scheduling/procurement recommendation; `:treat` is the
  actual planting/harvest/labor field work performed on the operator's
  own lot; `:record` is logging the completed harvest (yield, evidence,
  compliance parameters) into records; `:audit` is compliance audit, the
  terminal state. This sequence matches the registry's own registered
  `:operating-states` for ISIC 0128 exactly.

  Each transition can accept a proposal and yield an audit fact.")

(def all-phases
  "All valid phases in the spice/aromatic/drug-and-pharmaceutical crop
  farm-operations workflow."
  [:intake :survey :advise :treat :record :audit])

(def phase-sequence
  "Ordered phases representing normal farm-lot progression."
  [:intake :survey :advise :treat :record :audit])

(defn valid-phase?
  "Check if a phase is valid."
  [phase]
  (contains? (set all-phases) phase))

(defn- index-of
  "Portable (Clojure/ClojureScript) index lookup -- `.indexOf` is a
  JVM-only `java.util.List` method that ClojureScript's PersistentVector
  does not implement, so it is avoided here even though `phase-sequence`
  is a plain vector. Returns -1 when `x` is not found, matching
  `java.util.List/indexOf`'s contract."
  [coll x]
  (or (first (keep-indexed (fn [i v] (when (= v x) i)) coll)) -1))

(defn can-transition?
  "Check if a transition from one phase to another is valid
  (must be forward-only in the sequence, no backtracking). Always returns a
  boolean (never nil), including when either phase is invalid."
  [from-phase to-phase]
  (boolean
   (and (valid-phase? from-phase) (valid-phase? to-phase)
        (let [from-idx (index-of phase-sequence from-phase)
              to-idx (index-of phase-sequence to-phase)]
          (and (>= from-idx 0) (>= to-idx 0) (< from-idx to-idx))))))
