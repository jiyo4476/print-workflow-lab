# print-workflow-lab

A small production-print workflow engine in Java, built to learn how high-volume document
workflows operate: hot-folder intake with job tickets, configurable workflows in
ProcessDirector's six phases (receive, prepare, assemble, print, insert, complete), splitting a
print run into documents, indexing them with document properties, presorting, AFP print files
split on their page groups and indexed from TLEs, and (coming next) single-document reprint.

It's a learning project, not a product. The concepts follow the way enterprise print workflow
systems model jobs, documents and steps.

## The demo scenario

A billing system writes one big PDF containing hundreds of customer statements. Drop it in
`hotfolder/pdf/` (the `pdf-statements` input device) and the workflow:

1. picks it up once the file has finished copying, with its job ticket (`run.pdf.jobprops`)
   if one came first, and in the **receive** phase sets job properties from the ticket,
2. in the **prepare** phase, **splits** it into one PDF per statement, detecting boundaries from
   the account number in each page header (and a "Page 1 of N" line, so two statements in a row
   for one account stay separate),
3. **indexes** each statement: account number, name, address, zip and page count become
   document properties,
4. **routes** each statement to a queue: a bad address (blank name, zip not five digits) goes to
   `exceptions`, more than 6 pages to `large-envelope`, everything else to `standard`,
5. **presorts** statements by zip code (postal presort), unless the ticket says `presort=false`,
6. in the **print** phase, writes each queue's documents in order to
   `output/<jobId>/<queue>/`, plus one `documents.csv` index for the whole job.

`job.json` shows which phase the job is in as it goes. The same run works from AFP with
`workflows/statements-afp.yaml`, and from a PDF that carries a DPart tree with
`workflows/statements-dpart.yaml`.

## Concept map

| This project | Print workflow concept |
|---|---|
| `config/input-devices.yaml`, `InputDevices`, `HotFolderWatcher` | Input devices: each hot folder feeds its own workflow |
| `workflows/*.yaml` | Workflow definition, configured not coded |
| `Phase` / `phases:` (receive, prepare, assemble, print, insert, complete) | Workflow phases |
| `Step` + `StepRegistry` | Step templates and the steps built from them |
| `Job` / `Job.properties()` | Job and job properties |
| `Document` / `Document.properties()` | Documents and document properties |
| `SetJobPropsFromTextFileStep` + `<input>.jobprops` | `SetJobPropsFromTextFile`, the job ticket that starts most workflows |
| `SplitPdfStep` | Identifying PDF documents by page regions |
| `SplitAfpStep` | Identifying AFP documents by page groups, with TLEs as document properties |
| `SplitPdfByDPartStep`, `pdf/DPartTree`, `generate --dpart` | PDF/VT document parts (`/DPartRoot`): the same index as AFP page groups + TLEs, carried inside the PDF |
| `MapPropertiesStep` | Mapping input index names to document properties |
| `SortDocumentsStep` | Sorting documents for postal presort |
| `RouteDocumentsStep` / `when:` | Connectors with rules |
| `RunExternalProgramStep` | External steps calling other programs |
| `documents.csv` | Document properties file |
| `afp/`: `AfpReader`, `AfpWriter`, `TleDecoder`, `TleEncoder`, `dump-afp` | AFP structured fields, page groups and TLEs |
| `pdf/`: `XrefReader`, `PdfDocumentReader`, `dump-pdf` | What's inside the PDF input a workflow receives |
| `dump-pdf --xref`, `PdfDocumentReader.revision(n)` | Incremental updates: an edited PDF still holds every earlier version |
| `ContentStreamParser`, `dump-content`, `dump-text` | What a page is: positioned drawing operators, not lines of text |
| `TextPositionExtractor`, `SplitPdf` `extractor: builtin` | Why indexing by text position is fragile, and what PDF/VT or AFP TLEs fix |
| `generate-afp` | AFP print files from a composition system, with index tags per document |
| WAR on Tomcat, `/api/*` servlets | The workflow server and its web services |
| Marionette dashboard (`src/main/webapp/`) | The operator web interface |

## Run it

Requires Java 21. The Maven wrapper downloads Maven on first use.

