package io.github.seraphina.agent;

import io.github.seraphina.agent.api.SeraTransImpl;
import io.github.seraphina.utility.JvmUtility;

import java.util.HashSet;
import java.util.Set;

public class Agent {
    public static final Set<SeraTransImpl> transformers = new HashSet<>();

    public static synchronized void start(String[] args) {
        Thread transThread = new Thread(() -> {

        });
        JvmUtility.protectThread(transThread);
        transThread.start();
    }

    public static void detachAll() {}
}
