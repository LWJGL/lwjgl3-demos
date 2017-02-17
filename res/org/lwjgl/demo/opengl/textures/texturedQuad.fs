#version 130

uniform sampler2D tex;

in vec2 texCoordsVarying;
out vec4 color;

void main() {
  color = texture(tex, texCoordsVarying);
}