```bash
./mvnw verify                                     # build + tests
./mvnw -q exec:java -Dexec.args="generate 200 run.pdf"   # make a fake 200-statement print run
./mvnw -q exec:java -Dexec.args="generate 50 bad.pdf 5"  # ...50 statements, 5 with bad addresses
./mvnw -q exec:java -Dexec.args="generate 200 run.pdf 0 42 --dpart"  # ...with a DPart tree (seed 42)
./mvnw -q exec:java -Dexec.args="generate-afp 200 run.afp"  # the same kind of run as AFP
./mvnw -q exec:java -Dexec.args="generate-afp 50 run.afp 5 42"  # 5 bad addresses, seed 42
./mvnw -q exec:java -Dexec.args="generate-afp 50 legacy.afp --tle-names legacy"  # ACCT_NO, ...
./mvnw -q exec:java -Dexec.args="generate-afp 50 mainframe.afp --framing bdw"  # blocked records, like a mainframe data set
./mvnw -q exec:java                               # start the input devices (config/input-devices.yaml)
./mvnw -q exec:java -Dexec.args="run --devices my-devices.yaml"  # ...from another devices file
./mvnw -q exec:java -Dexec.args="run workflows/statements-afp.yaml *.afp"  # one device on ./hotfolder
./mvnw -q exec:java -Dexec.args="dump-afp statement.afp"  # print an AFP file's structure
./mvnw -q exec:java -Dexec.args="dump-afp mainframe.afp --framing auto"  # ...for a file with record headers
./mvnw -q exec:java -Dexec.args="dump-pdf run.pdf"        # print a PDF's version, trailer and pages
./mvnw -q exec:java -Dexec.args="dump-pdf run.pdf 12"     # one object (a Contents number from the dump)
./mvnw -q exec:java -Dexec.args="dump-pdf --xref edited.pdf"  # each revision, and what it changed
./mvnw -q exec:java -Dexec.args="dump-content run.pdf 1"  # page 1's drawing operators
./mvnw -q exec:java -Dexec.args="dump-text run.pdf 1"     # page 1's glyphs: text, x, y, width, size
```

Then move `run.pdf` into `hotfolder/pdf/` (or `run.afp` into `hotfolder/afp/`) and look in
`output/`. The glob ignores case, so `RUN.PDF` is picked up too. A file that can't be claimed yet (Windows reports it as in use while another
program still has it open) stays in the hot folder and is retried every 5 seconds; the folder is
also rescanned at once if the operating system drops file events in a burst. On Windows use
`mvnw.cmd`. In
PowerShell, quote any `-D` argument; otherwise PowerShell splits it at the dot and Maven reports
an unknown lifecycle phase:

```powershell
.\mvnw.cmd -q exec:java "-Dexec.args=generate 200 run.pdf"
```

### Input devices

Like ProcessDirector's input devices, each hot folder feeds its own workflow.
`config/input-devices.yaml` lists them, and `run` with no arguments starts them all:

| Device | Folder | Files | Workflow |
|---|---|---|---|
| `pdf-statements` | `hotfolder/pdf/` | `*.pdf` | `statements.yaml` |
| `afp-statements` | `hotfolder/afp/` | `*.afp` | `statements-afp.yaml` |
| `afp-legacy` | `hotfolder/afp-legacy/` | `*.afp` | `statements-afp-legacy.yaml` (files from `--tle-names legacy`) |

```yaml
devices:
  - name: pdf-statements      # unique; jobs get the job property inputDevice=pdf-statements
    folder: hotfolder/pdf     # each device has its own folder
    glob: "*.pdf"             # which files are inputs, ignoring case
    workflow: workflows/statements.yaml
```

- **One folder per device.** Two devices can't share a folder, even with different globs (or the
  same folder written two ways, like `hotfolder/pdf` and `hotfolder/x/../pdf`). Every file in a
  folder belongs to that folder's device. Nested folders are fine: watching isn't recursive.
- **Checked at startup.** Every device's workflow is loaded and built before any folder is
  watched. A missing or broken workflow, a repeated name or folder, an invalid glob, a missing
  field or an unknown key stops startup with a message naming the device.
- **Each device has its own watcher thread.** All devices hand jobs to one shared queue, so jobs
  still run one at a time.
- **Each job records its device** as the job property `inputDevice`. A job ticket can't change it
  (or `inputFileName`); a ticket line for either is ignored, with a warning.
- **Job tickets** (`*.jobprops`) are never inputs, whatever the glob. Any other file that doesn't
  match the device's glob is left in place and logged once, not on every rescan.

`run <workflow.yaml> [glob]` is still there as a shortcut: one device named `default` on
`hotfolder/`.

### Workflow phases

Like Ricoh ProcessDirector, every workflow groups its steps into the same six phases, which
always run in this order. A phase can be empty, as `assemble` and `insert` are for now:

| Phase | What happens there | In `statements.yaml` |
|---|---|---|
| `receive` | Accept the input: set job properties, detect the data stream | `set-job-props` |
| `prepare` | Turn the input into documents: split, index, map, route, sort | `split-and-index`, `route`, `presort` |
| `assemble` | Build what will be printed from the documents | (empty) |
| `print` | Send the job to the printer; here, write it to the output folder | `write-output` |
| `insert` | Reconcile what the inserter finished against what was printed | (empty) |
| `complete` | Notify, retain, clean up | (empty) |

```yaml
phases:
  receive:
    - name: set-job-props
      type: SetJobPropsFromTextFile
  prepare:
    - name: split-and-index
      type: SplitPdf
      params: { ... }
  print:
    - name: write-output
      type: WriteOutput
```

Phases must be listed in that order, each at most once, and names ignore case. A phase can be
left out entirely. A workflow that lists `steps:` directly, or puts phases out of order, fails
to load with a message saying so. The job's phase is the phase of the step it's on: a new job is
in `receive`, a finished one is in `complete`, and a failed one stays in the phase where it
failed. `job.json`, `error.txt` and the log all show it.

### Job tickets

