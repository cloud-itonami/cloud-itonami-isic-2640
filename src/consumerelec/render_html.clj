(ns consumerelec.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave5 rollout ledger seq 6): this repo previously had NO demo page and
  no generator at all. This namespace drives the REAL actor stack
  (`consumerelec.operation` -> `consumerelec.governor` -> `consumerelec.store`)
  through a scenario adapted from this repo's own `consumerelec.sim` demo
  driver (`clojure -M:dev:run`, confirmed to run correctly against the
  real seeded batch/equipment directory before this file was written --
  cross-checked against `consumerelec.store/sample-data!`'s own ids
  (batch-001/002/003, smt-001, final-test-002): unlike
  `cloud-itonami-isic-851`'s `schoolops.sim`, this repo's own sim driver
  uses ids that DO match its own store's seed data, so it was safe to
  reuse rather than author from scratch), trimmed to a representative
  subset (one full commit->escalate->approve lifecycle plus three
  distinct HARD-hold reasons) and rendered deterministically -- no
  invented numbers, no timestamps in the page content, byte-identical
  across reruns against the same seed (verified by diffing two
  consecutive runs).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [consumerelec.store :as store]
            [consumerelec.operation :as op]
            [langgraph.graph :as g]))

;; ----------------------------- harness (unchanged across every repo
;; in this cluster -- do not rewrite, only copy) -----------------------

