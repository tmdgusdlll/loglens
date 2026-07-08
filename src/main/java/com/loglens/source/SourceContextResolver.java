package com.loglens.source;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class SourceContextResolver {

    // "com.example.demo.FooService$Inner.method(FooService.java:42)" →
    //   group1=FQCN(클래스, $내부클래스 포함), group2=파일 base, group3=줄번호
    private static final Pattern LOCATION =
            Pattern.compile("^([\\w$.]+)\\.[\\w$<>]+\\((\\w+)\\.java:(\\d+)\\)$");
    private static final int CONTEXT_LINES = 10;

    private final Path watchDir;

    public SourceContextResolver(Path watchDir) {
        this.watchDir = watchDir;
    }

    public Optional<String> resolve(String location) {
        Matcher m = LOCATION.matcher(location);
        if (!m.matches()) {
            return Optional.empty();
        }
        String fqcn = m.group(1);
        int dollar = fqcn.indexOf('$');
        if (dollar >= 0) {
            fqcn = fqcn.substring(0, dollar); // 내부 클래스는 바깥 클래스 파일에 있음
        }
        String fileBase = m.group(2);
        if (!fqcn.endsWith("." + fileBase) && !fqcn.equals(fileBase)) {
            return Optional.empty();
        }
        Path relPath = Path.of(fqcn.replace('.', '/') + ".java");

        try (Stream<Path> walk = Files.walk(watchDir)) {
            // 줄번호 자릿수는 정규식이 제한하지 않으므로 int 오버플로 가능성이 있어 try 안에서 처리한다.
            int lineNo = Integer.parseInt(m.group(3));
            Optional<Path> file = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.endsWith(relPath))
                    .findFirst();
            if (file.isEmpty()) {
                return Optional.empty();
            }
            List<String> lines = Files.readAllLines(file.get());
            if (lineNo < 1 || lineNo > lines.size()) {
                return Optional.empty();
            }
            int from = Math.max(1, lineNo - CONTEXT_LINES);
            int to = Math.min(lines.size(), lineNo + CONTEXT_LINES);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i <= to; i++) {
                sb.append(i == lineNo ? ">" : " ")
                        .append(String.format("%3d: ", i))
                        .append(lines.get(i - 1))
                        .append('\n');
            }
            return Optional.of(sb.toString());
        } catch (IOException | UncheckedIOException | NumberFormatException e) {
            // Files.walk는 지연 순회 중 하위 디렉토리 접근 실패 시 UncheckedIOException을 던진다.
            return Optional.empty(); // 읽기/파싱 실패 = 컨텍스트 없이 진행 (ADR 0010: 루프는 죽지 않는다)
        }
    }
}
