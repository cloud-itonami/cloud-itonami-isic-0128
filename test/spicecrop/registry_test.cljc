(ns spicecrop.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [spicecrop.registry :as registry]))

;; ──────────────────────── Cultivation License ──────────────────────

(deftest cultivation-license-expired-test
  (testing "expiry in the future returns false (no violation)"
    (is (false? (registry/cultivation-license-expired? 2000 1000))))

  (testing "expiry exactly now returns false"
    (is (false? (registry/cultivation-license-expired? 1000 1000))))

  (testing "expiry in the past returns true (violation)"
    (is (true? (registry/cultivation-license-expired? 500 1000)))))

;; ──────────────────────── Quota Tracking ──────────────────────

(deftest quota-tracking-lapsed-test
  (testing "recent reconciliation returns false (no violation)"
    (let [now 1000000000
          ten-days-ago (- now (* 10 24 60 60 1000))]
      (is (false? (registry/quota-tracking-lapsed? ten-days-ago now)))))

  (testing "lapsed reconciliation returns true (violation)"
    (let [now 1000000000
          hundred-days-ago (- now (* 100 24 60 60 1000))]
      (is (true? (registry/quota-tracking-lapsed? hundred-days-ago now))))))

;; ──────────────────────── Harvest Quota ──────────────────────

(deftest harvest-quota-exceeded-test
  (testing "harvest at the licensed quota returns false (no violation)"
    (is (false? (registry/harvest-quota-exceeded? 150 150))))

  (testing "harvest below the licensed quota returns false"
    (is (false? (registry/harvest-quota-exceeded? 100 150))))

  (testing "harvest above the licensed quota returns true (violation)"
    (is (true? (registry/harvest-quota-exceeded? 200 150)))))
