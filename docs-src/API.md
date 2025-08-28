# API Documentation (Scaladoc)

Eru ships with aggregated Scaladoc for the public API surface. This complements our mdoc guides and provides a discoverable reference for every public member.

## How to generate locally

- Generate aggregated API docs (JVM modules):

```
sbt genApiDocs
```

- Output location (default):
  - `target/scala-3.7.2/unidoc/` (HTML Scaladoc aggregated across modules)

Notes:
- The aggregation covers `eru-core` (JVM) and `eru-runtime` (JVM) so users get a single unified API view.
- We intentionally keep site deployment as a later, separate step per the Release Plan.

## Why this matters

- Correctness & Guided Correctness: complete, authoritative docs for all public APIs reduce misuse.
- Ergonomics: unified entry points and discoverable signatures make the library feel native to Scala 3.
- Observability: public observer types and events are fully documented and browsable.

See also the guides in this `docs-src` folder for task‑oriented documentation.
