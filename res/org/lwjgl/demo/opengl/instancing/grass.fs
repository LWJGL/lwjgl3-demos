/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 110

uniform sampler2D tex0;
uniform sampler2D tex1;
uniform sampler2D tex2;
uniform sampler2D tex3;

varying vec2 texCoordVarying;
varying float yVarying;
varying vec3 normalVarying;
varying float texIndexVarying;

const vec3 LIGHT_DIR = normalize(vec3(0.1, 1, 0.1));

void main(void) {
  vec3 n = normalize(normalVarying);
  float dot = max(0.0, dot(LIGHT_DIR, n));
  vec4 col;
  int texIndexVaryingI = int(texIndexVarying);
  if (texIndexVaryingI == 0)
    col = texture2D(tex0, texCoordVarying);
  else if (texIndexVaryingI == 1)
    col = texture2D(tex1, texCoordVarying);
  else if (texIndexVaryingI == 2)
    col = texture2D(tex2, texCoordVarying);
  else
    col = texture2D(tex3, texCoordVarying);
  col.rgb *= yVarying;
  if (col.a < 0.8)
    discard;
  gl_FragColor = vec4(col.rgb * dot, col.a);
}
