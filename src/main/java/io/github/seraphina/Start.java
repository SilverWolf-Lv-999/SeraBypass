package io.github.seraphina;

import com.sun.jna.Native;
import io.github.seraphina.agent.Agent;
import io.github.seraphina.agent.impl.TestSeraTrans;
import io.github.seraphina.utility.jvm.HotSpotMemoryUtility;
import io.github.seraphina.utility.jvm.JvmtiUtility;
import io.github.seraphina.utility.hook.SeraLegitHook;

public class Start {
    public static void main(String[] args) {
        Native.main(args);
        JvmtiUtility.initializeJvmtiPeer();
        Agent.start(args);
//        Set<?> allclass = HotSpotMemoryUtility.getAllLoadedClasses();
//        for (Object o : allclass) {
//            System.out.println(o);
//        }
//        Set<Object> objects = HotSpotMemoryUtility.getAllLoadedObjects();
//        for (Object o : objects) {
//            System.out.println(o);
//        }
        System.out.println(a());
        SeraLegitHook.hookMethod(Start.class, "a", 0);
        System.out.println(a());
        Agent.reg(new TestSeraTrans());
    }

    public static int a() {
        return 1;
    }
}
