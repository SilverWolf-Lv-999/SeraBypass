package io.github.seraphina;

import com.sun.jna.Native;
import io.github.seraphina.agent.Agent;
import io.github.seraphina.utility.JvmUtility;
import io.github.seraphina.utility.SeraLegitHook;

public class Start {
    public static void main(String[] args) {
        Native.main(args);
        JvmUtility.peerJVMTI();
        Agent.start(args);
//        Set<?> allclass = JvmUtility.getAllLoaedClasses();
//        for (Object o : allclass) {
//            System.out.println(o);
//        }
//        Set<Object> objects = JvmUtility.getAllLoaedObjects();
//        for (Object o : objects) {
//            System.out.println(o);
//        }
        System.out.println(a());
        SeraLegitHook.hookMethod(Start.class, "a", 0);
        System.out.println(a());
    }

    public static int a() {
        return 1;
    }
}
