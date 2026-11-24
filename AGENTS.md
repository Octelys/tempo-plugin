# AGENTS.md

JetBrains IDE plugin that tracks active time per project and logs it to Tempo (Jira Cloud).
Kotlin only, no .NET backend; it depends on the IntelliJ Platform alone, so it runs in every
JetBrains IDE. It is compiled against Rider (`ProductVersion` in `gradle.properties`).

## Layout

| Path | What it is |
| --- | --- |
| `src/main/kotlin/.../core` | Pure logic, no platform types: issue keys, sessions, worklog planning. Unit-tested. |
| `src/main/kotlin/.../tracking` | Input listener, per-project session tracking, issue resolution, Git branch lookup |
| `src/main/kotlin/.../ledger` | Persisted pending sessions and submitted worklogs (`tempo-worklogs.xml`) |
| `src/main/kotlin/.../submission` | Sends worklogs to Tempo; notifications |
| `src/main/kotlin/.../api` | Jira Cloud v3 and Tempo v4 REST clients, Tempo OAuth token endpoints |
| `src/main/kotlin/.../auth` | Tempo bearer token (API token or OAuth, with refresh) and the browser login flow |
| `src/main/kotlin/.../settings`, `ui`, `actions` | Settings page, status bar widget, Tools › Tempo menu |
| `src/main/resources/META-INF` | `plugin.xml`, plus `tempo-withGit.xml` loaded only when Git is enabled |

## Build and test

```bash
./gradlew test          # unit tests (core logic)
./gradlew buildPlugin   # plugin zip in build/distributions
./gradlew runIde        # sandbox IDE with the plugin installed
```

- `PluginVersion` in `gradle.properties` and the `<version>` in `plugin.xml` are
  `_PLACEHOLDER_` on purpose. CI stamps them via `update-version.sh`. Never commit a
  concrete version there.
- Git support is an optional dependency: only `GitBranchNameProvider` may import `git4idea.*`,
  and it is registered from `tempo-withGit.xml`. Keep everything else on the platform API.
- Keep logic that can be tested without the platform in `core`, and cover it there.

## File editing

- Modify files with your editor/patch tools (str-replace, create-file, apply-patch).
- Do not write files through the shell: no heredocs, no `echo >`, `printf >`, `tee`, or `sed -i`.
- The shell is for read-only inspection and for running builds, tests, and git commands.
