package io.github.seraphina;

import com.sun.jna.Native;
import io.github.seraphina.agent.Agent;
import io.github.seraphina.agent.impl.TestSeraTrans;
import io.github.seraphina.cpp.SeraBypass;
import io.github.seraphina.test.TransTarget;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Start {
    public static final Logger LOGGER = LogManager.getLogger();

    public static void main(String[] args) {
        Native.main(args);
        Agent.reg(new TestSeraTrans());
        Agent.start(args);
        TransTarget.class.getName();
        Agent.transform(TransTarget.class);
        SeraBypass.nativeSayHello();
//        Set<?> allclass = HotSpotMemoryUtility.getAllLoadedClasses();
//        for (Object o : allclass) {
//            System.out.println(o);
//        }
//        Set<Object> objects = HotSpotMemoryUtility.getAllLoadedObjects();
//        for (Object o : objects) {
//            System.out.println(o);
//        }
//        System.out.println(a());
//        SeraLegitHook.hookMethod(Start.class, "a", 0);
//        System.out.println(a());
//        new Thread(() -> {
//            while (true) {
//                try {
//                    Thread.sleep(500);
//                    TransTarget.targetAdded();
//                    TransTarget.targetModify();
//                    for (Method m : TransTarget.class.getDeclaredMethods()) {
//                        System.out.println(m.getName());
//                    }
//                    for (Field f : TransTarget.class.getDeclaredFields()) {
//                        System.out.println(f.getName());
//                    }
//                    try {
//                        TransTarget.class.getDeclaredMethod("targetRemoved");
//                        System.out.println("targetNoRemoved");
//                    } catch (NoSuchMethodException expected) {
//                    }
//                } catch (InterruptedException exception) {
//                    Thread.currentThread().interrupt();
//                    return;
//                }
//            }
//        }).start();
    }

    public static int a() {
        return 1;
    }
}



