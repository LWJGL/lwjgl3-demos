/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 150

#define LIGHT_INTENSITY 0.9
#define AMBIENT 0.1

uniform sampler1DArray depthMaps;
uniform vec3 lightPosition;

in vec4 lightBiasedClipPositions[4];
in vec3 worldPosition;
in vec3 worldNormal;

out vec4 color;

int quadrant(vec2 dir) {
    if (abs(dir.x) > abs(dir.y))
		if (dir.x > 0.0) return 1;
		else return 3;
	if (dir.y > 0.0) return 2;
	return 0;
}

void main(void) {
	int q = quadrant(worldPosition.xz - lightPosition.xz);
	vec4 lightTexPosition = lightBiasedClipPositions[q] / lightBiasedClipPositions[q].w;
	float depth = texture(depthMaps, vec2(lightTexPosition.x, q)).r;
	color = vec4(AMBIENT, AMBIENT, AMBIENT, 1.0);
	if (depth >= lightTexPosition.z) {
		float dot = max(0.0, dot(normalize(lightPosition - worldPosition), worldNormal));
		color += vec4(LIGHT_INTENSITY, LIGHT_INTENSITY, LIGHT_INTENSITY, 1.0) * dot;
	}
}
