package com.example.abac.abac.engine;

import org.springframework.expression.EvaluationContext;
import org.springframework.expression.PropertyAccessor;
import org.springframework.expression.TypedValue;

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
    public Class<?>[] getSpecificTargetClasses() {
        return new Class<?>[]{Map.class};
    }

    @Override
    public boolean canRead(EvaluationContext context, Object target, String name) {
        return target instanceof Map;
    }

    @Override
    public TypedValue read(EvaluationContext context, Object target, String name) {
        Map<?, ?> map = (Map<?, ?>) target;
        return new TypedValue(map.get(name));
    }

    @Override
    public boolean canWrite(EvaluationContext context, Object target, String name) {
        // 策略表达式是只读的：不允许通过表达式回写属性。
        return false;
    }

    @Override
    public void write(EvaluationContext context, Object target, String name, Object newValue) {
        throw new UnsupportedOperationException("policy expressions are read-only");
    }
}
