package com.loglens.parser;

import com.loglens.model.ErrorEvent;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StackTraceAggregator {

    // Logback 기본 패턴(BasicConfigurator)의 줄 시작: "HH:mm:ss.SSS [thread]" (ADR 0001)
    private static final Pattern LOG_LINE_START =
            Pattern.compile("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3} \\[");
    private static final Pattern EXCEPTION_LINE =
            Pattern.compile("^((?:[\\w$]+\\.)+[\\w$]*(?:Exception|Error)[\\w$]*)(?::.*)?$");
    private static final Pattern AT_LINE =
            Pattern.compile("^\\s+at\\s+(\\S.*)$");

    private final Clock clock;
    private final StringBuilder partialLine = new StringBuilder();
    private final List<String> block = new ArrayList<>();

    public StackTraceAggregator(Clock clock) {
        this.clock = clock;
    }

    public List<ErrorEvent> accept(String chunk) {
        List<ErrorEvent> events = new ArrayList<>();
        partialLine.append(chunk);
        int nl;
        while ((nl = partialLine.indexOf("\n")) >= 0) {
            String line = partialLine.substring(0, nl);
            partialLine.delete(0, nl + 1);
            onLine(line, events);
        }
        return events;
    }

    public List<ErrorEvent> flush() {
        List<ErrorEvent> events = new ArrayList<>();
        if (partialLine.length() > 0) {
            // 개행 없이 끝난 마지막 줄도 버려지지 않도록 일반 줄과 동일하게 분류한다
            onLine(partialLine.toString(), events);
            partialLine.setLength(0);
        }
        emitIfTrace(events);
        return events;
    }

    private void onLine(String rawLine, List<ErrorEvent> events) {
        // CRLF 로그 파일 대응: 정규식이 "$" 앞에 남은 \r 때문에 매칭 실패하는 것을 방지
        String line = stripTrailingCr(rawLine);
        if (LOG_LINE_START.matcher(line).find()) {
            // 새 로그 줄 = 직전 트레이스 블록의 끝
            emitIfTrace(events);
        } else if (!line.isBlank()) {
            block.add(line);
        }
    }

    private static String stripTrailingCr(String line) {
        if (line.endsWith("\r")) {
            return line.substring(0, line.length() - 1);
        }
        return line;
    }

    private void emitIfTrace(List<ErrorEvent> events) {
        if (block.isEmpty()) {
            return;
        }
        String exceptionType = null;
        String location = null;
        for (String line : block) {
            if (exceptionType == null) {
                Matcher m = EXCEPTION_LINE.matcher(line);
                if (m.matches()) {
                    exceptionType = m.group(1);
                }
            } else {
                Matcher m = AT_LINE.matcher(line);
                if (m.matches()) {
                    location = m.group(1);
                    break;
                }
            }
        }
        if (exceptionType != null && location != null) {
            events.add(new ErrorEvent(exceptionType, location,
                    String.join("\n", block), LocalDateTime.now(clock)));
        }
        block.clear();
    }
}
