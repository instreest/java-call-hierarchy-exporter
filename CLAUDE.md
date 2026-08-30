# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A static analyzer that walks a Java project with Eclipse JDT Core and exports its call hierarchy as CSV. It is a **tool for investigating impact before a code change**: given an entry point, show every method reachable from it, in a form that opens in Excel and jumps back into Eclipse.

Documentation is in Japanese and is part of the deliverable, not an afterthought:

- `README.md` — user-facing: how to run it, what every CSV column means
- `config/config.properties` — **the single source of truth for settings.** Every key is documented inline; do not duplicate the list elsewhere
- `docs/DESIGN.md` — internal design, written so another AI session can reimplement the tool. Chapter 11 is a pitfalls table, chapter 12 is the acceptance criteria
- `docs/QA.md` — decisions made while implementing the dataflow analysis, with the reasoning
- `docs/QA-build.md` — same, for the build/run environment (jbangw, `//DEPS`/`//JAVA`, where downloads land)
- `docs/QA-issue29.md` — same, for the `callee` column format and how lambdas / method references are treated

**Read `docs/DESIGN.md` before changing analysis behavior.** Most of what looks like an easy improvement is something chapter 11 already records as a trap.

## Commands

`./jbangw` is the one-command path: it fetches JBang itself (from Maven Central, SHA-256
pinned), the dependency jars and a JDK 25 into `.jbang/` inside the project, leaving the
user's home untouched. There is **no build file** — the dependency (`//DEPS`), the JDK
(`//JAVA 25`) and console encoding (`//JAVA_OPTIONS`) are directives at the top of
`src/CallHierarchyExporter.java`; to javac they are plain comments. The JDK is pinned to 25
because JDT puts the running JVM's bootclasspath on the analysis classpath — the JDK it
runs on changes what resolves. `jbang-catalog.json` maps the alias `CallHierarchyExporter.java`
to `src/CallHierarchyExporter.java` so the Quick start command needs no `src/` prefix.
`javac` + `java` remains the supported path for users on a locked-down Windows/Pleiades box (see README).

```bash
./jbangw CallHierarchyExporter.java --args="config/config.properties"   # the one Quick start command
./jbangw src/CallHierarchyExporter.java config/config.properties        # same, without alias or --args= sugar
find .jbang/repository -name "*.jar" -exec cp {} lib/ \;                 # populate ./lib (copyLibs replacement)

javac -encoding UTF-8 -Xlint:all -cp "lib/*" -d bin src/CallHierarchyExporter.java
java -cp "bin:lib/*" CallHierarchyExporter config/config.properties
```

`main()` strips a leading `--args=` from its first argument — that sugar keeps the
documented command shape; the plain path form works identically.

There is **no test suite** — no `src/test`, no test task. Verification is done by running the tool:

1. **Self-analysis.** Point `config.properties` at this repo with `library.folders` set to `./lib`. Type-resolution failures must be **0**; the count is printed in the log.
2. **`docs/DESIGN.md` chapter 12.** Nine checklists covering naming, initializers, traversal, cache, dataflow, and output stability. When you add analysis behavior, add its criteria there and actually run them.
3. **Determinism.** Run twice and `diff` both CSVs — they must be byte-identical. Run cold and warm (cache deleted vs. reused) — also identical.

Build a throwaway project under the scratchpad with the Java shapes you care about and check the emitted rows; that is how every feature in `docs/QA.md` was validated.

## Architecture

`src/CallHierarchyExporter.java` is one ~5400-line file in the **default (unnamed) package**, with everything as nested `static` classes. This is deliberate — `docs/DESIGN.md` §3 explains it: the target environments cannot always run a build tool, so `javac` on one file has to work. Refactoring into multiple files is allowed but would break that property; discuss it before doing it.

### Three phases, joined by a cache file

```
Phase 1  CacheUpdater / CallEdgeExtractor   .java  → cache (TSV)
Phase 2  CallGraph.buildFrom                cache  → CSR graph (memory)
Phase 3  StreamingTreeWalker et al.         graph  → call-hierarchy.csv, methods.csv
```

Passing data between phases **through a file** is the load-bearing decision. It gives incremental re-analysis, and it keeps phase 1's results off the heap. Three memory rules follow from it and cannot be retrofitted (§10):