Most ProcessDirector workflows start with `SetJobPropsFromTextFile`, and so do these. It reads a
job ticket, a small text file that comes with the print file, and sets job properties from it, so
whoever submits a job can say how to handle it. Name it after the input with `.jobprops` added and
copy it into the hot folder **before** the input: `run.pdf` takes `run.pdf.jobprops` along when
it's claimed.

```
# Job ticket for run.pdf
customer=ACME Bank
mailClass=first
presort=false
```

One `name=value` per line (UTF-8). Names and values are trimmed, `#` lines and blank lines are
skipped, a value may contain `=`, and a later line for the same name wins. A line with no `=`
fails the job with its line number. A line can't change `inputFileName`, which the app sets
(it's ignored, with a warning). The watcher never treats a ticket as a job, whatever the glob,
waits for it to finish copying, and won't claim the input while the ticket can't be moved; a
failed job's ticket goes to `error/<jobId>/` with its input. With no ticket the step does nothing; `required: true` makes
a missing ticket fail the job, and `fileName:` reads another file in the job's work folder.

The properties are job properties, so any step's `when:` can use them. In `statements.yaml` a
ticket with `presort=false` (or `no` / `off`, in any case) skips the presort step:

```yaml
- name: presort
  type: SortDocuments
  params: { by: zip }
  when: { property: presort, operator: notMatches, value: "(?i)false|no|off" }
```

### Where a job's files go

| Folder | What's in it |
|---|---|
| `work/<jobId>/` | The input file (and its `.jobprops` ticket) while it's processed, intermediate documents, and `job.json` |
| `output/<jobId>/<queue>/` | One queue's finished documents (`standard`, `exceptions`, ...) in presort order, from `00001.pdf` (the extension follows the document's format) |
| `output/<jobId>/documents.csv` | One row per document with its properties, including `queue`; `file` is the path below `output/<jobId>/`. `position`, `sequence` and `file` are reserved: a property with one of those names fails the job |
| `error/<jobId>/` | Only for failed jobs: the input file and `error.txt` |

`job.json` is rewritten after every step, so it always shows how far a job has got: its id,
state, phase, current step, error message (if it failed), job properties, document count, and
each step's phase, start and finish time and outcome (`COMPLETED`, `FAILED`, or `SKIPPED` when the step's
`when:` was false). It's written to a temp file and then moved into place, so it's
never half-written.

When a step throws, the job stops there: later steps don't run, the input file moves to
`error/<jobId>/`, and `error.txt` says which step failed, when, and why:

```
Job:    3fc8bd7b41e0
Input:  blank.pdf
Phase:  prepare
Step:   split-and-index
Time:   2026-11-12T19:13:04.699Z
Error:  No 'accountNumber' found on page 1
Work:   C:\print-workflow-lab\work\3fc8bd7b41e0
```

`Work:` is where `job.json` and any partial output were left. Job IDs are 12 hex digits. If the
program is stopped while a job runs, the job fails the same way, with an error saying it was
interrupted, so its input can be dropped in the hot folder again.

Each failed job gets its own folder, so two failed files with the same name never overwrite each
other. If the input can't be moved, `error.txt` adds a `Note:` line saying where it was left.

### Routing rules

A `RouteDocuments` step sets each document's `queue` property. Routes are checked in order and
the first match wins; documents that match none go to `default` (without a `default`, they fail
the job). Any step can also take a `when:`, tested against the job's properties, and is skipped
when it's false. See `workflows/statements.yaml`.

A condition is `{ property, operator, value }`, or `{ all: [...] }` / `{ any: [...] }`, nested as
deep as needed (an empty `all` is true, an empty `any` false). Operators:

| Operator | True when |
|---|---|
| `equals` / `notEquals` | the value is / isn't exactly `value` |
| `matches` / `notMatches` | the **whole** value does / doesn't match the regex `value` |
| `greaterThan` / `lessThan` | compared as numbers when both sides are numbers (`10 > 9`), else as text |
| `missing` | the property is absent or blank (takes no `value`) |

An absent property never equals, matches or compares, so `notEquals` and `notMatches` are true
for it. Mistakes in rules (unknown operator, bad regex, no `value`) stop the workflow from
loading, with the step's name in the message. In double-quoted YAML a regex backslash is doubled
(`"\\d{5}"`); in single quotes it isn't (`'\d{5}'`).

### Reading AFP

An AFP (MO:DCA) file is a flat run of *structured fields*, each preceded by `0x5A`: a 2-byte
length (8 to 32,767) that counts itself but not the `0x5A`, a 3-byte type ID (`D3A8AD` is BNG,
Begin Named Page Group), a flags byte, two reserved bytes (often used as a sequence number),
then data. The reader strips padding and splits off any extension. Begin and End fields nest: document, page groups,
pages. In a statement run each page group is one customer, and the Tag Logical Elements (TLEs)
at its start are that document's index. Text inside AFP is EBCDIC (code page IBM037 by default).

`dump-afp` prints one line per field, indented between each Begin and its End, with TLE values
inline:

