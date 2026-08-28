\# Event Tracker

A small Java tool that watches a GitHub repo and/or a Jira project for activity
(labels/tags moved, PRs merged, tickets moving status, etc.) and shows it two ways:

- **Live mode**: polls on an interval and prints new events to the terminal as they happen.
- **Report mode**: pulls everything that happened in the last N days (default 30) into a browsable `report.html`.

## How it's put together

```
Main.java              - CLI entry point, wires everything together
config/AppConfig.java  - reads config.properties (or env vars)
github/GitHubClient.java - calls the GitHub REST API (issues/events endpoint)
jira/JiraClient.java     - calls the Jira REST API (search + changelog)
model/Event.java         - one common shape for a "thing that happened", from either source
core/EventPoller.java    - the live polling loop
html/HtmlReportWriter.java - turns a list of Events into a plain HTML table
```

Both API clients turn whatever they fetch into the same `Event` shape, so the
CLI printer and the HTML writer don't need to know or care whether something
came from GitHub or Jira.

## Setup

1. `cp config.properties.example config.properties`
2. Fill in whichever of GitHub / Jira you want to use (you can use just one):
   - GitHub: a [personal access token](https://github.com/settings/tokens) (optional for public repos) + `owner/repo`
   - Jira: your site URL, login email, an [API token](https://id.atlassian.com/manage-profile/security/api-tokens), and a project key

That's it — no separate build step needed, `run.sh` handles it (see below).

## Run it

**Easiest**: `./run.sh live` or `./run.sh report [days]` — this one script
builds the jar the first time (and rebuilds automatically whenever you edit
a `.java` file), then runs it. On Windows use `run.bat` instead of `run.sh`
(same arguments). If `./run.sh` says "permission denied," run
`chmod +x run.sh` once.

```
./run.sh live          # print new events to the console as they happen (checks every 30s by default)
./run.sh report        # write report.html covering the last 30 days
./run.sh report 7      # or a custom window, e.g. last 7 days
```

**Manually**, if you'd rather control the build yourself:
```
mvn package
java -jar target/event-tracker.jar live
java -jar target/event-tracker.jar report 7
```

Open `report.html` or `live.html` in any browser — they're plain static files,
no server needed. `live.html` refreshes itself automatically at the same
interval you're polling on.

## Showing just your own activity

By default the tool shows everyone's activity on the configured repo/project.
Set `filter.onlyMine=true` in `config.properties` to narrow it down to just
you:
- GitHub: needs a real token (not blank) — it calls `GET /user` once at
  startup to find out who the token belongs to, then only keeps events where
  you're the actor (you labeled/merged/closed/etc. something).
- Jira: uses your configured `jira.email`/`jira.apiToken` — calls
  `GET /myself` once at startup, then only keeps changelog entries you
  authored.

Note this filters to events **you personally performed**, not "every PR
where I'm involved" — e.g. someone else labeling your PR won't show up,
since you didn't do that action. If you want "PRs I opened, plus everything
that happens on them" instead, that's a different (bigger) change — ask if
that's actually what you need.

## Notes / things to extend later

- Live mode keeps track of "already seen" events in memory only, so it starts
  fresh (silently loads a baseline, then only reports new activity) each time
  you restart it.
- GitHub's `issues/events` endpoint covers labels, assignees, merges, closes,
  renames, etc. for both issues and PRs.
- Jira "events" here are individual changelog field changes (status, labels,
  assignee, ...) — a status change is Jira's equivalent of moving a card
  between board columns.
- Jira caveat: Atlassian's search API only returns each ticket's 10 most
  recent changelog entries. If one ticket has more than 10 field changes
  inside your report window, the oldest of those will be missing. Fine for
  normal usage; a ticket that busy is unusual.
- Uses `GET /rest/api/3/search/jql` (the current Jira Cloud search endpoint —
  the older `/rest/api/3/search` was fully removed by Atlassian in Oct 2025).
