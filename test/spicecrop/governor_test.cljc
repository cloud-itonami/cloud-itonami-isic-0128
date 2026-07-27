(ns spicecrop.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [spicecrop.advisor :as advisor]
            [spicecrop.governor :as governor]))

(def ^:private now-ms #?(:clj (System/currentTimeMillis) :cljs (.now js/Date)))
(def ^:private ten-days-ago (- now-ms (* 10 24 60 60 1000)))
(def ^:private hundred-days-ago (- now-ms (* 100 24 60 60 1000)))
(def ^:private ten-days-from-now (+ now-ms (* 10 24 60 60 1000)))

(def ^:private evidence-checklist
  [:farm-registration-record :field-boundary-map :harvest-record
   :cultivation-license-on-file :quota-reconciliation-log])

(def ^:private clean-poppy-lot
  "Baseline clean farm-lot for a controlled substance crop category
  (licensed opium poppy) -- has cultivation-license/quota-tracking/
  harvest-quota specs."
  {:crop-category :pharma/licensed-opium-poppy
   :jurisdiction :jp/maff
   :field-id "field-42"
   :cultivation-license-expiry-date ten-days-from-now
   :quota-last-reconciliation-date ten-days-ago
   :reported-harvest-kg 100
   :licensed-quota-kg 150
   :evidence-checklist evidence-checklist})

(def ^:private clean-pepper-lot
  "Baseline clean farm-lot for a non-controlled crop category (black
  pepper) -- has NO cultivation-license/quota-tracking fields at all."
  {:crop-category :spice/black-pepper
   :jurisdiction :jp/maff
   :field-id "field-77"
   :evidence-checklist evidence-checklist})

;; ──────────────────────── Registration Invariant ──────────────────────

(deftest farm-lot-not-registered-violation-test
  (testing "log-harvest-record against a never-registered farm-lot is a hard block"
    (let [store {:farm-lots {}}
          req {:op :log-harvest-record :subject "lot-999"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :farm-lot-not-registered) (:violations result)))))

  (testing "schedule-farm-operation against a never-registered farm-lot is a hard block"
    (let [store {:farm-lots {}}
          req {:op :schedule-farm-operation :subject "lot-999"}
          prop {:cites [] :value {} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :farm-lot-not-registered) (:violations result)))))

  (testing "flag-compliance-concern against a never-registered farm-lot is a hard block"
    (let [store {:farm-lots {}}
          req {:op :flag-compliance-concern :subject "lot-999"}
          prop {:cites [{:spec "Field-Report"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :farm-lot-not-registered) (:violations result)))))

  (testing "coordinate-supply-order against a never-registered farm-lot is a hard block"
    (let [store {:farm-lots {}}
          req {:op :coordinate-supply-order :subject "lot-999"}
          prop {:cites [{:spec "Supplier-Catalog"}] :value {:jurisdiction :jp/maff :cost-usd 100} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :farm-lot-not-registered) (:violations result))))))

;; ──────────────────────── Spec Basis ──────────────────────

(deftest spec-basis-violation-test
  (testing "proposal with no jurisdiction citation is a hard violation"
    (let [store {:farm-lots {"lot-001" clean-poppy-lot}}
          req {:op :log-harvest-record :subject "lot-001"}
          prop {:cites [] :value {:jurisdiction nil}}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :no-spec-basis) (:violations result)))))

  (testing "proposal with proper citation passes spec basis check"
    (let [store {:farm-lots {"lot-001" clean-poppy-lot}}
          req {:op :log-harvest-record :subject "lot-001"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:hard? result))))))

;; ──────────────────────── Cultivation License Violations ──────────────────────

