# ADR-0001: ConsumerElecAdvisor ⊣ Consumer Electronics Plant Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-2640` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by prior actors in this
fleet.

## Context

`cloud-itonami-isic-2640` publishes an OSS blueprint for consumer-
electronics **plant operations coordination** (production-batch
product-type/dielectric-safety-test-voltage/quantity/defect-rate data
logging, SMT/assembly/test-bench-equipment maintenance scheduling,
safety-concern flagging, and outbound consumer-electronics product
shipment coordination). Like every actor in this fleet, the blueprint
alone is not an implementation: this ADR records the governed-actor
architecture that promotes it to real, tested code, following the same
langgraph StateGraph + independent Governor + Phase 0->3 rollout
pattern established across the cloud-itonami fleet.

The closest domain analog is `cloud-itonami-isic-2710` (Manufacture of
electric motors, generators, transformers and electricity distribution
and control apparatus): both are back-office coordination actors for a
fixed processing PLANT with electronics-assembly/test equipment and a
real physical safety dimension, and both share the same four-op shape
(`:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/
`:coordinate-shipment`) and the same two-entity verified/registered
gate structure (equipment for maintenance scheduling, batch for
shipment coordination). This build mirrors `cloud-itonami-isic-2710`'s
architecture closely but adapts the hazard profile and equipment/
product vocabulary to the consumer-electronics plant: this vertical's
central equipment is a surface-mount-technology (SMT) PCB-assembly
line (component placement + reflow soldering) and a final-assembly/
test bench, rather than 2710's winding/insulation/high-voltage
dielectric-test line for motors/generators/transformers; its permanent
equipment-actuation block guards SMT/assembly/test-bench EQUIPMENT
(`:actuate-equipment?`, same field name, same posture) rather than
2710's winding/assembly/test-bench equipment; its production-batch
record declares a `:product-type` (closed set spanning
television/audio-device/video-device/smart-speaker/wearable-device)
and a `:dielectric-test-kv` (the routine hipot/withstand safety-test
voltage in kV for mains-connected units, plausibility-checked 0-5 kV
against IEC 62368-1's electric-strength test-voltage tables for audio/
video/ICT equipment, a much lower ceiling than 2710's 0-2500 kV power-
transformer range since consumer electronics are low-power/low-voltage
by comparison) in addition to a `:defect-rate-percent`, rather than
2710's `:dielectric-test-kv` grounded in IEC 60076-3's power-
transformer lightning-impulse withstand table; and its shipment
quantity is tracked in finished-unit UNITS (`:units`/`:quantity-units`/
`:shipped-units`), the same shape 2710 uses for finished motors/
generators/transformers/apparatus (counted, not weighed, for freight
coordination) since consumer-electronics products are likewise
discrete counted units rather than a bulk weight.

This vertical additionally has a DOMAIN-SPECIFIC safety-concern
vocabulary neither 2710 nor its own analogs need in the same form:
manufacture of consumer electronics carries a battery-safety hazard
(lithium-ion/lithium-polymer battery packs in wearables/portable audio
devices) alongside the electrical-safety hazard 2710 shares, plus a
RoHS-compliance dimension (Restriction of Hazardous Substances in
electronic components) specific to consumer-electronics regulatory
regimes. `:flag-safety-concern` therefore surfaces a "battery-safety/
electrical-safety/RoHS-compliance concern" (vs. 2710's "insulation-
failure/electrical-safety/high-voltage-test-hazard concern"), and this
actor is never the certification authority — any proposal (regardless
of op) that declares `:issue-certification? true` is a HARD,
PERMANENT, unconditional block
(`consumerelec.governor/certification-authority-blocked-violations`),
the same "no phase, no human override" posture 2710 establishes for its
own electrical-safety-certification block, adapted to the consumer-
electronics certification regimes (e.g. UL 62368-1, CE marking under
the EU Radio Equipment Directive / Low Voltage Directive, FCC Part 15,
RoHS Directive 2011/65/EU).

This vertical has NO pre-existing `kotoba-lang/consumerelec`-style
capability library to wrap (verified: no such repo exists). This build
therefore uses self-contained domain logic — pure functions in
`consumerelec.registry` (equipment/batch verification, shipment-
quantity recompute, product-type validation, dielectric-safety-test-
voltage plausibility validation, defect-rate plausibility validation)
are re-verified independently by the governor, the same "ground truth,
not self-report" discipline established across prior actors (most
directly `cloud-itonami-isic-2710`'s `elecequipmfg.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:consumer-electronics-plant-operations-governor`, is grep-verified
UNIQUE fleet-wide (`gh search code
"consumer-electronics-plant-operations-governor" --owner
cloud-itonami`, zero hits before this repo was created).

## Decision

### Decision 1: Self-contained domain logic (no external consumer-electronics-manufacturing capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
consumer-electronics vertical has NO pre-existing capability library to
wrap. The equipment/batch-verification / shipment-quantity /
product-type / dielectric-safety-test-voltage / defect-rate validation
functions live as pure functions in `consumerelec.registry` and are
re-verified independently by `consumerelec.governor` — the same
"ground truth, not self-report" discipline established across prior
actors (most directly `cloud-itonami-isic-2710`'s
`elecequipmfg.registry`).

### Decision 2: Coordination, not control — scope boundary at the back-office

This actor is **strictly back-office coordination** of consumer-
electronics plant operations. It does NOT:
- Control SMT, assembly, or test-bench equipment directly
- Make plant-safety or certification decisions (exclusive to the human plant supervisor / accredited certification body)
- Actuate SMT/assembly/test-bench equipment
- Self-issue a consumer-electronics safety-certification mark (e.g. UL/CE/FCC/RoHS)

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human plant-supervisor
approval. This is not a replacement for the supervisor's authority or
the certification body's authority — it is a proposal-screening and
documentation layer.

**CRITICAL SAFETY BOUNDARY**: consumer-electronics manufacturing is a
safety-critical domain (battery-safety hazard, electrical-safety
hazard, RoHS-compliance requirement, downstream product-safety and
worker-safety consequence). Safety-concern flagging NEVER auto-
commits. All safety concerns escalate immediately to human review.

### Decision 3: Safety-concern escalation — always human sign-off

`:flag-safety-concern` (battery-safety concern, electrical-safety
concern, RoHS-compliance concern, equipment-safety concern) ALWAYS
escalates, never auto-commits. This is not a "low-stakes proposal" —
it is a circuit-breaker that must reach human authority.

### Decision 4: Two independent verified/registered gates (equipment AND batch), not one

Like `cloud-itonami-isic-2710`, this vertical has TWO entity kinds each
gating a different op: `:schedule-maintenance` independently verifies
the referenced **equipment** unit's own `:verified?`/`:registered?`
fields; `:coordinate-shipment` independently verifies the referenced
**batch**'s own `:verified?`/`:registered?` fields. Both are the same
"plant/batch record must be independently verified/registered before
any action" HARD invariant applied to the two distinct record kinds
this domain actually has. `:coordinate-shipment` additionally
independently recomputes whether a batch's own recorded shipped-to-
date unit quantity plus the proposal's own claimed unit quantity would
exceed the batch's own recorded production quantity — never taken on
the advisor's self-report.

### Decision 5: HARD invariants (no override)

Four HARD governor invariants (elaborated into twelve concrete checks
in `consumerelec.governor`, mirroring `cloud-itonami-isic-2710`'s own
elaboration of its HARD invariants into concrete checks) block
proposals and cannot be overridden by human approval:
1. Plant/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action is taken against it, and a shipment's quantity must independently recompute within the batch's own logged production quantity
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Direct SMT/assembly/test-bench-equipment control, equipment actuation, or self-issued consumer-electronics safety certification is permanently blocked
4. The op allowlist is closed — `:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` only

## Consequences

(+) Consumer-electronics plant operations back-office now has a
documented, governed, auditable coordination layer that funnels all
decisions through independent validation before human approval.

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

- `cloud-itonami-isic-2640`: `clojure -M:test` green (77 tests, 210
  assertions, 0 failures, 0 errors; see the superproject ADR and
  `kotoba-lang/industry` registry entry for the exact raw output,
  verified from an independent fresh clone), `clojure -M:lint` clean,
  `clojure -M:dev:run` demo narrative exercises proposal submission,
  escalation, and every HARD-hold scenario directly (not-propose-
  effect, unknown-op, equipment-not-verified, batch-not-verified,
  shipment-quantity-exceeded, equipment-actuate-blocked,
  certification-authority-blocked, already-scheduled, invalid-product-
  type, invalid-dielectric-test-kv, invalid-defect-rate).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) — no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `clojure -M:test` resolves offline inside the monorepo checkout.
