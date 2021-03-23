# lwjgl3-opengl-demos + GraalVM native image

OpenGL Demos for LWJGL 3 compiled to native executable by GraalVM native-image utility.

All OpenGL demos in [src/org/lwjgl/demo/opengl](src/org/lwjgl/demo/opengl) are included in the build.

Gradle and Maven build scripts are provided for building the project,
which requires JDK 11+ or GraalVM 21+ (for native image).

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

To build and run the OpenGL demos in standard JVM with Gradle, execute the `run` task:

	gradlew run

By default, the [SimpleDrawElements](src/org/lwjgl/demo/opengl/SimpleDrawElements.java) demo is executed
by the above `run` task without parameter. To run a different OpenGL demo, e.g.
[WavefrontObjDemo](src/org/lwjgl/demo/opengl/assimp/WavefrontObjDemo.java), execute the `run` task
with that specific demo class as parameter:

	gradlew run --args=opengl.assimp.WavefrontObjDemo

System properties can be passed on to the running demo with the -D parameter,
e.g. to print out some debug info in the console:

	gradlew run --args=opengl.assimp.WavefrontObjDemo -Dorg.lwjgl.util.Debug=true

The above tasks can use any standard JDK 11+.

### Produce native executable

To generate native executable, GraalVM 21+ need be set up as mentioned in
*GraalVM pre-requisites* section above.

Once GraalVM is set up and available in the path, run the `nativeBuild` task:

	gradlew nativeBuild

The `nativeBuild` task would take a while to compile the OpenGL demo source code and
link them with the LWJGL libraries into an executable file.
The resulting `lwjgl3-opengl-demos` file is (in Linux):

	build/client/x86_64-linux/lwjgl3-opengl-demos

(or if building on a Windows machine:

	build\client\x86_64-windows\lwjgl3-opengl-demos.exe

)

which can then be run directly with a demo class as parameter
(e.g. [DepthEdgeShaderDemo20](src/org/lwjgl/demo/opengl/fbo/DepthEdgeShaderDemo20.java)):

	./build/client/x86_64-linux/lwjgl3-opengl-demos opengl.fbo.DepthEdgeShaderDemo20

System properties can be passed on to the running demo with the -D parameter,
e.g. to print out some debug info in the console:

	./build/client/x86_64-linux/lwjgl3-opengl-demos opengl.fbo.DepthEdgeShaderDemo20 -Dorg.lwjgl.util.Debug=true

(or if building on a Windows machine:

	build\client\x86_64-windows\lwjgl3-opengl-demos.exe opengl.fbo.DepthEdgeShaderDemo20
	build\client\x86_64-windows\lwjgl3-opengl-demos.exe opengl.fbo.DepthEdgeShaderDemo20 -Dorg.lwjgl.util.Debug=true

)

## Maven build tasks

### Run in standard JVM

To build and run the OpenGL demos in standard JVM with Maven, execute the
`compile` then `exec:exec` tasks:

	mvnw compile
	mvnw exec:exec

By default, the [SimpleDrawElements](src/org/lwjgl/demo/opengl/SimpleDrawElements.java) demo is executed
by the above `exec:exec` task without parameter. To run a different OpenGL demo, e.g.
[WavefrontObjDemo](src/org/lwjgl/demo/opengl/assimp/WavefrontObjDemo.java), execute the `exec:exec` task
with that specific demo class as value of the property `class`:

	mvnw exec:exec -Dclass=opengl.assimp.WavefrontObjDemo

System properties can be passed on to the running demo with the -Dsys.props parameter,
e.g. to print out some debug info in the console:

	mvnw exec:exec -Dclass=opengl.assimp.WavefrontObjDemo -Dsys.props="-Dorg.lwjgl.util.Debug=true"

The above tasks can use any standard JDK 11+.

### Produce native executable

To generate native executable, GraalVM 21+ need be set up as mentioned in
*GraalVM pre-requisites* section above.

Once GraalVM is set up and available in the path, run the `client:build` task:

	mvnw client:build

The `client:build` task would take a while to compile the OpenGL demo source code and
link them with the LWJGL libraries into an executable file.
The resulting `lwjgl3-opengl-demos` file is (in Linux):

	target/client/x86_64-linux/lwjgl3-opengl-demos

(or if building on a Windows machine:

	target\client\x86_64-windows\lwjgl3-opengl-demos.exe

)

which can then be run directly with a demo class as parameter
(e.g. [Demo33Ubo](src/org/lwjgl/demo/opengl/raytracing/Demo33Ubo.java)):

	./target/client/x86_64-linux/lwjgl3-opengl-demos opengl.raytracing.Demo33Ubo

System properties can be passed on to the running demo with the -D parameter,
e.g. to print out some debug info in the console:

	./target/client/x86_64-linux/lwjgl3-opengl-demos opengl.raytracing.Demo33Ubo -Dorg.lwjgl.util.Debug=true

(or if building on a Windows machine:

	target\client\x86_64-windows\lwjgl3-opengl-demos.exe opengl.raytracing.Demo33Ubo
	target\client\x86_64-windows\lwjgl3-opengl-demos.exe opengl.raytracing.Demo33Ubo -Dorg.lwjgl.util.Debug=true

)

## Compressed native executable

The resulting `lwjgl3-opengl-demos` executable file, whether produced by Gradle or Maven build script,
can be further reduced in size via compression using the [UPX](https://upx.github.io) utility,
as described [here](https://medium.com/graalvm/compressed-graalvm-native-images-4d233766a214).

For example, the resulting `lwjgl3-opengl-demos.exe` native application file produced in Windows
is normally 72MB in size, but is compressed to 24MB with the UPX command: `upx --best lwjgl3-opengl-demos.exe`

