(ns agmachmfg.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300)
  for `cloud-itonami-isic-2821`: this repo had NO demo page and no
  generator at all. This namespace drives the REAL actor stack
  (`agmachmfg.advisor` -> `agmachmfg.operation` -> `agmachmfg.governor`
  -> `agmachmfg.phase` -> `agmachmfg.store`, compiled as a langgraph-clj
  StateGraph and executed with `langgraph.graph/run*`) and renders the
  page from the store the run actually wrote.

  ## No invented values

  Every row on the page is either (a) read back out of the real
  `agmachmfg.store` MemStore after the run, (b) a fact the real graph
  appended to the append-only ledger, or (c) derived from a real
  namespace's own data (`agmachmfg.phase/phases`,
  `agmachmfg.governor/allowed-ops`). Nothing is typed in by hand. Where
  a value the page would like to show does not actually exist in the
  store, the page says so instead of inventing it: `approver-attribution`
  re-checks, AT RENDER TIME, whether the human approver's id actually
  reached the SSoT or the ledger, by walking every register the `Store`
  protocol exposes and asking whether an approver-shaped key is present.
  It does not assert a conclusion in prose -- a prose claim about the
  store's behaviour becomes a lie the moment the store is fixed.

  ## Why this scenario

  It walks a clean plant-operations episode (log a production batch ->
  schedule an assembly-line maintenance window -> flag a safety concern
  -> coordinate an outbound shipment), shows a human DECLINING a second
  shipment, and then exercises every HARD check the Agricultural and
  Forestry Machinery Plant Operations Governor implements, none of which
  ever reaches a human:

    1. `:not-propose-effect`             -- a caller whose own request
                                            declares `:effect :direct-write`
    2. `:unknown-op` (+ 3)               -- an op outside the closed four-op
                                            allowlist. The advisor's fallback
                                            proposal is `:effect :noop`, so
                                            check 3 fires in the same hold.
    3. `:equipment-control-blocked`      -- shown ALONE via a deliberately
                                            ROGUE advisor injected over the
                                            SAME store through the seam
                                            `operation/build` already exposes.
                                            The shipped mock advisor can never
                                            emit a non-allowlisted effect, so
                                            injection is the only honest way to
                                            demonstrate this defense-in-depth.
    4. `:equipment-actuate-blocked`      -- `:actuate-equipment? true`
    5. `:certification-authority-blocked`-- `:issue-certification? true`
    6. `:equipment-not-verified`         -- `harv-002`, unverified/unregistered
    7. `:already-scheduled`              -- the same maintenance window twice
    8. `:batch-not-verified`             -- `batch-003`, unverified/unregistered
    9. `:shipment-quantity-exceeded`     -- twice, both branches: a claim that
                                            overruns `batch-002`'s own logged
                                            production quantity, AND a shipment
                                            stating no amount at all (un-checkable
                                            headroom is not headroom)
   10. `:invalid-product-type`           -- a fabricated product type
   11. `:invalid-pto-no-load-speed`      -- an implausible PTO no-load reading
   12. `:invalid-defect-rate`            -- an implausible defect-rate reading

  ## Determinism

  Nothing in the path reads a clock or an RNG: the mock advisor is a
  `case` over the request, the registry's record numbers are zero-padded
  sequences off the store's own counters, and the store is a plain atom.
  Every collection iterated for the page is sorted (`sort-by :id`, sorted
  key walks) so map iteration order cannot leak into the bytes. Two
  consecutive runs are byte-identical; verify with
  `clojure -M:dev:render-html <a> && clojure -M:dev:render-html <b> && cmp <a> <b>`.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [agmachmfg.advisor :as advisor]
            [agmachmfg.governor :as governor]
            [agmachmfg.operation :as op]
            [agmachmfg.phase :as phase]
            [agmachmfg.store :as store]))

