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
