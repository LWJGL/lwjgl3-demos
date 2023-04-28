# lwjgl3-demos
Demo suite for LWJGL 3

## Example Images

[vulkan/raytracing/SimpleSphere.java](./src/org/lwjgl/demo/vulkan/raytracing/SimpleSphere.java)

![vulkan/raytracing/SimpleSphere.java](md/simplesphere.jpg)

[vulkan/raytracing/SdfBricks.java](./src/org/lwjgl/demo/vulkan/raytracing/SdfBricks.java)

![vulkan/raytracing/SdfBricks.java](md/sdf-bricks.jpg)

[opengl/raytracing/VoxelLightmapping2.java](./src/org/lwjgl/demo/opengl/raytracing/VoxelLightmapping2.java)

![opengl/raytracing/VoxelLightmapping2.java](md/voxellightmapping2.jpg)

[opengl/raytracing/tutorial/Tutorial3.java](./src/org/lwjgl/demo/opengl/raytracing/tutorial/Tutorial3.java)

![opengl/raytracing/tutorial/Tutorial3.java](md/tutorial3.jpg)

[opengl/raytracing/tutorial/Tutorial8_2.java](./src/org/lwjgl/demo/opengl/raytracing/tutorial/Tutorial8_2.java)

![opengl/raytracing/tutorial/Tutorial8_2.java](md/tutorial8_2.jpg)

[opengl/sampling/HierarchicalSampleWarping.java](./src/org/lwjgl/demo/opengl/sampling/HierarchicalSampleWarping.java)

![opengl/sampling/HierarchicalSampleWarping.java](md/hsw.jpg)

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
