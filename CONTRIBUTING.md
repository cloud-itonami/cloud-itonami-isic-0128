# Contributing to cloud-itonami-isic-0128

Thank you for your interest in contributing to the Growing Of Spices,
Aromatic, Drug And Pharmaceutical Crops Coordination actor.

## Scope

This repository is a specialization of the cloud-itonami architecture for
ISIC 0128 (growing of spices, aromatic, drug and pharmaceutical crops).
Contributions should:

1. Extend or correct the **Governor rules** (spice/aromatic/drug-and-pharmaceutical-crop farm-operations compliance constraints)
2. Add **crop categories** or **jurisdictional requirements** to the facts registry
3. Improve **test coverage** for spice/aromatic/drug-and-pharmaceutical-crop-specific scenarios
4. Clarify **documentation** and ADRs

## Prohibited Changes

Do **not**:

- Add authority to finalize a controlled-substance cultivation-license approval/renewal
- Add authority to finalize a diversion-control-compliance clearance
- Modify the Governor to allow LLM confidence to override compliance/regulatory hard holds
- Rephrase the scope-exclusion check as a free-text scan of `:rationale`/disclaimer strings for bare nouns (this actor family has a known bug class where that causes the advisor's own legitimate disclaimer text to self-trip the check -- the check must stay a structural, explicit `:value` boolean-flag check)
- Add JVM-only code (all source must be `.cljc` / portable)
- Change the AGPL-3.0-or-later license

## Process

1. Open an issue describing your proposed change
2. Link to the relevant ADR in the `kotoba-lang/industry` registry repository (or the `com-junkawasaki/root` superproject's `90-docs/adr/`)
3. Submit a pull request against `main`
4. Ensure all tests pass: `clojure -M:test`
5. Run linter: `clojure -M:lint`

## Code Style

- Use `.cljc` for all source (no `.clj` or `.cljs` only)
- Follow Clojure conventions (kebab-case, docstrings on public fns)
- Governor rules must be pure, side-effect-free predicates
- Test all new facts and registry entries

## Questions?

File an issue or reach out to the maintainers.
