# Contributing to Faldony

Thanks for considering a contribution — we're glad you're here. Faldony is a
local-first, single-node project with a fairly opinionated architecture.
That doesn't mean it's closed off: it just means a little context goes a long
way. Here are a few light pointers to help your change land smoothly and
review quickly.

## Ground rules

- **Scope alignment.** Work that strengthens the project's direction is
  prioritized: local-first operation, a single-node footprint, PostgreSQL as
  the unified data layer, hybrid retrieval, durable workflow orchestration
  via Temporal, and a rich Kotlin Multiplatform client.
- **Backend stability.** The backend is still being hardened; behaviour can
  change. Prefer changes that preserve the existing invariants (content
  addressing, single-write finalize, the durable processing ledger, typed
  failure handling).
- **Style.** Kotlin idioms, no dead dependencies, and comments only where
  they explain *why*. New migrations must never silently revoke or alter
  already-applied ones.

## The standard GitHub flow

1. **Open an issue first.** Describe what you want to change, why, and how it
   fits the architecture. For bugs, include the repro and what you observed vs.
   expected.
2. **Ask to be assigned.** Comment on the issue asking for assignment before
   writing code — this signals you are committing to the work and avoids two
   people implementing the same thing. For issues touching external
   components, see the [prior research](#prior-research-before-asking-to-be-assigned)
   note first.
3. **Implement in a branch.** Create a feature branch off `master`.
4. **Open a pull request** referencing the issue (`Closes #<issue>`), with a
   focused diff and a short description of the approach.
5. **Review iterates.** Be responsive to review comments; a maintainer will
   follow up in a timely manner.

## Prior research before asking to be assigned

Getting assigned is easier when you can show you have already looked into the
issue — it signals intent to see the work through. This matters most for
issues that touch external components, because the root cause often lives
upstream rather than in Faldony:

- **Docling / `docling-serve` issues** — run your question past the domain
  specialist at https://app.dosu.dev/097760a8-135e-4789-8234-90c8837d7f1c; it
  knows `docling-serve` and can quickly tell whether the behaviour you see is
  a genuine bug, a configuration matter, or expected.
- **Temporal issues** — consult <https://docs.temporal.io/> (AI mode or the
  docs MCP) for anything touching workflows, activities, retries, schedules,
  or worker behaviour.
- **Everything else** — a quick search of the web, the relevant upstream
  GitHub repository, or official docs

If the problem spans several layers — say, a conversion failure inside
Docling that then misbehaves in the Temporal workflow — check each layer
against its source and see how the findings fit together into one coherent
explanation.

No need to be exhaustive: a few lines in your assignment request showing what
you found (and where) is enough. The point is to arrive with a rough idea of
the problem and a sketch of the fix — not to have solved it already.

## Pull request checklist

- [ ] Opened an issue and asked to be assigned
- [ ] Change is scoped
- [ ] Existing tests pass; new behaviour has test coverage where feasible
- [ ] Follows the code style of the surrounding code
- [ ] No secrets, absolute paths, or machine-specific configuration added
- [ ] Git history is clean and self-contained

## Code of conduct

Be respectful and constructive in issues, PRs, and reviews. Harassment or
hostile behaviour has no place in this project.