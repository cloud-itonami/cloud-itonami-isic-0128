(ns spicecrop.store-test
  (:require [clojure.test :refer [deftest is testing]]
            [spicecrop.store :as store]))

;; ──────────────────────── Farm-Lot Retrieval ──────────────────────

(deftest farm-lot-test
  (testing "retrieve an existing farm-lot"
    (let [lot-data {:crop-category :pharma/licensed-opium-poppy :field-id "field-42"}
          st {:farm-lots {"lot-001" lot-data}}
          result (store/farm-lot st "lot-001")]
      (is (= result lot-data))))

  (testing "nonexistent farm-lot returns nil"
    (let [st {:farm-lots {}}
          result (store/farm-lot st "nonexistent")]
      (is (nil? result)))))

(deftest farm-lot-registered-test
  (testing "registered farm-lot returns true"
    (let [st {:farm-lots {"lot-001" {:field-id "field-42"}}}
          result (store/farm-lot-registered? st "lot-001")]
      (is (true? result))))

  (testing "unregistered farm-lot returns false"
    (let [st {:farm-lots {}}
          result (store/farm-lot-registered? st "lot-999")]
      (is (false? result)))))

;; ──────────────────────── Farm-Lot Status Checks ──────────────────────

(deftest farm-lot-already-logged-test
  (testing "logged farm-lot is detected"
    (let [st {:farm-lots {"lot-001" {:logged? true}}}
          result (store/farm-lot-already-logged? st "lot-001")]
      (is (true? result))))

  (testing "unlogged farm-lot returns false"
    (let [st {:farm-lots {"lot-001" {:logged? false}}}
          result (store/farm-lot-already-logged? st "lot-001")]
      (is (false? result))))

  (testing "nonexistent farm-lot returns false"
    (let [st {:farm-lots {}}
          result (store/farm-lot-already-logged? st "lot-001")]
      (is (false? result)))))

;; ──────────────────────── Farm-Lot Logging ──────────────────────

(deftest log-harvest-record-test
  (testing "logging a farm-lot marks it as logged"
    (let [st {:farm-lots {}}
          lot-data {:crop-category :pharma/licensed-opium-poppy}
          result (store/log-harvest-record st "lot-001" lot-data)]
      (is (true? (get-in result [:farm-lots "lot-001" :logged?])))))

  (testing "logging preserves farm-lot data"
    (let [st {:farm-lots {}}
          lot-data {:crop-category :pharma/licensed-opium-poppy :field-id "field-42"}
          result (store/log-harvest-record st "lot-001" lot-data)]
      (is (= (:crop-category (get-in result [:farm-lots "lot-001"])) :pharma/licensed-opium-poppy))
      (is (= (:field-id (get-in result [:farm-lots "lot-001"])) "field-42")))))

;; ──────────────────────── Farm-Lot Scheduling ──────────────────────

(deftest mark-scheduled-test
  (testing "marking a farm-lot marks it as scheduled"
    (let [st {:farm-lots {"lot-001" {:field-id "field-42"}}}
          result (store/mark-scheduled st "lot-001")]
      (is (true? (get-in result [:farm-lots "lot-001" :scheduled?]))))))

;; ──────────────────────── Audit Trail ──────────────────────

(deftest audit-trail-test
  (testing "audit trail is initially empty"
    (let [st {:facts []}
          result (store/audit-trail st)]
      (is (empty? result))))

  (testing "appended facts appear in audit trail"
    (let [st {:facts []}
          fact1 {:t :test-fact :detail "test 1"}
          fact2 {:t :test-fact :detail "test 2"}
          st' (store/append-fact st fact1)
          st'' (store/append-fact st' fact2)
          result (store/audit-trail st'')]
      (is (= (count result) 2))
      (is (= (first result) fact1))
      (is (= (second result) fact2)))))

(deftest append-fact-test
  (testing "appending a fact increases ledger length"
    (let [st {:facts []}
          fact {:t :governor-hold :op :log-harvest-record}
          result (store/append-fact st fact)]
      (is (= (count (:facts result)) 1))
      (is (= (first (:facts result)) fact)))))
