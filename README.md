# lwjgl3-demos
Demo suite for LWJGL 3

## Building

    ./mvnw package
    
To override main class

    ./mvnw package -Dclass=opengl.UniformArrayDemo

## Running

    java -jar target/lwjgl3-demos.jar

on Mac OS you need to specify the `-XstartOnFirstThread` JVM argument, so the above becomes:

    java -XstartOnFirstThread -jar target/lwjgl3-demos.jar

To override main class

    java -cp target/lwjgl3-demos.jar org.lwjgl.demo.opengl.UniformArrayDemo
