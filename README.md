# cloud-itonami-isic-0128: Growing Of Spices, Aromatic, Drug And Pharmaceutical Crops Coordination Actor

**ISIC Rev. 5 0128** — Growing of Spices, Aromatic, Drug and Pharmaceutical Crops

A distributed actor for autonomous, compliant coordination of spice/
aromatic/drug-and-pharmaceutical-crop cultivation operations: farm-lot
intake → field/crop-condition survey → scheduling/procurement advice →
planting/harvest/labor field work → harvest-record logging → compliance
audit. Sealed LLM advisor; independent Governor enforcement; append-only
audit ledger. **Not cultivation-license or diversion-control-clearance
authority.** Granting, renewing, or finalizing a controlled-substance
cultivation license or a diversion-control-compliance clearance remains
exclusive to the licensing/regulatory authority, and this actor never
finalizes either decision on its own — that is a HARD, PERMANENT
Governor block, never overridable by human approval.

## Scope

ISIC 0128 covers **both** entirely mundane spice/aromatic crops (black
pepper, vanilla, cinnamon, peppermint) **and** licit drug/pharmaceutical-
precursor crop cultivation. This build's illustrative crop categories
are:

- **Non-controlled** (no cultivation-license or quota-tracking
  requirement): black pepper, vanilla, cinnamon, peppermint
- **Controlled substance crops** (genuine cultivation-license expiry,
  quota-tracking reconciliation, licensed-quota ceiling): licensed opium
  poppy (pharmaceutical morphine feedstock), licensed coca leaf
  (pharmaceutical/traditional use in the few jurisdictions where this is
  legal), licensed medicinal cannabis

— all grown and OWNED by the operator, who already holds an
independently-verified/registered farm-lot record this actor only
checks the status of, never issues.

This actor coordinates the operator's **own farm operations logistics**:

- Harvest-record logging (planting/yield/harvest batch data, compliance
  parameters)
- Planting/harvesting/labor field-operation scheduling proposals
- Compliance-concern escalation (suspected diversion, license-compliance
  question, pest outbreak — always escalates)
- Seed/fertilizer/input procurement proposals

**Out of scope, permanently:**
- Finalizing a controlled-substance cultivation-license approval or
  renewal (HARD, permanent, un-overridable Governor block)
- Finalizing a diversion-control-compliance clearance (HARD, permanent,
  un-overridable Governor block)
- Custom farm work performed for OTHER farms' crops
- Regulatory interpretation (proposals cite jurisdiction specifications;
  the Governor enforces only published requirements)

## Design

### Governor (Independent Compliance Layer)

The Governor is the separation-of-powers enforcement. It never trusts the
advisor's confidence for anything compliance-relevant, and it always wins
over the advisor.

- **Hard HOLD** (un-overridable):
  - Operation outside the closed allowlist (`:op-not-allowed`)
  - Proposal asserting an `:effect` other than `:propose`
    (`:effect-not-propose`)
  - Farm-lot not independently verified/registered in the store —
    applies to ALL FOUR allowed ops (`:farm-lot-not-registered`)
  - No jurisdiction citation (`:no-spec-basis`)
  - Evidence checklist incomplete (`:evidence-incomplete`)
  - Cultivation license expired (`:cultivation-license-expired`) — only
    for controlled substance crop categories
  - Quota-tracking record lapsed (`:quota-tracking-lapsed`) — only for
    controlled substance crop categories
  - Harvest quota exceeded (`:harvest-quota-exceeded`) — only for
    controlled substance crop categories
  - Proposal covertly requests to finalize a controlled-substance
    cultivation-license approval/renewal or a diversion-control-
    compliance clearance
    (`:cultivation-license-or-diversion-clearance-blocked`) — a HARD,
    PERMANENT block, defense-in-depth against every op
  - Unresolved compliance concern (`:compliance-flag-unresolved`)
  - Farm-lot already logged (`:already-logged`, double-commit guard)
