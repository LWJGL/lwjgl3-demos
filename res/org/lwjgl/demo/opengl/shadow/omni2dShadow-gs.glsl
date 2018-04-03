/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
#version 150

uniform mat4 viewProjectionMatrix[4];

layout(triangles) in;
layout(triangle_strip, max_vertices = 12) out;
 
void main() {	
	for (int layer = 0; layer < 4; layer++) {
		for (int i = 0; i < gl_in.length(); i++) {
			gl_Layer = layer;
			gl_Position = viewProjectionMatrix[layer] * gl_in[i].gl_Position;
			EmitVertex();
		}
		EndPrimitive();
	}
}
