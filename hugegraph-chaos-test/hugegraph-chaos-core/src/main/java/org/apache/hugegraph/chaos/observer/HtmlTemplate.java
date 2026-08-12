/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hugegraph.chaos.observer;

import org.apache.hugegraph.chaos.model.ChaosReport;

import java.time.format.DateTimeFormatter;

public final class HtmlTemplate {

    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private HtmlTemplate() {
    }

    public static String render(ChaosReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n");
        sb.append("<html lang=\"en\">\n");
        sb.append("<head>\n");
        sb.append("<meta charset=\"UTF-8\">\n");
        sb.append("<title>Chaos Test Report - ").append(escape(report.getScenarioName()))
          .append("</title>\n");
        sb.append("<style>\n");
        sb.append(css());
        sb.append("</style>\n");
        sb.append("</head>\n");
        sb.append("<body>\n");
        sb.append("<div class=\"container\">\n");
        sb.append("<h1>Chaos Test Report</h1>\n");
        sb.append(summary(report));
        sb.append(steps(report));
        sb.append("</div>\n");
        sb.append("</body>\n");
        sb.append("</html>\n");
        return sb.toString();
    }

    private static String summary(ChaosReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"summary\">\n");
        sb.append("<h2>Summary</h2>\n");
        sb.append("<table>\n");
        sb.append(row("Scenario", escape(report.getScenarioName())));
        sb.append(row("Status",
                      report.isPassed()
                      ? "<span class=\"pass\">PASSED</span>"
                      : "<span class=\"fail\">FAILED</span>"));
        long skippedCount = report.getStepResults().stream()
                                  .filter(ChaosReport.StepResult::isSkipped).count();
        if (skippedCount > 0) {
            sb.append(row("Skipped Steps",
                          "<span class=\"skip\">" + skippedCount + " step(s) skipped " +
                          "(environment limitations)</span>"));
        }
        if (report.getStartTime() != null) {
            sb.append(row("Start", report.getStartTime().format(FMT)));
        }
        if (report.getEndTime() != null) {
            sb.append(row("End", report.getEndTime().format(FMT)));
        }
        sb.append(row("Duration", report.getDuration().getSeconds() + "s"));
        sb.append("</table>\n");
        if (report.getError() != null) {
            sb.append("<div class=\"error\">\n");
            sb.append("<h3>Error</h3>\n");
            sb.append("<pre>").append(escape(report.getError().getMessage()))
              .append("</pre>\n");
            sb.append("</div>\n");
        }
        sb.append("</div>\n");
        return sb.toString();
    }

    private static String steps(ChaosReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"steps\">\n");
        sb.append("<h2>Steps</h2>\n");
        sb.append("<table>\n");
        sb.append("<thead><tr>");
        sb.append("<th>#</th>");
        sb.append("<th>Name</th>");
        sb.append("<th>Type</th>");
        sb.append("<th>Status</th>");
        sb.append("<th>Duration</th>");
        sb.append("<th>Message</th>");
        sb.append("</tr></thead>\n");
        sb.append("<tbody>\n");
        int idx = 1;
        for (ChaosReport.StepResult result : report.getStepResults()) {
            sb.append("<tr>");
            sb.append("<td>").append(idx++).append("</td>");
            sb.append("<td>").append(escape(result.getStepName())).append("</td>");
            sb.append("<td>").append(result.getStepType()).append("</td>");
            sb.append("<td>")
              .append(result.isSkipped()
                      ? "<span class=\"skip\">SKIPPED</span>"
                      : result.isPassed()
                      ? "<span class=\"pass\">PASSED</span>"
                      : "<span class=\"fail\">FAILED</span>")
              .append("</td>");
            sb.append("<td>").append(result.getDuration().getSeconds()).append("s</td>");
            sb.append("<td>").append(escape(result.getMessage())).append("</td>");
            sb.append("</tr>\n");
        }
        sb.append("</tbody>\n");
        sb.append("</table>\n");
        sb.append("</div>\n");
        return sb.toString();
    }

    private static String row(String key, String value) {
        return "<tr><th>" + key + "</th><td>" + value + "</td></tr>\n";
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }

    private static String css() {
        return "body { font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\","
            + "Roboto, sans-serif; margin: 0; padding: 0; background: #f5f5f5; }"
            + ".container { max-width: 960px; margin: 40px auto;"
            + "background: #fff; padding: 32px; border-radius: 8px;"
            + "box-shadow: 0 2px 8px rgba(0,0,0,0.1); }"
            + "h1 { margin-top: 0; color: #333; }"
            + "h2 { color: #555; border-bottom: 1px solid #eee; padding-bottom: 8px; }"
            + "table { width: 100%; border-collapse: collapse; margin-top: 12px; }"
            + "th, td { text-align: left; padding: 10px 12px;"
            + "border-bottom: 1px solid #eee; }"
            + "th { background: #fafafa; font-weight: 600; width: 160px; }"
            + "thead th { background: #f0f0f0; width: auto; }"
            + ".pass { color: #2e7d32; font-weight: 600; }"
            + ".fail { color: #c62828; font-weight: 600; }"
            + ".skip { color: #f57f17; font-weight: 600; }"
            + ".error { margin-top: 16px; padding: 12px; background: #ffebee;"
            + "border-radius: 4px; }"
            + ".error pre { margin: 0; white-space: pre-wrap; }";
    }
}
