/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 110

uniform mat4 viewProjectionMatrix;

attribute vec3 position;

void main(void) {
    gl_Position = viewProjectionMatrix * vec4(position, 1.0);
}
