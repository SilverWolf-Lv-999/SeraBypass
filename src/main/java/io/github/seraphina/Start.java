package io.github.seraphina;

import com.sun.jna.Native;
import io.github.seraphina.agent.Agent;
import io.github.seraphina.agent.impl.TestSeraTrans;
import io.github.seraphina.cpp.SeraBypass;
import io.github.seraphina.utility.SysUtility;
import io.github.seraphina.utility.win.NativeLibrary;
import io.github.seraphina.test.TransTarget;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Start {
    private static final String SERA_BYPASS_NATIVE_RESOURCE = "sera_bypass.dll";
    private static final String SERA_BYPASS_NATIVE_BOOTSTRAP = "sera_bypass_register_natives";

    public static final Logger LOGGER = LogManager.getLogger();

    public static void main(String[] args) throws NoSuchMethodException {
        NativeLibrary nativeLibrary = SysUtility.loadNative(SeraBypass.class, SERA_BYPASS_NATIVE_RESOURCE);
//        nativeLibrary.registerNative(
//                SERA_BYPASS_NATIVE_BOOTSTRAP,
//                SeraBypass.class.getDeclaredMethod("nativeSayHello")
//        );
//        SeraBypass.nativeSayHello();
        LOGGER.info("sera_bypass.dll JNI smoke test completed");

        Native.main(args);
        Agent.reg(new TestSeraTrans());
        Agent.start(args);
        TransTarget.class.getName();
        Agent.transform(TransTarget.class);
    }

    public static int a() {
        return 1;
    }
}
