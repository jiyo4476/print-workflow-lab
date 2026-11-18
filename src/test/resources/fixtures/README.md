# Test fixtures

Small checked-in files that tests read: AFP samples, workflow YAMLs, golden dump output.

- Keep each file small (a few KB) and say in the test which generator or seed produced it.
- Golden files are updated deliberately, never regenerated just to make a test pass.
- Load them from the classpath, e.g. `getClass().getResource("/fixtures/<file>")`.

## `afp/`

- `minimal.afp` (320 bytes): one document, two named page groups with TLEs, one page each.
  Written by `make_minimal_afp.py` with Python's `cp037` codec, so it doesn't depend on this
  project's code. `DumpAfpTest.minimalFixtureIsTheDocumentedStructure` rebuilds it with
  `AfpBytes` and checks the bytes match. Regenerate with
  `python make_minimal_afp.py minimal.afp`.
- `minimal.dump.txt`: the golden `dump-afp` output for `minimal.afp`. Offsets were checked by
  hand when it was created. `.gitattributes` keeps it LF so the comparison is byte for byte.
- `afpworld-health-coverage.dump.txt`: the golden `dump-afp` output (25 fields, 0 problems) for
  a real AFP file from another tool, used by
  `DumpAfpTest.afpWorldHealthCoverageSampleMatchesGoldenDump`. Checked when created: the last
  field (EPF at `0x31E7`, length 8) ends at byte 12,784, the file's size. The file has no named
  page groups or TLEs, so it checks the reader, not PRD-05's split.

## Local-only samples (`samples/`, not in git)

Third-party files with no redistribution license stay on your machine in the gitignored
`samples/` folder at the repo root. Tests that need them skip when they're missing.

- `samples/afpworld/01_Health_Coverage.{afp,pdf,html}`: one letter in three formats, the shared
  example input for AFP, PDF and HTML. Download "Example Healthcare document" from
  [AFPWorld's sample files](https://www.afpworld.com/educate/sample-afp-files.html) (made with
  Timitoo Formatter Fox; © AFPWorld.COM, no license to redistribute) and unzip it there. The
  HTML links `./css/w3.css` and `./img/timitoo_logo.png`, which aren't in the download, so it
  renders unstyled; the AFP and PDF have the logo embedded.