(def ^:private coordinator
  "The operator context every request in this scenario runs under --
  the same shape `agmachmfg.sim` uses."
  {:actor-id "coord-1" :actor-role :plant-coordinator :phase 3})

;; ----------------------------- the real run -----------------------------

(defn- record!
  "Append one real graph result to the ordered run log."
  [runs tid request result]
  (swap! runs conj {:tid tid
                    :request request
                    :audit (vec (get-in result [:state :audit]))
                    :disposition (get-in result [:state :disposition])})
  result)

(defn- exec!
  "One operation with no human in the loop (auto-commit or HARD hold)."
  [runs actor tid request]
  (record! runs tid request
           (g/run* actor {:request request :context coordinator} {:thread-id tid})))

(defn- resume!
  "One operation the governor/phase gate escalates, then resumed by a
  human decision. `:audit`'s reducer is `into` and the checkpointer
  restores the accumulated channel, so only the resumed result is
  recorded -- it already carries the whole episode."
  [runs actor tid request decision]
  (g/run* actor {:request request :context coordinator} {:thread-id tid})
  (record! runs tid request
           (g/run* actor {:approval decision} {:thread-id tid :resume? true})))

(def ^:private rogue-advisor
  "A deliberately MALFUNCTIONING advisor: it claims an
  `:tractor/actuate` effect, which the shipped mock advisor can never
  emit. Injected over the SAME store to prove
  `:equipment-control-blocked` fires on its own -- a compromised or
  broken advisor gains nothing by lying about what would commit."
  (reify advisor/Advisor
    (-advise [_ _ request]
      {:summary    (str (:subject request) ": ROGUE advisor claiming a direct assembly-line actuation")
       :rationale  "この助言者は許可リスト外の :effect を返す(本来あり得ない)"
       :cites      [:id]
       :effect     :tractor/actuate
       :value      {:id (:subject request)}
       :stake      nil
       :confidence 0.99})))

