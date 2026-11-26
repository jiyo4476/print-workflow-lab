# PRD-11 — PDF preflight step

| Plan phase | Size | Depends on | Branch |
|---|---|---|---|
| PDF | M | 07, 02 | `feature/11-preflight` |

## Summary

A `PreflightPdf` step that checks each PDF document for print-readiness (PDF version, encryption,
embedded fonts, page size, rotation) and records the result as document properties. Documents
that fail are routed to exceptions, or fail the whole job, depending on configuration.

## Why

A bad file caught before it reaches a press is cheap. A missing font, the wrong page size or an
encrypted file found on the press can waste a run and a day of schedule. Preflight is routine
work for print workflow engineers, who set up the checks and explain failures to customers.
Building one shows that the project understands what goes wrong in production, not only how PDF
is structured.

## Users and scenarios

- **Workflow designer** adds a preflight step that allows Letter pages only, PDF version 1.7 or
  lower, and embedded fonts with Standard 14 fonts allowed.
- **Operator** opens the exceptions queue and sees a document with `preflight=fail` and
  `preflightIssues="page 3: font Arial not embedded"`. They can tell the customer exactly what to
  fix.
- **Developer** runs the sample workflow on a generated run, where every statement passes, and
  on one generated with a defect flag (for example `generate 4 a4.pdf --page-size a4`), where
  every statement fails, with the reasons in `documents.csv`.

## Goals

- Five configurable checks: maximum PDF version, not encrypted, fonts embedded (with a
  `allowStandard14` switch), allowed page sizes within a tolerance, and no unexpected `/Rotate`.
- Two document properties on every checked document: `preflight` (`pass` or `fail`) and
  `preflightIssues` (a readable list of problems).
- Routing through PRD-02 rules, or an immediate job failure with `failJob: true`.
- Generator options that produce deliberately failing files, so every check has a real example.

## Non-goals

- Fixing files: embedding fonts, scaling pages, removing encryption, or flattening rotation.
- Color management (ICC profiles, CMYK/RGB checks), image resolution, transparency, bleed and
  trim boxes, or full PDF/X or PDF/A validation.
- Checking fonts inside annotation appearance streams. Form XObjects are checked (FR-3).

## Requirements

**Functional**

- **FR-1** Version check. The version comes from the `%PDF-` header, or from the catalog
  `/Version` entry if present (the later one wins, as in the PDF spec). A version above the
  configured `maxVersion` fails. Default `maxVersion: 1.7`.
- **FR-2** Encryption check. A trailer `/Encrypt` entry fails with a message that says the file is
  password-protected and cannot be printed as supplied. In a workflow that splits first, a file
  that needs a password to open never reaches preflight: `SplitPdf` fails the job with the same
  words ("The input file is encrypted, so it cannot be printed as supplied: ..."), so the
  operator's `error.txt` gives the reason instead of PDFBox's "the password is incorrect". A file
  with only an owner password opens and splits, and its split documents are not encrypted.
- **FR-3** Font check. Every font reachable from a page must have a font descriptor that contains
  `/FontFile`, `/FontFile2` or `/FontFile3`. Fonts are collected from:
  - the page's `/Resources /Font` dictionary, and
  - the `/Resources /Font` dictionary of every Form XObject the page draws, directly or through
    another Form XObject, to any depth.

  A Standard 14 font (Helvetica, Times, Courier, Symbol, ZapfDingbats, and their bold and italic
  variants) with no descriptor passes only when `allowStandard14: true`. Each failure names the
  page where the font is used and the font's base name. A font used in several places is reported
  once per page.
- **FR-4** Page size check. Each page's effective MediaBox (width and height, in points) must
  match one of the configured `pageSizes` within `tolerance` points (default 1). The default
  `pageSizes` is `[letter]` (612 × 792 pt). A4 (595 × 842 pt) is available as a named size.
  Either orientation matches: a landscape Letter page (792 × 612 pt) passes a Letter check.
  Size names are case-insensitive (`A4` or `a4`).
