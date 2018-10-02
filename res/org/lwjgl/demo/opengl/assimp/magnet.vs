/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 110

attribute vec4 aVertex;
attribute vec3 aNormal;
uniform mat4 uModelMatrix;
uniform mat4 uViewProjectionMatrix;
uniform mat3 uNormalMatrix;
varying vec3 vPosition;
varying vec3 vNormal;

void main() {
    vec4 modelPosition = uModelMatrix * aVertex;
    gl_Position = uViewProjectionMatrix * modelPosition;
    vPosition = modelPosition.xyz;
    vNormal = uNormalMatrix * aNormal;
}
