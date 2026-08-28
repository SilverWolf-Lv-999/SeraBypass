package io.github.seraphina;

import com.sun.jna.Native;
import io.github.seraphina.agent.Agent;
import io.github.seraphina.agent.impl.TestSeraTrans;
import io.github.seraphina.jnct.JNCT;
import io.github.seraphina.reflect.LambdaManager;
import io.github.seraphina.test.TestObj;
import io.github.seraphina.utility.SysUtility;
import io.github.seraphina.test.TransTarget;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Start {

    public static final Logger LOGGER = LogManager.getLogger();

    public static void main(String[] args) {
        System.out.print(" __ __ __  ______  __      ______  ______  ___ __ __  ______       __  __  ______  ______      \n" +
                "/_//_//_/\\/_____/\\/_/\\    /_____/\\/_____/\\/__//_//_/\\/_____/\\     /_/\\/_/\\/_____/\\/_____/\\     \n" +
                "\\:\\\\:\\\\:\\ \\::::_\\/\\:\\ \\   \\:::__\\/\\:::_ \\ \\::\\| \\| \\ \\::::_\\/_    \\:\\ \\:\\ \\::::_\\/\\::::_\\/_    \n" +
                " \\:\\\\:\\\\:\\ \\:\\/___/\\:\\ \\   \\:\\ \\  _\\:\\ \\ \\ \\:.      \\ \\:\\/___/\\    \\:\\ \\:\\ \\:\\/___/\\:\\/___/\\   \n" +
                "  \\:\\\\:\\\\:\\ \\::___\\/\\:\\ \\___\\:\\ \\/_/\\:\\ \\ \\ \\:.\\-/\\  \\ \\::___\\/_    \\:\\ \\:\\ \\_::._\\:\\::___\\/_  \n" +
                "   \\:\\\\:\\\\:\\ \\:\\____/\\:\\/___/\\:\\_\\ \\ \\:\\_\\ \\ \\. \\  \\  \\ \\:\\____/\\    \\:\\_\\:\\ \\/____\\:\\:\\____/\\ \n" +
                "    \\_______\\/\\_____\\/\\_____\\/\\_____\\/\\_____\\/\\__\\/ \\__\\/\\_____\\/     \\_____\\/\\_____\\/\\_____\\/ \n" +
                "                                                                                               ");
        System.out.println();
        SysUtility.loadNative(Start.class, "sera_bypass.dll");
        JNCT.ivk("hello");
        TestObj obj = (TestObj) JNCT.ivk("defineHiddenClass", "io.github.seraphina.test.HiddenObj", System.class.getClassLoader());
        obj.print();
        JNCT.ivk("peerJvmTI");
        LOGGER.info("sera_bypass.dll JNI smoke test completed");
        Native.main(args);
        Agent.reg(new TestSeraTrans());
        Agent.start(args);
        TransTarget.class.getName();
        Agent.transform(TransTarget.class);
        Runtime.getRuntime().addShutdownHook(new Thread(LambdaManager::clearCache));
    }

    public static int a() {
        return 1;
    }
}
