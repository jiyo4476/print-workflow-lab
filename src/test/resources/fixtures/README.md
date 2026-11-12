# Test fixtures

Small checked-in files that tests read: AFP samples, workflow YAMLs, golden dump output.

- Keep each file small (a few KB) and say in the test which generator or seed produced it.
- Golden files are updated deliberately, never regenerated just to make a test pass.
- Load them from the classpath, e.g. `getClass().getResource("/fixtures/<file>")`.
