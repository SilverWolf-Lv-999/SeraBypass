package io.github.seraphina;

import com.sun.jna.Native;
import io.github.seraphina.agent.Agent;
import io.github.seraphina.agent.impl.TestSeraTrans;
import io.github.seraphina.agent.impl.TransSystem;
import io.github.seraphina.jnct.JNCT;
import io.github.seraphina.reflect.LambdaManager;
import io.github.seraphina.test.TestObj;
import io.github.seraphina.utility.SysUtility;
import io.github.seraphina.test.TransTarget;
import io.github.seraphina.utility.hook.SeraLegitHook;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

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
        JNCT.ivk("hello");
        TestObj obj = (TestObj) JNCT.ivk("defineHiddenClass", "io.github.seraphina.test.HiddenObj", System.class.getClassLoader());
        obj.print();
        JNCT.ivk("peerJvmTI");
        LOGGER.info("sera_bypass.dll JNI smoke test completed");
        Native.main(args);
        String instanceFieldName = "instanceFieldAlias" + Integer.toHexString(args.length);
        String instanceMethodName = "instanceMethod" + Integer.toHexString(args.length);
        try {
            LOGGER.info("instance member test: instantiate");
            Start instance = new Start();
            LOGGER.info("instance member test: add field");
            SeraLegitHook.addInstanceField(
                    Start.class,
                    instanceFieldName,
                    "instanceValue",
                    Modifier.PRIVATE | Modifier.TRANSIENT);
            LOGGER.info("instance member test: resolve field");
            Field instanceField = Start.class.getDeclaredField(instanceFieldName);
            instanceField.setInt(instance, 23);
            LOGGER.info(
                    "instance field {} shared backing value {}",
                    instanceFieldName,
                    instance.instanceValue);

            LOGGER.info("instance member test: add method");
            SeraLegitHook.addInstanceMethod(
                    Start.class,
                    instanceMethodName,
                    value -> ((Start) value).instanceValue += 5);
            LOGGER.info("instance member test: resolve method");
            Method instanceMethod = Start.class.getDeclaredMethod(instanceMethodName);
            instanceMethod.invoke(instance);
            LOGGER.info(
                    "instance method {} updated backing value {}",
                    instanceMethodName,
                    instance.instanceValue);

            SeraLegitHook.removeMethod(Start.class, instanceMethodName);
            try {
                Start.class.getDeclaredMethod(instanceMethodName);
                throw new IllegalStateException("instance method was not removed");
            } catch (NoSuchMethodException expected) {
            }
            SeraLegitHook.removeField(Start.class, instanceFieldName);
            try {
                Start.class.getDeclaredField(instanceFieldName);
                throw new IllegalStateException("instance field was not removed");
            } catch (NoSuchFieldException expected) {
            }
        } catch (Throwable throwable) {
            LOGGER.error("instance member smoke test failed", throwable);
            throw new IllegalStateException("instance member smoke test failed", throwable);
        }
        LOGGER.info("instance member test: complete");
        {
            Agent.reg(new TestSeraTrans());
            Agent.reg(new TransSystem());
        }
        Agent.start(args);
        TransTarget.class.getName();
        {
            Agent.transform(TransTarget.class);
            Agent.transform(System.class);
            Agent.transform(Object.class);
        }
        System.out.println(a());
        SeraLegitHook.hookMethod(Start.class, "a", 0);
        System.out.println(a());
        new Thread(() -> {
            for (Method m : TransTarget.class.getDeclaredMethods()) {
                LOGGER.info(m.getName());
            }
            for (Field m : TransTarget.class.getDeclaredFields()) {
                LOGGER.info(m.getName());
            }
        }).start();
        try {
            Class<?> klass = Class.forName("jdk.internal.misc.Unsafe");
            Object jdkUnsafe = LambdaManager.getField(klass, "theUnsafe").get();
            System.out.println(jdkUnsafe);
        } catch (Exception exception) {
            exception.printStackTrace();
        }
        Object.class.getClass();
        Runtime.getRuntime().addShutdownHook(new Thread(()-> {
            LOGGER.info("stop");
            LambdaManager.clearCache();
        }));
    }

    private int instanceValue = 7;

    public static int a() {
        return 1;
    }
}
