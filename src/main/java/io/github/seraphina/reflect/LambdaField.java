package io.github.seraphina.reflect;

import java.util.Objects;

public class LambdaField<T> extends SeraLambda<T> {
    @FunctionalInterface
    public interface Getter {
        Object get() throws Throwable;
    }

    @FunctionalInterface
    public interface Setter {
        void set(Object value) throws Throwable;
    }

    private final Getter getter;
    private final Setter setter;
    private final Class<?> fieldType;

    LambdaField(Getter getter, Setter setter, Class<?> fieldType) {
        this.getter = Objects.requireNonNull(getter, "getter");
        this.setter = Objects.requireNonNull(setter, "setter");
        this.fieldType = Objects.requireNonNull(fieldType, "fieldType");
    }

    @Override
    @SuppressWarnings("unchecked")
    public T get() {
        try {
            return (T) getter.get();
        } catch (Throwable throwable) {
            throw LambdaManager.rethrow(throwable);
        }
    }

    public void set(T value) {
        LambdaManager.checkFieldValue(fieldType, value);
        try {
            setter.set(value);
        } catch (Throwable throwable) {
            throw LambdaManager.rethrow(throwable);
        }
    }
}
