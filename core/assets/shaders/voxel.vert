attribute vec3 a_position;
attribute vec3 a_normal;
attribute vec4 a_color;
attribute vec2 a_texCoord0;

uniform mat4 u_proj;
uniform vec3 u_offset;
uniform float u_scale;
uniform float u_rot;

varying vec4 v_color;
varying vec2 v_uv;
varying vec3 v_pos;

void main(){
    float c = cos(u_rot), s = sin(u_rot);
    vec3 p = a_position * u_scale;
    p = vec3(p.x * c - p.y * s, p.x * s + p.y * c, p.z) + u_offset;
    vec3 n = vec3(a_normal.x * c - a_normal.y * s, a_normal.x * s + a_normal.y * c, a_normal.z);

    float light = 0.5 + 0.5 * max(dot(n, normalize(vec3(-0.35, -0.45, 0.82))), 0.0);
    v_color = vec4(a_color.rgb * light, a_color.a);
    v_uv = a_texCoord0;
    v_pos = p;
    gl_Position = u_proj * vec4(p, 1.0);
}
