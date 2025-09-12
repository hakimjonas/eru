# Eru Release Strategy: Private → Public Launch

## Phase 1: Private SNAPSHOT Release (This Week)

### 1. Configure SNAPSHOT Version
```scala
// In build.sbt
ThisBuild / version := "0.9-SNAPSHOT"
```

### 2. Setup CI Publishing (Mirror Valar)
```yaml
# Copy from Valar CI workflow
- name: Publish SNAPSHOT
  run: sbt publishSigned
  env:
    SONATYPE_USERNAME: ${{ secrets.SONATYPE_USERNAME }}
    SONATYPE_PASSWORD: ${{ secrets.SONATYPE_PASSWORD }}
    PGP_SECRET: ${{ secrets.PGP_SECRET }}
```

### 3. Verify Private Access
```scala
// SNAPSHOT dependencies in Valar
libraryDependencies ++= Seq(
  "net.ghoula" %% "eru-core" % "0.9-SNAPSHOT",
  "net.ghoula" %% "eru-runtime" % "0.9-SNAPSHOT"
)

// Add snapshot resolver
resolvers += "Sonatype snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/"
```

## Phase 2: Valar Integration (2-3 Weeks)

### Migration Tasks
- [ ] Replace custom effect type with `Eru[E, A]`
- [ ] Migrate fiber operations to Eru runtime 
- [ ] Update parallel operations (`parSequence`, `zipPar`)
- [ ] Migrate resource management to Eru patterns
- [ ] Update all tests and benchmarks
- [ ] Performance validation vs current Valar

### Integration Benefits
- **Proven performance**: Eru's 4-80x speed advantage
- **Cross-platform**: Native compilation ready
- **Zero maintenance**: Offload effect system to dedicated library
- **Future-proof**: Continued Eru development

## Phase 3: Joint 1.0 Public Release

### Eru 1.0.0 Features
- ✅ Production-ready (576+ tests passing)
- ✅ Exceptional performance validated
- ✅ Complete documentation
- ✅ Cross-platform support (JVM + Native)

### Valar 1.0.0 Features  
- **Powered by Eru**: High-performance effect system
- **Enhanced performance**: Inherit Eru's speed advantages
- **Native ready**: Cross-platform deployment
- **Simplified codebase**: Focus on domain logic

### Launch Messaging
- "Valar 1.0: Now powered by Eru effect system"
- "Next-generation functional programming stack"
- "Performance meets correctness in production"

## Release Coordination

### Timeline
- **Week 1**: Eru 0.9-SNAPSHOT published privately
- **Weeks 2-3**: Valar rebase and integration testing  
- **Week 4**: Joint 1.0 release announcement

### Marketing Strategy
- Technical blog posts comparing before/after performance
- Conference talks on effect system architecture
- Community adoption through proven production use