```
Offset    Length  Field
00000000      16  BDT  Begin Document  "STMTRUN "
00000011      16    BNG  Begin Named Page Group  "G0000001"
00000022      49      TLE  Tag Logical Element  accountNumber = "1234567890"
00000054      32      TLE  Tag Logical Element  name = "AVERY NGUYEN"
0000008E      16      BPG  Begin Page  "P0000001"
...
17 fields, 0 problems
```

Offsets are hex and lengths are each field's own length value. Problems are marked `!!` and don't
stop the dump: an End with no Begin or with a different name, a page group never closed, a
segmented field interrupted by another field, a TLE with no value. Bytes that aren't a structured
field end it with their offset. The exit code is 1 when there are problems.
For a file from another country pass its code page: `dump-afp file.afp IBM500`. A TLE that names
its own code page (an `X'01'` triplet) uses that one. The rules follow the MO:DCA Reference,
AFPC-0004-11.

`generate-afp <count> <out.afp> [badAddresses] [seed]` writes AFP test input. Each statement is a
named page group (`BNG S0000001`) with three TLEs (`accountNumber`, `name`, `zip`), then its
pages. Each page has an environment group (a font reference by name, page and text
descriptors) and one presentation text object (PTOCA). `generate` takes the same optional seed,
and the same seed gives the same statements in both formats, so a PDF run and an AFP run can be
compared document by document. A bad-address statement has an empty `name` TLE or a 4-digit
`zip`. Output is byte-identical for a seed; the PDF isn't, because PDFBox writes a random file
ID. `--tle-names legacy` names the TLEs `ACCT_NO`, `CUST_NAME` and `ZIP_CODE` instead, the way
another composition application might.

### Reading PDF

A PDF is read from the end, unlike AFP. `startxref` near the end gives the offset of the
cross-reference table, which gives the byte offset of every numbered object (`12 0 obj ...
endobj`); the trailer after it points at the catalog, and the catalog at the page tree. Objects
refer to each other as `12 0 R` and are read only when something asks for them. Page content is
a stream of drawing operators, usually Flate-compressed.

`dump-pdf` prints what the `pdf/` package finds, without PDFBox:

```
PDF       1.4
Trailer   <</Root 1 0 R /ID [<99170FBB...> <99170FBB...>] /Size 29>>
Objects   28 in use, 1 free; xref at offset 0x000011C4 (4548)
Catalog   1 0 R  <</Type /Catalog /Version /1.4 /Pages 2 0 R>>
Pages     6
Page 1    3 0 R
  MediaBox  [0.0 0.0 612.0 792.0]
  Fonts     /F1 Helvetica-Bold, /F2 Helvetica
  Contents  10 0 R  /FlateDecode, 474 bytes, 1817 decoded
```