1. Never accumulate parse results — write each file's analysis to the cache and drop it.
2. Never hold edges as objects — the graph is CSR (`offsets[] / calleeIds[] / callLines[] / bindKinds[] / recvKinds[]` plus parallel `int` arrays indexing shared string pools).
3. Never build a tree — the DFS writes one CSV row at a time and keeps only the current path.

### Resolving which concrete class runs

The interesting part of the code. An interface-typed call is narrowed by stages, stopping at the first that decides (§7.3):

| Stage | Label | Basis |
|---|---|---|
| 0 | `STATIC_BOUND:*` | not virtually dispatched |
| 1 | `NO_OVERRIDE` / `SINGLE_IMPL` / `NO_IMPL` | only one candidate exists |
| 2 | `LOCAL_NEW(_MULTI)` | `new` in the same method |
| 3 | (extension label) | `TypeCandidateProvider` plugin |
| 4 | `DATAFLOW_FACTORY` | the factory method's `return` |
| — | `DATAFLOW_PARAM` / `DATAFLOW_FIELD` | path-dependent, applied in the walker |
| 5 | `CHA` | several candidates remain |

**Path-dependence is why `DATAFLOW_PARAM` / `DATAFLOW_FIELD` are not in `resolveEdge()`.** The same call site resolves differently depending on which root you reached it from, so they cannot be memoized per edge; the walker carries `pathParamTypes[depth]` / `pathCtorArgs[depth]` forward. Factory returns *are* path-independent, so they live in `resolveEdge()` and are memoized — which is also why `methods.csv` reflects them but not the other two.

`Origin` (`T:` new / `A:` param / `M:` return / `F:` field / `L:` string literal / `C:` `Class.forName` / `U:` unknown) is the shared representation for "where did this value come from", carried through the cache.

### The governing principle

**Failing to narrow is far less harmful than narrowing wrongly.** Output is used to decide what to re-test; "too many candidates" costs extra reading, "one candidate, and it was the wrong one" means a missed regression. So:

- One untrackable `return` disqualifies the whole method — record unknowns explicitly (`U`) rather than concluding from the subset.
- Anything that cannot be established is emitted as `CHA候補N件（未展開）: <reason>` — **never silently dropped.** A dropped call reads as "not called".
- Guards exist to refuse narrowing (a constructor that does not assign the field, a reassigned local, a non-`private`/`final` field). Removing one to raise the hit rate is a regression.

## Conventions

- **`src/CallHierarchyExporter.java` is CRLF**; the Markdown and properties files are LF. Flipping line endings turns a 200-line diff into a 5000-line one — check `file` before and after editing.
- Comments explain **why**, in Japanese, matching the surrounding density. The pitfalls in `docs/DESIGN.md` §11 are mostly annotated at their site in the code; keep that link when you touch them.
- Bump `CacheFormat.VERSION` on any format change. The cache header also carries the source level, so changing `source.level` invalidates it — anything that alters parse results must be part of that key.
- CSV: `call-hierarchy` must stay the **last** column (it is variable-length), and notes are appended as its last element rather than given a column of their own.
- `callee` is `fqcn.method(abbreviated param types)`; `root` and `call-hierarchy` stay short (`Class.method`). Abbreviating params can collide (`java.util.List` vs `other.List`), so colliding labels fall back to fully-qualified params — do not remove that fallback.
- Constructors are kept out of both CSVs as rows but stay in the hierarchy path, displayed as the class name; `<init>` survives only in `caller`, because Eclipse's Java Stack Trace Console needs that form.
- Lambda bodies are attributed to the **enclosing method**, not to a synthetic lambda method. Redirecting them would hide every lambda passed to a `java.**` API behind `exclude.packages` — `docs/QA-issue29.md` Q6 has the reasoning. Method references (`::`) are recorded as calls from the enclosing method.
- Keep `README.md`, `config/config.properties`, and `docs/DESIGN.md` consistent with the code in the same change. Several past bugs were the docs describing behavior the code never had.

## Working in this repo

Develop on the branch named in the task, commit, and push there. Pull requests are only opened when asked.
