/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
#version 150

layout(triangles) in;
layout(triangle_strip, max_vertices = 12) out;

uniform mat4 projection;
uniform vec3 lightPosition;

vec3 transform(vec3 pos, int layer) {
  pos -= lightPosition;
  switch (layer) {
    case 0:
      return pos;
    case 1:
      return vec3(-pos.z, -pos.y, -pos.x);
    case 2:
      return vec3(-pos.x, pos.y, -pos.z);
    case 3:
      return vec3(-pos.z, pos.y, pos.x);
  }
}

void main() {	
	for (int layer = 0; layer < 4; layer++) {
		for (int i = 0; i < gl_in.length(); i++) {
			gl_Layer = layer;
			gl_Position = projection * vec4(transform(gl_in[i].gl_Position.xyz, layer), 1.0);
			EmitVertex();
		}
		EndPrimitive();
	}
}
