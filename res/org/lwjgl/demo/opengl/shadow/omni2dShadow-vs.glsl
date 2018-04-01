/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
#version 150

in vec3 position;

void main(void) {
	gl_Position = vec4(position, 1.0);
}
