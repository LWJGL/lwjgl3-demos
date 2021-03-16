package org.lwjgl.demo;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class DemoLauncher {

    public static void main(String[] args) {
        String demoClassName = "bgfx.Bump"; //default is the bgfx.Bump demo
        if (args.length > 0) {
            demoClassName = args[0];
        }
        try {
            Class<?> demoClass = Class.forName("org.lwjgl.demo." + demoClassName);
            Method mainMethod = demoClass.getMethod("main", String[].class);
            mainMethod.invoke(null, (Object)new String[]{});
        } catch (ClassNotFoundException | NoSuchMethodException | SecurityException | 
                 IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
            e.printStackTrace();
        }
    }

}
