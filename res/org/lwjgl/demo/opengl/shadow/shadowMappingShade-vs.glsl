/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 110

uniform mat4 viewProjectionMatrix;
uniform mat4 lightViewProjectionMatrix;
uniform mat4 biasMatrix;

attribute vec3 position;
attribute vec3 normal;

varying vec4 lightBiasedClipPosition;
varying vec3 worldPosition;
varying vec3 worldNormal;

void main(void) {
    /* Pass the position and normal to the fragment shader
       (we do lighting computations in world coordinates) */
    worldPosition = position;
    worldNormal = normal;

    /* Compute vertex position as seen from
       the light and use linear interpolation when passing it
       to the fragment shader
    */
    lightBiasedClipPosition = biasMatrix * lightViewProjectionMatrix * vec4(position, 1.0);

    /* Normally transform the vertex */
    gl_Position = viewProjectionMatrix * vec4(position, 1.0);
}
