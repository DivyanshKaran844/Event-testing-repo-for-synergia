package com.eventtracker.html;

import com.eventtracker.model.Event;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

/**
 * Renders a list of Events as a single self-contained HTML file (no external
 * JS/CSS) so it can just be opened straight from disk in a browser.
 */
public class HtmlReportWriter {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /**
     * @param autoRefreshSeconds if > 0, the page reloads itself on that interval
     *                           (used for the "live" view); pass 0 for a static report.
     */
    public void write(String filePath, String pageTitle, List<Event> events, int autoRefreshSeconds) {
        List<Event> sorted = events.stream()
                .sorted(Comparator.comparing((Event e) -> e.time).reversed())
                .toList();

        long githubCount = sorted.stream().filter(e -> e.source.equals("GitHub")).count();
        long jiraCount = sorted.stream().filter(e -> e.source.equals("Jira")).count();

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='utf-8'>");
        html.append("<title>").append(escape(pageTitle)).append("</title>");
        if (autoRefreshSeconds > 0) {
            html.append("<meta http-equiv='refresh' content='").append(autoRefreshSeconds).append("'>");
        }
        html.append(style());
        html.append("</head><body>");
        html.append("<h1>").append(escape(pageTitle)).append("</h1>");
        html.append("<p class='meta'>Generated ").append(TIME_FORMAT.format(java.time.Instant.now())).append("</p>");

        html.append("<div class='stats'>")
                .append(statCard(sorted.size(), "Total events", null))
                .append(statCard(githubCount, "GitHub", "github"))
                .append(statCard(jiraCount, "Jira", "jira"))
                .append("</div>");

        html.append("<div class='toolbar'>")
                .append("<label for='sourceFilter'>Filter: </label>")
                .append("<select id='sourceFilter' onchange='filterTable()'>")
                .append("<option value='all'>All sources</option>")
                .append("<option value='github'>GitHub only</option>")
                .append("<option value='jira'>Jira only</option>")
                .append("</select></div>");

        html.append("<table id='eventTable'><thead><tr>")
                .append("<th>Time</th><th>Source</th><th>Type</th><th>Title</th>")
                .append("<th>Detail</th><th>Actor</th><th>Link</th>")
                .append("</tr></thead><tbody>");

        for (Event e : sorted) {
            html.append("<tr data-source='").append(e.source.toLowerCase()).append("'>");
            html.append("<td>").append(TIME_FORMAT.format(e.time)).append("</td>");
            html.append("<td><span class='badge ").append(e.source.toLowerCase()).append("'>")
                    .append(escape(e.source)).append("</span></td>");
            html.append("<td>").append(escape(e.type)).append("</td>");
            html.append("<td>").append(escape(e.title)).append("</td>");
            html.append("<td>").append(escape(e.description)).append("</td>");
            html.append("<td>").append(escape(e.actor)).append("</td>");
            html.append("<td>").append(e.url.isBlank() ? "" :
                    "<a href='" + escape(e.url) + "' target='_blank'>open</a>").append("</td>");
            html.append("</tr>");
        }

        html.append("</tbody></table>");
        html.append(script());
        html.append("</body></html>");

        try {
            Files.writeString(Path.of(filePath), html.toString());
        } catch (IOException ex) {
            System.err.println("Failed to write HTML report to " + filePath + ": " + ex.getMessage());
        }
    }

    private String statCard(long count, String label, String badgeClass) {
        String accent = badgeClass == null ? "" : " " + badgeClass;
        return "<div class='stat-card" + accent + "'><div class='stat-count'>" + count +
                "</div><div class='stat-label'>" + escape(label) + "</div></div>";
    }

    private String script() {
        return "<script>" +
                "function filterTable(){" +
                "var value=document.getElementById('sourceFilter').value;" +
                "var rows=document.querySelectorAll('#eventTable tbody tr');" +
                "rows.forEach(function(row){" +
                "row.style.display=(value==='all'||row.dataset.source===value)?'':'none';" +
                "});}" +
                "</script>";
    }

    private String style() {
        return "<style>" +
                "body{font-family:Arial,Helvetica,sans-serif;margin:2rem;background:#f7f7f9;color:#222;}" +
                "h1{margin-bottom:0.2rem;}" +
                ".meta{color:#666;margin-top:0;margin-bottom:1.2rem;}" +
                ".stats{display:flex;gap:12px;margin-bottom:1.2rem;}" +
                ".stat-card{background:#fff;border-radius:8px;padding:14px 20px;box-shadow:0 1px 3px rgba(0,0,0,0.1);" +
                "border-left:4px solid #999;min-width:120px;}" +
                ".stat-card.github{border-left-color:#24292e;}" +
                ".stat-card.jira{border-left-color:#0052cc;}" +
                ".stat-count{font-size:28px;font-weight:bold;}" +
                ".stat-label{color:#666;font-size:13px;}" +
                ".toolbar{margin-bottom:10px;font-size:14px;}" +
                ".toolbar select{padding:4px 8px;font-size:14px;}" +
                "table{border-collapse:collapse;width:100%;background:#fff;box-shadow:0 1px 3px rgba(0,0,0,0.1);}" +
                "th,td{padding:8px 10px;border-bottom:1px solid #eee;text-align:left;font-size:14px;}" +
                "th{background:#2d2f36;color:#fff;position:sticky;top:0;}" +
                "tr:hover{background:#f1f5ff;}" +
                ".badge{padding:2px 8px;border-radius:10px;color:#fff;font-size:12px;}" +
                ".badge.github{background:#24292e;}" +
                ".badge.jira{background:#0052cc;}" +
                "a{color:#0052cc;text-decoration:none;}" +
                "</style>";
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