(deftest cultivation-license-expired-violation-test
  (testing "expired cultivation license triggers hard violation"
    (let [store {:farm-lots {"lot-001" (assoc clean-poppy-lot
                                                :cultivation-license-expiry-date hundred-days-ago)}}
          req {:op :log-harvest-record :subject "lot-001"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :cultivation-license-expired) (:violations result)))))

  (testing "current cultivation license passes"
    (let [store {:farm-lots {"lot-001" clean-poppy-lot}}
          req {:op :log-harvest-record :subject "lot-001"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:hard? result)))))

  (testing "non-controlled crop category never triggers this rule"
    (let [store {:farm-lots {"lot-002" clean-pepper-lot}}
          req {:op :log-harvest-record :subject "lot-002"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (not (some #(= (:rule %) :cultivation-license-expired) (:violations result)))))))

;; ──────────────────────── Quota Tracking Violations ──────────────────────

(deftest quota-tracking-lapsed-violation-test
  (testing "lapsed quota-tracking reconciliation triggers hard violation"
    (let [store {:farm-lots {"lot-001" (assoc clean-poppy-lot
                                                :quota-last-reconciliation-date hundred-days-ago)}}
          req {:op :log-harvest-record :subject "lot-001"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :quota-tracking-lapsed) (:violations result)))))

  (testing "non-controlled crop category never triggers this rule"
    (let [store {:farm-lots {"lot-002" clean-pepper-lot}}
          req {:op :log-harvest-record :subject "lot-002"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (not (some #(= (:rule %) :quota-tracking-lapsed) (:violations result)))))))

;; ──────────────────────── Harvest Quota Violations ──────────────────────

(deftest harvest-quota-exceeded-violation-test
  (testing "harvest above the licensed quota triggers hard violation"
    (let [store {:farm-lots {"lot-001" (assoc clean-poppy-lot :reported-harvest-kg 500)}}
          req {:op :log-harvest-record :subject "lot-001"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :harvest-quota-exceeded) (:violations result)))))

  (testing "harvest at or below the licensed quota passes"
    (let [store {:farm-lots {"lot-002" clean-poppy-lot}}
          req {:op :log-harvest-record :subject "lot-002"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:hard? result)))))

  (testing "non-controlled crop category never triggers this rule"
    (let [store {:farm-lots {"lot-003" clean-pepper-lot}}
          req {:op :log-harvest-record :subject "lot-003"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (not (some #(= (:rule %) :harvest-quota-exceeded) (:violations result)))))))

;; ──────────────────────── Evidence Completeness ──────────────────────

(deftest evidence-incomplete-violation-test
  (testing "incomplete evidence checklist triggers hard violation"
    (let [store {:farm-lots {"lot-001" (assoc clean-poppy-lot
                                                :evidence-checklist [:farm-registration-record])}}
          req {:op :log-harvest-record :subject "lot-001"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :evidence-incomplete) (:violations result))))))

;; ──────── Cultivation-License / Diversion-Control-Clearance Block ────────

(deftest cultivation-license-or-diversion-clearance-blocked-violation-test
  (testing "a proposal covertly requesting to finalize a cultivation-license approval is a hard, permanent block"
    (let [store {:farm-lots {"lot-001" clean-poppy-lot}}
          req {:op :schedule-farm-operation :subject "lot-001"}
          prop {:cites [] :value {:finalize-cultivation-license-approval? true} :confidence 0.99}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :cultivation-license-or-diversion-clearance-blocked) (:violations result)))))

  (testing "a proposal covertly requesting to finalize a cultivation-license renewal is a hard, permanent block"
    (let [store {:farm-lots {"lot-001" clean-poppy-lot}}
          req {:op :schedule-farm-operation :subject "lot-001"}
          prop {:cites [] :value {:finalize-cultivation-license-renewal? true} :confidence 0.99}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :cultivation-license-or-diversion-clearance-blocked) (:violations result)))))

  (testing "a proposal covertly requesting to finalize a diversion-control-compliance clearance is a hard, permanent block"
    (let [store {:farm-lots {"lot-001" clean-poppy-lot}}
          req {:op :log-harvest-record :subject "lot-001"}
          prop {:cites [{:spec "ISO-12345"}]
                :value {:jurisdiction :jp/maff :finalize-diversion-control-clearance? true}
                :confidence 0.99}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :cultivation-license-or-diversion-clearance-blocked) (:violations result))))))

;; ──────────────────────── Compliance Flag Violations ──────────────────────

(deftest compliance-flag-unresolved-violation-test
  (testing "an unresolved compliance flag triggers hard violation"
    (let [store {:farm-lots {"lot-001" (assoc clean-poppy-lot
                                                :compliance-concern-raised? true
                                                :compliance-concern-resolved? false)}}
          req {:op :log-harvest-record :subject "lot-001"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :compliance-flag-unresolved) (:violations result)))))

  (testing "a resolved compliance flag does not trigger this rule"
    (let [store {:farm-lots {"lot-002" (assoc clean-poppy-lot
                                                :compliance-concern-raised? true
                                                :compliance-concern-resolved? true)}}
          req {:op :log-harvest-record :subject "lot-002"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (not (some #(= (:rule %) :compliance-flag-unresolved) (:violations result)))))))

;; ──────────────────────── Escalation (Low Confidence) ──────────────────────

(deftest low-confidence-escalation-test
  (testing "low confidence proposal escalates even when hard checks pass"
    (let [store {:farm-lots {"lot-001" clean-poppy-lot}}
          req {:op :schedule-farm-operation :subject "lot-001"}
          prop {:cites [] :value {} :confidence 0.5}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:ok? result)))
      (is (true? (:escalate? result)))
      (is (false? (:hard? result))))))

;; ──────────────────────── High Stakes Escalation ──────────────────────

(deftest high-stakes-escalation-test
  (testing "log-harvest-record escalates even when all checks pass"
    (let [store {:farm-lots {"lot-001" clean-poppy-lot}}
          req {:op :log-harvest-record :subject "lot-001"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.95}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:ok? result)))
      (is (true? (:escalate? result)))
      (is (false? (:hard? result))))))

;; ──────────────────────── Compliance Concern Always Escalates ──────────────────────

(deftest compliance-concern-always-escalates-test
  (testing "a clean flag-compliance-concern proposal is never auto-ok"
    (let [store {:farm-lots {"lot-001" clean-poppy-lot}}
          req {:op :flag-compliance-concern :subject "lot-001"}
          prop {:cites [{:spec "Field-Report"}] :value {:jurisdiction :jp/maff} :confidence 0.99}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:ok? result)))
      (is (true? (:escalate? result)))
      (is (false? (:hard? result))))))

;; ──────────────────────── High-Cost Supply Order Escalation ──────────────────────

(deftest high-cost-supply-order-escalation-test
  (testing "a supply order above the cost threshold escalates even when clean"
    (let [store {:farm-lots {"lot-001" clean-poppy-lot}}
          req {:op :coordinate-supply-order :subject "lot-001"}
          prop {:cites [{:spec "Supplier-Catalog"}]
                :value {:jurisdiction :jp/maff :cost-usd 10000}
                :confidence 0.9}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:ok? result)))
      (is (true? (:escalate? result)))
      (is (false? (:hard? result)))))


;; ──────────── The threshold gate must not read the number it doubts ────────────

(deftest threshold-gate-fails-safe-when-the-value-is-unverifiable
  (testing "`(some-> v (> threshold))` returned nil when `cost-usd` was ABSENT,
            so a proposal carrying no figure at all skipped the gate entirely"
    (let [store {:farm-lots {"lot-001" clean-poppy-lot}}
          req {:op :coordinate-supply-order :subject "lot-001"}
          prop {:cites [{:spec "Supplier-Catalog"}]
                :value {:jurisdiction :jp/maff}
                :confidence 0.99}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:ok? result)))
      (is (true? (:escalate? result)))))

  (testing "a non-numeric figure escalates rather than being compared"
    (doseq [bad ["10000" :unknown {}]]
      (let [store {:farm-lots {"lot-001" clean-poppy-lot}}
            req {:op :coordinate-supply-order :subject "lot-001"}
            prop {:cites [{:spec "Supplier-Catalog"}]
                  :value {:jurisdiction :jp/maff :cost-usd bad}
                  :confidence 0.99}
            result (governor/check req {:actor-id "gov-1"} prop store)]
        (is (false? (:ok? result))
            (str "non-numeric " (pr-str bad) " must escalate, not slip through"))))))
  (testing "a supply order at or below the cost threshold does not force escalation"
    (let [store {:farm-lots {"lot-002" clean-poppy-lot}}
          req {:op :coordinate-supply-order :subject "lot-002"}
          prop {:cites [{:spec "Supplier-Catalog"}]
                :value {:jurisdiction :jp/maff :cost-usd 1000}
                :confidence 0.9}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:ok? result))))))

;; ──────────────────────── Already Logged Violation ──────────────────────

(deftest already-logged-violation-test
  (testing "farm-lot already logged triggers hard violation"
    (let [store {:farm-lots {"lot-001"
                              {:crop-category :pharma/licensed-opium-poppy
                               :logged? true}}}
          req {:op :log-harvest-record :subject "lot-001"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :already-logged) (:violations result))))))

;; ──────────────────────── Op-Not-Allowed Violation ──────────────────────

(deftest op-not-allowed-violation-test
  (testing "an out-of-allowlist op (e.g. finalizing a cultivation-license decision) is a hard, permanent block"
    (let [store {:farm-lots {"lot-001" clean-poppy-lot}}
          req {:op :finalize-cultivation-license-approval :subject "lot-001"}
          prop {:cites [{:spec "License-Authority-Manual"}] :value {:jurisdiction :jp/maff} :confidence 0.99}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :op-not-allowed) (:violations result))))))

;; ──────────────────────── Effect-Not-Propose Violation ──────────────────────

(deftest effect-not-propose-violation-test
  (testing "a proposal asserting a non-:propose effect is a hard, permanent block"
    (let [store {:farm-lots {"lot-001" clean-poppy-lot}}
          req {:op :schedule-farm-operation :subject "lot-001"}
          prop {:effect :commit :cites [] :value {} :confidence 0.9}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :effect-not-propose) (:violations result))))))

