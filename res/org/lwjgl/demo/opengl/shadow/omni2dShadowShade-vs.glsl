/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
#version 150

uniform mat4 viewProjectionMatrix;
uniform mat4 lightViewProjectionMatrices[4];
uniform mat4 biasMatrix;

in vec3 position;
in vec3 normal;

out vec4 lightBiasedClipPositions[4];
out vec3 worldPosition;
out vec3 worldNormal;

void main(void) {
	worldPosition = position;
	worldNormal = normal;
	for (int i = 0; i < 4; i++) {
		lightBiasedClipPositions[i] = biasMatrix * lightViewProjectionMatrices[i] * vec4(worldPosition, 1.0);
	}
	gl_Position = viewProjectionMatrix * vec4(position, 1.0);
}
