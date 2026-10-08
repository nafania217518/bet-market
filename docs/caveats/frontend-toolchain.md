# Frontend toolchain: Node version

Checked 2026-10-08.

## Use Node 24 (LTS) or 26, not 23

- Vitest 5 needs Node `^22.12.0 || ^24.0.0 || >=26.0.0`. jsdom 30 needs `^22.22.2 || ^24.15.0 || >=26.0.0`.
- Node 23 is not in either range. npm prints `EBADENGINE`, and the tests can fail in odd ways.
- Node 23 is an odd (non-LTS) release. It reached end of life on 2025-06-01.
- Vite does not test odd Node releases in CI.

What the project does:

- The Docker build uses `node:24-alpine`.
- Local runs use any Node in the ranges above. On this machine the nvm default is Node 26.11.1 with npm 12.2.0 (upgraded from 23.6.0 on 2026-10-09).
- `scripts/ws-check.mjs` (smoke step 21) needs Node >= 22 for the global `WebSocket` and `fetch`.

## Vitest 5 changes that affect tests

- It clears mocks before every test by default. Set mock return values in each test or in `beforeEach`.
- It removes `test.sequential`.
- It writes generated files into `.vitest/`. That folder is ignored by git and Docker.

## Sources

1. npm registry `engines` fields: `npm view vitest@5 engines`, `npm view jsdom@30 engines`, `npm view vite@8 engines`.
2. Vitest 5 release blog: https://vitest.dev/blog/vitest-5.html
3. Vitest 5 migration summary: https://codingdunia.com/blog/vitest-5-migration-guide/
4. What changed in Vitest 5: https://blog.openreplay.com/vitest-5-changes/
5. Node.js release dates and EOL: https://endoflife.date/api/nodejs.json
6. Vite releases (odd Node releases are not tested in CI): https://vite.dev/releases
