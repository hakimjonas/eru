# Eru Pre-Release Polish Plan

## Overview

This plan outlines a systematic approach to polish Eru before the 0.1.0 release. The work is organized into phases, each suitable for a separate PR, progressing from low-risk cleanup to detailed code review.

---

## Phase 1: Project Sanitation & Root Cleanup
**Branch**: `polish/phase1-project-sanitation`
**Risk Level**: Low
**Estimated Scope**: Small

### Goals
- Clean up root directory from temporary and generated files
- Organize scripts and tools appropriately
- Update `.gitignore` to prevent future clutter
- Remove outdated documentation or scripts

### Tasks

#### 1.1 Root Directory Cleanup
- [ ] Remove `test_output.txt` (temporary test output file)
- [ ] Review `benchmark-results/` folder:
  - Contains timestamped JSON files (likely CI artifacts)
  - Decision: Remove from repo and add to `.gitignore`, or move to a dedicated location
- [ ] Audit shell scripts in root:
  - `run-all-tests.sh` - Keep (useful for contributors)
  - `validate-docs.sh` - Keep (useful for contributors)
  - Consider moving to `tools/` or keep in root for discoverability

#### 1.2 Update .gitignore
- [ ] Add `test_output.txt`
- [ ] Add `benchmark-results/` (if not already ignored)
- [ ] Review for any other temporary file patterns

#### 1.3 Scripts & Tools Review
- [ ] `scripts/verify-snapshot.scala` - Verify still needed or remove
- [ ] `tools/` directory:
  - `analyze-benchmarks.py` - Review and document purpose
  - `analyze-benchmarks.scala` - Review and document purpose
  - `analyze-benchmarks.sh` - Review and document purpose
  - `run-benchmarks.sh` - Review and document purpose
  - `eru-api-helper.scala` - Review and document purpose
- [ ] Add `tools/README.md` explaining what each script does and when to use it

#### 1.4 Markdown Files Audit
- [ ] Review all root-level `.md` files:
  - `README.md` - Keep, will be addressed in Phase 3
  - `CONTRIBUTING.md` - Keep, will be addressed in Phase 3
  - `LICENSE` - Keep
- [ ] Check `docs-src/` for outdated or duplicate files

### Success Criteria
- Root directory contains only essential files
- All temporary/generated files removed
- Clear documentation for remaining scripts
- Updated `.gitignore` prevents future clutter

---

## Phase 2: File & Test Naming Audit
**Branch**: `polish/phase2-file-naming`
**Risk Level**: Medium (requires refactoring imports)
**Estimated Scope**: Medium

### Goals
- Review and improve test file naming for professionalism
- Ensure names are descriptive but not overreaching
- Avoid inviting scrutiny with grandiose claims in file names

### Tasks

#### 2.1 Test File Naming Review
Current problematic patterns identified:
- `EruMonadLawsSpec.scala` → Consider: `EruMonadSpec.scala` or `MonadLawsSpec.scala`
- `EruResourceLawsSpec.scala` → Consider: `ResourceLawsSpec.scala` or `ResourceSpec.scala`
- Property-based specs are fine but review for clarity

#### 2.2 Systematic Review
- [ ] List all `*Spec.scala` files (92 files total)
- [ ] Categorize by naming pattern:
  - `*LawsSpec` - Review for simplification
  - `*PropertyBasedSpec` - Review for consistency (maybe just `*PropertySpec`)
  - `*MathematicallyCorrect*` - If any exist, rename immediately
  - Others - Check for clarity and professionalism

#### 2.3 Benchmark File Naming
- [ ] Review benchmark file names in `eru-bench-jvm/` and `eru-bench-matrix/`
- [ ] Ensure names focus on "what is measured" not "how good we are"
- [ ] Example: Instead of "EruVsCatsEffectBenchmark" → "CoreOperationsBenchmark"

#### 2.4 Refactoring Impact
- [ ] For each renamed file:
  - Update imports in test files
  - Update any documentation references
  - Verify builds pass on both JVM and Native

### Success Criteria
- All test names are professional and descriptive
- No overreaching claims in file names
- All imports updated correctly
- Full test suite passes

---

## Phase 3: Documentation Tone, Style & Messaging Audit
**Branch**: `polish/phase3-documentation-audit`
**Risk Level**: Low
**Estimated Scope**: Large

### Goals
- Ensure all documentation reflects professional and humble approach
- Remove marketing language and LLM markers
- Establish consistent voice across all docs
- Re-evaluate benchmarking presentation strategy

### Tasks

#### 3.1 Establish Style Guidelines
- [ ] Document tone guidelines:
  - Professional but approachable
  - Factual, not promotional
  - Humble about limitations
  - Respectful of other libraries
- [ ] Create "words to avoid" list:
  - "Revolutionary", "game-changing", "best"
  - Excessive emojis (one found in observability doc)
  - Absolute claims without evidence

