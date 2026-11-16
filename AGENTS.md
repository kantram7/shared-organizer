# Local context and public files

- If `private/PERSONAL-CONTEXT.txt` exists, read it for local project context. It is optional and must remain untracked.
- Use `docs/personal-context.example.txt` as the public template. Keep real names, device details, local paths and personal preferences out of tracked documentation and demo data.
- Public participant labels are `Пользователь 1` and `Пользователь 2`. Author attribution in `LICENSE` is intentional and may remain public.
- Never copy private context, signing keys, tokens, local service configuration or backup archives into commits, patches, public reports or issue comments.
- Before committing, inspect the staged file list and diff. Do not force-add ignored private files. `.gitignore` does not protect files already tracked by Git.
- Keep SDKs, caches, signing material and backups in the separately configured environment directory.
