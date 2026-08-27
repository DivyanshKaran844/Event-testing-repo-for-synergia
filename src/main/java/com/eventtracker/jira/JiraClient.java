package com.eventtracker.jira;

import com.eventtracker.model.Event;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Talks to the Jira Cloud REST API to read ticket activity for one project:
 * https://developer.atlassian.com/cloud/jira/platform/rest/v3/api-group-issue-search/#api-rest-api-3-search-jql-get
 *
 * Every field change on every ticket (status, labels, assignee, ...) shows up
 * in the issue's "changelog". A status change is Jira's equivalent of "a tag
 * moved on the board" (moving a card between columns changes its status).
 */
public class JiraClient {

    private final String baseUrl;
    private final String authHeader;
    private final String projectKey;
    private final HttpClient http = HttpClient.newHttpClient();

    // Set by restrictToAuthenticatedUser(); when non-null, only changelog
    // entries authored by this display name are returned.
    private String onlyActorDisplayName;

    public JiraClient(String baseUrl, String email, String apiToken, String projectKey) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.projectKey = projectKey;
        String creds = email + ":" + apiToken;
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Looks up who config's email/token belong to (GET /myself) and, from then
     * on, only returns changes that person made - i.e. "just my activity"
     * instead of the whole project's. Prints a warning and leaves things
     * unfiltered if the lookup fails.
     */
    public void restrictToAuthenticatedUser() {
        JSONObject me = get(baseUrl + "/rest/api/3/myself");
        if (me == null) {
            System.err.println("Jira: couldn't verify your identity - showing everyone's events.");
            return;
        }
        onlyActorDisplayName = me.optString("displayName", null);
        System.out.println("Jira: filtering to just your activity (" + onlyActorDisplayName + ")");
    }

    /** All field-change events on tickets in this project within the last `days` days. */
    public List<Event> fetchEventsForLastDays(int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        String jql = "project = " + projectKey + " AND updated >= -" + days + "d ORDER BY updated DESC";
        return search(jql, 100, since);
    }

    /**
     * Convenience used by the live poller: changelog entries from the last day.
     * Jira's changelog expand always returns an issue's FULL history with no
     * server-side time filter, so without this cutoff a ticket with years of
     * history would dump every past change on every poll.
     */
    public List<Event> fetchLatestEvents() {
        Instant since = Instant.now().minus(1, ChronoUnit.DAYS);
        String jql = "project = " + projectKey + " AND updated >= -1d ORDER BY updated DESC";
        return search(jql, 25, since);
    }

    /**
     * NOTE: Jira's search+expand=changelog only returns each issue's 10 most
     * recent changelog entries (an Atlassian API limitation, not a bug here).
     * If a single ticket has more than 10 field changes inside your report
     * window, the oldest ones in that window will be missed. For a ticket
     * that busy, use Jira directly. Fetching full history would require the
     * separate (currently experimental) /rest/api/3/changelog/bulkfetch API.
     */
    private List<Event> search(String jql, int maxResults, Instant since) {
        List<Event> events = new ArrayList<>();
        // NOTE: the old GET /rest/api/3/search endpoint was fully removed by Atlassian
        // in October 2025 (returns 410 Gone) - /search/jql is its replacement.
        String url = baseUrl + "/rest/api/3/search/jql?jql=" + urlEncode(jql)
                + "&maxResults=" + maxResults
                + "&expand=changelog"
                + "&fields=summary";

        JSONObject response = get(url);
        if (response == null) return events;

        JSONArray issues = response.optJSONArray("issues");
        if (issues == null) return events;

        for (int i = 0; i < issues.length(); i++) {
            JSONObject issue = issues.getJSONObject(i);
            events.addAll(toEvents(issue, since));
        }
        if (onlyActorDisplayName != null) {
            events.removeIf(e -> !e.actor.equalsIgnoreCase(onlyActorDisplayName));
        }
        return events;
    }

    private List<Event> toEvents(JSONObject issue, Instant since) {
        List<Event> events = new ArrayList<>();
        String key = issue.getString("key");
        String summary = issue.getJSONObject("fields").optString("summary", "(no summary)");
        String ticketUrl = baseUrl + "/browse/" + key;

        JSONObject changelog = issue.optJSONObject("changelog");
        if (changelog == null) return events;

        JSONArray histories = changelog.optJSONArray("histories");
        if (histories == null) return events;

        for (int h = 0; h < histories.length(); h++) {
            JSONObject history = histories.getJSONObject(h);
            Instant created = Instant.parse(normalizeJiraTimestamp(history.getString("created")));
            if (created.isBefore(since)) continue;

            String author = history.has("author")
                    ? history.getJSONObject("author").optString("displayName", "unknown")
                    : "unknown";
            String historyId = history.optString("id", String.valueOf(created.toEpochMilli()));

            JSONArray items = history.optJSONArray("items");
            if (items == null) continue;

            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                String field = item.optString("field", "field");
                String from = item.optString("fromString", "none");
                String to = item.optString("toString", "none");

                events.add(new Event(
                        "Jira",
                        "jira-" + key + "-" + historyId + "-" + field,
                        field.equalsIgnoreCase("status") ? "status_changed" : field + "_changed",
                        key + ": " + summary,
                        "changed " + field + " from '" + from + "' to '" + to + "'",
                        author,
                        created,
                        ticketUrl
                ));
            }
        }
        return events;
    }

    /** Jira sometimes returns "2024-01-01T10:00:00.000+0000" (no colon in the offset); Instant.parse wants "+00:00". */
    private String normalizeJiraTimestamp(String ts) {
        return ts.replaceAll("([+-]\\d{2})(\\d{2})$", "$1:$2");
    }

    private JSONObject get(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .header("Authorization", authHeader)
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.println("Jira API error " + response.statusCode() + ": " + response.body());
                return null;
            }
            return new JSONObject(response.body());
        } catch (IOException | InterruptedException e) {
            System.err.println("Failed to reach Jira: " + e.getMessage());
            return null;
        }
    }

    private String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
