package com.example.baseplate.contract.arch;

import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AR-03：业务模块在编译期看不到 runtime 内部类。
 * 用 JavaCompiler 编译一段反向引用探针，断言编译失败——编译不过比断言硬。
 */
class ReverseCompileProbeTest {

    @Test
    void businessModuleCannotCompileAgainstRuntimeInternals() throws Exception {
        String probe = ""
                + "package probe;\n"
                + "public class Bad {\n"
                + "    com.example.baseplate.runtime.port.PortRegistry registry;\n"
                + "}\n";

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "需要 JDK 运行测试（不是 JRE）");

        JavaFileObject source = new SimpleJavaFileObject(
                URI.create("string:///probe/Bad.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return probe;
            }
        };

        Path out = Files.createTempDirectory("baseplate-probe-out");
        StringWriter errors = new StringWriter();
        boolean compiled = compiler.getTask(
                errors, null, null,
                List.of("-classpath", System.getProperty("java.class.path"),
                        "-d", out.toString(), "-proc:none"),
                null, List.of(source)).call();

        try {
            assertFalse(compiled, "探针不应编译成功：业务模块若能引用 runtime 内部类，编译期边界已破");
            String message = errors.toString();
            assertTrue(message.contains("runtime") || message.contains("PortRegistry")
                            || message.contains("不存在") || message.contains("does not exist")
                            || message.contains("cannot find symbol"),
                    "失败原因应为找不到 runtime 类，实际: " + message);
        } finally {
            try (var walk = Files.walk(out)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
}
