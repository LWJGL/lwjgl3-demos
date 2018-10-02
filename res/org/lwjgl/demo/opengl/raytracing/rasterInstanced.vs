/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 130

uniform mat4 viewMatrix;
uniform mat4 projectionMatrix;

in vec3 vertexPosition;
in vec3 vertexNormal;
in vec3 boxCenter;
in vec3 boxHalfSize;

out vec4 worldPosition;
out vec4 worldNormal;

void main(void) {
  worldPosition = vec4(boxCenter + vertexPosition * boxHalfSize, 1.0);
  worldNormal = vec4(vertexNormal, 0.0);
  gl_Position = projectionMatrix * viewMatrix * worldPosition;
}
