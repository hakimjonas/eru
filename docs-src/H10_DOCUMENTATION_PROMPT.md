# H.10 DOCUMENTATION EXCELLENCE — EXECUTION PROMPT

## OBJECTIVE
Complete the documentation infrastructure and align all content with Manifesto standards to deliver exceptional user experience for the Eru alpha release.

## CONTEXT
The Eru project has validated performance (best-in-class across most benchmarks) and completed alpha hardening. The core is production-ready, but the documentation infrastructure needs completion to match the code quality standards.

## CURRENT ISSUES IDENTIFIED
1. **Broken README references**: Links point to root directory files that actually exist in `docs-src/`
2. **Missing mdoc integration**: No documentation generation despite comprehensive `docs-src/` content
3. **No mkdocs configuration**: Missing site generation infrastructure
4. **Content alignment needed**: Docs need audit against current reality and Four Pillars

## EXECUTION PHASES

### Phase 1: Fix README.md References
**Problem**: README.md contains broken links to guides
```markdown
- **Quickstart — Synchronous Core and Pure Composition**: quickstart.md
- **Resource Safety — Patterns with .ensure and .autoClose**: resources.md
- **Concurrency — Fibers and structured concurrency**: concurrency.md
- **Observability — EruObserver and debugging**: observer.md
```

**Solution**: Update to point to actual locations in `docs-src/`
```markdown
- **Quickstart — Synchronous Core and Pure Composition**: docs-src/QUICKSTART.md
- **Resource Safety — Patterns with .ensure and .autoClose**: docs-src/RESOURCES.md
- **Concurrency — Fibers and structured concurrency**: docs-src/CONCURRENCY.md
- **Observability — EruObserver and debugging**: docs-src/OBSERVER.md
```

**Validation**: All links should resolve correctly when viewed on GitHub

### Phase 2: Add mdoc Plugin and Build Infrastructure
**Problem**: No documentation generation despite comprehensive markdown content

**Solution**: Add to `build.sbt`
```scala
// Add to plugins.sbt or inline
addSbtPlugin("org.scalameta" % "mdoc" % "2.6.1")

// Add to build.sbt in root project settings
lazy val docs = project
  .in(file("eru-docs"))
  .enablePlugins(MdocPlugin)
  .settings(
    mdocIn := file("docs-src"),
    mdocOut := file("docs"),
    mdocVariables := Map(
      "VERSION" -> version.value
    ),
    publish / skip := true
  )
  .dependsOn(eruCoreJVM, eruRuntimeJVM)

// Add documentation aliases
addCommandAlias("docs", "docs/mdoc")
addCommandAlias("docsWatch", "docs/mdoc --watch")
```

**Validation**: `sbt docs` should generate documentation without errors

### Phase 3: Create mkdocs.yml Configuration
**Problem**: No site generation infrastructure

**Solution**: Create `mkdocs.yml` in project root
```yaml
site_name: Eru Effect System
site_description: The definitive effect system for discerning Scala 3 developers
site_url: https://hakimjonas.github.io/eru
repo_url: https://github.com/hakimjonas/eru
repo_name: hakimjonas/eru

nav:
  - Home: index.md
  - Manifesto: MANIFESTO.md
  - Quick Start: QUICKSTART.md
  - Guides:
    - Resources: RESOURCES.md
    - Concurrency: CONCURRENCY.md
    - Observer: OBSERVER.md
  - Development:
    - Immediate Action: IMMEDIATE_ACTION.md

theme:
  name: material
  palette:
    - scheme: default
      primary: deep purple
      accent: amber
  features:
    - navigation.tabs
    - navigation.sections
    - navigation.expand
    - search.highlight

markdown_extensions:
  - admonition
  - codehilite
  - toc:
      permalink: true
  - pymdownx.superfences
  - pymdownx.tabbed

docs_dir: docs
```

**Validation**: mkdocs should build site successfully

### Phase 4: Content Audit and Alignment
**Problem**: Documentation may not reflect current reality or Manifesto standards

**Audit Checklist for each document in `docs-src/`**:

1. **MANIFESTO.md**: ✓ Already aligned with Four Pillars
2. **QUICKSTART.md**: Verify examples work with current API
3. **RESOURCES.md**: Ensure `.ensure` and `bracket` examples are accurate
4. **CONCURRENCY.md**: Update to reflect "concurrency-lite" current status
5. **OBSERVER.md**: Verify EruObserver examples and event descriptions
6. **IMMEDIATE_ACTION.md**: ✓ Just updated

**Content Standards**:
- All code examples must compile and run
- No overclaims about capabilities
- Clear distinction between current and future features
- Alignment with Four Pillars: Correctness, Ergonomics, Guided Correctness, Observability
- Professional tone matching Manifesto quality

**Tone and Voice Guidelines**:
- **Neutral and factual**: Present capabilities and performance results objectively without hyperbole
- **Professional humility**: Acknowledge strengths without ego-driven language or "loudtalking super dev" tone
- **Architectural focus**: Emphasize good design principles, pragmatism, and user-friendliness over performance tricks
- **Balanced messaging**: Showcase excellent performance naturally within context, not as primary selling point
- **Respectful positioning**: Compare with other libraries factually, avoiding dismissive or superior language
- **Value-driven narrative**: Position Eru as result of combining correctness, documentation quality, and architectural soundness

**Validation Steps**:
- All markdown compiles through mdoc without errors
- Code examples execute successfully
- Links resolve correctly
- Content reflects current reality accurately

## ACCEPTANCE CRITERIA
- [ ] All README.md guide references resolve correctly
- [ ] `sbt docs` generates documentation successfully
- [ ] mkdocs builds site without errors
- [ ] All code examples in docs compile and run
- [ ] Content accurately reflects current capabilities
- [ ] No broken links or outdated information
- [ ] Documentation quality matches code quality standards
- [ ] Four Pillars principles clearly reflected throughout

## ALIGNMENT WITH MANIFESTO
This work directly serves:
- **Pillar II (Ergonomics)**: Exceptional developer experience through clear documentation
- **Pillar III (Guided Correctness)**: Users guided to correct patterns through good docs
- **Pillar IV (Observability)**: Transparent documentation of capabilities and limitations

## SUCCESS METRICS
- Documentation builds cleanly in CI/CD
- Users can follow guides without confusion
- No GitHub issues about broken documentation links
- Professional presentation matching the code quality standards