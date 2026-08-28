package io.github.seraphina.reflect;

import java.util.Objects;

public class LambdaMethod<T> extends SeraLambda<T> {
    @FunctionalInterface
    public interface ReturnInvoker {
        Object invoke() throws Throwable;
    }

    @FunctionalInterface
    public interface VoidInvoker {
        void invoke() throws Throwable;
    }

    private final ReturnInvoker returnInvoker;
    private final VoidInvoker voidInvoker;

    LambdaMethod(ReturnInvoker returnInvoker) {
        this.returnInvoker = Objects.requireNonNull(returnInvoker, "returnInvoker");
        this.voidInvoker = null;
    }

    LambdaMethod(VoidInvoker voidInvoker) {
        this.returnInvoker = null;
        this.voidInvoker = Objects.requireNonNull(voidInvoker, "voidInvoker");
    }

    @Override
    @SuppressWarnings("unchecked")
    public T get() {
        try {
            if (voidInvoker != null) {
                voidInvoker.invoke();
                return null;
            }
            return (T) returnInvoker.invoke();
        } catch (Throwable throwable) {
            throw LambdaManager.rethrow(throwable);
        }
    }

    @Override
    public void ivk() {
        try {
            if (voidInvoker != null) {
                voidInvoker.invoke();
            } else {
                returnInvoker.invoke();
            }
        } catch (Throwable throwable) {
            throw LambdaManager.rethrow(throwable);
        }
    }
}