;; ──────── Regulatory-Compliance Scope Boundary (Domain-Specific) ────────

(deftest no-license-or-clearance-finalization-op-in-allowlist-test
  (testing "the closed allowlist never contains any license/clearance finalization op"
    (is (not (contains? governor/allowed-ops :finalize-cultivation-license-approval)))
    (is (not (contains? governor/allowed-ops :finalize-cultivation-license-renewal)))
    (is (not (contains? governor/allowed-ops :finalize-diversion-control-clearance)))
    (is (= 4 (count governor/allowed-ops)))))

(deftest flag-compliance-concern-never-in-any-phase-auto-set-test
  (testing "flag-compliance-concern is never eligible for auto-commit at any phase"
    (doseq [[phase ops] governor/phase-auto-ops]
      (is (not (contains? ops :flag-compliance-concern))
          (str phase " must never auto-commit flag-compliance-concern"))
      (is (false? (governor/auto-eligible-at-phase? phase :flag-compliance-concern))
          (str phase " auto-eligible? must be false for flag-compliance-concern"))))

  (testing "log-harvest-record is never eligible for auto-commit at any phase (high-stakes actuation)"
    (doseq [[phase ops] governor/phase-auto-ops]
      (is (not (contains? ops :log-harvest-record))
          (str phase " must never auto-commit log-harvest-record"))
      (is (false? (governor/auto-eligible-at-phase? phase :log-harvest-record))
          (str phase " auto-eligible? must be false for log-harvest-record")))))