`dump-pdf two-statements.pdf 10` prints one object: its dictionary one entry per line, and its stream
decoded when that gives text (a content stream's `BT /F1 14 Tf ... ET`). Every version from
PDF 1.0 to 2.0 is read; a page inherits `MediaBox`, `Resources`, `CropBox` and `Rotate` from the
page-tree nodes above it. Only Flate is decoded: a stream with another filter (`/DCTDecode`, a
JPEG) is reported as not decoded, and the rest of the file still dumps. The exit code is 1 when
the file can't be read or the object isn't there.

Most files written since PDF 1.5 (including `generate`'s) have no `xref` table: the
cross-reference section is itself a compressed stream, binary rows usually run through a PNG
predictor, and most small objects are packed into *object streams* (`Objects   37 in use (27 in
object streams)` for a 3-statement run). An *incremental update* doesn't rewrite the file: it appends the changed
objects and a new cross-reference section whose `/Prev` points at the old one, so the old
version is still in the file. `dump-pdf --xref` lists the sections, one per revision:

```
Revision 1  table at 0x000000D5 (213): 6 entries
Revision 2  table at 0x000001D4 (468): 3 entries, /Prev 213
  Changed   3
  Added     6
  Freed     5
```

In code, `PdfDocumentReader.revision(1)` reads the file as it was before any update. That's why
a "small edit" to a PDF can leave the old content in the file for anyone with a parser.

### Content streams

A page's content is a program of postfix operators: the operands, then the operator. There are
no lines or words, only "set the font", "move to x y" and "show these bytes" (Annex A lists 73
operators). `dump-content` prints a page's operators, indented inside `q`/`Q` (save and restore
the graphics state), `BT`/`ET` (a text object) and marked content, each with what it does:

```
Page 1 of 6  3 0 R
  Contents  10 0 R  /FlateDecode, 474 bytes, 1817 decoded
BT                                      % begin text
  /F1 14 Tf                             % font and size: Helvetica-Bold
  72 752 Td                             % next line, offset by tx ty
  (Front Range Credit Union) Tj         % show text
ET                                      % end text
...
140 operations
```

`72 752` is in PDF's own coordinates: points from the *bottom*-left corner, so 752 is 40 pt from
the top of a 792-pt page. A page whose `/Contents` is an array is read as the streams joined.
`Do` names what it paints (an image's size, or "form" for a form XObject, whose own content
isn't followed); an unmatched `Q` or `ET` and an operator that isn't in Annex A are noted on
their line.

Inline images (`BI ... ID <bytes> EI`) are the one part of content that isn't tokens: the bytes
can hold anything, including `EI`. `ContentStreamParser` ends them by a declared length
(`/L`, PDF 2.0), else by the size the image's own parameters give (width, height, bits and
color components), and only scans for `EI` when neither is known (a filtered image, or a named
color space). PDFBox 3.0.3's parser scans, and loses everything after an inline image it wrote
itself (`ContentStreamVsPdfBoxTest`).

### Text positions and `extractor: builtin`

Where a glyph lands takes the whole state: the current transformation matrix (`cm`, saved and
restored by `q`/`Q`), the text matrix (`Td`, `Tm`, `T*`), the font size, character and word
spacing, horizontal scaling, and the width of every glyph before it on the line, from the
font's `/Widths` or, for the Standard 14 fonts, Adobe's metrics. A `TJ` array's numbers move
glyphs by thousandths of a text unit (kerning). `TextPositionExtractor` does all of that and
gives each character's position in the coordinates workflow regions use: points from the
top-left of the page. `dump-text` prints them, one glyph per line:

```
Page 1 of 6  3 0 R
text              x        y    width    size
"F"           72.00    40.00     8.55   14.00
"r"           80.55    40.00     5.45   14.00
...
919 glyphs
```

The text is quoted, and a control or other unprintable character is written `\uXXXX`, so what a
file says never reaches the terminal raw.

`SplitPdf` takes the region text from PDFBox by default, or from this reader with
`extractor: builtin`:

```yaml
    - name: split-and-index
      type: SplitPdf
      params:
        extractor: builtin    # or pdfbox (the default)
```

Both put a glyph in a region when the point where it starts on its baseline is inside the
rectangle, and give the same `documents.csv` for generated runs (`BuiltinExtractorWorkflowTest`).
Both read fake bold (text drawn twice, slightly apart) once. Two differences are on purpose:
overlapping regions each get every glyph inside them (PDFBox gives a shared glyph to one region
only), and a region's lines come top to bottom.

The built-in reader only handles **simple fonts** (Type 1 and TrueType with one byte per
character, WinAnsi or Standard encoding). A **CID font** (Type 0, as used for CJK text and many
embedded subsets), a Type 3 font, another encoding, a font whose own built-in encoding applies
(symbolic, or embedded with no base encoding), a rotated page or rotated text fails the job with
the page number ("unsupported font ...") instead of indexing the page with wrong text.
`ToUnicode` maps aren't read. Use `pdfbox` for those files. `builtin` also reads the whole file
into memory beside PDFBox's copy.

### Splitting a PDF on its document parts

`SplitPdf` has to guess where a statement starts from where text sits on the page. A PDF 2.0 or
PDF/VT file can carry the answer itself: a `/DPartRoot` in the catalog points at a tree of
*document parts*, where each part names its first and last page (`/Start`, `/End`) and holds
index values in a `/DPM` dictionary (`AccountNumber`, `Name`, `Zip`). It is the PDF counterpart
of AFP page groups and TLEs. `generate --dpart` writes a tree of that form (not a PDF/VT
conformant file: it has no output intents or XMP metadata). `dump-pdf` shows the catalog's
`/DPartRoot` reference; `dump-pdf <file> <objectNumber>` shows each node.

`workflows/statements-dpart.yaml` uses `SplitPdfByDPart`:

- `level` picks which depth of the tree holds the documents (1 is the root node's children). If
  it's left out, the file's `/RecordLevel` is used, or 1 if it has none; the job log says which
  level was used and why. A `/RecordLevel` that isn't a whole number of 1 or more fails the job.
  The root node counts as level 0, so a `/RecordLevel` of 1 is its children. That is what vendor
  documentation says (Callas pdfToolbox: "since the root is zero"; PDFlib's examples name the
  levels `root` and `recipient` with `recordlevel=1`), but the text of ISO 32000-2 itself wasn't
  available to check, so for a file from another tool, set `level` and look at the documents it
  makes.
- `properties` maps DPM keys to document property names (`AccountNumber: accountNumber`), so
  routing and output are the same as for the other splitters. `requiredProperties` marks a
  document with `indexError` when one is missing or blank, and the route step sends it to
  `exceptions`.
- A branch node may leave out `/Start` and `/End`; its pages are then the ones its children
  cover. Both `/DParts [a b]` and the PDF 2.0 form `/DParts [[a b]]` are read.
- A file with no tree fails the job and the message suggests `SplitPdf`. So do overlapping page
  ranges, and so does a page that no document at the chosen level covers. A cover or separator
  page outside the records is left out on purpose with `uncoveredPages: ignore` (the number left
  out is the `uncoveredPages` job property).
- An encrypted file (empty user password) or one with a damaged cross-reference table is first
  opened and saved clean by PDFBox, which is more forgiving than this project's own reader. The
  clean copy is deleted when the step ends; PDFBox also drops any permission restrictions the
  owner set. A file that needs a password, or that PDFBox can't open either, fails with both
  reasons in the message.
- **Output documents carry no back-pointers.** A PDF/VT page has a `/DPart` entry pointing at its
  node, and through it at the whole tree. Copied as it is, every customer's PDF would hold every
  other customer's name, account number and zip. The split steps remove `/DPart`, `/B` and
  `/PieceInfo` from the copied pages and drop link annotations (their destinations reach other
  pages), for `SplitPdf` as well.
- The file is held in memory twice (this project's reader and PDFBox); see "Memory and large
  files" below for how much heap that takes.

### Preflight

A `PreflightPdf` step checks each PDF document for print-readiness before it is routed. It sets two
document properties: `preflight` (`pass` or `fail`) and `preflightIssues`, the problems joined by
`; ` (empty on a pass). `workflows/statements-preflight.yaml` runs it after the split and routes
failures to `exceptions`.

| Check | Passes when | Param (default) |
|---|---|---|
| Version | The header version, or the catalog's `/Version`, is at most `maxVersion` | `maxVersion` (`"1.7"`) |
| Encryption | The trailer has no `/Encrypt`. An encrypted file reports only this, since its objects can't be read without the key | none |
| Fonts | Every font has `FontFile`, `FontFile2` or `FontFile3`, in page resources and in every Form XObject the page draws, nested to any depth | `allowStandard14` (`false`) |
| Page size | Each MediaBox is within `tolerance` points per side of an allowed size | `pageSizes` (`[letter]`; `letter` and `a4`), `tolerance` (`1`) |
| Rotation | `/Rotate` is 0. A value that isn't a multiple of 90 always fails | `allowRotate` (`false`) |

- Issues name the page, and the font where that applies: `page 3: font Arial not embedded`. A
  document lists at most 20 issues, then says how many more there are.
- A Standard 14 font (Helvetica, Times, Courier, Symbol, ZapfDingbats, and their variants) is not
  embedded in the file, and passes only when `allowStandard14: true`. A subset name such as
  `ABCDEF+Arial` is checked as `Arial`.
- A file that can't be read at all is a `fail` with `could not check this file: ...`. With
  `failJob: true`, the first failing document fails the job instead, and the message names it.
- Documents without `format: pdf` (AFP) are left alone.
- Not checked: fonts in annotation appearance streams, colour, image resolution, transparency, and
  PDF/X or PDF/A conformance. The check reads dictionaries only and never decodes a stream.

### Memory and large files

The PDF splitters (`SplitPdf` and `SplitPdfByDPart`) turn every object of a PDF that they reach
into Java objects, so the heap they need follows the file's **object count**, not its size on
disk. Measured with `SplitPdfByDPart` on files of different shapes (the smallest heap that worked;
the next step down failed):

| File | Size | Pages | Objects | Smallest heap that worked |
|---|---|---|---|---|
| 5,000 statements | 5.8 MB | 10,072 | 50,498 | about 58 MB |
| 20,000 statements | 23 MB | 40,059 | 201,045 | about 270 MB |
| 200,000 blank pages | 2 MB | 200,000 | 201,409 | about 336 MB |
| 400,000 blank pages | 4 MB | 400,000 | 402,409 | about 670 MB |
| 100,000 pages with a drawing | 12 MB | 100,000 | 301,208 | about 304 MB |
| 5 incompressible 4 MB images | 19 MB | 5 | 42 | 32 MB or less |

That is 0.7 to 1.6 KB of heap per object, and it scaled linearly when the blank-pages file was
doubled (the 400,000-page file was not used to fit the formula). Size on disk is no guide (the 2 MB
file needed ten times the heap of the 19 MB one), and neither is the page count (1.6 to 7 KB per
page). The step reads the object count from the file's last trailer without loading it, and
recommends **64 MB + 1.8 KB per object + the file's size**: a 200,000-object file about 450 MB, a
million objects about 1.9 GB. If it can't find the count it falls back to 64 MB + 16 times the file.
Treat a file past a few hundred MB, or a few million objects, as one to split upstream or to send
as AFP. AFP is different: it's read one field at a time, and a 59 MB file of 20,000 statements
split in 128 MB.

What the steps do about it:

- Before splitting, the step compares the JVM's heap limit (`-Xmx`) with that recommendation and
  logs a warning, with the `-Xmx` to use, if it's lower. It doesn't refuse the file, because only
  the split itself knows whether it fits.
- If the JVM runs out of Java heap space (or hits the garbage collector's overhead limit) during
  the split, the job fails into the error folder with the same advice, and the hot folder keeps
  working with the next file. Documents already written stay in the job's work folder. Other
  kinds of out-of-memory (no native threads, direct buffers, metaspace) aren't about the file and
  still escape as before. A heap far too small can spend minutes in garbage collection before the
  failure comes: the 5.8 MB file in 62 MB took about 100 seconds. After such a failure the next job
  in the same JVM behaved exactly as it does in a fresh one at every heap tried (13 to 30 MB, both
  steps, the next job a 3-statement file). A failure during a PDFBox class's static initialisation
  would leave that class unusable until restart; that wasn't seen and can't be ruled out.
- The figures assume the JVM is otherwise idle. They're compared with the heap *limit*, not what
  is free, so under Tomcat, where the dashboard shares the heap, a file that passes the check can
  still not fit.
- Every output document carries its own copy of the fonts and images its pages use, so a run that
  shares a large font comes out many times bigger than it went in (one embedded font turned 885 KB
  into 32 MB). The total is the `documentBytes` job property, and a log warning appears when the
  output is over 50 MB and more than 10 times the input. It's left out, with a warning, if a
  document's size can't be read.

Setting the heap depends on how it runs:

```bash
MAVEN_OPTS=-Xmx2g ./mvnw -q exec:java            # the devices run inside Maven's JVM
./mvnw package cargo:run -Dcargo.jvmargs=-Xmx2g  # Tomcat is started by cargo (with -Xms128m, so
                                                 # the -Xmx must be at least 128m)
JAVA_TOOL_OPTIONS=-Xmx2g java ...                # any other JVM; in a container, a JVM's default
                                                 # is a quarter of the memory limit, so also
                                                 # consider -XX:MaxRAMPercentage=75
```

### Splitting AFP

`workflows/statements-afp.yaml` is the statement workflow for AFP input. Its `SplitAfp` step
writes one AFP document per named page group, so nothing is guessed from where text sits on the
page:

- Each document is a standalone AFP file: the fields before the first group (the BDT and any
  inline resource group), anything between the previous group and this one (an IMM, say), the
  group, and an EDT named like the BDT. `dump-afp` reads each one with no problems.
- The TLEs directly in the group become document properties, in file order, followed by
  `pageCount` and `format=afp`. (`SplitPdf` sets `format=pdf`.)
- `requiredProperties: [accountNumber, name, zip]`: a group without one of them, or with it
  blank, gets an `indexError` property (`missing: zip`) instead of failing the job, and the route
  step sends it to `exceptions`. A TLE repeated with a different value is marked the same way.
- Bytes that aren't AFP, a Begin closed by the wrong End, a page outside any group, or a file
  with no page groups fail the job, with the byte offset where there is one. When the file isn't
  AFP at all the message says what it looks like instead (a PDF, PostScript, PCL, text, or
  structured fields with no `0x5A` before them).
- A file may hold several documents (`BDT ... EDT`, as when AFP files are joined with `cat`).
  Each is split on its own, with its own preamble and medium map. A document is followed by
  another document, not by loose fields or a page group: either fails the job, with or without
  page groups in the document before it. A document with no page groups is skipped as long as
  another has some.
- **Nested groups.** By default a document is a page group outside any other. When the file
  wraps its statements in a group (a household around its accounts, a mailing around its
  households), set `groupLevel: 2` (or 3, and so on) to split at that depth instead. The groups
  above it become wrappers: each document begins with its wrappers' Begin fields and their own
  TLEs, and ends with the matching Ends. The wrappers' TLEs are inherited as properties, and the
  document's own TLE wins when both name the same one. So `requiredProperties` can be met by
  either. Anything else directly in a wrapper is kept for the documents of that wrapper only. A
  page directly inside a wrapper, or a file not nested as deep as `groupLevel`, fails the job
  and says that `groupLevel` is why.

**What sits between the fields.** MO:DCA defines the fields, not the file around them, and the
tools that write AFP disagree. `framing` on the `SplitAfp` step (and `--framing` on `dump-afp`)
says which form the file has:

| `framing` | Between fields | Where it comes from |
|---|---|---|
| `strict` (default) | nothing: `5A field 5A field ...` | what this project writes, and most AFP files |
| `padded` | carriage returns and line feeds before each field (and before the first); zero bytes after the last field only | a text-mode transfer, or padding to a block size |
| `rdw` | a 4-byte record descriptor word: the record's length, then two zero bytes | a variable-length data set copied from a mainframe in binary |
| `bdw` | that, and each block of records led by a 4-byte block descriptor word | a variable-blocked data set, as it was on the mainframe |
| `auto` | decided from the first bytes of the file (below) | when you don't know |

`strict` stays the default so that a damaged file is never read as something else. Each form
still checks every field. `padded` is looser than `strict` only in the line endings it skips and
the zero bytes after the last field; zero bytes anywhere else fail, because a field whose length is
too short leaves them behind. `rdw` and `bdw` are stricter in places: a record must hold exactly one
field, and a record must fit its block. `auto` reads a file that starts with a field as `padded` if
line endings come before it or right after its first field, and as `strict` otherwise (so a strict
file is still read strictly, and a file padded with zero bytes but no line endings needs `padded`
asked for); one that starts with a length and two zero bytes, then a `0x5A`, as `rdw` or `bdw`.
Records that span blocks (`VBS`) aren't supported, and say so. `generate-afp --framing
padded|rdw|bdw` writes each form for trying them out. A file this reader can't place fails with a
message that says what it looks like and what to try.

Different applications name the same index values differently. `MapProperties` renames document
properties after the split, so the routing, sorting and output steps stay the same.
`workflows/statements-afp-legacy.yaml` does that for `--tle-names legacy` files:

```yaml
- name: map-properties
  type: MapProperties
  params:
    rename:                       # old name: new name
      ACCT_NO: accountNumber
      CUST_NAME: name
      ZIP_CODE: zip
    requiredProperties: [accountNumber, name, zip]   # checked after renaming
```

All renames apply at once, so the map's order doesn't matter. A property the map doesn't list is
kept. If a document already has a different value under the new name, it's marked
`indexError=rename conflict: ACCT_NO -> accountNumber` and keeps both. Two old names mapped to one
new name stop the workflow from loading. The step works on PDF documents too, and never changes
the files: the TLEs keep their names inside the AFP.

### Web app on Tomcat

The project builds a WAR (`target/print-workflow-lab.war`) for Apache Tomcat 10.1
(Jakarta Servlet 6.0). To run it locally:

```bash
./mvnw package cargo:run                      # http://localhost:8080/
./mvnw package cargo:run -Dtomcat.port=8095   # if 8080 is taken
```

Cargo downloads Tomcat from Maven Central on first run and keeps it under `target/`. Stop with
Ctrl+C. To deploy to your own Tomcat instead, copy the WAR into its `webapps/` folder.

- `GET /api/health` returns `{"status":"UP","service":"print-workflow-lab"}`.
- `/` is the dashboard: **Marionette.js v4** (`backbone.marionette` 4.1.3) on Backbone 1.6.0,
  Backbone.Radio 2.0.0,
  Underscore 1.13.8 and jQuery 3.7.1. Tomcat serves the libraries from WebJars
  (`/webjars/...`), so there's no JavaScript build step. The app code is plain scripts in
  `src/main/webapp/js/`.

### Dashboard tests

The JavaScript tests are Mocha specs running in Node with jsdom, loading the same library
versions from npm:

```bash
npm ci
npm test
```

Library versions are pinned in three places: `pom.xml` (WebJars), `src/main/webapp/index.html`
(script tags) and `package.json` (tests). `versions.test.mjs` fails if they disagree, so change
all three together.

## Layout

```
src/main/java/dev/yjimmy/printlab/
  App.java                 entry point: run | generate | generate-afp | dump-afp | dump-pdf |
                           dump-content | dump-text
  workflow/                Job, Document, Step, WorkflowDefinition, WorkflowEngine, StepRegistry
  workflow/rules/          Condition, Rule, RuleParser: routing rules and `when:`
  steps/                   SetJobPropsFromTextFile, SplitPdf, SplitAfp, MapProperties,
                           RouteDocuments, SortDocuments,
                           RunExternalProgram, WriteOutput
  input/                   input devices: config, one HotFolderWatcher per device, JobIntake
  testdata/                fake statement print runs: shared data and layout, PDF and AFP writers
  afp/                     AFP reader and writer, field names, EBCDIC, TLE decoder and encoder,
                           dump tool
  pdf/                     PDF lexer, parser, xref table, Flate decoding, page tree, content
                           stream parser, dump tools
  web/                     servlets (HealthServlet)
src/main/webapp/
  index.html               dashboard page; loads WebJars, then js/
  js/                      Marionette app: health.js, app.js, main.js
src/test/js/               dashboard tests (Mocha + jsdom)
config/input-devices.yaml  the input devices `run` starts
workflows/                 sample workflows: statements.yaml (PDF), statements-dpart.yaml (PDF
                           with a DPart tree), statements-afp.yaml, statements-afp-legacy.yaml
```

## Roadmap

The step-by-step plan is in [`docs/PR_PLAN.md`](docs/PR_PLAN.md), with a requirements doc
(PRD) for each PR in [`docs/prd/`](docs/prd/README.md). Decisions still to be made are in
[`docs/OPEN_QUESTIONS.md`](docs/OPEN_QUESTIONS.md).

- [x] Workflow engine, YAML workflows, step registry
- [x] Hot folder intake that waits for files to finish copying
- [x] Input devices: several hot folders, each feeding its own workflow
      (`config/input-devices.yaml`)
- [x] PDF split + region-based indexing, zip presort, output with document index
- [x] **AFP:**
  - [x] Structured-field reader and dump tool
  - [x] Test-data writer with page groups + TLEs
  - [x] `SplitAfp` step that reads document properties from TLEs, and `MapProperties` for
        other TLE names
- [ ] **PDF without PDFBox:**
  - [x] Object reader, cross-reference table, page tree and `dump-pdf`
  - [x] Cross-reference streams, object streams, incremental updates
  - [x] Content streams and text positions (`dump-content`, `dump-text`, `extractor: builtin`)
  - [ ] PDF/VT document parts (DPart) indexing
  - [x] Preflight step
- [x] Routing rules: send documents to an exception queue (e.g. invalid zip), flag large
      documents for a different envelope
- [ ] Barcode step (ZXing) for inserter control
- [x] Tomcat WAR, `/api/health`, Marionette 4 dashboard shell
- [ ] Persist jobs and documents (PostgreSQL), REST API (servlets on Tomcat)
- [x] Job tickets: `SetJobPropsFromTextFile` sets job properties from `<input>.jobprops`
- [x] Workflow phases (receive, prepare, assemble, print, insert, complete), as in
      ProcessDirector
- [ ] Marionette dashboard: jobs by phase and step, document search, single-document reprint
- [ ] Docker Compose setup
- [ ] CI/CD: Jenkins running locally in Docker, deploying to a k3s server on ARM
