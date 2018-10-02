/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.intro;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL.*;
import static org.lwjgl.system.MemoryUtil.*;

import org.lwjgl.glfw.GLFWMouseButtonCallbackI;

/**
 * In this part we will see how callbacks work. Callbacks mean any method which we can register in a native library so
 * that the native library can call us back and invoke our callback method whenever it wants to.
 * <p>
 * One example of where callbacks occur frequently is GLFW. GLFW provides some number of different callbacks for various
 * events that happen on a window, such as resizing, maximizing, minimizing and mouse or keyboard events.
 * <p>
 * Now, before we go into using callbacks with LWJGL 3 and GLFW, we should first get a clear picture of what a callback
 * looks like in a native library, which LWJGL 3 tries to provide a Java counterpart for.
 * <p>
 * In a native library like GLFW a callback is nothing more than a function pointer. This means that it is a physical
 * virtual memory address pointing to an executable piece of code, a function. The function pointer also has a type to
 * make it callable in C. This function type consists of the parameter types and the return type, just like a method
 * signature in Java including the return type. So, both caller and callee agree on a defined set of parameters and a
 * return type to expect when the callback function is called.
 * <p>
 * When LWJGL 3 maps this concept of a function type into Java, it provides the user (that means you) with a Java
 * interface type that contains a single method. This method has the same (or similar) signature and return type as the
 * native callback function. If you want to see an example, look at {@link GLFWMouseButtonCallbackI}. It is an interface
 * with a single non-default method which must be implemented and will be called whenever the native library calls the
 * callback.
 * <p>
 * The fact that it is an interface with a single method makes it applicable to be the target of a Java 8 Lambda method
 * or a Java 8 method reference. That means, with callbacks we need not provide an actual implementation of the callback
 * interface by either anonymously or explicitly creating a class implementing that interface, but we can use Java 8
 * Lambda methods and Java 8 method references with a compatible signature.
 * <p>
 * If you are not yet familiar with Java 8 Lambda methods or Java 8 method references, please look them up on the Oracle
 * documentation. We will make use of them in the example code below.
 *
 * @author Kai Burjack
 */
public class Intro5 {

    /**
     * Callback method used with Java 8 method references. See the main() method below.
     */
    private static void mouseCallback(long win, int button, int action, int mods) {
        /* Print a message when the user pressed down a mouse button */
        if (action == GLFW_PRESS) {
            System.out.println("Pressed!");
        }
    }

    /**
     * In this demo we will register a callback with GLFW. We will use a mouse callback which notifies us whenever a
     * mouse button was pressed or released.
     */
    public static void main(String[] args) {
        glfwInit();
        long window = createWindow();

        /*
         * The following is one way of registering a callback. In this case with GLFW for receiving mouse button events
         * happening for the window.
         * 
         * We use an instance of an anonymous class which implements the callback interface GLFWMouseButtonCallbackI.
         * Instead of using the GLFWMouseButtonCallbackI interface, we could also just use the GLFWMouseButtonCallback
         * class (without the 'I' suffix). It would work the same, since that class implements the interface. But there
         * is a reason why that class exists in the first place, which will be covered later.
         */
        glfwSetMouseButtonCallback(window, new GLFWMouseButtonCallbackI() {
            /**
             * This is the single method of the callback interface which we must provide an implementation for.
             * <p>
             * This method will get called by the native library (GLFW) whenever some event happens with a mouse button.
             * For a more detailed explanation of the GLFW callback itself, see
             * <a href="http://www.glfw.org/docs/latest/group__input.html#gaef49b72d84d615bca0a6ed65485e035d">the GLFW
             * documentation</a>.
             * <p>
             * For now, we just do nothing in this method. We just assume that it was registered successfully.
             */
            public void invoke(long window, int button, int action, int mods) {
                /* We don't do anything here. */
            }
        });

        /*
         * The next possible way is to use Java 8 Lambda methods to avoid having to type the actual Java interface type
         * name. You either know the method signature or use an IDE with autocomplete/autosuggest support, such as
         * IntelliJ IDEA.
         */
        glfwSetMouseButtonCallback(window, (long win, int button, int action, int mods) -> {
            /* We also don't do anything here. */
        });

        /*
         * The last possible way to register a callback should look more familiar to C/C++ programmers. We use a Java 8
         * method reference. See the method mouseCallback() above for what happens when we receive a mouse event.
         */
        glfwSetMouseButtonCallback(window, Intro5::mouseCallback);

        /*
         * Now, when we start the application and click inside the window using any mouse button, we should see a
         * message printed.
         */

        /*
         * We don't render anything. Just an empty window. The focus of this introduction is just callbacks.
         */
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
        }
        glfwTerminate();
        System.out.println("Fin.");
    }

    private static long createWindow() {
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
        long window = glfwCreateWindow(800, 600, "Intro5", NULL, NULL);
        glfwMakeContextCurrent(window);
        createCapabilities();
        return window;
    }

}