- **Escalate** (human sign-off always required):
  - `:log-harvest-record` — the one real actuation event this actor
    performs, always requires human sign-off even when the Governor is
    otherwise clean
  - `:flag-compliance-concern` — a suspected-diversion/license-
    compliance/pest-outbreak concern is never auto-resolved by advisor
    confidence alone; also deliberately absent from every phase's
    `phase-auto-ops` auto-commit set
  - `:coordinate-supply-order` above `governor/supply-order-cost-threshold-usd`
    (5000 USD)
  - Low advisor confidence (below `governor/confidence-floor`, 0.6)
- **Commit** (advisor proposal approved; Governor clean; not a
  mandatory-escalation op):
  - Routine, low-stakes proposals only — in this actor's current
    allowlist that is effectively `:schedule-farm-operation` when clean,
    or `:coordinate-supply-order` at or below the cost threshold

### Operations (Proposals)

Closed allowlist — the advisor may **only** ever propose these four
operation types, all `:effect :propose`:

- **`:log-harvest-record`** — Log planting/harvest batch, yield/quality
  data, plus compliance parameters, into harvest records (always
  requires human sign-off)
- **`:schedule-farm-operation`** — Propose planting/harvesting/labor
  field-operation scheduling for the operator's own farm-lot (routine,
  low risk)
- **`:flag-compliance-concern`** — Surface a suspected-diversion/
  license-compliance/pest-outbreak concern; always escalates
- **`:coordinate-supply-order`** — Propose seed/fertilizer/input
  procurement (escalates above the cost threshold)

Any proposal for an operation outside this allowlist is refused
unconditionally by the Governor (`:op-not-allowed`), regardless of
advisor confidence. Any proposal that covertly requests to finalize a
controlled-substance cultivation-license approval/renewal or a
diversion-control-compliance clearance, even nested inside an otherwise-
allowed op, is likewise refused unconditionally
(`:cultivation-license-or-diversion-clearance-blocked`).

### Crop Categories

`spicecrop.facts/crop-categories` splits into two compliance shapes:

- **Non-controlled** (no cultivation-license or quota-tracking
  requirement at all): `:spice/black-pepper`, `:spice/vanilla`,
  `:aromatic/cinnamon`, `:aromatic/peppermint`
- **Controlled substance crops** (genuine cultivation-license expiry,
  quota-tracking reconciliation freshness, licensed-quota ceiling):
  `:pharma/licensed-opium-poppy`, `:pharma/licensed-coca-leaf`,
  `:pharma/licensed-medicinal-cannabis`

### Known Bug Class This Build Guards Against

In this actor family, a Governor scope-exclusion term list has
occasionally been phrased as a bare noun (e.g. "license") rather than as
the finalization/execution ACTION it is meant to block — which
accidentally matches inside the advisor's own default rationale/
disclaimer text (which routinely and correctly says things like "this
does not finalize any cultivation license or diversion-control
clearance"), causing the actor to self-block on its own legitimate happy
path. This build's `:cultivation-license-or-diversion-clearance-blocked`
rule is phrased as the finalization ACTION (explicit `:finalize-*?`
boolean `:value` flags) and never scans `:rationale` text at all.
`spicecrop.advisor/default-mock-proposals` deliberately includes
disclaimer text containing "license"/"clearance"/"diversion", and
`spicecrop.governor-test/default-mock-proposals-never-self-trip-scope-
exclusion-test` asserts none of them ever trip the rule.

## Testing

```bash
# Run full test suite
clojure -M:test

# Check code quality
clojure -M:lint

# Run demo simulation
clojure -M:run
```

## Standalone Use

This repo is **forkable outside the workspace**. If cloning standalone (not
in the kotoba-lang monorepo), override `:local/root` paths in `deps.edn`:

```clojure
{:deps {io.github.kotoba-lang/langchain {:git/url "https://github.com/kotoba-lang/langchain" :git/tag "v0.1.0"}
        io.github.kotoba-lang/langgraph {:git/url "https://github.com/kotoba-lang/langgraph" :git/tag "v0.1.0"}}}
```

## License

AGPL-3.0-or-later. Forking/contribution welcome; see `CONTRIBUTING.md`.

## Security

Report security issues to the issue tracker or private disclosure; see
`SECURITY.md`.

---

Part of **cloud-itonami**: autonomous actor fleet for regulated industries.
See [github.com/cloud-itonami](https://github.com/cloud-itonami).
