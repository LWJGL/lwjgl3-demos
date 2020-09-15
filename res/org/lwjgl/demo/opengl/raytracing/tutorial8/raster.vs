/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 430 core

uniform mat4 projectionMatrix;
uniform mat4 viewMatrix[2];
uniform vec3 cameraPosition;

in vec3 vertexPosition;
in vec3 vertexNormal;

out vec4 worldPosition;
out vec3 dir;
out vec4 worldNormal;
out vec4 viewPosition;
out vec4 prevViewPosition;
out vec4 clipPosition;
out vec4 prevClipPosition;

void main(void) {
  worldPosition = vec4(vertexPosition, 1.0);
  dir = worldPosition.xyz - cameraPosition;
  worldNormal = vec4(vertexNormal, 0.0);
  viewPosition = viewMatrix[0] * worldPosition;
  prevViewPosition = viewMatrix[1] * worldPosition;
  clipPosition = projectionMatrix * viewPosition;
  prevClipPosition = projectionMatrix * prevViewPosition;
  gl_Position = clipPosition;
}
