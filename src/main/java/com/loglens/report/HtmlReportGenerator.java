package com.loglens.report;

import com.loglens.model.AnalysisOutcome;
import com.loglens.model.ErrorRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class HtmlReportGenerator {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public Path generate(List<ErrorRecord> records, Path outputPath) throws IOException {
        Files.writeString(outputPath, render(records), StandardCharsets.UTF_8);
        return outputPath;
    }

    static String render(List<ErrorRecord> records) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                <!DOCTYPE html>
                <html lang="ko">
                <head>
                <meta charset="UTF-8">
                <title>loglens 에러 리포트</title>
                <style>
                body { font-family: sans-serif; margin: 2rem; }
                table { border-collapse: collapse; width: 100%; }
                th, td { border: 1px solid #ccc; padding: 8px; text-align: left; vertical-align: top; }
                th { background: #f4f4f4; }
                pre { background: #f8f8f8; padding: 8px; overflow-x: auto; }
                .label { color: #888; font-size: 0.85em; }
                </style>
                </head>
                <body>
                <h1>loglens 에러 리포트</h1>
                """);
        if (records.isEmpty()) {
            sb.append("<p>기록된 에러 없음</p>\n");
        } else {
            sb.append("<table>\n<tr><th>#</th><th>최초 발생</th><th>예외 타입</th>")
                    .append("<th>위치</th><th>발생</th><th>AI 분석</th></tr>\n");
            int index = 1;
            for (ErrorRecord record : records) {
                sb.append("<tr>")
                        .append("<td>").append(index++).append("</td>")
                        .append("<td>").append(TIME.format(record.event().timestamp())).append("</td>")
                        .append("<td>").append(escapeHtml(record.event().exceptionType())).append("</td>")
                        .append("<td><code>").append(escapeHtml(record.event().location())).append("</code>")
                        .append("<details><summary>스택트레이스</summary><pre>")
                        .append(escapeHtml(record.event().rawTrace())).append("</pre></details></td>")
                        .append("<td>").append(record.occurrenceCount()).append("회 반복 발생</td>")
                        .append("<td>").append(renderOutcome(record.outcome())).append("</td>")
                        .append("</tr>\n");
            }
            sb.append("</table>\n");
        }
        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    private static String renderOutcome(AnalysisOutcome outcome) {
        if (outcome instanceof AnalysisOutcome.Analyzed analyzed) {
            return "<span class=\"label\">[AI 추정 · 확신도: "
                    + escapeHtml(analyzed.result().confidence()) + "]</span><br>"
                    + "<b>원인:</b> " + escapeHtml(analyzed.result().cause()) + "<br>"
                    + "<b>설명:</b> " + escapeHtml(analyzed.result().explanation()) + "<br>"
                    + "<b>제안:</b> " + escapeHtml(analyzed.result().suggestion());
        }
        if (outcome instanceof AnalysisOutcome.Skipped skipped) {
            return "<span class=\"label\">[분석 건너뜀 - " + escapeHtml(skipped.reason()) + "]</span>";
        }
        if (outcome instanceof AnalysisOutcome.Failed failed) {
            return "<span class=\"label\">[분석 실패 - " + escapeHtml(failed.reason()) + "]</span>";
        }
        throw new IllegalStateException("처리하지 않은 AnalysisOutcome 타입: " + outcome.getClass());
    }

    static String escapeHtml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
