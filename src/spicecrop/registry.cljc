(ns spicecrop.registry
  "Pure validation functions for spice/aromatic/drug-and-pharmaceutical
  crop compliance parameters. These are called by the Governor to
  independently verify physical/regulatory constraints -- the advisor's
  confidence is NOT sufficient to override these checks.

  All functions here are pure arithmetic/boolean predicates with no
  host-clock or I/O calls, so this namespace stays trivially portable
  across Clojure/ClojureScript. Callers that need the current time (see
  `cultivation-license-expired?` / `quota-tracking-lapsed?`) obtain it
  themselves via a `:clj`/`:cljs` reader-conditional at the call site
  (see `spicecrop.governor`).

  NONE of these functions grant, renew, or finalize a controlled-
  substance cultivation license or a diversion-control-compliance
  clearance -- they only independently verify the STATUS of records this
  actor never issues (see `spicecrop.governor`'s permanent block on any
  proposal that would finalize such a decision).")

(defn cultivation-license-expired?
  "Independently verify that the farm's controlled-substance cultivation
  license has NOT expired as of `now-epoch-ms`. An expired license means
  the farm-lot's controlled-substance-crop cultivation is no longer
  independently authorized -- a genuine regulatory hazard distinct from
  any quota or evidence concern. This is a STATUS check only; it never
  renews or reissues the license."
  [expiry-epoch-ms now-epoch-ms]
  (< expiry-epoch-ms now-epoch-ms))

(defn quota-tracking-lapsed?
  "Independently verify that the farm-lot's chain-of-custody/quota-
  tracking record was reconciled within the last 30 days.
  `last-reconciliation-epoch-ms` and `now-epoch-ms` are both epoch
  milliseconds -- callers obtain `now` via a `:clj`/`:cljs` reader-
  conditional, keeping this namespace free of any host-clock call. A
  stale quota-tracking record risks undetected diversion of a
  controlled-substance-precursor crop."
  [last-reconciliation-epoch-ms now-epoch-ms]
  (> (- now-epoch-ms last-reconciliation-epoch-ms)
     (* 30 24 60 60 1000)))

(defn harvest-quota-exceeded?
  "Independently verify that the reported harvest quantity does not
  exceed the farm-lot's licensed quota ceiling. A harvest reported above
  the licensed quota is a genuine diversion-risk compliance signal --
  this predicate only compares two already-recorded numbers; it never
  itself adjusts, grants, or clears a quota."
  [reported-harvest-kg licensed-quota-kg]
  (> reported-harvest-kg licensed-quota-kg))