(defn run-demo!
  "Runs a fresh seeded store through the scenario described in the ns
  docstring. Returns `{:db :runs}` -- `:runs` is the ordered log of real
  graph results, `:db` the real store the actor wrote."
  []
  (let [db     (-> (store/mem-store) (store/sample-data!))
        actor  (op/build db)
        ;; same store, only the advisor is swapped
        broken (op/build db {:advisor rogue-advisor})
        runs   (atom [])]

    ;; --- clean plant-operations episode -------------------------------
    ;; phase 3 auto-commits a governor-clean production-batch log: it is
    ;; the ONLY op in any phase's :auto set.
    (exec! runs actor "t01"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:product-type :tractor :pto-no-load-speed-rpm 540.0
                    :defect-rate-percent 1.1 :last-assessed "2026-08-13"}})
    ;; :schedule-maintenance is never auto-eligible at ANY phase.
    (resume! runs actor "t02"
             {:op :schedule-maintenance :effect :propose :subject "mnt-1"
              :value {:equipment-id "trac-001" :maintenance-type :pto-guard-inspection
                      :scheduled-date "2026-08-20" :actuate-equipment? false}}
             {:status :approved :by "coord-1"})
    ;; a safety concern is ALWAYS high-stakes -- two independent layers agree.
    (resume! runs actor "t03"
             {:op :flag-safety-concern :effect :propose :subject "concern-1"
              :value {:equipment-id "trac-001" :severity :moderate
                      :description "PTOシャフトガードの緩み、油圧ホースの摩耗"}}
             {:status :approved :by "coord-1"})
    (resume! runs actor "t04"
             {:op :coordinate-shipment :effect :propose :subject "ship-1"
              :value {:batch-id "batch-001" :units 50.0
                      :destination "dealer-yard-north"}}
             {:status :approved :by "coord-1"})
    ;; the human is a real decision point, not a rubber stamp.
    (resume! runs actor "t05"
             {:op :coordinate-shipment :effect :propose :subject "ship-4"
              :value {:batch-id "batch-001" :units 400.0
                      :destination "dealer-yard-west"}}
             {:status :rejected :by "coord-1"})

    ;; --- every HARD check, none of which ever reaches a human ---------
    (exec! runs actor "t06"
           {:op :log-production-batch :effect :direct-write :subject "batch-001"
            :patch {:product-type :tractor}})
    (exec! runs actor "t07"
           {:op :actuate-assembly-line :effect :propose :subject "trac-001"})
    (exec! runs broken "t08"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:product-type :tractor}})
    (exec! runs actor "t09"
           {:op :schedule-maintenance :effect :propose :subject "mnt-3"
            :value {:equipment-id "trac-001" :maintenance-type :force-run
                    :scheduled-date "2026-09-01" :actuate-equipment? true}})
    (exec! runs actor "t10"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:issue-certification? true}})
    (exec! runs actor "t11"
           {:op :schedule-maintenance :effect :propose :subject "mnt-2"
            :value {:equipment-id "harv-002" :maintenance-type :calibration
                    :scheduled-date "2026-08-25" :actuate-equipment? false}})
    (exec! runs actor "t12"
           {:op :schedule-maintenance :effect :propose :subject "mnt-1"
            :value {:equipment-id "trac-001" :maintenance-type :pto-guard-inspection
                    :scheduled-date "2026-08-20" :actuate-equipment? false}})
    (exec! runs actor "t13"
           {:op :coordinate-shipment :effect :propose :subject "ship-2"
            :value {:batch-id "batch-003" :units 100.0
                    :destination "dealer-yard-south"}})
    (exec! runs actor "t14"
           {:op :coordinate-shipment :effect :propose :subject "ship-3"
            :value {:batch-id "batch-002" :units 100.0
                    :destination "dealer-yard-east"}})
    (exec! runs actor "t15"
           {:op :coordinate-shipment :effect :propose :subject "ship-5"
            :value {:batch-id "batch-001" :destination "dealer-yard-central"}})
    (exec! runs actor "t16"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:product-type :unobtainium}})
    (exec! runs actor "t17"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:pto-no-load-speed-rpm 999999.0}})
    (exec! runs actor "t18"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:defect-rate-percent 999.0}})

    {:db db :runs @runs}))

;; ----------------------------- rendering helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw-str [v] (if (keyword? v) (name v) (str v)))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- num [v] (str "<span class=\"num\">" (esc v) "</span>"))

(defn- dash [] "<span class=\"muted\">&mdash;</span>")

(defn- yes-no [v]
  (if (true? v)
    "<span class=\"ok\">yes</span>"
    "<span class=\"critical\">no</span>"))

