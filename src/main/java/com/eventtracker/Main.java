package com.eventtracker;

import com.eventtracker.config.AppConfig;
import com.eventtracker.core.EventPoller;
import com.eventtracker.github.GitHubClient;
import com.eventtracker.html.HtmlReportWriter;
import com.eventtracker.jira.JiraClient;
import com.eventtracker.model.Event;

import java.util.ArrayList;
import java.util.List;

/**
 * Entry point.
 *
 * Usage:
 *   java -jar event-tracker.jar live               -> poll GitHub/Jira and print new events as they happen
 *   java -jar event-tracker.jar report [days]       -> write report.html covering the last `days` days (default 30)
 */
public class Main {

    public static void main(String[] args) {
        AppConfig config = new AppConfig("config.properties");

        if (!config.githubConfigured() && !config.jiraConfigured()) {
            System.err.println("Neither GitHub nor Jira is configured. Copy config.properties.example to " +
                    "config.properties and fill in at least one of them.");
            System.exit(1);
        }

        GitHubClient gitHub = config.githubConfigured()
                ? new GitHubClient(config.githubToken(), config.githubRepo())
                : null;
        JiraClient jira = config.jiraConfigured()
                ? new JiraClient(config.jiraBaseUrl(), config.jiraEmail(), config.jiraApiToken(), config.jiraProjectKey())
                : null;

        printSourceStatus(gitHub, jira);

        if (config.onlyMine()) {
            if (gitHub != null) gitHub.restrictToAuthenticatedUser();
            if (jira != null) jira.restrictToAuthenticatedUser();
        }

        String mode = args.length > 0 ? args[0] : "report";

        try {
            switch (mode) {
                case "live" -> runLive(gitHub, jira, config.pollIntervalSeconds());
                case "report" -> runReport(gitHub, jira, args.length > 1 ? Integer.parseInt(args[1]) : 30);
                default -> printUsage();
            }
        } catch (InterruptedException e) {
            System.out.println("Stopped.");
        }
    }

    private static void printSourceStatus(GitHubClient gitHub, JiraClient jira) {
        System.out.println("GitHub: " + (gitHub != null ? "configured" : "not configured (skipping)"));
        System.out.println("Jira:   " + (jira != null ? "configured" : "not configured (skipping)"));
    }

    private static void runLive(GitHubClient gitHub, JiraClient jira, int intervalSeconds) throws InterruptedException {
        EventPoller poller = new EventPoller(gitHub, jira, "live.html", intervalSeconds);
        poller.run();
    }

    private static void runReport(GitHubClient gitHub, JiraClient jira, int days) {
        System.out.println("Fetching events from the last " + days + " day(s)...");
        List<Event> events = new ArrayList<>();

        if (gitHub != null) {
            events.addAll(gitHub.fetchEventsSince(java.time.Instant.now().minus(days, java.time.temporal.ChronoUnit.DAYS), 5));
        }
        if (jira != null) {
            events.addAll(jira.fetchEventsForLastDays(days));
        }

        String outputFile = "report.html";
        new HtmlReportWriter().write(outputFile, "Events - last " + days + " days", events, 0);
        System.out.println("Wrote " + events.size() + " event(s) to " + outputFile);
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  java -jar event-tracker.jar live          # print new events to the console as they happen");
        System.out.println("  java -jar event-tracker.jar report [days] # write report.html for the last N days (default 30)");
    }
}
