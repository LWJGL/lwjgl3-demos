/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 460
#extension GL_EXT_ray_tracing : enable

layout(location = 0) rayPayloadInEXT bool payload;

void main(void) {
  payload = false;
}
