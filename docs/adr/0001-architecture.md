# ADR-0001: AgMachAdvisor ⊣ Agricultural and Forestry Machinery Plant Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-2821` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by prior actors in this
fleet.

## Context

`cloud-itonami-isic-2821` publishes an OSS blueprint for agricultural/
forestry machinery (tractors, combine harvesters, tillage implements,
balers and forestry harvesters) **plant operations coordination**
(production-batch product-type/PTO-no-load-speed/quantity/defect-rate
data logging, assembly/test-bench-equipment maintenance scheduling,
safety-concern flagging, and outbound product shipment coordination).
Like every actor in this fleet, the blueprint alone is not an
implementation: this ADR records the governed-actor architecture that
promotes it to real, tested code, following the same langgraph
StateGraph + independent Governor + Phase 0->3 rollout pattern
established across the cloud-itonami fleet.

The closest domain analog is `cloud-itonami-isic-2826` (Manufacture of
machinery for textile, apparel and leather production): both are
back-office coordination actors for a fixed manufacturing plant with
electromechanically-assembled, test-bench-verified finished-goods
output and a real physical/worker safety dimension, and both share the
same four-op shape (`:log-production-batch`/`:schedule-maintenance`/
`:flag-safety-concern`/`:coordinate-shipment`), the same two-entity
verified/registered gate structure (equipment for maintenance
scheduling, batch for shipment coordination), and the same permanent
equipment-actuation and certification-authority blocks. This build
mirrors `cloud-itonami-isic-2826`'s architecture closely but adapts the
hazard profile, equipment vocabulary, and product taxonomy to the
agricultural/forestry-machinery plant: its finished goods are
tractors, combine harvesters, tillage implements, balers and forestry
harvesters rather than textile/apparel/leather production machinery,
so its equipment kinds are `:tractor-assembly-line` and
`:harvester-test-bench` rather than 2826's loom-assembly-line and
sewing-machine-test-bench, and its routine test-bench field is
`:pto-no-load-speed-rpm` (plausibility-checked 0-1200 rpm, informed by
the ISO 500 series (Agricultural tractors -- Rear-mounted power
take-off types 1, 2, 3 and 4) nominal PTO speeds of 540 rpm (540E) and
1000 rpm (1000E) with generous test-bench headroom, and by the general
mechanical/hydraulic-safety framework the ISO 4254 series
(Agricultural machinery -- Safety) establishes for this equipment
class) rather than 2826's `:no-load-run-speed-rpm` (weaving-loom/
sewing-machine/leather-cutting-machine running-in test speed,
plausibility-checked 0-8000 rpm). Like 2826, shipment quantity is
tracked in finished-unit UNITS (`:units`/`:quantity-units`/`:shipped-
units`), since agricultural/forestry machinery is likewise discrete
counted units rather than a bulk weight.

This vertical shares 2826's DOMAIN-SPECIFIC permanent block, adapted:
like textile/apparel/leather production machinery, agricultural and
forestry machinery is subject to machinery safety certification
regimes (e.g. CE marking under the EU Machinery Directive 2006/42/EC,
now Machinery Regulation (EU) 2023/1230), and additionally to
tractor-specific certification regimes not shared by 2826 -- ROPS
(Roll-Over Protective Structure) certification under the OECD Standard
Codes for the Official Testing of Agricultural and Forestry Tractors,
informed generally by the ISO 4254 series' safety-requirements
framework for this equipment class. This actor is never the
certification authority -- any proposal (regardless of op) that
declares `:issue-certification? true` is a HARD, PERMANENT,
unconditional block
(`agmachmfg.governor/certification-authority-blocked-violations`), the
same "no phase, no human override" posture as the equipment-actuation
block.

This vertical has NO pre-existing `kotoba-lang/agmachmfg`-style
capability library to wrap (verified: no such repo exists). This build
therefore uses self-contained domain logic -- pure functions in
`agmachmfg.registry` (equipment/batch verification, shipment-quantity
recompute, product-type validation, PTO no-load-speed plausibility
validation, defect-rate plausibility validation) are re-verified
independently by the governor, the same "ground truth, not
self-report" discipline established across prior actors (most
directly `cloud-itonami-isic-2826`'s `texmachmfg.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:agricultural-forestry-machinery-plant-operations-governor`, is
grep-verified UNIQUE fleet-wide (`gh search code
"agricultural-forestry-machinery-plant-operations-governor" --owner
cloud-itonami`, zero hits before this repo was created).

## Decision

