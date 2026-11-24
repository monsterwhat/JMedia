# JASSUB 2.5.36 (ASS/SSA subtitle renderer)

Unbundled ES modules from the `jassub@2.5.36` npm package (dist/), with its
runtime dependencies in `vendor/`: abslink 1.3.1, lfa-ponyfill 1.1.1,
rvfc-polyfill 1.0.8, throughput 1.0.2 (all from npm, sha512-verified).

Only changes to upstream files:
- bare imports (`abslink`, `abslink/w3c`, `lfa-ponyfill`, `rvfc-polyfill`,
  `throughput`) rewritten to relative `vendor/` paths;
- `vendor/throughput/index.js`: `module.exports =` -> `export default`;
- `worker/renderers/canvas-base.js`: `'../color-conversions'` -> `'../color-conversions.js'`
  (upstream extensionless import; only bundlers resolve it).

Loaded by js/player/SubtitleController.js with `import('/lib/jassub/jassub.js')`;
the worker is `worker/worker.js`, the WASM glue `wasm/jassub-worker(-modern).js`.
Licences: LICENSE (JASSUB) and vendor/*/LICENSE.

Declared licences (package.json): abslink Apache-2.0, lfa-ponyfill MIT, rvfc-polyfill GPL-3.0, throughput MIT.
