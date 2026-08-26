package io.github.seraphina.agent.impl;

import io.github.seraphina.agent.api.SeraTransImpl;

import java.security.ProtectionDomain;

public class TestSeraTrans implements SeraTransImpl {
    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        return SeraTransImpl.super.transform(loader, className, classBeingRedefined, protectionDomain, classfileBuffer);
    }
}
