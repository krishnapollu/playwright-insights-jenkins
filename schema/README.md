# Manifest contract v1

`manifest-v1.schema.json` is the wire format between the Playwright reporter and Jenkins plugin. Both producers and importers must validate it. `fixtures/` contains valid examples, including a complete empty run and an incomplete run. A missing manifest is an import error, not an empty-run manifest.

The importer must reject a file larger than 25 MiB **before parsing**. The schema limits arrays and strings, rejects unknown fields, and accepts only workspace-relative paths with forward slashes. JSON Schema does not check uniqueness or references, so importers must also reject duplicate project and test IDs, unknown project IDs, and retry indices that are not `0, 1, ...` in order. These rules are exercised by `contract.test.mjs`. The Java importer must enforce them independently.

`run.complete: false` means displayed counts are partial or unknown and the run is ineligible as a history baseline. A valid complete manifest with no tests is an explicit no-tests run. Each item in `tests` is one logical test regardless of its number of attempts. `expectedStatus` and `outcome` preserve Playwright's distinct semantics, including expected failures and unexpected passes.

Test identity is a stable hash of project ID, repository-relative file, title path, and repeat-each index. The hash algorithm and path normalization will be fixed with the reporter implementation. Consumers must treat `id` as opaque. Attachment paths are references only; Jenkins must verify each against archived artifacts before showing a link.

The reporter emits `1.1.0`, which adds bounded tags, annotations, worker index, step timing, and stdout/stderr previews. The importer also accepts legacy `1.0.0` manifests. Unknown fields remain invalid in both versions.

Run `npm ci && npm test` from the repository root.