#### 3.2 Core Documentation Files
- [ ] `README.md`:
  - Review "What Makes Eru Different" section - ensure it explains uniqueness without disparaging others
  - Check for marketing language
  - Ensure claims are factual and verifiable

- [ ] `MANIFESTO.md`:
  - Current: "the definitive effect system" - too strong
  - Current: "not an alternative to existing frameworks; it is a new benchmark" - reconsider
  - Rewrite to focus on design principles and goals, not positioning against others

- [ ] `CONTRIBUTING.md`:
  - Review for tone
  - Ensure welcoming and inclusive

- [ ] `docs-src/QUICKSTART.md`:
  - Review for clarity and approachability
  - Remove any marketing language

- [ ] `docs-src/API.md`:
  - Ensure technical and factual

- [ ] `docs-src/RESOURCES.md`:
  - Review for completeness and clarity

- [ ] `docs-src/OBSERVER.md`:
  - Remove emoji from code example (⚡ found)
  - Review for technical accuracy

#### 3.3 The Eru Book (docs-src/book/)
- [ ] Chapter 01 - The Eru Vision:
  - Likely contains positioning statements
  - Rewrite to focus on "what" and "why", not "better than"

- [ ] Chapter 12 - Performance & Optimization:
  - **Critical section for benchmarking strategy**
  - Current approach uses JMH and real measurements (good)
  - Review how comparisons to other libraries are presented

- [ ] All other chapters (02-14):
  - Systematic review for tone and style
  - Remove marketing language
  - Ensure technical accuracy

#### 3.4 Benchmarking Strategy Decision
**Current situation**: Benchmarks exist to validate performance, not to criticize competitors

**Options to consider**:

1. **Keep benchmarks, reframe messaging**:
   - Focus on "Eru achieves X ops/ms in our tests"
   - Avoid "X times faster than Y"
   - Present as "performance characteristics" not "comparisons"
   - Include methodology and caveats

2. **Move comparison benchmarks to separate document**:
   - Main docs show Eru's performance in isolation
   - Separate "Performance Comparison Methodology" doc for those interested
   - De-emphasize in main README

3. **Remove specific library comparisons**:
   - Keep internal benchmarks for regression detection
   - Remove or de-emphasize comparative results
   - Focus on "fast enough for production use"

**Recommendation**: Option 1 + 2 hybrid
- Main docs focus on Eru's characteristics
- Brief mention that "Eru performs well compared to established libraries"
- Detailed methodology available for those interested
- Respectful tone: "We designed Eru to be performant. Here's what we measured."

#### 3.5 Specific Changes Needed
- [ ] Remove or soften phrases like:
  - "50-80x faster than Cats Effect" (if present in docs)
  - Any language that could be read as dismissive
  - Superlatives without qualification

- [ ] Replace with:
  - "Eru demonstrates strong performance characteristics in our benchmarks"
  - "Designed for production workloads requiring high throughput"
  - "Performance validated through comprehensive JMH benchmarks"

#### 3.6 Code Examples Review
- [ ] Ensure all code examples are realistic and practical
- [ ] Remove any emoji from code (one found)
- [ ] Verify examples compile and run correctly

### Success Criteria
- Consistent, professional tone across all documentation
- No marketing language or overreaching claims
- Respectful treatment of other libraries
- Clear, factual presentation of performance characteristics
- All documentation validates with mdoc

---

## Phase 4: Code Syntax & Documentation Audit
**Branch**: Multiple branches by module:
- `polish/phase4-core-audit`
- `polish/phase4-runtime-audit`
- `polish/phase4-tests-audit`

**Risk Level**: Medium (documentation changes only, no functionality changes)
**Estimated Scope**: Very Large

### Goals
- File-by-file review of syntax and documentation
- Ensure all public APIs are properly documented
- Clean up unnecessary code bloat
- Report any potential issues found (without fixing functionality)

### Approach
Module-by-module, file-by-file review with a systematic checklist for each file.

### Review Checklist (Per File)

#### Syntax Review
- [ ] Remove unnecessary imports
- [ ] Remove commented-out code (unless specifically marked as examples)
- [ ] Check for consistent formatting (scalafmt should handle this)
- [ ] Look for overly complex expressions that could be simplified
- [ ] Verify error messages are clear and helpful
- [ ] Check for TODO/FIXME comments and document them

#### Documentation Review
- [ ] All public classes have scaladoc
- [ ] All public methods have scaladoc
- [ ] All public types have scaladoc
- [ ] Scaladoc is clear and includes:
  - What the method/class does
  - Parameter descriptions (if any)
  - Return value description
  - Example usage (where helpful)
  - Any preconditions or invariants
- [ ] No marketing language in documentation
- [ ] No emoji in documentation
- [ ] Technical accuracy verified

#### Issue Reporting
- [ ] Flag potential bugs (without fixing)
- [ ] Flag performance concerns (without fixing)
- [ ] Flag unclear code (without refactoring)
- [ ] Document any confusing patterns

