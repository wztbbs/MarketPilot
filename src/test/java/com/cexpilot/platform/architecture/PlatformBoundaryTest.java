package com.cexpilot.platform.architecture;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** 同一 Maven 工程中约束包边界；同时检查源码引用和 class 常量池中的实际依赖。 */
class PlatformBoundaryTest {
    private static final Pattern PROJECT_TYPE = Pattern.compile("com[./]cexpilot[./]([A-Za-z_$][\\w$]*)");
    @Test void platformDoesNotReferenceBusinessBootstrapOrFormerPackages() throws Exception {
        var violations = new ArrayList<String>();
        Path root = Path.of("src/main/java/com/cexpilot/platform");
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = Files.readString(file).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
                check(file.toString(), code, violations);
            }
        }
        Path compiled = Path.of("target/classes/com/cexpilot/platform");
        try (var files = Files.walk(compiled)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList())
                for (String value : constants(file)) check(file.toString(), value, violations);
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }
    @Test void businessDoesNotDependOnBootstrap() throws Exception {
        var violations = new ArrayList<String>();
        try (var files = Files.walk(Path.of("src/main/java/com/cexpilot/business"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = Files.readString(file).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
                if (code.contains("com.cexpilot.bootstrap")) violations.add(file.toString());
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }
    private void check(String source, String value, List<String> violations) {
        var matcher = PROJECT_TYPE.matcher(value);
        while (matcher.find()) if (!"platform".equals(matcher.group(1)))
            violations.add(source + " -> " + value);
    }
    private List<String> constants(Path file) throws IOException {
        var strings = new ArrayList<String>();
        try (var input = new DataInputStream(Files.newInputStream(file))) {
            assertEquals(0xCAFEBABE, input.readInt());
            input.readUnsignedShort(); input.readUnsignedShort();
            int size = input.readUnsignedShort();
            for (int i = 1; i < size; i++) switch (input.readUnsignedByte()) {
                case 1 -> strings.add(input.readUTF());
                case 3, 4, 9, 10, 11, 12, 17, 18 -> input.skipNBytes(4);
                case 5, 6 -> { input.skipNBytes(8); i++; }
                case 7, 8, 16, 19, 20 -> input.skipNBytes(2);
                case 15 -> input.skipNBytes(3);
                default -> throw new IOException("未知 class 常量类型: " + file);
            }
        }
        return strings;
    }
}
