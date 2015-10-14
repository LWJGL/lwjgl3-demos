#version 110
#extension GL_EXT_geometry_shader4 : require

void main(void) {
  gl_Position = gl_PositionIn[0];
  EmitVertex();
  gl_Position = gl_PositionIn[2];
  EmitVertex();
  gl_Position = gl_PositionIn[4];
  EmitVertex();
}
