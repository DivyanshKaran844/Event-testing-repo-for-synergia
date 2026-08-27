package com.eventtracker.github;

import com.eventtracker.model.Event;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Talks to the GitHub REST API to read issue/PR activity for one repo:
 * https://docs.github.com/en/rest/issues/events
 *
 * This single endpoint covers most of what a PR/ticket board cares about:
 * labels being added/removed ("a tag moved"), PRs being merged/closed,
 * reviewers requested, assignees changed, etc.
 */
public class GitHubClient {

    private static final int PAGE_SIZE = 100;

    private final String token;
    private final String owner;
    private final String repo;
    private final HttpClient http = HttpClient.newHttpClient();

    // Set by restrictToAuthenticatedUser(); when non-null, only events whose
    // actor matches this GitHub login are returned.
    private String onlyActorLogin;

    public GitHubClient(String token, String ownerSlashRepo) {
        this.token = token;
        String[] parts = ownerSlashRepo.split("/", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("github.repo must look like owner/repo, got: " + ownerSlashRepo);
        }
        this.owner = parts[0];
        this.repo = parts[1];
    }

    /**
     * Looks up who the configured token belongs to (GET /user) and, from then on,
     * only returns events that person did - i.e. "just my activity" instead of
     * everyone's. Requires a token; prints a warning and leaves things
     * unfiltered if there isn't one or the lookup fails.
     */
    public void restrictToAuthenticatedUser() {
        if (token == null || token.isBlank()) {
            System.err.println("GitHub: can't filter to 'just my activity' without a token - showing everyone's events.");
            return;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.github.com/user"))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + token)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.println("GitHub: couldn't verify your identity (HTTP " + response.statusCode() +
                        ") - showing everyone's events.");
                return;
            }
            onlyActorLogin = new JSONObject(response.body()).optString("login", null);
            System.out.println("GitHub: filtering to just your activity (" + onlyActorLogin + ")");
        } catch (IOException | InterruptedException e) {
            System.err.println("GitHub: couldn't verify your identity (" + e.getMessage() + ") - showing everyone's events.");
        }
    }

    /**
     * Fetches up to maxPages * 100 of the most recent issue/PR events, newest first
     * as returned by GitHub, and hands back the ones no older than `since`.
     */
    public List<Event> fetchEventsSince(Instant since, int maxPages) {
        List<Event> events = new ArrayList<>();
        for (int page = 1; page <= maxPages; page++) {
            JSONArray batch = fetchPage(page);
            if (batch.isEmpty()) break;

            boolean crossedCutoff = false;
            for (int i = 0; i < batch.length(); i++) {
                JSONObject raw = batch.getJSONObject(i);
                Instant createdAt = Instant.parse(raw.getString("created_at"));
                if (createdAt.isBefore(since)) {
                    crossedCutoff = true;
                    continue;
                }
                events.add(toEvent(raw));
            }
            // Events come back newest-first, so once we see one older than the
            // cutoff on a page there is nothing useful left on later pages either.
            if (crossedCutoff) break;
        }
        return applyActorFilter(events);
    }

    /** Convenience used by the live poller: just the most recent page of events. */
    public List<Event> fetchLatestEvents() {
        List<Event> events = new ArrayList<>();
        JSONArray batch = fetchPage(1);
        for (int i = 0; i < batch.length(); i++) {
            events.add(toEvent(batch.getJSONObject(i)));
        }
        return applyActorFilter(events);
    }

    private List<Event> applyActorFilter(List<Event> events) {
        if (onlyActorLogin == null) return events;
        events.removeIf(e -> !e.actor.equalsIgnoreCase(onlyActorLogin));
        return events;
    }

    private JSONArray fetchPage(int page) {
        String url = String.format(
                "https://api.github.com/repos/%s/%s/issues/events?per_page=%d&page=%d",
                owner, repo, PAGE_SIZE, page);

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET();
        // Public repos work without a token too, just at a much lower rate limit.
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpRequest request = builder.build();

        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.println("GitHub API error " + response.statusCode() + ": " + response.body());
                return new JSONArray();
            }
            return new JSONArray(response.body());
        } catch (IOException | InterruptedException e) {
            System.err.println("Failed to reach GitHub: " + e.getMessage());
            return new JSONArray();
        }
    }

    private Event toEvent(JSONObject raw) {
        JSONObject issue = raw.optJSONObject("issue");
        String title = issue != null ? issue.optString("title", "(no title)") : "(no title)";
        String htmlUrl = issue != null ? issue.optString("html_url", "") : "";
        boolean isPullRequest = issue != null && issue.has("pull_request");

        String actor = raw.has("actor") && !raw.isNull("actor")
                ? raw.getJSONObject("actor").optString("login", "unknown")
                : "unknown";

        String type = raw.optString("event", "unknown");
        String description = describe(raw, type, isPullRequest);

        return new Event(
                "GitHub",
                "gh-" + raw.optLong("id"),
                type,
                (isPullRequest ? "PR: " : "Issue: ") + title,
                description,
                actor,
                Instant.parse(raw.getString("created_at")),
                htmlUrl
        );
    }

    private String describe(JSONObject raw, String type, boolean isPullRequest) {
        switch (type) {
            case "labeled":
            case "unlabeled":
                String label = raw.has("label") ? raw.getJSONObject("label").optString("name", "?") : "?";
                return (type.equals("labeled") ? "added label '" : "removed label '") + label + "'";
            case "merged":
                return "pull request merged";
            case "closed":
                return (isPullRequest ? "pull request" : "issue") + " closed";
            case "reopened":
                return (isPullRequest ? "pull request" : "issue") + " reopened";
            case "review_requested":
                return "review requested";
            case "assigned":
            case "unassigned":
                return raw.has("assignee")
                        ? type + " " + raw.getJSONObject("assignee").optString("login", "?")
                        : type;
            case "renamed":
                return raw.has("rename")
                        ? "renamed from '" + raw.getJSONObject("rename").optString("from", "?")
                            + "' to '" + raw.getJSONObject("rename").optString("to", "?") + "'"
                        : "renamed";
            default:
                return type.replace('_', ' ');
        }
    }
}
