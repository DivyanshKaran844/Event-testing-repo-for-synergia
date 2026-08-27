package com.eventtracker.config;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Loads settings from config.properties (see config.properties.example).
 * Any of the GitHub or Jira fields can be left blank -> that source is simply skipped.
 */
public class AppConfig {

    private final Properties props = new Properties();

    public AppConfig(String path) {
        Path file = Path.of(path);
        if (Files.exists(file)) {
            try (FileInputStream in = new FileInputStream(file.toFile())) {
                props.load(in);
            } catch (IOException e) {
                throw new RuntimeException("Could not read config file: " + path, e);
            }
        } else {
            System.out.println("No " + path + " found, falling back to environment variables.");
        }
    }

    private String get(String propKey, String envKey) {
        String value = props.getProperty(propKey);
        if (value == null || value.isBlank()) {
            value = System.getenv(envKey);
        }
        return value;
    }

    public String githubToken() { return get("github.token", "GITHUB_TOKEN"); }
    public String githubRepo() { return get("github.repo", "GITHUB_REPO"); }

    public String jiraBaseUrl() { return get("jira.baseUrl", "JIRA_BASE_URL"); }
    public String jiraEmail() { return get("jira.email", "JIRA_EMAIL"); }
    public String jiraApiToken() { return get("jira.apiToken", "JIRA_API_TOKEN"); }
    public String jiraProjectKey() { return get("jira.projectKey", "JIRA_PROJECT_KEY"); }

    public int pollIntervalSeconds() {
        String value = get("poll.intervalSeconds", "POLL_INTERVAL_SECONDS");
        return value == null ? 30 : Integer.parseInt(value.trim());
    }

    /** true = only show events you personally did, instead of everyone's on the repo/project. */
    public boolean onlyMine() {
        String value = get("filter.onlyMine", "FILTER_ONLY_MINE");
        return value != null && Boolean.parseBoolean(value.trim());
    }

    public boolean githubConfigured() {
        // A token isn't strictly required for public repos, just a much lower rate limit.
        return notBlank(githubRepo());
    }

    public boolean jiraConfigured() {
        return notBlank(jiraBaseUrl()) && notBlank(jiraEmail())
                && notBlank(jiraApiToken()) && notBlank(jiraProjectKey());
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
