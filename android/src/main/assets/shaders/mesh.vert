#version 300 es
// Malla del escaneo. Colores de vértice en espacio LINEAL (como en la web).
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNor;
layout(location = 2) in vec3 aCol;
layout(location = 3) in vec2 aUv;
uniform mat4 uMvp;
uniform mat4 uModel;
out vec3 vWorld;
out vec3 vNor;
out vec3 vCol;
out vec2 vUv;
void main() {
  vec4 w = uModel * vec4(aPos, 1.0);
  vWorld = w.xyz;
  vNor = mat3(uModel) * aNor;
  vCol = aCol;
  vUv = aUv;
  gl_Position = uMvp * vec4(aPos, 1.0);
}
