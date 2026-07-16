(ns spicecrop.operation-test
  (:require [clojure.test :refer [deftest is testing]]
            [spicecrop.operation :as operation]
            [spicecrop.governor :as governor]))

(def ^:private now-ms #?(:clj (System/currentTimeMillis) :cljs (.now js/Date)))
(def ^:private ten-days-ago (- now-ms (* 10 24 60 60 1000)))
(def ^:private ten-days-from-now (+ now-ms (* 10 24 60 60 1000)))

(def ^:private clean-poppy-lot
  {:crop-category :pharma/licensed-opium-poppy
   :jurisdiction :jp/maff
   :field-id "field-42"
   :cultivation-license-expiry-date ten-days-from-now
   :quota-last-reconciliation-date ten-days-ago
   :reported-harvest-kg 100
   :licensed-quota-kg 150
   :evidence-checklist [:farm-registration-record :field-boundary-map :harvest-record
                        :cultivation-license-on-file :quota-reconciliation-log]})

(deftest run-operation-commit-test
  (testing "clean, non-actuation proposal commits with no hold facts"
    (let [store {:farm-lots {"lot-001" clean-poppy-lot}}
          request {:op :schedule-farm-operation :subject "lot-001"}
          proposal {:cites [{:spec "Farm-Schedule"}]
                    :value {:jurisdiction :jp/maff}
                    :effect :propose
                    :confidence 0.9}
          context {:actor-id "op-1" :hold-fact-fn governor/hold-fact}
          result (operation/run-operation request context proposal store governor/check)]
      (is (true? (:ok? result)))
      (is (= [] (:facts result))))))

(deftest run-operation-hold-test
  (testing "hard-violating proposal (already-logged lot) produces a hold fact"
    (let [store {:farm-lots {"lot-002" {:crop-category :pharma/licensed-opium-poppy
                                         :logged? true}}}
          request {:op :log-harvest-record :subject "lot-002"}
          proposal {:cites [{:spec "Farm-Harvest-Log"}]
                    :value {:jurisdiction :jp/maff}
                    :effect :propose
                    :confidence 0.9}
          context {:actor-id "op-1" :hold-fact-fn governor/hold-fact}
          result (operation/run-operation request context proposal store governor/check)]
      (is (false? (:ok? result)))
      (is (= 1 (count (:facts result))))
      (is (= :governor-hold (:t (first (:facts result)))))
      (is (true? (:hard? (:verdict result)))))))

(deftest run-operation-escalate-test
  (testing "clean but high-stakes proposal is not auto-ok (escalation required)"
    (let [store {:farm-lots {"lot-003" clean-poppy-lot}}
          request {:op :log-harvest-record :subject "lot-003"}
          proposal {:cites [{:spec "Farm-Harvest-Log"}]
                    :value {:jurisdiction :jp/maff}
                    :effect :propose
                    :confidence 0.95}
          context {:actor-id "op-1" :hold-fact-fn governor/hold-fact}
          result (operation/run-operation request context proposal store governor/check)]
      (is (false? (:ok? result)))
      (is (false? (:hard? (:verdict result))))
      (is (true? (:escalate? (:verdict result))))
      ;; operation.cljc has a single :ok?/not-ok? gate today; both hard-hold
      ;; and escalate-only verdicts route through the same hold-fact-fn.
      ;; Callers distinguish the two by inspecting `(:verdict result)`.
      (is (= 1 (count (:facts result)))))))

(deftest run-operation-compliance-concern-always-escalates-test
  (testing "a clean flag-compliance-concern proposal is never auto-ok"
    (let [store {:farm-lots {"lot-004" clean-poppy-lot}}
          request {:op :flag-compliance-concern :subject "lot-004"}
          proposal {:cites [{:spec "Field-Report"}]
                    :value {:jurisdiction :jp/maff}
                    :effect :propose
                    :confidence 0.99}
          context {:actor-id "op-1" :hold-fact-fn governor/hold-fact}
          result (operation/run-operation request context proposal store governor/check)]
      (is (false? (:ok? result)))
      (is (false? (:hard? (:verdict result))))
      (is (true? (:escalate? (:verdict result)))))))

(deftest run-operation-op-not-allowed-test
  (testing "an out-of-allowlist op (e.g. finalizing a cultivation-license decision) is a hard, permanent block"
    (let [store {:farm-lots {"lot-005" clean-poppy-lot}}
          request {:op :finalize-cultivation-license-approval :subject "lot-005"}
          proposal {:cites [{:spec "License-Authority-Manual"}]
                    :value {:jurisdiction :jp/maff}
                    :effect :propose
                    :confidence 0.99}
          context {:actor-id "op-1" :hold-fact-fn governor/hold-fact}
          result (operation/run-operation request context proposal store governor/check)]
      (is (false? (:ok? result)))
      (is (true? (:hard? (:verdict result))))
      (is (some #(= (:rule %) :op-not-allowed) (:violations (:verdict result)))))))

(deftest run-operation-effect-not-propose-test
  (testing "a proposal asserting a non-:propose effect is a hard, permanent block"
    (let [store {:farm-lots {"lot-006" clean-poppy-lot}}
          request {:op :schedule-farm-operation :subject "lot-006"}
          proposal {:cites [{:spec "Farm-Schedule"}]
                    :value {:jurisdiction :jp/maff}
                    :effect :commit
                    :confidence 0.9}
          context {:actor-id "op-1" :hold-fact-fn governor/hold-fact}
          result (operation/run-operation request context proposal store governor/check)]
      (is (false? (:ok? result)))
      (is (true? (:hard? (:verdict result))))
      (is (some #(= (:rule %) :effect-not-propose) (:violations (:verdict result)))))))

(deftest run-operation-farm-lot-not-registered-test
  (testing "any op against a never-registered farm-lot is a hard block"
    (let [store {:farm-lots {}}
          request {:op :schedule-farm-operation :subject "lot-999"}
          proposal {:cites []
                    :value {}
                    :effect :propose
                    :confidence 0.9}
          context {:actor-id "op-1" :hold-fact-fn governor/hold-fact}
          result (operation/run-operation request context proposal store governor/check)]
      (is (false? (:ok? result)))
      (is (true? (:hard? (:verdict result))))
      (is (some #(= (:rule %) :farm-lot-not-registered) (:violations (:verdict result)))))))

(deftest run-operation-high-cost-supply-order-escalates-test
  (testing "a supply order above the cost threshold is not auto-ok"
    (let [store {:farm-lots {"lot-007" clean-poppy-lot}}
          request {:op :coordinate-supply-order :subject "lot-007"}
          proposal {:cites [{:spec "Supplier-Catalog"}]
                    :value {:jurisdiction :jp/maff :cost-usd 10000}
                    :effect :propose
                    :confidence 0.9}
          context {:actor-id "op-1" :hold-fact-fn governor/hold-fact}
          result (operation/run-operation request context proposal store governor/check)]
      (is (false? (:ok? result)))
      (is (false? (:hard? (:verdict result))))
      (is (true? (:escalate? (:verdict result)))))))

(deftest run-operation-low-cost-supply-order-commits-test
  (testing "a supply order at or below the cost threshold commits when clean"
    (let [store {:farm-lots {"lot-008" clean-poppy-lot}}
          request {:op :coordinate-supply-order :subject "lot-008"}
          proposal {:cites [{:spec "Supplier-Catalog"}]
                    :value {:jurisdiction :jp/maff :cost-usd 1000}
                    :effect :propose
                    :confidence 0.9}
          context {:actor-id "op-1" :hold-fact-fn governor/hold-fact}
          result (operation/run-operation request context proposal store governor/check)]
      (is (true? (:ok? result)))
      (is (= [] (:facts result))))))
