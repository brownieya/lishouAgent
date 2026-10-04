# lishouAgent development instructions

- Read `DEVELOPMENT_PLAN.md` before starting work. It is the requirement and progress source of truth.
- At each development milestone or handoff, update the matching task status, validation results, current phase and dated progress log in that document. Mark a task complete only when its acceptance criteria are met.
- Preserve common/local/production configuration separation. Tracked configuration and examples contain environment placeholders only. Real local secrets belong in ignored `config/application-local.yml`; production secrets come from the deployment environment.
- Never commit company Wiki documents/images, chat memory, API keys, passwords or `.env`. Keep private data under ignored `data/` and do not copy it into Docker build contexts.
- Use the dedicated `lishou_agent` database. Do not repoint this project at the source project's database or migrate that database without explicit authorization.
- Schema changes use a new Flyway migration. Do not edit a migration already applied to a deployed database.
- Report tested behavior separately from planned functionality. Schema scaffolding does not mean the corresponding UI/API is implemented.
- Prefer local mocked tests. Calls that embed Wiki data transmit it to the configured embedding provider; do not trigger bulk ingestion as a build/startup side effect.
- Do not expose arbitrary shell, filesystem, MCP or web search tools to company chat. Do not log secrets or full private documents.
