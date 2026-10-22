uniform sampler2D u_texture;

varying vec4 v_color;
varying vec2 v_uv;
varying float v_fog;

void main(){
    vec4 c = v_color * texture2D(u_texture, v_uv);
    if(c.a < 0.1) discard;
    gl_FragColor = vec4(mix(c.rgb, vec3(0.55, 0.72, 0.88), v_fog), c.a);
}
