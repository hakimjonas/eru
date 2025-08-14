# Junie's Development Guidelines for the Eru Project

**Objective:** To ensure that every contribution to the `Eru` project is a direct reflection of its core manifesto,
adhering to the highest standards of correctness, code quality, and developer experience.

## 1. Core Mission

Your primary directive is to help build **Eru**, the definitive effect system for the discerning Scala 3 developer.
Every line of code you write must be in service of this goal. You are not just writing code; you are building a
benchmark for what a modern, principled, and ergonomic library can be.

Refer to the **[MANIFESTO.md](./docs-src/MANIFESTO.md)** as your guiding star. Its principles are not suggestions; they
are the law of this project.

## 2. The Four Pillars: Your Decision-Making Framework

Before implementing any feature or making any change, evaluate your approach against these four pillars.

### Pillar I: Correctness as the Unseen Foundation

- **Priority:** Correctness is the absolute, non-negotiable prerequisite. It is never to be traded for convenience or
  performance.
- **Action:** Implement features using a "correctness-first" approach. This means favoring pure functions, immutable
  data structures, and designs that can be proven sound.

### Pillar II: Radical Ergonomics

- **Priority:** The developer experience must be joyful and intuitive.
- **Action:** Design APIs that are fluent, discoverable, and feel like a natural extension of the Scala language. When
  adding features, consider how they can be presented to the user in the simplest, most elegant way possible.

### Pillar III: A "Pit of Success"

- **Priority:** The easiest, most obvious way to use `Eru` must also be the most correct, performant, and resource-safe
  way.
- **Action:** When designing functions or APIs, structure them so that the developer is naturally guided toward the best
  practice.

### Pillar IV: Exceptional Observability

- **Priority:** The runtime must not be a black box.
- **Action:** Ensure that new features are designed with diagnostics in mind. Errors should be rich, structured data.
  Where appropriate, features should integrate with the `EruObserver` pattern.

## 3. Scala 3 Language and API Design Directives

`Eru` must be an exemplary showcase of modern Scala 3. You are directed to prefer modern language features to their
predecessors, as they are the tools we use to achieve our core principles.

- **Use `enum` for Algebraic Data Types (ADTs):** Prefer `enum` over `sealed trait` and `case class` hierarchies for
  defining new data types like `Eru` or `ExitCase`.
- **Use `opaque type` for Domain Integrity:** When creating a new type that wraps a primitive (e.g., `FiberId`), you *
  *must** use an `opaque type` to enforce type safety.
- **Use `extension` Methods for Fluent APIs:** The primary mechanism for enriching core data types like `Eru[A]` is
  through `extension` methods.
- **Use `given` and `using` for Typeclasses:** This is the modern, idiomatic way to handle contextual abstractions in
  Scala 3.
- **Embrace Compositional Types (`&` and `|`):** Leverage intersection and union types to model requirements and
  outcomes with maximum precision.

## 4. Documentation and Comments

The project's code must be self-documenting, and its public API must be impeccably documented for the end-user.

- **No Inline Comments:** You **must not** leave inline code comments (e.g., `// This does X`). Code should be made
  clear through expressive variable names, well-named functions, and clean structure. If a piece of code is so complex
  that it requires a comment to be understood, your first action is to refactor the code to be simpler.
- **Scaladoc for All Public APIs:** Every single `public` member (class, trait, enum, method, value) **must** have a
  complete and well-written `scaladoc` comment. This documentation is user-facing and must clearly explain the purpose
  of the member, its parameters (`@param`), what it returns (`@return`), and any relevant usage examples.
- **We use mdoc for Documentation:** All project documentation should be written in Markdown and placed in the
  `docs-src` directory. This includes the main project documentation, guides, and any other relevant information.

## 5. The Unbreakable Development Workflow

You must follow this workflow for **every single contribution**. No steps may be skipped.

1. **Understand the Task:** Clearly state the goal of the change you are about to make, referencing the relevant pillar(
   s).
2. **Implement the Code:** Write clean, self-documenting Scala code that adheres to all directives.
3. **Write Comprehensive Tests:**
    - Every new public function or feature **must** be accompanied by a comprehensive test suite.
    - Your goal is **full and correct test coverage**. Aim for logical coverage of all possible paths.
4. **Run All Checks Locally:** Before finalizing your contribution, you **must** run the following sbt commands from the
   project root and ensure they all pass.
    - `sbt check`: Validates formatting, linting, and documentation.
    - `sbt eruCoreJVM/test`: Runs all tests on the JVM.
    - `sbt eruCoreNative/test`: Runs all tests on Scala Native.
5. **Prepare for Submission:** Once all checks and tests pass, run the final preparation command.
    - `sbt prepare`: Applies all fixes and regenerates documentation.

Only after completing all five steps is a contribution considered ready.

## 6. Code Quality and Style

- All code must be formatted with **`scalafmt`**. The configuration is in `.scalafmt.conf`.
- All code must be free of linting errors as defined by **`scalafix`**. The configuration is in `.scalafix.conf`.
