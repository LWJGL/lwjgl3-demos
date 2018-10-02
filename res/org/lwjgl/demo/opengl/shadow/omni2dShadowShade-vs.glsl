/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 150

uniform mat4 viewProjection;
uniform mat4 lightProjection;
uniform vec3 lightPosition;

in vec3 position;
in vec3 normal;

out vec4 lightBiasedClipPositions[4];
out vec3 worldPosition;
out vec3 worldNormal;

void main(void) {
    worldPosition = position;
    worldNormal = normal;
    vec3 p = position - lightPosition;
    lightBiasedClipPositions[0] = lightProjection * vec4(p, 1.0);
    lightBiasedClipPositions[1] = lightProjection * vec4(-p.z, -p.y, -p.x, 1.0);
    lightBiasedClipPositions[2] = lightProjection * vec4(-p.x, p.y, -p.z, 1.0);
    lightBiasedClipPositions[3] = lightProjection * vec4(-p.z, p.y, p.x, 1.0);
    gl_Position = viewProjection * vec4(position, 1.0);
}
