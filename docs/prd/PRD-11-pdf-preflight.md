# PRD-11 — PDF preflight step

| Plan phase | Size | Depends on | Branch |
|---|---|---|---|
| PDF | M | 07, 02 | `feature/11-preflight` |

## Summary

A `PreflightPdf` step that checks each document for print-readiness (PDF version, encryption,
font embedding, page size, rotation) and records the result as document properties. Failing
documents are routed to exceptions, or fail the job, depending on configuration.

## Why

Catching a bad file before it reaches a printer is routine work in production print: a missing
font, the wrong page size or an encrypted file can waste a press run. Preflight is something
print workflow engineers set up and troubleshoot for customers, so building one shows you know
what goes wrong in practice.

## Users and scenarios

- **Workflow designer** adds a preflight step allowing Letter pages only, PDF ≤ 1.7, and
  embedded fonts (Standard 14 allowed).
- **Operator** sees `preflight=fail` and `preflightIssues="page 3: font Arial not embedded"` for
  a document in the exceptions queue.

## Goals

- Configurable checks: max version, not encrypted, fonts embedded (`allowStandard14`), allowed
  page sizes with tolerance, no unexpected `/Rotate`.
- `preflight` and `preflightIssues` document properties; routing via PRD-02 or `failJob: true`.
- Generator options that produce failing files.

## Non-goals

- Fixing files (embedding fonts, converting page sizes).
- Color management, image resolution, transparency or full PDF/X validation.

## Requirements

**Functional**
- **FR-1** Version check: the header version (or catalog `/Version`) is at or below a configured
  maximum.
- **FR-2** Encryption check: a trailer `/Encrypt` fails.
- **FR-3** Font check: every font's descriptor has `FontFile`, `FontFile2` or `FontFile3`;
  Standard 14 fonts pass only when `allowStandard14: true`.
- **FR-4** Page size: each page's MediaBox matches an allowed size within a tolerance (e.g.
  Letter ± 1 pt); a non-zero `/Rotate` fails unless allowed.
- **FR-5** Results: `preflight=pass|fail`; `preflightIssues` lists each issue with its page
  number and font name where relevant.
- **FR-6** `failJob: true` fails the job on the first failing document; otherwise documents are
  marked and routed by rules.
- **FR-7** Generator options for A4 pages, encryption, and an embedded font (an openly licensed
  font such as Liberation Sans).

**Non-functional**
- **NFR-1** Issue messages are written for operators: plain words, page numbers, no stack
  traces.

## Design notes

- Built on the PRD-07 reader for structure checks. Encryption fixtures use PDFBox's
  `StandardProtectionPolicy`.
- Check the bundled font's license before committing it.

## Acceptance criteria

| # | Criterion | Verified by |
|---|---|---|
| AC-1 | Each check has a passing and a failing case | `PreflightPdfStepTest.*` |
| AC-2 | Issues name the page and font | `issuesListNamesPageAndFont` |
| AC-3 | Failing documents land in exceptions with issues in `documents.csv` | `PreflightRoutingTest` |

## Test plan

- Unit — `PreflightPdfStepTest`: `encryptedFileFails`, `a4PageFailsLetterOnlyCheck`,
  `nonEmbeddedFontFails`, `embeddedFontPasses`, `standard14AllowedWhenConfigured`,
  `versionAboveMaximumFails`, `issuesListNamesPageAndFont`
- Workflow — `PreflightRoutingTest`

## Risks and open questions

- The generator's Helvetica is a non-embedded Standard 14 font, so the default statement run fails
  a strict check. The sample workflow sets `allowStandard14: true`, with a comment explaining
  why.

## Definition of done

- Shared DoD, plus: README lists the preflight checks and how to configure them.
