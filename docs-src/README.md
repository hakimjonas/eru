# Eru

Eru is a pragmatic and ergonomic effect system for Scala 3, built for correctness, performance, and a joyful developer experience. It serves as the powerful, cross-platform foundation for the [Valar](https://github.com/hakimjonas/valar) validation library.

This project is guided by a strong philosophical vision for what a modern effect system should be. To understand the design principles and goals of Eru, please read our core document:

### [**The Eru Manifesto**](./MANIFESTO.md)

## Status

Eru is currently in the initial design and development phase.

## License

Eru is licensed under the **MIT License**. See the [LICENSE](../LICENSE) file for details.

## Quickstart

Start here for the synchronous core and pure composition patterns:
- Eru Quickstart — Synchronous Core: [quickstart.md](./quickstart.md)

## Development Playbook

For the point-by-point execution plan aligned with our Manifesto and guidelines, see:
- Eru Development Playbook — Point-by-Point Plan: [PLAYBOOK.md](./PLAYBOOK.md)

## Async Runtime Direction

The path to fibers, Exit/Cause, cancellation, and observability:
- Async Runtime Direction — Fibers, Exit/Cause, and Observability: [design/async.md](./design/async.md)

## Guides

- Resource Safety — ensure and bracket: [resources.md](./resources.md)
- Observability — EruObserver, events, and .debug: [observer.md](./observer.md)

## Integrations

- Valar Integration Plan — Refactoring Valar on Eru: [integrations/valar.md](./integrations/valar.md)
- Valar repository (open-source): https://github.com/hakimjonas/valar
- Valar on Maven Central: see coordinates in Valar’s README, or search: https://search.maven.org/search?q=valar%20hakimjonas
