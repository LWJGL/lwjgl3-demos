/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.intro;

import org.lwjgl.glfw.GLFW;

import static org.lwjgl.glfw.GLFW.*;
import static java.lang.System.*;

/**
 * This is the first of a mini introductory series to working with LWJGL 3.
 * <p>
 * The purpose of this series is to get you comfortable with the concepts behind LWJGL 3. It will not teach you OpenGL,
 * nor will it provide you with a readily usable little engine or game.
 * <p>
 * Instead, we will focus on the underlying principles and concepts of LWJGL 3. These concepts cut across the whole
 * LWJGL 3 library and apply to all library bindings, not just OpenGL. Therefore, we will learn:
 * <ul>
 * <li>the naming scheme used by LWJGL 3 to map native library functions to Java methods
 * <li>how to pass arrays/buffers of data between Java and a native library, such as OpenGL
 * <li>how to allocate such array/buffers efficiently depending on their time of life
 * <li>how to use callback functions required by certain native libraries
 * </ul>
 * It is common to find people new to LWJGL 3 and OpenGL asking how to most effectively and efficiently learn LWJGL 3.
 * This could mean two things:
 * <ul>
 * <li>learning the underlying principles and best practices of using LWJGL 3
 * <li>learning any of the native libraries, most commonly OpenGL
 * </ul>
 * This introduction series will focus on the former.
 * 
 * @author Kai Burjack
 */
public class Intro1 {

    /**
     * We will keep things simple and only use the native library <a href="http://www.glfw.org/">GLFW</a> via the
     * binding that LWJGL 3 provides for it, as a starting example. If you would like to get more familiar with GLFW
     * first, please consult its <a href="http://www.glfw.org/documentation.html">documentation</a> and
     * <a href="https://github.com/glfw/glfw">GitHub repository</a>.
     * <p>
     * The goal here is to learn how LWJGL 3 makes native library functions accessible in our Java applications.
     * <p>
     * What is noteworthy here, is that LWJGL 3 provides bindings to native libraries in a straightfoward way. This
     * means without unnecessarily surprising a user familiar with those native libraries when she/he moves to
     * Java/LWJGL 3. In essence this means that function names will remain the same in the LWJGL 3 binding.
     */
    public static void main(String[] args) {
        /*
         * When using GLFW via C/C++ we would have to initialize GLFW via a call to glfwInit(). In Java with LWJGL 3
         * this is no different. What we see below is a call to the static method in the GLFW class. LWJGL 3 is all
         * about using static functions to relieve the client/user from dragging object instances around. The goal
         * behind this is that any LWJGL 3 Java application should feel just like its C/C++ counterpart would. This also
         * enables easy porting of C/C++ examples to LWJGL 3.
         */
        if (!GLFW.glfwInit()) {
            /* We exit(1) here, just for presentation purposes. Throwing an exception would be better! */
            System.exit(1);
        }

        /*
         * Your usual application code, such as a game loop...
         */

        /*
         * After we are finished with GLFW, we should destroy it.
         */
        GLFW.glfwTerminate();

        /*
         * The above example used static method calls on the GLFW class, which each call mentioned explicitly. That does
         * not quite feel like a C/C++ application. Luckily, since Java 1.5 we have static imports. We could drop the
         * mention of the GLFW class on the method invocation when we import it using a static import (see above).
         */
        if (!glfwInit()) {
            exit(1);
        }

        /*
         * Your usual application code, such as a game loop...
         */

        /*
         * Destroy GLFW again.
         */
        glfwTerminate();

        /*
         * Now, the above became indistinguishable from a C/C++ program, and could have been copied from such into a
         * Java method and would have worked.
         */

        /*
         * And that should be it for the first introduction. We learnt that LWJGL 3 provides bindings to native library
         * functions via static methods on a class which is named like the native library containing that function.
         */
        System.out.println("Fin.");
    }

}
