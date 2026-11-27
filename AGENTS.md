# Repository Guidelines

## Project Structure & Module Organization

This is a single Maven module. Java code lives under `src/main/java/org/jayjay/autosignin/`: `task/` holds site-specific check-in flows, `entity/` message models, `util/` shared HTTP and notification helpers, `history/` the per-run result record, `dashboard/` the read-only web board, and `MainApplication` orchestrates each run. Tests mirror these packages under `src/test/java`. Resources are in `src/main/resources/`, with assets in `static/` and the dashboard front end in `src/main/resources/dashboard/` (plain ES modules, no build step). `Dockerfile`, `entrypoint.sh`, and `docker-compose.yml` define container scheduling; CI is in `.github/workflows/auto.yml`.

## Build, Test, and Development Commands

Use JDK 17, matching `pom.xml`, Docker, and CI.

```bash
mvn clean package                         # build the bundled JAR
mvn clean install                         # reproduce the CI build
mvn test                                  # run the JUnit phase
./run.sh                                  # load .env and run the default JAR
cp .env.example .env
docker compose up -d --build              # build and start services
docker compose logs -f auto-sign-in
docker compose down
```

## Coding Style & Naming Conventions

Use four-space indentation, UTF-8, and same-line braces. Keep packages lowercase; use `PascalCase` for classes, `camelCase` for methods and variables, and `UPPER_SNAKE_CASE` for constants and environment keys. Put site behavior in a `CheckInTask` subclass, shared request/JSON code in `CheckInTask` or `ApiUtil`, and delivery changes in `NotificationSender`. No formatter or linter is configured, so preserve local formatting.

## Testing Guidelines

Tests use JUnit 4.13.1 under `src/test/java` with `*Test.java` names. Keep network, credential, and headless-browser calls out of unit tests; mock or isolate those boundaries, as `YunqiaoCheckInTaskTest` does with a local HTTP server. Run `mvn test` before opening a PR. `DashboardAssetsTest` runs `node --check` over the dashboard ES modules (skipped when node is unavailable) because the front end has no build step, so syntax errors would otherwise only surface in the browser. There is no configured coverage threshold.

## Security & Configuration

Copy `.env.example` for local configuration and never commit `.env`, passwords, cookies, or notification tokens. Use GitHub Actions Secrets for the workflow. The dashboard reads the same state but must stay read-only and must never echo configuration values: `.env*`, `watcha-session.json`, and `siemens-session-*.txt` are equivalent to credentials. Check-in tasks call third-party services and the YongHong flow launches Chrome, so use test accounts and verify service terms before changing request behavior.

## Commit & Pull Request Guidelines

Recent commits use short Chinese subject lines focused on one change. Follow that convention; when needed, list impact and verification as `-` body items. PRs should explain behavior or configuration changes, link the issue, list commands run, and call out Docker or workflow effects. Add logs or screenshots only when they clarify runtime behavior, with credentials removed.