(defn- fact-of [audit t] (first (filter #(= t (:t %)) audit)))

(defn- facts-of [audit t] (filter #(= t (:t %)) audit))

(defn- tr [& cells] (str "        <tr>" (apply str (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- rows [xs] (if (seq xs) (str/join "\n" xs) "        <tr><td colspan=\"9\"><span class=\"muted\">この実行では該当なし</span></td></tr>"))

(defn- deep-key-names
  "Every key name appearing anywhere in a nested structure, as strings.
  Used to ask the SSoT what it actually holds instead of assuming."
  [x]
  (cond
    (map? x) (into (into #{} (map kw-str) (keys x))
                   (mapcat deep-key-names (vals x)))
    (sequential? x) (into #{} (mapcat deep-key-names x))
    (set? x) (into #{} (mapcat deep-key-names x))
    :else #{}))

;; ----------------------------- derived sections -----------------------------

(defn- holds
  "The HARD `:governor-hold` facts the run actually appended to the
  store's append-only ledger."
  [db]
  (filterv #(= :governor-hold (:t %)) (store/ledger db)))

(defn- shipment-subjects
  "Every `:coordinate-shipment` subject this run actually attempted --
  the store protocol has no `all-shipments`, so the ids come from the
  real requests rather than from a hand-typed list."
  [runs]
  (->> runs
       (filter #(= :coordinate-shipment (:op (:request %))))
       (map #(:subject (:request %)))
       distinct
       sort))

(defn- approver-attribution
  "DERIVED, at render time, honest disclosure about where the human
  approver's id actually ends up.

  `operation`'s `:request-approval` node attaches the approver on the
  commit record's `:payload`. Whether that survives into the SSoT is a
  property of THIS repo's `store/commit-record!`, and asserting the
  answer in prose would go stale the day it changes. So this walks every
  register the `Store` protocol exposes, collects every key name that
  actually exists anywhere in them, and reports whether an
  approver-shaped key is among them -- plus whether the ledger carries
  an `:approval-granted` fact at all. The approvers themselves come from
  the run's real audit channel."
  [db runs]
  (let [registers (concat (store/all-batches db)
                          (store/all-equipment db)
                          (store/all-maintenance db)
                          (keep #(store/shipment db %) (shipment-subjects runs))
                          (store/safety-concerns db)
                          (map second (sort-by key (store/get-records db)))
                          (store/maintenance-history db)
                          (store/shipment-history db))
        names     (deep-key-names registers)
        approver? #(contains? #{"approved-by" "approved_by" "approver"
                                "approved-by-id" "approved_by_id"}
                              (str/lower-case %))
        granted   (keep #(fact-of (:audit %) :approval-granted) runs)]
    {:approvers   (vec (sort (into #{} (keep :by) granted)))
     :approvals   (count granted)
     :on-record?  (boolean (some approver? names))
     :on-ledger?  (boolean (some #(= :approval-granted (:t %)) (store/ledger db)))
     :register-keys (count names)}))

;; ----------------------------- tables -----------------------------

(defn- batch-rows [db]
  (for [b (store/all-batches db)]
    (tr (code (:id b))
        (esc (kw-str (:product-type b)))
        (esc (:model b))
        (num (:pto-no-load-speed-rpm b))
        (num (:quantity-units b))
        (num (:defect-rate-percent b))
        (yes-no (:verified? b))
        (yes-no (:registered? b))
        (num (:shipped-units b)))))

(defn- equipment-rows [db]
  (for [e (store/all-equipment db)]
    (tr (code (:id e))
        (esc (kw-str (:kind e)))
        (yes-no (:verified? e))
        (yes-no (:registered? e))
        (if (:last-maintenance-date e) (esc (:last-maintenance-date e)) (dash))
        (if (:last-scheduled-maintenance-date e)
          (esc (:last-scheduled-maintenance-date e))
          (dash)))))

(defn- phase-cell [ph-n op]
  (let [{:keys [writes auto]} (get phase/phases ph-n)]
    (cond
      (contains? auto op)   "<span class=\"ok\">auto-commit</span>"
      (contains? writes op) "<span class=\"warn\">human approval</span>"
      :else                 "<span class=\"muted\">no write</span>")))

(defn- phase-gate-rows []
  ;; Derived wholly from `agmachmfg.phase/phases` +
  ;; `agmachmfg.governor/allowed-ops`. Adding a phase or moving an op
  ;; into an `:auto` set changes this table with no edit here.
  (let [phase-ns (sort (keys phase/phases))]
    (for [op (sort governor/allowed-ops)]
      (apply tr (code op) (map #(phase-cell % op) phase-ns)))))

(defn- outcome-cell [{:keys [audit disposition]}]
  (let [hold (fact-of audit :governor-hold)
        rej  (fact-of audit :approval-rejected)
        appr (fact-of audit :approval-granted)
        comm (fact-of audit :committed)]
    (cond
      hold (str "<span class=\"critical\">HARD hold &middot; "
                (str/join ", " (map #(esc (kw-str (:rule %))) (:violations hold)))
                "</span>")
      rej  (str "<span class=\"warn\">human declined &middot; "
                (esc (kw-str (or (-> rej :violations first :rule) :approver-rejected)))
                "</span>")
      (and appr comm) (str "<span class=\"ok\">approved by " (esc (:by appr))
                           " &rarr; committed</span>")
      comm "<span class=\"ok\">auto-committed (phase-3)</span>"
      :else (str "<span class=\"muted\">" (esc (kw-str (or disposition "in progress"))) "</span>"))))

(defn- request-rows [runs]
  (for [{:keys [tid request audit] :as r} runs]
    (tr (code tid)
        (code (:op request))
        (code (:effect request))
        (esc (:subject request))
        (if (fact-of audit :approval-requested)
          "<span class=\"warn\">yes</span>"
          "<span class=\"muted\">no</span>")
        (outcome-cell r))))

(defn- hold-rows [db]
  (for [f (holds db)
        v (:violations f)]
    (tr (code (:rule v))
        (code (:op f))
        (esc (:subject f))
        (esc (:detail v)))))

(defn- maintenance-rows [db]
  (for [m (store/all-maintenance db)]
    (tr (code (:id m))
        (code (:equipment-id m))
        (esc (kw-str (:maintenance-type m)))
        (esc (:scheduled-date m))
        (yes-no (:scheduled? m))
        (if (:maintenance-number m) (code (:maintenance-number m)) (dash)))))

(defn- shipment-rows [db runs]
  (for [id (shipment-subjects runs)
        :let [s (store/shipment db id)]
        :when s]
    (tr (code (:id s))
        (code (:batch-id s))
        (num (:units s))
        (esc (:destination s))
        (if (:shipment-number s) (code (:shipment-number s)) (dash)))))

(defn- draft-rows [db]
  (for [r (concat (store/maintenance-history db) (store/shipment-history db))]
    (tr (code (get r "record_id"))
        (esc (get r "kind"))
        (esc (or (get r "maintenance_id") (get r "shipment_id")))
        (esc (or (get r "equipment_id") (dash)))
        (yes-no (get r "immutable")))))

(defn- concern-rows [db]
  (for [c (store/safety-concerns db)]
    (tr (code (:id c))
        (code (:equipment-id c))
        (esc (kw-str (:severity c)))
        (esc (:description c)))))

(defn- ledger-rows [db]
  (for [f (store/ledger db)]
    (tr (code (:t f))
        (code (:op f))
        (esc (:subject f))
        (esc (kw-str (:disposition f)))
        (if-let [b (seq (:basis f))]
          (str/join ", " (map #(code (kw-str %)) b))
          (dash))
        (if (:summary f) (esc (:summary f)) (dash)))))

;; ----------------------------- the page -----------------------------

(defn- section [title lead headers body-rows]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       "    <p class=\"muted\">" lead "</p>\n"
       "    <table>\n"
       "      <thead><tr>" (apply str (map #(str "<th>" % "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n"
       (rows body-rows) "\n"
       "      </tbody>\n"
       "    </table>\n"
       "  </section>\n"))

(defn- approver-section [{:keys [approvers approvals on-record? on-ledger? register-keys]}]
  (let [where (cond
                (and on-record? on-ledger?) "<span class=\"ok\">SSoT レコードと台帳の両方に保持</span>"
                on-record? "<span class=\"ok\">SSoT レコードに保持</span>"
                on-ledger? "<span class=\"ok\">監査台帳に保持</span>"
                :else "<span class=\"critical\">どちらにも保持されていない &mdash; (audit only &mdash; not retained in the store record)</span>")]
    (str "  <section class=\"card\">\n"
         "    <h2>Approver attribution (measured at render time)</h2>\n"
         "    <p class=\"muted\">この節は散文の主張ではなく、生成時に実際の store を歩いた結果です。"
         "`Store` プロトコルが公開する全レジスタ (" register-keys " 個の異なるキー名) を走査し、"
         "承認者を表すキーが実在するかを確認しています。store 側が修正されればこの表示も自動で変わります。</p>\n"
         "    <table>\n"
         "      <thead><tr><th>Observed</th><th>Value</th></tr></thead>\n"
         "      <tbody>\n"
         (rows [(tr "この実行で人間が承認した回数" (num approvals))
                (tr "監査チャネルが記録した承認者"
                    (if (seq approvers)
                      (str/join ", " (map code approvers))
                      (dash)))
                (tr "承認者キーが SSoT レコードに存在するか" (yes-no on-record?))
                (tr "<code>:approval-granted</code> が監査台帳に存在するか" (yes-no on-ledger?))
                (tr "結論" where)])
         "\n"
         "      </tbody>\n"
         "    </table>\n"
         "  </section>\n")))

(defn render
  "Renders the whole operator console from a completed `run-demo!`
  result. Reads only the real store and the real run log."
  [{:keys [db runs]}]
  (let [attribution (approver-attribution db runs)
        hs          (holds db)
        hard-rules  (vec (sort (distinct (map #(kw-str (:rule %)) (mapcat :violations hs)))))
        phase-ns    (sort (keys phase/phases))]
    (str
     "<!DOCTYPE html>\n<html lang=\"ja\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
     "<meta name=\"color-scheme\" content=\"light\">"
     "<title>cloud-itonami-isic-2821 &middot; 農業用・林業用機械製造 オペレーターコンソール</title>"
     "<style>" (jp-go-dds.skin/dds+skin) "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Manufacture of agricultural and forestry machinery (ISIC 2821) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · 保守作業の予定と出荷調整は常に人間承認 · 設備の直接操作と機械安全認証の自己発行は恒久的に禁止</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>この頁について</h2>\n"
     "    <p>このページは手書きではありません。<code>agmachmfg.render-html</code> が実際の actor スタック"
     " (<code>advisor</code> → <code>operation</code> (langgraph StateGraph) → <code>governor</code>"
     " → <code>phase</code> → <code>store</code>) を <code>clojure -M:dev:render-html</code> で走らせ、"
     "その実行が書き込んだ store と append-only 監査台帳から生成しています。"
     "実行から取得できなかった値は捏造せず、その旨を明示します。</p>\n"
     "    <p class=\"muted\">この実行: "
     (num (count runs)) " 件のリクエスト / "
     (num (count (store/ledger db))) " 件の台帳ファクト / "
     (num (count hs)) " 件の HARD governor hold ("
     (str/join ", " (map code hard-rules))
     ")。生成器は HARD hold が 0 件なら書き出しを拒否して例外を投げます。</p>\n"
     "  </section>\n"

     (section "生産バッチ (production batches, SSoT)"
              (str "実行後の <code>agmachmfg.store</code> の batch レジスタそのもの。"
                   "<code>verified?</code> / <code>registered?</code> は governor が"
                   "提案の自己申告ではなく、この記録から独立に再確認する ground truth です。"
                   "<code>shipped units</code> は実行中にコミットされた出荷分を含みます。")
              ["Batch" "Product type" "Model" "PTO no-load rpm" "Quantity (units)"
               "Defect rate (%)" "Verified?" "Registered?" "Shipped (units)"]
              (batch-rows db))

     (section "組立・試験設備 (assembly / test-bench equipment, SSoT)"
              (str "同じく実行後のレジスタ。未検証・未登録の設備に対する保守作業予定提案は "
                   "<code>:equipment-not-verified</code> で HARD hold されます。")
              ["Equipment" "Kind" "Verified?" "Registered?" "Last maintenance" "Last scheduled window"]
              (equipment-rows db))

     (section "Phase gate (rollout phases 0→3)"
              (str "<code>agmachmfg.phase/phases</code> と <code>agmachmfg.governor/allowed-ops</code> から"
                   "導出した表です (この頁に固定値は書かれていません)。"
                   "<code>:schedule-maintenance</code> はどの phase の <code>:auto</code> にも属さない"
                   "構造的事実であり、rollout で解禁される予定のものではありません。")
              (cons "Op" (map #(str "Phase " % " · " (:label (get phase/phases %))) phase-ns))
              (phase-gate-rows))

     (section "この実行のリクエスト (real graph runs)"
              (str "1 行 = <code>langgraph.graph/run*</code> の 1 実行。"
                   "「Human asked?」は <code>interrupt-before #{:request-approval}</code> によって"
                   "実際に人間へ判断が渡ったかどうかで、HARD hold は決して人間に届きません。")
              ["Thread" "Op" "Request :effect" "Subject" "Human asked?" "Outcome"]
              (request-rows runs))

     (section "HARD governor holds (この実行で実際に発火した検査)"
              (str "監査台帳の <code>:governor-hold</code> ファクトから展開。"
                   "1 行 = 1 違反。detail は governor が生成した文字列そのものです。"
                   "HARD hold は phase でも人間の承認でも上書きできません。")
              ["Rule" "Op" "Subject" "Detail (governor's own text)"]
              (hold-rows db))

     (section "保守作業予定 (maintenance windows, SSoT)"
              (str "コミットされた保守作業予定の DRAFT。<code>scheduled?</code> は"
                   "二重予約を防ぐ専用フラグで、<code>:status</code> の値ではありません。")
              ["Maintenance" "Equipment" "Type" "Scheduled date" "Scheduled?" "Draft record"]
              (maintenance-rows db))

     (section "出荷調整 (shipments, SSoT)"
              (str "コミットされた出荷調整の DRAFT。実運送業者への発注ではなく、"
                   "プラント側が保持する記録です。")
              ["Shipment" "Batch" "Units" "Destination" "Draft record"]
              (shipment-rows db runs))

     (section "レジストリ草案レコード (append-only registry drafts)"
              (str "<code>agmachmfg.registry</code> が生成した不変レコードの追記ログ。"
                   "record 番号は store 自身のカウンタから導かれるゼロ埋め連番で、時計も乱数も使いません。")
              ["Record" "Kind" "Subject id" "Equipment" "Immutable?"]
              (draft-rows db))

     (section "安全懸念 (safety concerns, append-only)"
              (str "<code>:flag-safety-concern</code> は常に high-stakes として扱われ、"
                   "governor の信頼度ゲートと phase の <code>:auto</code> 集合の両方が"
                   "独立に自動コミットを拒否します。")
              ["Concern" "Equipment" "Severity" "Description"]
              (concern-rows db))

     (approver-section attribution)

     (section "監査台帳 (append-only audit ledger)"
              (str "この実行が実際に追記した全ファクト。提案・保留・コミット・人間の却下が"
                   "すべて 1 本の追記専用ログに並びます。")
              ["Fact" "Op" "Subject" "Disposition" "Basis" "Summary"]
              (ledger-rows db))

     "</main>\n"
     "<footer>\n"
     "  <p>Generated by <code>clojure -M:dev:render-html</code> from <code>src/agmachmfg/render_html.clj</code>"
     " — cloud-itonami-isic-2821 (ISIC 2821, Manufacture of agricultural and forestry machinery)."
     " ページ内に時刻は含めません (再実行がバイト同一になるため)。</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db] :as result} (run-demo!)
        hs (holds db)]
    ;; A console that shows no real HARD hold is not evidence of a
    ;; governor. Make it a build-time invariant, not a convention.
    (when (empty? hs)
      (throw (ex-info (str "no :governor-hold fact on the ledger — "
                           "refusing to write a console that shows no real hold")
                      {:ledger-facts (count (store/ledger db))})))
    (let [f (java.io.File. ^String out)]
      (when-let [p (.getParentFile f)] (.mkdirs p))
      (spit f (render result)))
    (println "wrote" out
             (str "(" (count (store/ledger db)) " ledger facts, "
                  (count hs) " HARD holds, "
                  (count (:runs result)) " requests)"))))
