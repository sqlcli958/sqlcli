package com.sqlcli.strategy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 防复发：{@link SqlExecutionPolicy} 和 {@link DatabaseCapabilities} 上的每个 boolean 开关
 * 都必须至少被 {@code src/main} 下的生产代码消费一次。
 *
 * <p>反模式是「声明了一个开关，只有测试在断言它，生产代码从不读它」——测试全绿给的安全感是假的，
 * 因为没有任何执行路径真的因为这个开关的值而分叉。新增开关时如果忘记接线，这条测试会挂。
 */
class PolicyCapabilitiesCoverageTest {

    private static final Path SRC_MAIN = Path.of("src", "main", "java");
    private static final List<Class<?>> CHECKED_TYPES = List.of(SqlExecutionPolicy.class, DatabaseCapabilities.class);

    @Test
    void everyBooleanGetterIsConsumedByProductionCode() throws IOException {
        String allSource = readAllJavaSource();
        List<String> unused = new ArrayList<>();
        for (Class<?> type : CHECKED_TYPES) {
            for (Method method : type.getMethods()) {
                if (!isBooleanGetter(method)) {
                    continue;
                }
                if (!allSource.contains(method.getName() + "(")) {
                    unused.add(type.getSimpleName() + "#" + method.getName());
                }
            }
        }
        assertTrue(unused.isEmpty(),
                "以下 getter 声明了但生产代码无人消费——要么接上，要么删掉: " + unused);
    }

    private boolean isBooleanGetter(Method method) {
        return method.getParameterCount() == 0
                && method.getReturnType() == boolean.class
                && method.getName().startsWith("is")
                && Modifier.isPublic(method.getModifiers());
    }

    private String readAllJavaSource() throws IOException {
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> files = Files.walk(SRC_MAIN)) {
            for (Path path : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                sb.append(Files.readString(path));
            }
        }
        return sb.toString();
    }
}
