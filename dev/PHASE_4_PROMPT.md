## Phase 4: Finalization and Validation (Refined Prompt)

Objective

To complete the unified fiber runtime by implementing a comprehensive test suite, removing all obsolete code, and ensuring all new and modified code is documented to the highest standard, preparing the project for a 1.0.0 release.

Context

Phases 1-3 have established the core fiber system and the pure, transparent concurrency combinators. Phase 4 is the final step, focused on rigorous quality assurance, cleanup, and documentation to deliver a production-ready product.

## Implementation Tasks

Task 1: Comprehensive Fiber Runtime Test Suite (CRITICAL)

This is the highest priority. The goal is to build a test suite that rigorously proves the correctness of the new fiber runtime.

Create a dedicated test directory eru-runtime/shared/src/test/scala/net/ghoula/eru/fiber/ and implement the following:

    FiberLifecycleSpec.scala: Test the complete fiber lifecycle (fork, await, success, failure, interruption).

    FiberInterruptionSpec.scala: Test complex interruption scenarios, ensuring cancellation is propagated correctly.

    FiberFinalizerIntegrationSpec.scala: Critically important. Add extensive tests to verify that FILO (First-In-Last-Out) finalizer semantics are perfectly preserved across all concurrent operations (zipPar, raceAll, etc.) and interruption scenarios.

    FiberErrorPropagationSpec.scala: Test that errors and defects are correctly propagated between parent and child fibers.

    FiberStressSpec.scala: Implement stress tests to ensure the runtime is stable under high load (e.g., thousands of concurrent fibers) and that deep fiber nesting does not cause stack or memory issues.

    FiberPropertySpec.scala: Implement property-based tests to verify that the fork/await operations are referentially transparent and preserve monad laws.

Task 2: Code Removal and Cleanup

With the new pure combinators in EruRuntime.scala now implemented and tested, the old backend infrastructure is obsolete.

    Identify and Remove Obsolete Code: Go through the eru-runtime module and completely remove the old ConcurrencyBackend.scala and VTOnlyBackend.scala implementations, along with the BackendProvider service loader mechanism.

    Simplify EruRuntime.scala: Remove any remaining delegation logic that points to the now-deleted backend. The EruRuntime object should now only contain the pure implementations.

Task 3: Final Documentation Pass

This task is to ensure the project's documentation meets the high standards of the manifesto.

    Review All Modified Files: Go through every file that has been created or modified during the entire four-phase refactor (e.g., Eru.scala, EruFiber.scala, EruRuntime.scala, and all new internal files).

    Update All Scaladoc: Ensure all documentation is:

        User-Facing: Explains what a function does and why a user would use it, not internal implementation details.

        Correct and Up-to-Date: Accurately reflects the new, unified, fiber-based reality of the codebase.

        Aligned with the Manifesto: Uses professional language consistent with the principles of Correctness, Ergonomics, Guided Correctness, and Observability.

## Success Criteria

The refactor is complete and successful when:

    The new comprehensive test suite is fully implemented and all tests pass.

    All old, obsolete backend code has been completely removed from the codebase.

    The Scaladoc for all new and modified components is complete, user-facing, and accurate.

    The project compiles cleanly, and all existing tests continue to pass.