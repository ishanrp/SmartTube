# Fork overrides

SmartTube uses `MediaServiceCore` as an upstream git submodule. This fork keeps
that submodule untouched so it can still be updated normally, while applying a
small set of fork-specific source overrides at Gradle configuration time.

## Local subscription cache

`RssService.kt` is overridden to make anonymous/local subscription groups less
bursty and more resilient:

- per-channel in-memory cache with a 10-minute fresh TTL;
- stale-while-revalidate for up to 24 hours;
- last-known-good fallback when YouTube/RSS/enrichment requests fail;
- a five-minute retry backoff after failures;
- a global limit of four concurrent channel fetches;
- single-flight locking per channel;
- protection against a partial/empty channel enrichment response wiping a valid
  RSS feed.

The override is copied over the checked-out submodule source from the root
`build.gradle`. GitHub Actions checks out submodules before invoking Gradle, so
the same source is used both locally and in CI.

This deliberately avoids forking `MediaServiceCore` just to carry one patch.
When upstream changes `RssService.kt`, review this override against the new
upstream version before syncing the submodule pointer.
