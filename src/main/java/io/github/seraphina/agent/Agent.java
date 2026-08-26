package io.github.seraphina.agent;

import io.github.seraphina.agent.api.SeraTransImpl;
import io.github.seraphina.utility.thread.ThreadUtility;

import java.util.HashSet;
import java.util.Set;

public class Agent {
    public static final Set<SeraTransImpl> transformers = new HashSet<>();

    public static synchronized void start(String[] args) {
        Thread transThread = new Thread(() -> {
//            System.out.println("456");
//            for (int i = 1; i < 3000; i++) {
//                System.out.println(i);
//            }
        });
        ThreadUtility.protectThread(transThread);
        transThread.start();
//        System.out.println("123");
//        try {
//            Thread.sleep(5L);
//            transThread.stop();
//            System.out.println("stop!");
//            transThread.interrupt();
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
    }

    public static void detachAll() {}
}
