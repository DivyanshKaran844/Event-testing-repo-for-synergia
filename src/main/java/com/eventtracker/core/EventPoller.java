package com.eventtracker.core;

import com.eventtracker.github.GitHubClient;
import com.eventtracker.html.HtmlReportWriter;
import com.eventtracker.jira.JiraClient;
import com.eventtracker.model.Event;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The "live" mode: every `intervalSeconds`, ask GitHub/Jira what's new,
 * print anything we haven't printed before to the console, and refresh
 * live.html with the running list so it can be left open in a browser tab.
 */
public class EventPoller {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final int MAX_EVENTS_KEPT_FOR_HTML = 300;

    private final GitHubClient gitHub;   // null if not configured
    private final JiraClient jira;       // null if not configured
    private final HtmlReportWriter htmlWriter = new HtmlReportWriter();
    private final String liveHtmlPath;
    private final int intervalSeconds;

    private final Set<String> seenEventIds = new LinkedHashSet<>();
    private final List<Event> recentEvents = new ArrayList<>();

    public EventPoller(GitHubClient gitHub, JiraClient jira, String liveHtmlPath, int intervalSeconds) {
        this.gitHub = gitHub;
        this.jira = jira;
        this.liveHtmlPath = liveHtmlPath;
        this.intervalSeconds = intervalSeconds;
    }

    /** Runs forever (until Ctrl+C), polling on a fixed interval. */
    public void run() throws InterruptedException {
        System.out.println("Watching for events every " + intervalSeconds + "s. Press Ctrl+C to stop.");
        System.out.println("Live view: " + liveHtmlPath);

        // Prime with whatever already exists so the first real poll only reports
        // genuinely new activity instead of dumping the whole current backlog.
        int baseline = 0;
        if (gitHub != null) baseline += filterNew(safeFetch("GitHub", gitHub::fetchLatestEvents)).size();
        if (jira != null) baseline += filterNew(safeFetch("Jira", jira::fetchLatestEvents)).size();
        System.out.println("Loaded " + baseline + " existing event(s) as baseline, watching for new ones...");

        while (true) {
            List<Event> newEvents = new ArrayList<>();

            if (gitHub != null) {
                newEvents.addAll(filterNew(safeFetch("GitHub", gitHub::fetchLatestEvents)));
            }
            if (jira != null) {
                newEvents.addAll(filterNew(safeFetch("Jira", jira::fetchLatestEvents)));
            }

            if (!newEvents.isEmpty()) {
                newEvents.sort((a, b) -> a.time.compareTo(b.time));
                for (Event e : newEvents) {
                    System.out.println(TIME_FORMAT.format(Instant.now()) + " NEW  " + e.toCliLine());
                }
                recentEvents.addAll(0, newEvents);
                trimRecentEvents();
                htmlWriter.write(liveHtmlPath, "Live Event Feed", recentEvents, intervalSeconds);
            } else {
                System.out.println(TIME_FORMAT.format(Instant.now()) + " no new events");
            }

            Thread.sleep(intervalSeconds * 1000L);
        }
    }

    private List<Event> filterNew(List<Event> fetched) {
        List<Event> fresh = new ArrayList<>();
        for (Event e : fetched) {
            if (seenEventIds.add(e.id)) {
                fresh.add(e);
            }
        }
        return fresh;
    }

    private interface Fetcher { List<Event> fetch(); }

    private List<Event> safeFetch(String sourceName, Fetcher fetcher) {
        try {
            return fetcher.fetch();
        } catch (Exception e) {
            System.err.println("Error polling " + sourceName + ": " + e.getMessage());
            return List.of();
        }
    }

    private void trimRecentEvents() {
        while (recentEvents.size() > MAX_EVENTS_KEPT_FOR_HTML) {
            recentEvents.remove(recentEvents.size() - 1);
        }
    }
}
