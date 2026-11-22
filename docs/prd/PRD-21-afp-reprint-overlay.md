# PRD-21 — Mark AFP reprints with a page overlay

| Plan phase | Size | Depends on | Branch |
|---|---|---|---|
| AFP | M | 15 | `feature/21-afp-reprint-overlay` |

> **Order:** added after the plan was written. It builds on PRD-15 and can land any time after
> it.

## Summary

PRD-15 marks reprinted AFP pages by adding a "REPRINT" text object to every page. This PR adds
the production approach: a `REPRINT` **page overlay**, defined once as an inline resource and
referenced from every page with `MPO` and `IPO`. The reprint step can then use either mark.

## Why

- Overlays are how production AFP systems add marks, logos and forms: the content is defined
  once and placed on as many pages as needed. Building one shows AFP resources, not just page
  text, which is the more interview-relevant skill.
- It reuses PRD-15's `AfpRewriter` and `PageBuilder`, so the new work is the resource and its
  references.
- Keeping both marks lets the demo compare them: the same visible result, built two ways.

## Users and scenarios

- **Workflow designer** sets `mark: overlay` on the reprint step. Reprinted AFP documents carry
  one `REPRINT` overlay in their resource group, and every page shows it.
- **Developer** runs `dump-afp` on a reprinted document and sees the overlay resource, plus the
  overlay name and position on each page's `MPO` and `IPO`.

## Goals

- An `afp/OverlayWriter` that writes a named overlay resource.
- Inline resource group handling: create the group, or add to an existing one.
- `MPO` and `IPO` encoders, and their decoding in `dump-afp`.
- A `mark: text | overlay` param on the reprint step.

## Non-goals

- Medium overlays applied by a form definition (`BFM`/`BMM`/`MMO`). Explain the difference in the
  interview instead; see the design notes.
- Overlays from an external resource library, or overlays containing images or graphics.
- Removing the per-page text object from PRD-15; it stays as `mark: text`.

## Requirements

**Functional**
- **FR-1** With `mark: overlay`, the document's inline resource group holds one `REPRINT` page
  overlay:
  ```
  BRG                      created at the front of the file if the document has none
    BRS REPRINT            Resource Object Type triplet (X'21'): overlay
      BMO REPRINT
        BAG
          MCF              the same font as the PRD-15 mark
          PGD              the overlay's own size, e.g. 2 x 0.5 inches
          PTD
        EAG
        BPT
          PTX              "REPRINT"
        EPT
      EMO
    ERS
  ERG
  ```
- **FR-2** If the document already has a resource group, the overlay is added to it rather than
  in a second group. Reprinting a document that already has the overlay doesn't add another.
- **FR-3** Every page maps the overlay with an `MPO` (Map Page Overlay) inside its `BAG … EAG`,
  and places it with an `IPO` (Include Page Overlay) after `EAG`. The position comes from the
  same step param as PRD-15's mark, converted to the page's units.
- **FR-4** `mark: text` (the default until this PR is checked in a viewer) keeps PRD-15's
  behavior; `mark: overlay` uses FR-1 to FR-3. The `Reprint` TLE is added either way.
- **FR-5** `dump-afp` shows the overlay name and position for `MPO` and `IPO`, and the resource
  type for `BRS`.
- **FR-6** `SplitAfp` (PRD-05) keeps copying the inline resource group into every split
  document, so an overlay survives a later split.

**Non-functional**
- **NFR-1** Fields other than the inserted ones are copied byte for byte, as in PRD-15.

## Design notes

- **Page overlay vs. medium overlay:** a page overlay is placed by the page data, on chosen pages,
  as here. A medium overlay is applied to every sheet by the form definition, with no change to
  the page data. A reprint mark could use either; the medium overlay needs a form definition,
  which this project doesn't build.
- Check the Resource Object Type code for an overlay (expected X'FC') and the `MPO` and `IPO`
  layouts against the MO:DCA reference before relying on them.

## Acceptance criteria

| # | Criterion | Verified by |
|---|---|---|
| AC-1 | With `mark: overlay`, one inline `REPRINT` overlay, and `MPO` + `IPO` on every page | `ReprintOverlayTest.*` |
| AC-2 | An existing resource group gets the overlay added; reprinting twice adds nothing | `existingResourceGroupGetsOverlayAdded`, `reprintingTwiceDoesNotDuplicateTheOverlay` |
| AC-3 | `mark: text` still behaves as PRD-15 | `ReprintAfpTest.*` still passes |
| AC-4 | The dump decodes the new fields | `DumpAfpTest.dumpShowsOverlayNameAndPositionForMpoAndIpo` |
| AC-5 | The overlay shows on the page in a real viewer | Manual: Papyrus AFP Viewer screenshot in the PR |

## Test plan

- Unit — `ReprintOverlayTest`: `overlayResourceIsInlineOnce`,
  `existingResourceGroupGetsOverlayAdded`, `everyPageMapsAndIncludesTheOverlay`,
  `reprintingTwiceDoesNotDuplicateTheOverlay`, `reprintedAfpReadsBackCleanly`,
  `originalPageFieldsAreUnchanged`
- Unit — `OverlayWriterTest.writesBalancedOverlayResource`
- Unit — `SplitAfpStepTest.overlayInResourceGroupSurvivesSplit`
- Unit — `DumpAfpTest.dumpShowsOverlayNameAndPositionForMpoAndIpo`
- Manual: open a reprinted AFP document in Papyrus AFP Viewer with each `mark` setting, and
  confirm both show "REPRINT" in the same place.

## Risks and open questions

- Viewers handle inline overlays differently; the manual viewer check (AC-5) is the real test.
  If Papyrus doesn't render it, record what it shows and try a second viewer before switching
  the default to `overlay`.

## Definition of done

- Shared DoD, plus: the demo script can show both marks; `docs/OPEN_QUESTIONS.md` Q-07 records
  that the overlay was built.
