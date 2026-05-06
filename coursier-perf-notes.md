# Coursier resolution performance notes

Captured from a `nix run -- lock` against a cross JVM+Native project with a
heavy test scope (cats-effect-laws etc.) — process pid 18862, ~1m35s elapsed.

## Symptoms

App appears to hang. `jstack` shows two `Fetch.fetchResult` calls in flight
concurrently (parTraverse over targets × parMapN over compiler/lib/native/test
fetches). Two `coursier-pool-1-thread-N` threads burn 40+ seconds of CPU each
while the rest of the pool is parked. State is RUNNABLE the whole time — not
deadlocked, just very slow.

## Root causes (two issues, both upstream-fixable)

### 1. Per-step O(n²) recomputation in `Resolution` lazy vals

Hot stack on `coursier-pool-1-thread-3`:

```
Resolution.reverseDependencies (lazy val, lzycompute)
  -> Resolution.remainingDependencies (lazy val, lzycompute)
    -> Resolution.newDependencies (lazy val, lzycompute)
      -> Resolution.nextNoMissingUnsafe
        -> Resolution.nextIfNoMissing
          -> ResolutionProcess.run0  (loop)
```

`ResolutionProcess.run0` advances the resolution one step at a time via
`Continue.nextNoCont`. Each step constructs a *new* `Resolution` instance with
fresh empty lazy-val slots, so `reverseDependencies` and friends are
recomputed from scratch over the full (growing) dependency set on every
iteration.

**Fix shape**: thread an incremental reverse-deps map through the resolution
loop instead of recomputing from `dependencies` on each step. Lives in
`coursier.core.Resolution` (modules/core/shared/.../Resolution.scala).
Moderate difficulty.

### 2. Global `Dependency` cache + slow `equals` under parallel fetches

Hot stack on `coursier-pool-1-thread-1`:

```
Resolution.merge0
  -> Dependency.withVersionConstraint
    -> Dependency.apply
      -> Cache.cacheMethod
        -> ConcurrentReferenceHashMap.get
          -> Dependency.equals
            -> MinimizedExclusions.equals
              -> HashSet.equals  (O(n))
                -> Tuple2.equals
                  -> BoxesRunTime.equals2
```

`coursier.util.Cache.cacheMethod` uses a process-wide
`ConcurrentReferenceHashMap`. With multiple Fetches running concurrently, the
shared map fills faster, so each `equals` check (O(n) over `MinimizedExclusions`)
walks more entries. Pathological N×N when the test scope is large.

**Fix shape**: cache `hashCode` on `Dependency` (or specifically on
`MinimizedExclusions`) so `equals` fast-fails on hash mismatch. Or scope the
cache per-resolution. Simple PR, large payoff.

## Files to look at upstream

- `modules/core/shared/src/main/scala/coursier/core/Resolution.scala`
  (`reverseDependencies`, `remainingDependencies`, `newDependencies`,
  `nextNoMissingUnsafe`, `merge0`)
- `modules/util/shared/src/main/scala/coursier/util/Cache.scala`
  (`cacheMethod`)
- `modules/core/shared/src/main/scala/coursier/core/Dependency.scala`
- `modules/core/shared/src/main/scala/coursier/core/MinimizedExclusions.scala`

## Practical mitigation in scala-cli-nix (not done)

Even after upstream fixes, capping concurrent `Fetch.fetchResult` calls is
sensible. A `Semaphore`/`parTraverseN(N)` (e.g. N=2-3) on `fetchArtifacts`
keeps some pipelining (download IO overlaps CPU resolution) without the
equals-storm on the shared `Dependency` cache.

## Repro

Cross JVM+Native project with a big test scope (cats-effect-laws is a good
stress test). Run `nix run -- lock` and `jstack` the JVM after ~30s. Look for
RUNNABLE threads pinned in `Resolution.merge0` and `Resolution.reverseDependencies`.
