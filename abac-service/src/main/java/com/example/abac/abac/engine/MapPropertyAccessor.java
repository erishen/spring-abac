package com.example.abac.abac.engine;

import org.springframework.expression.EvaluationContext;
import org.springframework.expression.PropertyAccessor;
import org.springframework.expression.TypedValue;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * SpEL 属性访问器：只认 Map，把 {@code resource.classification} 翻译成
 * {@code map.get("classification")}。
 *
 * 为什么不直接用 SpEL 默认的反射访问器：默认访问器允许调用 Map 上的任意方法，
 * 而策略表达式是外部输入的字符串。这里把取值范围严格限制为「读 Map 的键」，
 * 读不到就返回 null（SpEL 里 null 参与比较会自然判 false），不抛异常、不越界。
 */
public class MapPropertyAccessor implements PropertyAccessor {

    @Override
    @Nullable
    public Class<?>[] getSpecificTargetClasses() {
        return new Class<?>[]{Map.class};
    }

    @Override
    public boolean canRead(@NonNull EvaluationContext context, @Nullable Object target,
                           @NonNull String name) {
        return target instanceof Map;
    }

    @Override
    @NonNull
    public TypedValue read(@NonNull EvaluationContext context, @Nullable Object target,
                           @NonNull String name) {
        // target 由 canRead 保证是 Map；JDT 不做跨方法分析，这里用 pattern 匹配
        // 让编译器确认 map 非空（理论上到不了 NULL 分支，防御性兜底）。
        if (!(target instanceof Map<?, ?> map)) {
            return new TypedValue(null);
        }
        return new TypedValue(map.get(name));
    }

    @Override
    public boolean canWrite(@NonNull EvaluationContext context, @Nullable Object target,
                            @NonNull String name) {
        // 策略表达式是只读的：不允许通过表达式回写属性。
        return false;
    }

    @Override
    public void write(@NonNull EvaluationContext context, @Nullable Object target,
                      @NonNull String name, @Nullable Object newValue) {
        throw new UnsupportedOperationException("policy expressions are read-only");
    }
}
