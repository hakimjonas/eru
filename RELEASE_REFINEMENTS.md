# Eru 1.0.0 Release Refinements

This document contains a curated list of recommended refinements to bring the Eru codebase to a polished, production-ready 1.0.0 state. The suggestions are minor and focus on improving documentation, clarity, and maintainability in line with the project's manifesto.

---

### File: `eru-core/src/main/scala/net/ghoula/eru/Eru.scala`

This core file is in excellent condition. These suggestions aim for perfection.

1.  **Refine `Eru.fork` Scaladoc for "Confident Humility"**
    *   **Observation:** The current docstring for `Eru.fork` lists general library features like "Type-safe" and "Resource-safe" which apply to the entire library, not just this method.
    *   **Recommendation:** Focus the documentation entirely on the specific user-facing behavior of `fork`. This makes the documentation more precise and allows the library's overall safety to be a delightful discovery for the user, rather than an explicit claim in every method.

2.  **Consolidate `runSync` Logic to Reduce Duplication**
    *   **Observation:** The public entry points, `runSyncWithFibers` and `runSyncWithFibersAndObserver`, share a significant amount of implementation logic (e.g., initializing the scheduler, creating the fiber set, running the main loop, and draining finalizers).
    *   **Recommendation:** Introduce a new private helper method, `private def runProgram(...)`, to encapsulate this shared logic. The two public `runSync` methods would then become simple, clean wrappers around this core executor, improving maintainability.

3.  **Adjust Visibility of `executeWithFinalizers`**
    *   **Observation:** The method `Eru.executeWithFinalizers` is public, but its documentation notes it's for "runtime backends". With the refactoring away from an explicit backend system, its role is now purely internal.
    *   **Recommendation:** Change the visibility of `executeWithFinalizers` from `public` to `private[eru]`. This correctly hides it from the public API while keeping it accessible to necessary internal components.

---

### File: `eru-runtime/shared/src/main/scala/net/ghoula/eru/EruRuntime.scala`

This file's recent refactoring is a major architectural win. These suggestions focus on aligning the documentation with the new, superior implementation.

1.  **Refine `zipPar` Scaladoc for Clarity and Honesty**
    *   **Observation:** The documentation for `zipPar` still contains a sentence from the old implementation about immediate cancellation. The new implementation provides a stronger guarantee by running both effects to completion to ensure all finalizers execute.
    *   **Recommendation:** Update the documentation to accurately describe the current behavior. Explain that both effects are run to completion to ensure all resources are properly cleaned up, and that the first error is then propagated. This is a more honest and impressive description of the structured concurrency guarantees.

2.  **Refine `race` Scaladoc to Manage Expectations**
    *   **Observation:** The documentation for `race` attempts to explain the different behaviors on different backends (JVM vs. Native), which can be confusing.
    *   **Recommendation:** Simplify the documentation to describe the semantic intent: "Races two effects, returning the result of whichever completes first." Mention that the loser is "signaled to cancel" and that its finalizers are guaranteed to run. This provides a correct, high-level mental model for the user.

3.  **Improve `parSequence` Implementation for Efficiency**
    *   **Observation:** The current implementation of `parSequence` is recursive and builds up lists of fibers and exits, which can be inefficient for large lists.
    *   **Recommendation:** Refactor `parSequence` to be more direct. A more idiomatic and performant approach would be to use `Eru.fork` on all effects to get a `List[EruFiber[E, A]]`, and then use a single `parTraverse` on that list of fibers to `await` them all in parallel.

4.  **Refine `raceAll` Documentation**
    *   **Observation:** The documentation for `raceAll` is good but could be slightly more aligned with the "confidently humble" tone.
    *   **Recommendation:** Simplify the description to focus on the user-facing behavior: "Races multiple effects and returns the result of the first one to complete, along with its original index."

---

### File: `eru-core/src/main/scala/net/ghoula/eru/EruFiber.scala`

1.  **Clarify `EruFiber` Scaladoc**
    *   **Observation:** The main `EruFiber` trait documentation is good but could be more explicit about its nature as a pure handle.
    *   **Recommendation:** Add a sentence to the main Scaladoc emphasizing that an `EruFiber` is an immutable and pure *description* of a concurrent computation, not a running process itself.

2.  **Refine `await` Scaladoc**
    *   **Recommendation:** Add a note specifying that `await` will only ever return once. Subsequent calls to `await` on the same fiber will return the same `Exit` value immediately without re-executing any logic.

3.  **Refine `interrupt` Scaladoc**
    *   **Recommendation:** Add a sentence to clarify that interrupting a fiber is also a descriptive action. The interruption will be processed cooperatively by the fiber at its next safe interruption point.

---

### File: `eru-core/src/main/scala/net/ghoula/eru/Exit.scala`

1.  **Refine `Exit.Die` Scaladoc**
    *   **Recommendation:** Add a sentence explaining that this case represents an *unexpected* or *unrecoverable* error (a "defect" in the program's logic), as opposed to a typed error handled in `Exit.Failure`.

2.  **Refine `Exit.Interrupt` Scaladoc**
    *   **Recommendation:** Add a note clarifying that when a fiber is interrupted, its finalizers are still guaranteed to be executed before the fiber terminates.

---

### File: `eru-core/src/main/scala/net/ghoula/eru/EruObserver.scala`

1.  **Clarify `EruEvent.Step` Scaladoc**
    *   **Recommendation:** Add a note to its Scaladoc clarifying that this event is intended for low-volume, human-readable debugging traces and should not be used for high-frequency, performance-critical metrics.

