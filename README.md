# ⏱️ Tempo Time Tracker for JetBrains IDEs

Tracks the time you actively spend in Rider (or any JetBrains IDE) and logs it to
[Tempo](https://www.tempo.io/) on Jira Cloud.

## Features

- **Counts active time only.** Time accrues while you type, click or navigate in a project's
  window. After a configurable stretch without input (default 5 minutes) the session ends at your
  last activity, so idle time is never logged.
- **Knows which issue you are on.** In order of precedence:
  1. an issue set manually (*Tools › Tempo › Set Issue…* or the status bar widget),
  2. the Jira key in the current Git branch (`features/ABC-123-new-login` → `ABC-123`),
  3. the project's default issue (*Settings › Tools › Tempo*).
- **One worklog per issue and day.** Later submissions update that worklog in Tempo instead of
  adding new ones.
- **Automatic or on-demand submission.** Send every N minutes (default: automatically, every
  5 minutes), or only when you run *Tools › Tempo › Submit Worklogs*.
- **Nothing gets lost.** Unsent time is saved and survives IDE restarts.

## Setup

1. Install the plugin, then open *Settings › Tools › Tempo*.
2. **Jira Cloud:** enter your site URL (`https://your-domain.atlassian.net`), your Atlassian email and an
   [API token](https://id.atlassian.com/manage-profile/security/api-tokens). These are used to
   look up issue ids and your account id.
3. **Tempo:** create an API token in Tempo (*Settings › API integration*) and paste it in.
4. Choose a submission mode and, optionally, a default issue for the project.

Tokens are stored in the IDE password safe (by default, the system keychain).

## How time becomes worklogs

- Tracked sessions are grouped by issue and day. The worklog starts at the day's first session
  on that issue.
- Totals are rounded to the nearest increment (default 1 minute). Today's time that still rounds
  to zero stays pending; past days round up to one increment.
- If a worklog the plugin created is deleted in Tempo, the next submission creates a new one with
  only the time still pending. If you edit one in Tempo, the next submission on that issue that
  day overwrites the duration with the tracked total.

## Development

```bash
./gradlew test          # unit tests
./gradlew runIde        # sandbox IDE with the plugin
./gradlew buildPlugin   # zip in build/distributions
```
