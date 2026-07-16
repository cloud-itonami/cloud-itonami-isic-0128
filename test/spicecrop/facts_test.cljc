(ns spicecrop.facts-test
  (:require [clojure.test :refer [deftest is testing]]
            [spicecrop.facts :as facts]))

;; ──────────────────────── Crop-Category Lookups ──────────────────────

(deftest crop-category-by-id-test
  (testing "licensed-opium-poppy crop category exists"
    (let [s (facts/crop-category-by-id :pharma/licensed-opium-poppy)]
      (is (some? s))
      (is (= (:id s) :pharma/licensed-opium-poppy))
      (is (true? (:controlled-substance-crop? s)))
      (is (true? (:cultivation-license-required? s)))))

  (testing "black-pepper crop category exists and has no license/quota spec"
    (let [s (facts/crop-category-by-id :spice/black-pepper)]
      (is (some? s))
      (is (false? (:controlled-substance-crop? s)))
      (is (false? (:cultivation-license-required? s)))
      (is (false? (:quota-tracking-required? s)))))

  (testing "vanilla crop category exists and has no license/quota spec"
    (let [s (facts/crop-category-by-id :spice/vanilla)]
      (is (some? s))
      (is (false? (:controlled-substance-crop? s)))))

  (testing "cinnamon crop category exists and has no license/quota spec"
    (let [s (facts/crop-category-by-id :aromatic/cinnamon)]
      (is (some? s))
      (is (false? (:controlled-substance-crop? s)))))

  (testing "peppermint crop category exists and has no license/quota spec"
    (let [s (facts/crop-category-by-id :aromatic/peppermint)]
      (is (some? s))
      (is (false? (:controlled-substance-crop? s)))))

  (testing "licensed-coca-leaf crop category exists and requires license/quota tracking"
    (let [s (facts/crop-category-by-id :pharma/licensed-coca-leaf)]
      (is (some? s))
      (is (true? (:controlled-substance-crop? s)))
      (is (true? (:quota-tracking-required? s)))))

  (testing "licensed-medicinal-cannabis crop category exists and requires license/quota tracking"
    (let [s (facts/crop-category-by-id :pharma/licensed-medicinal-cannabis)]
      (is (some? s))
      (is (true? (:controlled-substance-crop? s)))
      (is (true? (:quota-tracking-required? s)))))

  (testing "nonexistent crop category returns nil"
    (is (nil? (facts/crop-category-by-id :nonexistent/type)))))

;; ──────────────────────── Jurisdiction Lookups ──────────────────────

(deftest jurisdiction-by-id-test
  (testing "JP MAFF jurisdiction exists"
    (let [j (facts/jurisdiction-by-id :jp/maff)]
      (is (some? j))
      (is (contains? (set (:required-evidence j)) :cultivation-license-on-file))))

  (testing "US DEA/USDA jurisdiction exists"
    (let [j (facts/jurisdiction-by-id :us/dea-usda)]
      (is (some? j))
      (is (contains? (set (:required-evidence j)) :quota-reconciliation-log))))

  (testing "EU REG1307 jurisdiction exists"
    (let [j (facts/jurisdiction-by-id :eu/reg1307)]
      (is (some? j))
      (is (contains? (set (:required-evidence j)) :farm-registration-record))))

  (testing "nonexistent jurisdiction returns nil"
    (is (nil? (facts/jurisdiction-by-id :xx/unknown)))))

;; ──────────────────────── Compliance Predicates ──────────────────────

(deftest cultivation-license-current-test
  (let [poppy (facts/crop-category-by-id :pharma/licensed-opium-poppy)
        pepper (facts/crop-category-by-id :spice/black-pepper)]
    (testing "license expiring in the future is current"
      (is (true? (facts/cultivation-license-current? 2000 1000 poppy))))

    (testing "license expiring in the past is not current"
      (is (false? (facts/cultivation-license-current? 500 1000 poppy))))

    (testing "non-controlled crop category never needs a license"
      (is (false? (facts/cultivation-license-current? 2000 1000 pepper))))))

(deftest quota-tracking-current-test
  (let [poppy (facts/crop-category-by-id :pharma/licensed-opium-poppy)
        pepper (facts/crop-category-by-id :spice/black-pepper)
        now 1000000000
        ten-days-ago (- now (* 10 24 60 60 1000))
        hundred-days-ago (- now (* 100 24 60 60 1000))]
    (testing "recent reconciliation is current"
      (is (true? (facts/quota-tracking-current? ten-days-ago now poppy))))

    (testing "lapsed reconciliation is not current"
      (is (false? (facts/quota-tracking-current? hundred-days-ago now poppy))))

    (testing "non-controlled crop category never needs quota tracking"
      (is (false? (facts/quota-tracking-current? ten-days-ago now pepper))))))

(deftest harvest-within-quota-test
  (let [poppy (facts/crop-category-by-id :pharma/licensed-opium-poppy)
        pepper (facts/crop-category-by-id :spice/black-pepper)]
    (testing "reported harvest at or below licensed quota passes"
      (is (true? (facts/harvest-within-quota? 100 150 poppy)))
      (is (true? (facts/harvest-within-quota? 150 150 poppy))))

    (testing "reported harvest above licensed quota fails"
      (is (false? (facts/harvest-within-quota? 200 150 poppy))))

    (testing "non-controlled crop category has no licensed quota to satisfy"
      (is (false? (facts/harvest-within-quota? 100 150 pepper))))))

;; ──────────────────────── Evidence Completeness ──────────────────────

(deftest required-evidence-satisfied-test
  (testing "complete evidence checklist passes"
    (let [j (facts/jurisdiction-by-id :jp/maff)
          evidence [:farm-registration-record :field-boundary-map :harvest-record
                    :cultivation-license-on-file :quota-reconciliation-log]]
      (is (true? (facts/required-evidence-satisfied? j evidence)))))

  (testing "incomplete evidence fails"
    (let [j (facts/jurisdiction-by-id :jp/maff)
          evidence [:farm-registration-record :field-boundary-map]]
      (is (false? (facts/required-evidence-satisfied? j evidence)))))

  (testing "raw jurisdiction id call convention also works"
    (let [evidence [:farm-registration-record :field-boundary-map :harvest-record
                    :cultivation-license-on-file :quota-reconciliation-log]]
      (is (true? (facts/required-evidence-satisfied? :us/dea-usda evidence)))))

  (testing "unknown jurisdiction never satisfies"
    (is (false? (facts/required-evidence-satisfied? :xx/unknown [])))))
