# lwjgl3-demos + GraalVM native image

Demos for LWJGL 3, compiled to native executable by GraalVM native-image utility.

All demos in [src](src/org/lwjgl/demo) are included in the build. Most demos are portable
across different OSes, but some are GPU-specific, like the [Vulkan demos](src/org/lwjgl/demo/vulkan)
which need a discrete GPU with supporting Vulkan driver, or the [CUDA demos](src/org/lwjgl/demo/cuda)
which require a NVIDIA graphics card to run.

Gradle and Maven build scripts are provided for building the project,
which requires JDK 11+ or GraalVM 21+ (for native image).

## GraalVM pre-requisites

### Setup for GraalVM native-image utility and C compiler

The [GraalVM native-image](https://www.graalvm.org/reference-manual/native-image) page
shows how to set up GraalVM and its native-image utility for common platforms.
[Gluon](https://gluonhq.com/) also provides some setup
[details](https://docs.gluonhq.com/#_platforms) for GraalVM native-image creation.

This project's Gradle build script uses the
[client-gradle-plugin](https://github.com/gluonhq/client-gradle-plugin)
from Gluon to build the native executable from Gradle with GraalVM.

Gluon also provides the [client-maven-plugin](https://github.com/gluonhq/client-maven-plugin)
which is used in this project's Maven build script and works similarly to the above
client-gradle-plugin.

### Native-image configuration files

The GraalVM native-image utility will use the configuration files in
`res/META-INF/native-image` folder to assist in the native-image generation.

The configuration files were generated when running the demos in standard JVM with a
[GraalVM agent](https://www.graalvm.org/reference-manual/native-image/BuildConfiguration/#assisted-configuration-of-native-image-builds),
which tracks all usages of dynamic features of an execution of the demos
and writes the info to the configuration files.
Usage of the agent is contained in the [Gradle build script](build.gradle#L129),
or in the [Maven build script](pom.xml#L163), which can be turned on (i.e. uncommented)
and the demos are re-run to update the configuration files, if need be.

The above agent is not perfect; it sometimes misses some classes referenced via reflection
which is used extensively in LWJGL. In the case of the LWJGL demos, many org.lwjgl.bgfx.\*
and org.lwjgl.assimp.\* classes need be added manually to the configuration files,
to avoid ClassNotFoundException being thrown when running the [bgfx](src/org/lwjgl/demo/bgfx)
or [assimp](src/org/lwjgl/demo/opengl/assimp) demos in the generated native image.

Gluon's client-gradle-plugin/client-maven-plugin also support their own configuration files,
in `res/META-INF/substrate/config` folder, which can be platform-specific
(e.g. for the Eclipse [SWT](https://www.eclipse.org/swt/) library used in our
[SWT demos](src/org/lwjgl/demo/opengl/swt)), as documented
[here](https://docs.gluonhq.com/#_substrate_config_files).
These files need be maintained manually.

## Gradle build tasks

### Run in standard JVM

To build and run the demos in standard JVM with Gradle, execute the `run` task:

	gradlew run

By default, the [Bump](src/org/lwjgl/demo/bgfx/Bump.java) demo is executed
by the above `run` task without parameter. To run a different demo, e.g.
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

The `nativeBuild` task would take a while to compile all demo source code and
link them with the LWJGL libraries into an executable file.
The resulting `lwjgl3-demos` file is (in Linux):

	build/client/x86_64-linux/lwjgl3-demos

(or if building on a Windows machine:

	build\client\x86_64-windows\lwjgl3-demos.exe

)

which can then be run directly with a demo class as parameter
(e.g. [DepthEdgeShaderDemo20](src/org/lwjgl/demo/opengl/fbo/DepthEdgeShaderDemo20.java)):

	./build/client/x86_64-linux/lwjgl3-demos opengl.fbo.DepthEdgeShaderDemo20

System properties can be passed on to the running demo with the -D parameter,
e.g. to print out some debug info in the console:

	./build/client/x86_64-linux/lwjgl3-demos opengl.fbo.DepthEdgeShaderDemo20 -Dorg.lwjgl.util.Debug=true

(or if building on a Windows machine:

	build\client\x86_64-windows\lwjgl3-demos.exe opengl.fbo.DepthEdgeShaderDemo20
	build\client\x86_64-windows\lwjgl3-demos.exe opengl.fbo.DepthEdgeShaderDemo20 -Dorg.lwjgl.util.Debug=true

)

## Maven build tasks

### Run in standard JVM

To build and run the demos in standard JVM with Maven, execute the
`compile` then `exec:exec` tasks:

	mvnw compile
	mvnw exec:exec

By default, the [Bump](src/org/lwjgl/demo/bgfx/Bump.java) demo is executed
by the above `exec:exec` task without parameter. To run a different demo, e.g.
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

The `client:build` task would take a while to compile all demo source code and
link them with the LWJGL libraries into an executable file.
The resulting `lwjgl3-demos` file is (in Linux):

	target/client/x86_64-linux/lwjgl3-demos

(or if building on a Windows machine:

	target\client\x86_64-windows\lwjgl3-demos.exe

)

which can then be run directly with a demo class as parameter
(e.g. [Demo33Ubo](src/org/lwjgl/demo/opengl/raytracing/Demo33Ubo.java)):

	./target/client/x86_64-linux/lwjgl3-demos opengl.raytracing.Demo33Ubo

System properties can be passed on to the running demo with the -D parameter,
e.g. to print out some debug info in the console:

	./target/client/x86_64-linux/lwjgl3-demos opengl.raytracing.Demo33Ubo -Dorg.lwjgl.util.Debug=true

(or if building on a Windows machine:

	target\client\x86_64-windows\lwjgl3-demos.exe opengl.raytracing.Demo33Ubo
	target\client\x86_64-windows\lwjgl3-demos.exe opengl.raytracing.Demo33Ubo -Dorg.lwjgl.util.Debug=true

)

## Compressed native executable

The resulting `lwjgl3-demos` executable file, whether produced by Gradle or Maven build script,
can be further reduced in size via compression using the [UPX](https://upx.github.io) utility,
as described [here](https://medium.com/graalvm/compressed-graalvm-native-images-4d233766a214).

For example, the resulting `lwjgl3-demos.exe` native application file produced in Windows
is normally 109MB in size, but is compressed to 33MB with the UPX command: `upx --best lwjgl3-demos.exe`

