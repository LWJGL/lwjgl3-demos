# lwjgl3-vulkan-demos + GraalVM native image

Vulkan Demos for LWJGL 3 compiled to native executable by GraalVM native-image utility.

All Vulkan demos in [src/org/lwjgl/demo/vulkan](src/org/lwjgl/demo/vulkan) are included in the build.

Gradle and Maven build scripts are provided for building the project,
which requires JDK 11+ or GraalVM 21+ (for native image).

## *Status*

This branch is untested, as no Mac hardware is available for testing.
Native image build info below is likely not working, as the GraalVM
[configuration files](res/META-INF/native-image) must be updated by running on a Mac first.

## GraalVM pre-requisites

The [GraalVM native-image](https://www.graalvm.org/reference-manual/native-image) page
shows how to set up GraalVM and its native-image utility for common platforms.
[Gluon](https://gluonhq.com/) also provides some setup
[details](https://docs.gluonhq.com/#_platforms) for GraalVM native-image creation.

This project's Gradle build script uses the
[client-gradle-plugin](https://github.com/gluonhq/client-gradle-plugin)
from Gluon to build the native executable from Gradle with GraalVM.

The GraalVM native-image utility will use the configuration files in
`res/META-INF/native-image` folder to assist in the native-image generation.

Gluon also provides the [client-maven-plugin](https://github.com/gluonhq/client-maven-plugin)
which is used in this project's Maven build script and works similarly to the above
client-gradle-plugin.

## Gradle build tasks

### Run in standard JVM

To build and run the Vulkan demos in standard JVM with Gradle, execute the `run` task:

	gradlew run

By default, the [ColoredRotatingQuadDemo](src/org/lwjgl/demo/vulkan/ColoredRotatingQuadDemo.java)
demo is executed by the above `run` task without parameter. To run a different Vulkan demo, e.g.
[NvRayTracingExample](src/org/lwjgl/demo/vulkan/NvRayTracingExample.java), execute the `run` task
with that specific demo class as parameter:

	gradlew run --args=vulkan.NvRayTracingExample

System properties can be passed on to the running demo with the -D parameter,
e.g. to print out some debug info in the console:

	gradlew run --args=vulkan.NvRayTracingExample -Dorg.lwjgl.util.Debug=true

The above tasks can use any standard JDK 11+.

### Produce native executable

To generate native executable, GraalVM 21+ need be set up as mentioned in
*GraalVM pre-requisites* section above.

Once GraalVM is set up and available in the path, run the `nativeBuild` task:

	gradlew nativeBuild

The `nativeBuild` task would take a while to compile the Vulkan demo source code and
link them with the LWJGL libraries into an executable file.
The resulting `lwjgl3-vulkan-demos` file is:

	build/client/x86_64-macos/lwjgl3-vulkan-demos

which can then be run directly with a demo class as parameter
(e.g. [InstancedSpheresDemo](src/org/lwjgl/demo/vulkan/InstancedSpheresDemo.java)):

	./build/client/x86_64-macos/lwjgl3-vulkan-demos vulkan.InstancedSpheresDemo

System properties can be passed on to the running demo with the -D parameter,
e.g. to print out some debug info in the console:

	./build/client/x86_64-macos/lwjgl3-vulkan-demos vulkan.InstancedSpheresDemo -Dorg.lwjgl.util.Debug=true

## Maven build tasks

### Run in standard JVM

To build and run the Vulkan demos in standard JVM with Maven, execute the
`compile` then `exec:exec` tasks:

	mvnw compile
	mvnw exec:exec

By default, the [ColoredRotatingQuadDemo](src/org/lwjgl/demo/vulkan/ColoredRotatingQuadDemo.java) demo is executed
by the above `exec:exec` task without parameter. To run a different Vulkan demo, e.g.
[NvRayTracingExample](src/org/lwjgl/demo/vulkan/NvRayTracingExample.java), execute the `exec:exec` task
with that specific demo class as value of the property `class`:

	mvnw exec:exec -Dclass=vulkan.NvRayTracingExample

System properties can be passed on to the running demo with the -Dsys.props parameter,
e.g. to print out some debug info in the console:

	mvnw exec:exec -Dclass=vulkan.NvRayTracingExample -Dsys.props="-Dorg.lwjgl.util.Debug=true"

The above tasks can use any standard JDK 11+.

### Produce native executable

To generate native executable, GraalVM 21+ need be set up as mentioned in
*GraalVM pre-requisites* section above.

Once GraalVM is set up and available in the path, run the `client:build` task:

	mvnw client:build

The `client:build` task would take a while to compile the Vulkan demo source code and
link them with the LWJGL libraries into an executable file.
The resulting `lwjgl3-vulkan-demos` file is:

	target/client/x86_64-macos/lwjgl3-vulkan-demos

which can then be run directly with a demo class as parameter
(e.g. [TwoRotatingTrianglesDemo](src/org/lwjgl/demo/vulkan/TwoRotatingTrianglesDemo.java)):

	./target/client/x86_64-macos/lwjgl3-vulkan-demos vulkan.TwoRotatingTrianglesDemo

System properties can be passed on to the running demo with the -D parameter,
e.g. to print out some debug info in the console:

	./target/client/x86_64-macos/lwjgl3-vulkan-demos vulkan.TwoRotatingTrianglesDemo -Dorg.lwjgl.util.Debug=true

## Compressed native executable

The resulting `lwjgl3-vulkan-demos` executable file, whether produced by Gradle or Maven build script,
can be further reduced in size via compression using the [UPX](https://upx.github.io) utility,
as described [here](https://medium.com/graalvm/compressed-graalvm-native-images-4d233766a214).

