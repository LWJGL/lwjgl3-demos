/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core

uniform mat4 viewProjectionMatrix;
uniform mat4 viewMatrix;

layout(location = 0) in vec3 position;
layout(location = 1) in vec3 normal;

out vec3 vWorldPos;
out vec3 vNormal;
out float vViewZ;

void main(void) {
    vWorldPos = position;
    vNormal = normal;
    vViewZ = -(viewMatrix * vec4(position, 1.0)).z;
    gl_Position = viewProjectionMatrix * vec4(position, 1.0);
}
