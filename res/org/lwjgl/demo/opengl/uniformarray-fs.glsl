/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 150 core

uniform vec3 cols[4];
uniform int chosen;

out vec4 color;

void main(void) {
	color = vec4(cols[chosen], 1.0);
}
