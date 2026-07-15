# cloud-itonami-isic-2640: Manufacture of consumer electronics

Open Business Blueprint for **ISIC 2640**: manufacture of consumer electronics — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office **consumer-electronics-plant operations**: production-batch data logging (product-type/dielectric-safety-test-voltage/quantity/defect-rate), SMT/assembly/test-bench-equipment maintenance scheduling, safety-concern flagging, and outbound product shipment coordination.

This repository designs a forkable OSS business for consumer-
electronics-plant operations: run by a qualified operator so a plant
keeps its own operating records instead of renting a closed SaaS.

## Scope: plant operations coordination, not SMT/assembly/testing-line control

ISIC 2640 covers the **manufacturing plant** that places components on PCBs via surface-mount technology (SMT), reflow-solders them, assembles the finished consumer-electronics unit (television, audio/video device, smart speaker, wearable device), and tests it (functional test, safety/dielectric-withstand test) before shipment. This actor coordinates the back-office record keeping around that plant — it never touches the SMT/assembly/test-bench equipment directly, and it is never a consumer-electronics safety-certification authority (e.g. UL/CE/FCC/RoHS compliance marks).

## What this actor does

Proposes **plant operations coordination**, not equipment operation:
- `:log-production-batch` — SMT/assembly batch, output-quality/test-result data logging (administrative, not an operational decision)
- `:schedule-maintenance` — SMT/assembly/test-bench-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface a battery-safety/electrical-safety/RoHS-compliance concern (always escalates)
- `:coordinate-shipment` — outbound consumer-electronics product shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY — this is a safety-critical domain**
(SMT/assembly/test-bench line equipment, battery-safety hazard,
consumer-electronics safety/RoHS/FCC/CE certification, downstream
product-safety and worker-safety consequence):

- Does NOT control SMT, assembly, or test-bench equipment directly
- Does NOT make plant-safety or certification decisions (that's the plant supervisor's / certification body's exclusive human/institutional authority)
- Does NOT actuate SMT/assembly/test-bench equipment (human plant supervisor decides)
- Does NOT self-issue a consumer-electronics safety-certification mark (e.g. UL/CE/FCC/RoHS — the accredited certification body's exclusive authority — a PERMANENT, unconditional block)
- ONLY proposes/coordinates operations back-office; all actuation and certification requires explicit human/institutional authority
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`consumerelec.operation/build`, a langgraph-clj StateGraph):
1. **`consumerelec.advisor`** (sealed intelligence node, `ConsumerElecAdvisor`): proposes decisions only, never commits
2. **`consumerelec.governor`** (independent, `Consumer Electronics Plant Operations Governor`): validates against domain rules, re-derived from `consumerelec.registry`'s pure functions and `consumerelec.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Plant/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct SMT/assembly/test-bench-equipment control)
     - Directly actuating SMT/assembly/test-bench equipment (`:actuate-equipment? true`) is a PERMANENT, unconditional block
     - Self-issuing a consumer-electronics safety-certification mark (`:issue-certification? true`, any op) is a PERMANENT, unconditional block
     - A shipment may not push a batch's own recorded shipped quantity past its own logged production quantity (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:product-type` value on a production-batch patch
     - No physically implausible `:dielectric-test-kv` value on a production-batch patch
     - No physically implausible `:defect-rate-percent` value on a production-batch patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`consumerelec.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`consumerelec.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
clojure -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
