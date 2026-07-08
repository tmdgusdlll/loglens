package com.loglens.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SourceContextResolverTest {

    @TempDir
    Path watchDir;

    private void writeSource(String relDir, String fileName, int totalLines) throws IOException {
        Path dir = Files.createDirectories(watchDir.resolve(relDir));
        String body = IntStream.rangeClosed(1, totalLines)
                .mapToObj(i -> "line" + i)
                .collect(Collectors.joining("\n"));
        Files.writeString(dir.resolve(fileName), body);
    }

    @Test
    void 스택트레이스_위치의_소스_주변_줄을_찾는다() throws Exception {
        writeSource("src/main/java/com/example/demo", "FooService.java", 60);
        SourceContextResolver resolver = new SourceContextResolver(watchDir);

        Optional<String> context =
                resolver.resolve("com.example.demo.FooService.decrease(FooService.java:42)");

        assertTrue(context.isPresent());
        String snippet = context.get();
        assertTrue(snippet.contains("> 42: line42")); // 대상 줄 마커
        assertTrue(snippet.contains("  32: line32")); // 앞 10줄
        assertTrue(snippet.contains("  52: line52")); // 뒤 10줄
        assertFalse(snippet.contains("31: line31"));
        assertFalse(snippet.contains("53: line53"));
    }

    @Test
    void 파일_경계에서는_있는_범위만_반환한다() throws Exception {
        writeSource("src/main/java/com/example/demo", "FooService.java", 5);
        SourceContextResolver resolver = new SourceContextResolver(watchDir);

        Optional<String> context =
                resolver.resolve("com.example.demo.FooService.init(FooService.java:2)");

        assertTrue(context.isPresent());
        assertTrue(context.get().contains(">  2: line2"));
        assertTrue(context.get().contains("   1: line1"));
        assertTrue(context.get().contains("   5: line5"));
    }

    @Test
    void 소스를_못_찾으면_empty를_반환한다() {
        SourceContextResolver resolver = new SourceContextResolver(watchDir);
        assertTrue(resolver.resolve("org.thirdparty.Lib.run(Lib.java:10)").isEmpty());
    }

    @Test
    void 내부_클래스와_람다_프레임도_바깥_클래스_파일로_해석한다() throws Exception {
        writeSource("src/main/java/com/example/demo", "FooService.java", 30);
        SourceContextResolver resolver = new SourceContextResolver(watchDir);

        assertTrue(resolver
                .resolve("com.example.demo.FooService$Inner.lambda$run$0(FooService.java:15)")
                .isPresent());
    }

    @Test
    void 형식이_다른_location은_empty를_반환한다() {
        SourceContextResolver resolver = new SourceContextResolver(watchDir);
        assertTrue(resolver.resolve("Native Method 같은 이상한 문자열").isEmpty());
    }

    @Test
    void 줄번호가_int_범위를_넘으면_예외없이_empty를_반환한다() throws Exception {
        writeSource("src/main/java/com/example/demo", "FooService.java", 60);
        SourceContextResolver resolver = new SourceContextResolver(watchDir);

        // 정규식(\d+)은 자릿수를 제한하지 않으므로 Integer.parseInt 오버플로가 재현된다.
        Optional<String> context = resolver.resolve(
                "com.example.demo.FooService.decrease(FooService.java:99999999999999999999)");

        assertTrue(context.isEmpty());
    }

    @Test
    void 하위_디렉토리_접근이_거부되면_예외없이_empty를_반환한다() throws Exception {
        Path lockedDir = Files.createDirectory(watchDir.resolve("locked"));
        Files.createFile(lockedDir.resolve("Dummy.java"));

        boolean permissionApplied =
                lockedDir.toFile().setReadable(false, false)
                        && lockedDir.toFile().setExecutable(false, false)
                        && !lockedDir.toFile().canRead();
        // root 등 권한 제한이 무시되는 환경에서는 이 테스트를 신뢰성 있게 재현할 수 없어 건너뛴다.
        assumeTrue(permissionApplied, "파일 권한 제한이 적용되지 않는 환경(root 등)에서는 이 테스트를 건너뜀");

        try {
            SourceContextResolver resolver = new SourceContextResolver(watchDir);

            Optional<String> context =
                    resolver.resolve("com.example.demo.FooService.run(FooService.java:1)");

            assertTrue(context.isEmpty());
        } finally {
            lockedDir.toFile().setExecutable(true, false);
            lockedDir.toFile().setReadable(true, false);
        }
    }
}