(def ^:private operator
  {:actor-id "op-1" :actor-role :plant-coordinator :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach: batch-001 clears a clean production-batch-intake
  patch (auto-commit at phase 3, no physical/financial risk), a
  maintenance window against smt-001 (verified+registered SMT
  placement-line -- ALWAYS escalates, approved), a safety-concern flag
  against smt-001 (ALWAYS escalates regardless of confidence -- approved),
  and a shipment coordinated against batch-001 within its remaining
  quantity headroom (ALWAYS escalates -- approved); then three DISTINCT
  real HARD-hold reasons that never reach a human: a maintenance window
  proposed against final-test-002 (an UNVERIFIED/unregistered test
  bench -- `:equipment-not-verified`), a shipment coordinated against
  batch-003 (an UNVERIFIED/unregistered batch -- `:batch-not-verified`),
  and a shipment against batch-002 whose own claimed 100 units plus its
  already-logged 180 shipped units would exceed its own recorded 200-unit
  production quantity (`:shipment-quantity-exceeded`). Returns the
  resulting store -- every field read by `render` below is real
  governor/store output, not a hand-typed copy."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]
    (exec! actor "b1-intake"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:product-type :television :last-assessed "2026-07-14"}})

    (exec! actor "mnt-1-schedule"
           {:op :schedule-maintenance :effect :propose :subject "mnt-1"
            :value {:equipment-id "smt-001" :maintenance-type :nozzle-inspection
                    :scheduled-date "2026-08-01" :actuate-equipment? false}})
    (approve! actor "mnt-1-schedule")

    (exec! actor "concern-1-flag"
           {:op :flag-safety-concern :effect :propose :subject "concern-1"
            :value {:equipment-id "smt-001" :severity :moderate
                    :description "リフロー炉の温度異常兆候、電池パック搭載ラインの発熱懸念"}})
    (approve! actor "concern-1-flag")

    (exec! actor "ship-1-coordinate"
           {:op :coordinate-shipment :effect :propose :subject "ship-1"
            :value {:batch-id "batch-001" :units 50.0
                    :destination "buyer-warehouse-north"}})
    (approve! actor "ship-1-coordinate")

    (exec! actor "mnt-2-schedule"
           {:op :schedule-maintenance :effect :propose :subject "mnt-2"
            :value {:equipment-id "final-test-002" :maintenance-type :calibration
                    :scheduled-date "2026-08-01" :actuate-equipment? false}})

    (exec! actor "ship-2-coordinate"
           {:op :coordinate-shipment :effect :propose :subject "ship-2"
            :value {:batch-id "batch-003" :units 100.0
                    :destination "buyer-warehouse-south"}})

    (exec! actor "ship-3-coordinate"
           {:op :coordinate-shipment :effect :propose :subject "ship-3"
            :value {:batch-id "batch-002" :units 100.0
                    :destination "buyer-warehouse-east"}})
    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger subject-id]
  (last (filter #(= (:subject %) subject-id) ledger)))

(defn- status-cell [ledger subject-id]
  (let [f (last-fact-for ledger subject-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :governor-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- batch-row [ledger {:keys [id product-type model quantity-units defect-rate-percent
                                  verified? registered? shipped-units]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%.1f</td><td>%.1f</td><td>%.1f%%</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc (name (or product-type :n-a))) (esc model)
          (double (or quantity-units 0.0)) (double (or shipped-units 0.0))
          (double (or defect-rate-percent 0.0))
          (if (and verified? registered?) "<span class=\"ok\">verified &amp; registered</span>"
              "<span class=\"critical\">unverified/unregistered</span>")
          (status-cell ledger id)))

(defn- equipment-row [ledger {:keys [id kind verified? registered?]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc (name (or kind :n-a)))
          (if (and verified? registered?) "<span class=\"ok\">verified &amp; registered</span>"
              "<span class=\"critical\">unverified/unregistered</span>")
          (status-cell ledger id)))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc subject)
          (esc (or (some->> basis (map name) (str/join ", ")) (some-> disposition name) ""))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract (README `Ops`
  ;; table, `consumerelec.governor`/`consumerelec.phase`) -- documentation
  ;; of fixed behavior, not runtime telemetry, so it is legitimately
  ;; hand-described rather than derived from a live run.
  ["        <tr><td><code>:log-production-batch</code></td><td><span class=\"ok\">auto-commit when clean, phase 3, no physical/financial risk</span></td></tr>"
   "        <tr><td><code>:schedule-maintenance</code></td><td><span class=\"warn\">ALWAYS human approval &middot; never auto-eligible at any phase &middot; equipment verified/registered independently re-checked &middot; actuate-equipment? permanently blocked</span></td></tr>"
   "        <tr><td><code>:flag-safety-concern</code></td><td><span class=\"warn\">ALWAYS human approval &middot; high-stakes gate, never auto-commits regardless of confidence</span></td></tr>"
   "        <tr><td><code>:coordinate-shipment</code></td><td><span class=\"warn\">ALWAYS human approval &middot; batch verified/registered independently re-checked &middot; shipped+claimed quantity independently recomputed against logged production quantity</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        batches (->> (store/all-batches db) (sort-by :id))
        equipment (->> (store/all-equipment db) (sort-by :id))
        batch-rows (str/join "\n" (map (partial batch-row ledger) batches))
        equipment-rows (str/join "\n" (map (partial equipment-row ledger) equipment))
        ledger-rows (str/join "\n" (map ledger-row ledger))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-2640 &middot; consumer-electronics plant operations</title><style>\n"
     "table { width: 100%; border-collapse: collapse; font-size: 14px; }\n"
     ".ok { color: #137a3f; }\n"
     "body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }\n"
     "header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }\n"
     "th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }\n"
     "h2 { margin-top: 0; font-size: 15px; }\n"
     ".warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }\n"
     "main { max-width: 980px; margin: 24px auto; padding: 0 20px; }\n"
     "header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }\n"
     ".muted { color: #888; font-size: 13px; }\n"
     ".critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }\n"
     ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n"
     ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }\n"
     "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }\n"
     "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }\n"
     "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }\n"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Consumer-electronics plant operations (ISIC 2640) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample &middot; governor-gated &middot; equipment actuation and safety-certification issuance never performed</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Production batches</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>consumerelec.store</code> via <code>consumerelec.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Batch</th><th>Product type</th><th>Model</th><th>Quantity (units)</th><th>Shipped (units)</th><th>Defect rate</th><th>Status</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     batch-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>SMT/assembly/test-bench equipment</h2>\n"
     "    <table>\n"
     "      <thead><tr><th>Equipment</th><th>Kind</th><th>Status</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     equipment-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Consumer Electronics Plant Operations Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. Equipment/batch verified/registered status and shipment quantity are independently recomputed, never trusted from the proposal.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal, hold and commit this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/maintenance-history db)) "maintenance schedules,"
             (count (store/shipment-history db)) "shipments )")))
