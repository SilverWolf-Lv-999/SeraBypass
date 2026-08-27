package io.github.seraphina.cpp;

import io.github.seraphina.utility.SysUtility;

public final class SeraBypass {

    public static native void nativeSayHello();

    static {
        if (SysUtility.loadNative("sera_bypass.dll")) {
            System.out.println("dll has been load");
        }
    }
}
