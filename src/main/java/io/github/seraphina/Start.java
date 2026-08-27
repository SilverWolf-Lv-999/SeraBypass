package io.github.seraphina;

import com.sun.jna.Native;
import io.github.seraphina.agent.Agent;
import io.github.seraphina.agent.impl.TestSeraTrans;
import io.github.seraphina.jnct.JNCT;
import io.github.seraphina.test.TestObj;
import io.github.seraphina.utility.SysUtility;
import io.github.seraphina.test.TransTarget;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Start {

    public static final Logger LOGGER = LogManager.getLogger();

    public static void main(String[] args) {
        SysUtility.loadNative(Start.class, "sera_bypass.dll");
        JNCT.ivk("hello");
        TestObj obj = (TestObj) JNCT.ivk("defineHiddenClass", "io.github.seraphina.test.HiddenObj", System.class.getClassLoader());
        obj.print();
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
