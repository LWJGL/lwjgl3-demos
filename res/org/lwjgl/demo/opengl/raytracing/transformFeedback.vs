/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 130

uniform mat4 modelMatrix;
uniform mat4 viewMatrix;
uniform mat3 normalMatrix;
uniform mat4 projectionMatrix;

in vec3 vertexPosition;
in vec3 vertexNormal;

out vec4 viewPosition;
out vec4 viewNormal;

void main(void) {
  viewPosition = viewMatrix * modelMatrix * vec4(vertexPosition, 1.0);
  viewNormal = vec4(normalMatrix * vertexNormal, 0.0);
  gl_Position = projectionMatrix * viewPosition;
}