;; ──────── Known Bug Class: Mock-Advisor Self-Trip Regression Guard ────────
;;
;; Multiple sibling actors in this codebase family have independently
;; discovered and fixed the SAME bug class: the Governor's own scope-
;; exclusion term list was sometimes phrased as a bare noun, which then
;; accidentally matched inside the mock advisor's own DEFAULT rationale/
;; disclaimer text for a legitimate, allowed proposal -- causing the
;; actor to self-block on its own happy path. This actor's
;; `:cultivation-license-or-diversion-clearance-blocked` rule is phrased
;; as the finalization ACTION (explicit `:finalize-*?` boolean `:value`
;; flags) and NEVER scans `:rationale` text -- this test asserts that
;; invariant holds for every one of the advisor's default proposals,
;; whose rationale text deliberately contains "license"/"clearance"/
;; "diversion" as ordinary, correct compliance disclaimer language.

(deftest default-mock-proposals-never-self-trip-scope-exclusion-test
  (testing "none of the advisor's default happy-path proposals trip the license/diversion-clearance block, despite rationale text mentioning those words"
    (let [store {:farm-lots {"lot-001" clean-poppy-lot}}]
      (doseq [[op proposal] advisor/default-mock-proposals]
        (let [request {:op op :subject "lot-001"}
              result (governor/check request {:actor-id "gov-1"} proposal store)]
          (is (not (some #(= (:rule %) :cultivation-license-or-diversion-clearance-blocked)
                         (:violations result)))
              (str op " must never self-trip the scope-exclusion check on its own default rationale text"))
          (is (not (some #(= (:rule %) :op-not-allowed) (:violations result)))
              (str op " must be within the closed allowlist"))))))

  (testing "every default proposal's op is a member of the closed allowlist"
    (doseq [[op _proposal] advisor/default-mock-proposals]
      (is (contains? governor/allowed-ops op)))))
