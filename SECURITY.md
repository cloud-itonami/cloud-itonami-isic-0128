# Security Policy

## Reporting a Vulnerability

If you discover a security issue in cloud-itonami-isic-0128, **do not open a
public issue**. Instead, please report it to the cloud-itonami security team via
private disclosure:

- Email: [security contact to be added]
- GitHub Security Advisory: [will be set up]

Include:
- Description of the vulnerability
- Steps to reproduce
- Potential impact
- Any mitigation or workaround

## Security Scope

This repository implements the **Governor** for spice/aromatic/drug-and-
pharmaceutical-crop farm-operations coordination — a deterministic
compliance-checking layer that gates all proposals from the LLM/advisor.

Security-critical concerns:
1. **Hard violations in Governor rules must never be overridable** — a rule
   marked as HARD (e.g., `:already-logged`, `:cultivation-license-expired`,
   `:cultivation-license-or-diversion-clearance-blocked`) blocks the proposal
   unconditionally
2. **The `:cultivation-license-or-diversion-clearance-blocked` rule must
   never be relaxed to grant, renew, or finalize a controlled-substance
   cultivation license or diversion-control-compliance clearance** — this
   actor's scope is farm operations logistics only, permanently
3. **Escalation gates must trigger without race conditions** — compliance-
   concern flags always escalate to human sign-off before action
4. **Audit ledger integrity** — facts written to the store must be append-only
   and tamper-evident (in production deployment with external ledger backend)

## Testing

All Governor changes must:
- Have comprehensive test coverage (facts, registry, governor rules)
- Pass `clojure -M:test` and `clojure -M:lint`
- Include documentation of the spice/aromatic/drug-and-pharmaceutical-crop compliance/regulatory rationale
- Include regression coverage confirming the advisor's default proposals never self-trip the scope-exclusion check (see `spicecrop.governor-test`)

## Dependencies

This repo has minimal dependencies:
- `cognitect-labs/test-runner` (testing only)
- `clj-kondo` (linting only)
- (Optional for production: `langchain`, `langgraph` from workspace)

Dependency updates are reviewed for known CVEs before merge.
