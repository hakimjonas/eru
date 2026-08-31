### The Eru Manifesto

**Vision:** Eru is an effect system designed from first principles for Scala 3 and the JVM's virtual threads. ZIO and Cats Effect informed many of its design decisions. Working only against modern platforms, Scala 3's type system and Project Loom, opens design choices that backward compatibility would preclude.

Its worth is shown by the integrity of its own design, its lawfulness, and its behavior in operation, not by ecosystem breadth.

---

### Pillar I: Foundational Correctness

Correctness is the base layer; everything else builds on a deterministic, lawful core.

- **Pure program representation:** `Eru[E, A]` is a total, immutable description of a program. Interaction with the outside world is suspended inside the `Eru` context, preserving referential transparency.
- **Type-directed guarantees:** The Scala 3 type system is the primary correctness tool. Opaque types, GADTs, and compositional types encode domain and effect constraints, so errors surface at compile time rather than as runtime checks.
- **Law validation:** Adherence to the fundamental laws of functional programming is asserted with property-based tests.

---

### Pillar II: Pragmatic Ergonomics

Useful operations should not require verbosity.

- **Combinators on the type:** Retries, timeouts, and resource patterns are available as discoverable combinators on the `Eru` type.
- **Clarity over ceremony:** The API stays minimal and direct, favoring plain composition.

---

### Pillar III: Guided Correctness

The easiest path should be the correct one.

- **Resource discipline by design:** `bracket`/`ensure` encode acquisition, use, and release so the ergonomic path is the safe one.
- **Managed concurrency:** High-level concurrency primitives take over scheduling and isolation, making correct concurrent code the default.
- **Explicit blocking boundaries:** Blocking code runs through `Eru.blocking(...)`, keeping the application responsive by default.

---

### Pillar IV: Runtime Observability

A running program should not be a black box.

- **Structured failure data:** Errors are structured data, not opaque strings or exceptions.
- **Low-overhead tracing hooks:** Optional event emission gives timing, causality, and correlation without invasive instrumentation.
- **Unified observer interface:** `EruObserver` is the single integration surface for logging, metrics, and tracing backends.

---

Eru builds on ideas from ZIO and Cats Effect, and aims to be the effect system a Scala 3 codebase reaches for first.

---

*Eru is designed and developed by Hakim Jonas Ghoula.*
