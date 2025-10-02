# Performance Audit - Deep Chaining Issues

## Problem Pattern Identified

Using `foldLeft` with `flatMap` creates deeply nested closure chains that cause massive memory allocation and poor performance. This was the root cause of the 15MB allocation in `parSequence`.

## Affected Core Methods (eru-core)

All in `/eru-core/src/main/scala/net/ghoula/eru/Eru.scala`:

1. **`foreachDiscard`** (line 673)
   - Uses: `as.foldLeft(succeed(()))((accEru, element) => accEru.flatMap(...))`
   - Impact: Used in many places for side effects

2. **`collectAll`** (line 695)
   - Uses: `as.foldLeft(succeed(List.empty[B])) { (accEru, element) => accEru.flatMap(...)}`
   - Impact: Used by parallel operations

3. **`collectAllDiscard`** (line 714)
   - Uses: `as.foldLeft(succeed(List.empty[A])) { (accEru, effect) => accEru.flatMap(...)}`
   - Impact: Similar to collectAll

4. **`repeatN`** (line 989)
   - Uses: `(1 to n).foldLeft(succeed(start)) { (accEru, _) => accEru.flatMap(step)}`
   - Impact: Creates n-level deep chain

5. **`unfold`** (line 1030)
   - Uses: `foldLeft` with flatMap for iterative building
   - Impact: Can create very deep chains for large iterations

6. **`sequence`** (line 1069)
   - Uses: `foldLeft(succeed(List.empty[A])) { (accEru, effect) => accEru.flatMap(...)}`
   - Impact: Already caused 15MB allocation issue in parSequence

7. **`traverse`** (line 1106)
   - Uses: `foldLeft(succeed(List.empty[B])) { (accEru, input) => accEru.flatMap(...)}`
   - Impact: Core method used everywhere, including parSequence

## Affected Runtime Methods

1. **`Queue.putAll`** (`/eru-runtime/shared/src/main/scala/net/ghoula/eru/QueueImpl.scala:148`)
   - Uses: `as.foldLeft(Eru.unit) { (acc, a) => acc.flatMap(_ => put(a).eru)}`
   - Impact: Queue performance when batch inserting

2. **`parSequence` awaiting fibers** (`/eru-runtime/shared/src/main/scala/net/ghoula/eru/EruRuntime.scala:569`)
   - Uses: `Eru.traverse(fibers)(_.await)`
   - Impact: Already fixed the fork part, but await still uses traverse

## Solution Approaches

### Pattern 1: Tail Recursive Implementation
Instead of foldLeft with flatMap, use tail recursion:
```scala
@tailrec
def loop(remaining: List[A], acc: List[B]): Eru[E, List[B]] =
  remaining match {
    case Nil => Eru.succeed(acc.reverse)
    case head :: tail =>
      // Process head and continue
  }
```

### Pattern 2: Direct Iteration in Effect
Wrap the entire iteration in a single effect:
```scala
Eru.effect {
  val results = new ArrayBuffer[B]()
  for (item <- items) {
    results += process(item).unsafeRunSync()
  }
  results.toList
}
```

### Pattern 3: Chunked Processing
Break large collections into chunks to limit chain depth:
```scala
def processChunked[A, B](items: List[A], chunkSize: Int)(f: A => Eru[E, B]): Eru[E, List[B]] = {
  items.grouped(chunkSize).foldLeft(Eru.succeed(List.empty[B])) { (acc, chunk) =>
    // Process chunk with limited depth
  }
}
```

## Priority Order

### High Priority (Core bottlenecks)
1. `traverse` - Used everywhere, including in parSequence
2. `sequence` - Core collection operation
3. `foreachDiscard` - Common for side effects

### Medium Priority (Runtime operations)
4. `Queue.putAll` - Affects queue performance
5. `collectAll` / `collectAllDiscard` - Collection operations

### Low Priority (Less frequently used)
6. `repeatN` - Usually small n values
7. `unfold` - Special case iteration

## Testing Strategy

1. Create benchmarks for each affected method
2. Measure memory allocation before/after
3. Test with various collection sizes (10, 100, 1000, 10000 items)
4. Ensure stack safety is maintained

## Expected Impact

Based on the `parSequence` fix:
- Memory reduction: 100-300x
- Performance improvement: 50-300x
- This could significantly improve Eru's performance across the board