### Decision 1: Self-contained domain logic (no external agricultural/forestry-machinery-manufacturing capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
agricultural/forestry-machinery vertical has NO pre-existing
capability library to wrap. The equipment/batch-verification /
shipment-quantity / product-type / PTO-no-load-speed / defect-rate
validation functions live as pure functions in `agmachmfg.registry`
and are re-verified independently by `agmachmfg.governor` -- the same
"ground truth, not self-report" discipline established across prior
actors (most directly `cloud-itonami-isic-2826`'s
`texmachmfg.registry`).

### Decision 2: Coordination, not control — scope boundary at the back-office

This actor is **strictly back-office coordination** of agricultural/
forestry-machinery plant operations. It does NOT:
- Control tractor, combine-harvester, or tillage-implement assembly/test-bench equipment directly
- Make plant-safety or certification decisions (exclusive to the human plant supervisor / accredited certification body)
- Actuate assembly/test-bench equipment
- Self-issue a machinery safety certification mark (e.g. CE marking under the EU Machinery Directive, or ROPS certification under the OECD Standard Codes)

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human plant-supervisor
approval. This is not a replacement for the supervisor's authority or
the certification body's authority — it is a proposal-screening and
documentation layer.

**CRITICAL SAFETY BOUNDARY**: agricultural/forestry-machinery
manufacturing is a safety-critical domain (moving-part/pinch-point/
PTO-entanglement hazard on assembly/test-bench lines, machinery safety
certification, downstream worker-safety consequence). Safety-concern
flagging NEVER auto-commits. All safety concerns escalate immediately
to human review.

### Decision 3: Safety-concern escalation — always human sign-off

`:flag-safety-concern` (mechanical-safety concern, hydraulic-hazard
concern, PTO-guard-compliance concern, equipment-safety concern)
ALWAYS escalates, never auto-commits. This is not a "low-stakes
proposal" -- it is a circuit-breaker that must reach human authority.

### Decision 4: Two independent verified/registered gates (equipment AND batch), not one

Like `cloud-itonami-isic-2826`, this vertical has TWO entity kinds
each gating a different op: `:schedule-maintenance` independently
verifies the referenced **equipment** unit's own `:verified?`/
`:registered?` fields; `:coordinate-shipment` independently verifies
the referenced **batch**'s own `:verified?`/`:registered?` fields.
Both are the same "plant/batch record must be independently
verified/registered before any action" HARD invariant applied to the
two distinct record kinds this domain actually has.
`:coordinate-shipment` additionally independently recomputes whether a
batch's own recorded shipped-to-date unit quantity plus the
proposal's own claimed unit quantity would exceed the batch's own
recorded production quantity -- never taken on the advisor's
self-report.

### Decision 5: HARD invariants (no override)

Four HARD governor invariants (elaborated into twelve concrete checks
in `agmachmfg.governor`, mirroring `cloud-itonami-isic-2826`'s own
elaboration of its HARD invariants into concrete checks) block
proposals and cannot be overridden by human approval:
1. Plant/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action is taken against it, and a shipment's quantity must independently recompute within the batch's own logged production quantity
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Direct assembly/test-bench-equipment control, equipment actuation, or self-issued machinery safety certification is permanently blocked
4. The op allowlist is closed — `:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` only

## Consequences

(+) Agricultural/forestry-machinery plant operations back-office now
has a documented, governed, auditable coordination layer that funnels
all decisions through independent validation before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human plant-
supervisor sign-off, and no certification mark can ever be
self-issued.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into twelve concrete governor checks) protect against scope creep into
unauthorized equipment operation, equipment actuation, or
certification self-issuance. Safety concerns are a circuit-breaker,
not a threshold.

(+) Safety-critical discipline is explicit: safety-concern flagging
cannot be rate-limited, suppressed, or auto-decided by phase gate.
Human review is mandatory.

(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation, line operation, and certification
issuance remain human-/institution-controlled via external channels.

(-) No integration with real plant-management databases (equipment
telemetry, batch tracking, freight dispatch, certification-body APIs)
— this is a standalone coordinator blueprint.

## Verification

- `cloud-itonami-isic-2821`: `kbb -M:test` green (all tests pass;
  see the superproject ADR and `kotoba-lang/industry` registry entry
  for the exact `Ran N tests containing M assertions, 0 failures, 0
  errors` output, verified from an independent fresh clone), `clojure
  -M:lint` clean, `kbb -M:dev:run` demo narrative exercises
  proposal submission, escalation, and every HARD-hold scenario
  directly (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-quantity-exceeded, equipment-actuate-
  blocked, certification-authority-blocked, already-scheduled,
  invalid-product-type, invalid-pto-no-load-speed, invalid-defect-
  rate).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) — no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `kbb -M:test` resolves offline inside the monorepo checkout.
