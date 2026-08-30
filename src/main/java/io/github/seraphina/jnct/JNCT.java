package io.github.seraphina.jnct;

public class JNCT {
    protected static volatile String cmd;

    protected static volatile Object[] args;

    protected static volatile Object result;

    public static synchronized Object ivk(String command, Object... arg) {
        cmd = command;
        args = arg;
        while (result == null) {
            try {
                Thread.onSpinWait();
            } catch (Exception ignore) {

            }
        }
        cmd = null;
        args = null;
        Object tmp = result;
        result = null;
        return tmp;
    }
}
