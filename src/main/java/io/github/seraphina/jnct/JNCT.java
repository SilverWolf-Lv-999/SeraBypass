package io.github.seraphina.jnct;

import io.github.seraphina.Start;
import io.github.seraphina.utility.SysUtility;

public class JNCT {
    protected static volatile String cmd;

    protected static volatile Object[] args;

    protected static volatile Object result;

    public static synchronized Object ivk(String command, Object... arg) {
        args = arg;
        cmd = command;
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

    static {
        SysUtility.loadNative(Start.class, "sera_bypass.dll");
    }
}
