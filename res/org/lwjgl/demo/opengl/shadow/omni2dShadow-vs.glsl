/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 150

in vec3 position;

void main(void) {
    gl_Position = vec4(position, 1.0);
}
