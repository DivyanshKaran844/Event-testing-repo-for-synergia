package com.eventtracker.model;

import java.time.Instant;

/**
 * One thing that happened, whether it came from GitHub or Jira.
 * Keeping GitHub and Jira data in this single shape is what lets the
 * CLI printer and the HTML report treat both sources identically.
 */
public class Event {

    public final String source;      // "GitHub" or "Jira"
    public final String id;          // unique id, used to avoid printing/reporting duplicates
    public final String type;        // e.g. "labeled", "merged", "status_changed"
    public final String title;       // PR title / issue summary
    public final String description; // human readable detail, e.g. "moved from To Do to In Progress"
    public final String actor;       // who did it
    public final Instant time;
    public final String url;         // link back to the PR or ticket

    public Event(String source, String id, String type, String title,
                 String description, String actor, Instant time, String url) {
        this.source = source;
        this.id = id;
        this.type = type;
        this.title = title;
        this.description = description;
        this.actor = actor;
        this.time = time;
        this.url = url;
    }

    public String toCliLine() {
        return String.format("[%s] %-15s %-9s %-10s %-40s %s",
                time, source, type, actor, title, description);
    }
}