- **FR-5** Rotation check. A `/Rotate` that turns the page (90, 180 or 270 degrees, or any
  equivalent such as −90) fails unless `allowRotate: true`; 0 and full turns (360, −360) pass.
  Only multiples of 90 are valid values; any other value, including a fraction such as 90.5,
  fails.
- **FR-6** Results. Every checked document gets `preflight=pass` or `preflight=fail`.
  `preflightIssues` lists every issue found, joined by `"; "` (a single CSV cell), each with the
  page number and, where relevant, the font name. After 20 issues the rest are counted ("and N
  more issues"). A passing document gets an empty `preflightIssues`.
- **FR-7** Failure handling. With `failJob: true`, the job fails at the first failing document,
  and the error names its sequence and first issue. Otherwise, failing documents are marked and
  routed by PRD-02 rules (the sample routes them to `exceptions`).
- **FR-8** Generator options for test files: `--page-size a4`, `--encrypt` (user password, using
  PDFBox `StandardProtectionPolicy`), and `--form-xobject-font` (draws each page's text inside a
  Form XObject with a non-embedded Liberation Sans, for AC-4). All text the generator draws
  otherwise uses embedded Liberation Sans 2.1.5 (SIL Open Font License 1.1): a per-document
  subset, embedded as a simple TrueType font with `/WinAnsiEncoding` so the PRD-09 extractor can
  read it. The generator never uses Helvetica or any other Standard 14 font, so no commercial font
  is drawn or embedded, and its files pass the strict font check. (Done in the follow-up to
  PR 36, branch `feature/11b-liberation-sans`.)

**Non-functional**

- **NFR-1** Issue messages are written for operators: plain words, page numbers, and no stack
  traces or internal class names. An internal error in the check itself fails the document with
  `preflightIssues` set to "could not check this file" and the cause logged, and does not crash the
  job unless `failJob` is set.
- **NFR-2** Checks are read-only. The step never changes the document bytes.

## Design notes

- Structure checks use the PRD-07 reader (object lookup, the trailer, the catalog and page
  tree). The font check walks page resources and then Form XObjects. The step does not use PDFBox
  for checks, so the rules are tested against the project's own reader. PDFBox is used only to
  create encrypted fixtures.
- Form XObject walk: track visited object references so a form that draws itself (directly or
  through a cycle) is visited once. Cap the nesting depth (default 16) and fail the document with a
  plain message if the cap is reached, rather than recursing without limit.
- Each Form XObject's `/Resources` is used for its own content. A font inherited from a parent
  page is not assumed to be embedded just because the parent's dictionary lists it.
- Standard 14 detection uses the `BaseFont` name, not the absence of a descriptor alone. A
  non-embedded font with a non-standard name (for example `Arial`) is always a failure.
- The sample workflow uses the strict default (`allowStandard14` unset), since the generator
  embeds Liberation Sans. `allowStandard14: true` stays available for customer files that use
  Standard 14 fonts, and a test covers it.
- Liberation Sans is metric-compatible with Arial, which matches Helvetica's widths, so text
  positions from the PRD-09 extractor did not move: after the switch the golden `dump-text`
  output (every glyph's x, y and width) and page 1's content stream are byte-for-byte the same.
  Only the font names and the added font objects changed in the `dump-pdf` and `dump-content`
  goldens.
- Font license: Liberation Sans 2.1.5 is under the SIL Open Font License 1.1 (checked in the
  release's `LICENSE`). The unmodified release files and the license text are in
  `src/main/resources/fonts/` (main, not test resources, because the `generate` CLI uses them).
  Subsets are made in memory and only embedded in generated PDFs, so no Modified Version of the
  font is stored, and the Reserved Font Name is not an issue.
- Subsets keep only the TrueType tables ISO 32000-1 9.9 requires (with hinting) plus `cmap`,
  `post`, `name` and `OS/2`. The layout tables (`GPOS`, `GSUB`, `kern`) are about 85 KB per face
  and a PDF never reads them, so they are dropped: each face is about 8 KB per file.
- Routing: preflight sets properties only. It does not decide routing itself, so PRD-02 remains the
  single place where failures are routed.

## Acceptance criteria

| # | Criterion | Verified by |
|---|---|---|
| AC-1 | Each check has a passing and a failing case | `PreflightPdfStepTest.*` |
| AC-2 | Issues name the page and font | `issuesListNamesPageAndFont` |
| AC-3 | Failing documents land in exceptions with issues in `documents.csv` | `PreflightRoutingTest` |
| AC-4 | A non-embedded font inside a Form XObject fails, naming the page; an embedded one passes | `PreflightPdfStepTest.nonEmbeddedFontInFormXObjectFails`, `embeddedFontInFormXObjectPasses` |

## Test plan

- Unit — `PreflightPdfStepTest`
  - `encryptedFileFails`
  - `a4PageFailsLetterOnlyCheck`
  - `nonEmbeddedFontFails`, `embeddedFontPasses`
  - `nonEmbeddedFontInFormXObjectFails`, `embeddedFontInFormXObjectPasses`
  - `fontInNestedFormXObjectIsChecked` (form drawn by a form, two levels down)
  - `formXObjectCycleDoesNotLoop` (a form that draws itself)
  - `formNestingLimitFailsWithPlainMessage`
  - `standard14AllowedWhenConfigured`
  - `versionAboveMaximumFails`
  - `rotatedPageFailsUnlessAllowed`
  - `issuesListNamesPageAndFont`
  - `internalErrorMarksDocumentWithoutCrashingJob`
  - `failJobStopsAtTheFirstFailingDocument`, `tooManyIssuesAreSummarised`,
    `subsetPrefixDoesNotHideANonEmbeddedFont`, `afpDocumentsAreLeftAlone`
- Unit — `PageSizeToleranceTest`: a MediaBox 1 pt off Letter passes; 1.1 pt off fails.
- Unit — `PreflightRoutingTest`: a failing document is routed to `exceptions` by a `RouteDocuments`
  rule on `preflight`, and keeps its issues as properties.
- Workflow — `PreflightWorkflowTest`: generated statements run `statements-preflight.yaml` to
  completion, and each document has `preflight=pass` with `preflight` columns in `documents.csv`.
- Manual: run the sample workflow, open the exceptions output, and check that each message would
  make sense to an operator without reading code.

## Risks and open questions

- Generated files are larger: two subset fonts add about 17 KB to each file, and to each
  document split from it (a 10-statement run went from 12 KB to 29 KB). The checked-in
  `two-statements.pdf` fixture went from 5 KB to 22 KB.
- Form XObject walking adds the most code in this PR. Cycles and deep nesting are covered by the
  visited set and depth cap above, and by their tests.
- Annotation appearance streams are still unchecked, so a document with a non-embedded font only in
  an annotation can pass. This is recorded as a known gap for a later PR.

## Deferred from this PR

- **The Liberation Sans switch.** Done in the follow-up branch `feature/11b-liberation-sans`:
  the generator embeds Liberation Sans, PR 9's fixture and golden dumps were regenerated and
  checked by hand, and the sample workflow is back to the strict default.
- Grouping a repeated per-page issue into one line with a page range, and recording a reason for
  every document routed to `exceptions`, are tracked in `docs/PR_PLAN.md` (PR 11 follow-ups).

## Definition of done

- Shared DoD, plus:
  - The README lists the five checks, the two document properties and how to configure each check.
  - The sample workflow (`workflows/statements-preflight.yaml`) runs and passes.
  - The items in "Deferred from this PR" are either done or tracked as a follow-up.