### Module Breakdown

#### 4.1 eru-core (Priority: Highest)
Files to review (main sources):
- `Eru.scala` (1,738 lines - largest file, core GADT)
- `Exit.scala`
- `Result.scala`
- `UnifiedFiber.scala`
- `CorePrelude.scala`
- `internal/` directory
- `trace/` directory
- `patterns/` directory

**Sub-phases**:
- 4.1a: Core domain types (Eru, Exit, Result)
- 4.1b: Fiber and execution
- 4.1c: Internal utilities
- 4.1d: Public API and preludes

#### 4.2 eru-runtime (Priority: High)
Separate by platform:
- 4.2a: Shared runtime (`shared/src/`)
- 4.2b: JVM runtime (`jvm/src/`)
- 4.2c: Native runtime (`native/src/`)

Focus areas:
- Concurrency primitives (Ref, Queue, Semaphore, etc.)
- RuntimeBackend implementations
- Platform-specific code

#### 4.3 Test Suites (Priority: Medium)
- 4.3a: Core tests
- 4.3b: Runtime tests
- 4.3c: Integration tests

Note: Tests have different documentation standards, but should still be clear

#### 4.4 Examples (Priority: Low)
- Review all example files for:
  - Accuracy
  - Clarity
  - Best practices
  - Compilation

#### 4.5 Benchmarks (Priority: Low)
- Review benchmark implementations
- Ensure benchmarks are fair and representative
- Document benchmark methodology

### Success Criteria
- All public APIs fully documented
- No unnecessary code bloat
- Consistent documentation style
- All potential issues logged for separate review
- Clean, professional codebase ready for release

---

## Phase 5: Final Pre-Release Validation
**Branch**: `polish/phase5-final-validation`
**Risk Level**: Low
**Estimated Scope**: Small

### Goals
- Comprehensive validation that all changes integrate correctly
- Final sanity checks before release

### Tasks
- [ ] Run full test suite (JVM + Native)
- [ ] Validate all documentation with mdoc
- [ ] Run benchmarks to ensure no performance regressions
- [ ] Build examples and verify they work
- [ ] Review all changes holistically
- [ ] Spell-check all documentation
- [ ] Final git status check - no unexpected files
- [ ] Verify LICENSE and copyright notices
- [ ] Update CONTRIBUTING.md with any new processes

### Success Criteria
- All tests pass
- All docs validate
- No performance regressions
- Clean git state
- Ready for release

---

## Dependencies Between Phases

```
Phase 1 (Sanitation)
  ↓
Phase 2 (Naming) ← Must complete before Phase 3 (affects docs)
  ↓
Phase 3 (Documentation) ← Should complete before Phase 4 (sets style)
  ↓
Phase 4 (Code Audit) ← Can run concurrently in sub-phases
  ↓
Phase 5 (Validation)
```

---

## Git Branch Strategy

### Branch Naming Convention
- `polish/phase1-project-sanitation`
- `polish/phase2-file-naming`
- `polish/phase3-documentation-audit`
- `polish/phase4a-core-domain-types`
- `polish/phase4b-core-execution`
- (etc.)

### PR Strategy
- Each phase = one PR (except Phase 4 which is split by module)
- PRs should be reviewable in one sitting
- Each PR must pass CI before merge
- Merge to `claude/project-exploration-011CV1sXbtJd9PohYbr2UVWG` (current branch)

---

## Risk Management

### Low Risk
- Phase 1: File cleanup and .gitignore
- Phase 3: Documentation changes
- Phase 5: Validation only

### Medium Risk
- Phase 2: File renames (requires careful import updates)
- Phase 4: Documentation changes to code (must not change behavior)

### Mitigation
- Run full test suite after each phase
- Use git branches for easy rollback
- Keep PRs focused and reviewable
- Validate documentation changes with mdoc

---

## Timeline Estimate

Assuming focused work:
- Phase 1: 2-4 hours
- Phase 2: 4-8 hours (includes testing)
- Phase 3: 8-16 hours (comprehensive doc review)
- Phase 4: 20-40 hours (largest phase, per-file review)
- Phase 5: 2-4 hours

**Total**: 36-72 hours of focused work

Can be parallelized by having different people work on different modules in Phase 4.

---

## Notes

### Benchmarking Philosophy
We built benchmarks to ensure Eru is performant and can hold its own. The goal was never to criticize or belittle competitors. Performance data should be presented:
- Factually and with methodology
- Respectfully toward other libraries
- In context (what workload, what setup)
- Without making it the central message

### Tone Philosophy
- Be proud of the work without being arrogant
- Be confident without being dismissive of others
- Be technical without being obscure
- Be professional without being dry

### When in Doubt
- Less marketing language is better
- More technical accuracy is better
- Humility is strength
- Let the code speak for itself
