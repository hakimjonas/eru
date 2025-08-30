# Eru Development Playbook — Point-by-Point Plan

This playbook is the contributor’s entry point. It links to the authoritative plans and keeps the execution workflow front-and-center.

## Canonical References

- Immediate actions and roadmap to true concurrency: IMMEDIATE_ACTION.md
- Release-facing plan and acceptance criteria: RELEASE_PLAN.md
- Day-to-day execution checklist: WORKING_PLAN.md
- Roadmap and milestones: ROADMAP.md

## Unbreakable Workflow (Local)

1. Understand the task and map it to the Four Pillars.
2. Implement minimal, principled changes (pure core, zero casts).
3. Write/adjust tests for full logical coverage.
4. Run all checks locally:
   - `sbt check`
   - `sbt eruCoreJVM/test`
   - `sbt eruCoreNative/test`
   - `sbt eruRuntimeJVM/test`
   - `sbt eruRuntimeNative/test`
   - `sbt prepare`
5. Update the plan documents with a timestamped note.

## Docs Policy

- All user-facing documentation lives under docs-src.
- Filenames are UPPERCASE for top-level guides/plans.
- Root-level markdown files are legacy entry points; content is maintained only in docs-src.
