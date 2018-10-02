/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 150
#extension GL_ARB_gpu_shader5 : enable

#ifdef GL_ARB_gpu_shader5
layout(triangles, invocations = 4) in;
#else
layout(triangles) in;
#endif

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

void emitForLayer(int layer) {
    for (int i = 0; i < gl_in.length(); i++) {
        gl_Layer = layer;
        gl_Position = projection * vec4(transform(gl_in[i].gl_Position.xyz, layer), 1.0);
        EmitVertex();
    }
    EndPrimitive();
}

void withInstancing() {
    emitForLayer(gl_InvocationID);
}

void withoutInstancing() {
    for (int layer = 0; layer < 4; layer++) {
        emitForLayer(layer);
    }
}

void main() {
#ifdef GL_ARB_gpu_shader5
    withInstancing();
#else
    withoutInstancing();
#endif
}
