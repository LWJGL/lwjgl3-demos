# lwjgl3-bgfx-demos + GraalVM native image

BGFX Demos for LWJGL 3 compiled to native executable by GraalVM native-image utility.

Four BGFX demos are included in the build: [Bump](src/org/lwjgl/demo/bgfx/Bump.java),
[Cubes](src/org/lwjgl/demo/bgfx/Cubes.java), [Metaballs](src/org/lwjgl/demo/bgfx/Metaballs.java),
and [Raymarch](src/org/lwjgl/demo/bgfx/Raymarch.java).

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

To build and run the BGFX demos in standard JVM with Gradle, execute the `run` task:

	gradlew run

By default, the [Bump](src/org/lwjgl/demo/bgfx/Bump.java) demo is executed
by the above `run` task without parameter. To run a different BGFX demo, e.g.
[Raymarch](src/org/lwjgl/demo/bgfx/Raymarch.java), execute the `run` task
with that specific demo class as parameter (prefixed by `bgfx.`):

	gradlew run --args=bgfx.Raymarch

The above tasks can use any standard JDK 11+.

To generate native executable, GraalVM 21+ need be set up as mentioned in
*GraalVM pre-requisites* section above.

Once GraalVM is set up and available in the path, run the `nativeBuild` task:

	gradlew nativeBuild

The `nativeBuild` task would take a while to compile the BGFX demo source code and
link them with the LWJGL libraries into an executable file.
The resulting `lwjgl3-bgfx-demos` file is:

	build/client/x86_64-linux/lwjgl3-bgfx-demos

(or if building on a Windows machine:

	build\client\x86_64-windows\lwjgl3-bgfx-demos.exe

)

which can then be run directly:

	./build/client/x86_64-linux/lwjgl3-bgfx-demos

or, for example, with the [Cubes](src/org/lwjgl/demo/bgfx/Cubes.java)
demo as parameter prefixed by `bgfx.`:

	./build/client/x86_64-linux/lwjgl3-bgfx-demos bgfx.Cubes

(or if building on a Windows machine:

	build\client\x86_64-windows\lwjgl3-bgfx-demos.exe
	build\client\x86_64-windows\lwjgl3-bgfx-demos.exe bgfx.Cubes

)

## Maven build tasks

To build and run the BGFX demos in standard JVM with Maven, execute the
`compile` then `exec:exec` tasks:

	mvnw compile
	mvnw exec:exec

By default, the [Bump](src/org/lwjgl/demo/bgfx/Bump.java) demo is executed
by the above `exec:exec` task without parameter. To run a different BGFX demo, e.g.
[Raymarch](src/org/lwjgl/demo/bgfx/Raymarch.java), execute the `exec:exec` task
with that specific demo class (prefixed by `bgfx.`) as value of the property `class`:

	mvnw exec:exec -Dclass=bgfx.Raymarch

The above tasks can use any standard JDK 11+.

To generate native executable, GraalVM 21+ need be set up as mentioned in
*GraalVM pre-requisites* section above.

Once GraalVM is set up and available in the path, run the `client:build` task:

	mvnw client:build

The `client:build` task would take a while to compile the BGFX demo source code and
link them with the LWJGL libraries into an executable file.
The resulting `lwjgl3-bgfx-demos` file is:

	target/client/x86_64-linux/lwjgl3-bgfx-demos

(or if building on a Windows machine:

	target\client\x86_64-windows\lwjgl3-bgfx-demos.exe

)

which can then be run directly:

	./target/client/x86_64-linux/lwjgl3-bgfx-demos

or, for example, with the [Metaballs](src/org/lwjgl/demo/bgfx/Metaballs.java)
demo as parameter prefixed by `bgfx.`:

	./target/client/x86_64-linux/lwjgl3-bgfx-demos bgfx.Metaballs

(or if building on a Windows machine:

	target\client\x86_64-windows\lwjgl3-bgfx-demos.exe
	target\client\x86_64-windows\lwjgl3-bgfx-demos.exe bgfx.Metaballs

)

## Compressed native executable

The resulting `lwjgl3-bgfx-demos` executable file, whether produced by Gradle or Maven build script,
can be further reduced in size via compression using the [UPX](https://upx.github.io) utility,
as described [here](https://medium.com/graalvm/compressed-graalvm-native-images-4d233766a214).

For example, the resulting `lwjgl3-bgfx-demos.exe` native application file produced in Windows
is normally 59MB in size, but is compressed to 16MB with the UPX command: `upx --best lwjgl3-bgfx-demos.exe`

