uniform sampler2D u_texture;
uniform vec3 u_eye;
uniform float u_time;
uniform vec3 u_cloudLo;
uniform vec3 u_cloudHi;
uniform float u_dim;
uniform vec2 u_fog; //distance where the clouds start, and where nothing is left

varying vec4 v_color;
varying vec2 v_uv;
varying vec3 v_pos;

float hash(vec2 p){
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float noise(vec2 p){
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

float cloud(vec2 p){
    float n = 0.0, a = 0.5;
    for(int i = 0; i < 4; i++){
        n += a * noise(p);
        p = p * 2.03 + vec2(17.0, 9.0);
        a *= 0.5;
    }
    return n;
}

void main(){
    vec4 c = v_color * texture2D(u_texture, v_uv);
    if(c.a < 0.1) discard;

    float d = distance(v_pos.xy, u_eye.xy);
    float span = u_fog.y - u_fog.x;
    //two layers of cloud rolling past at different speeds; they make the edge of the world ragged and billowy
    vec2 q = v_pos.xy * 0.018;
    float n = cloud(q + vec2(u_time * 0.035, u_time * 0.012)) * 0.65 + cloud(q * 2.3 - vec2(u_time * 0.05, 0.0)) * 0.35;
    float fog = smoothstep(u_fog.x, u_fog.y, d + (n - 0.5) * span * 0.7);
    //low haze that thickens in the clouds
    fog = max(fog, smoothstep(0.35, 0.85, n) * smoothstep(u_fog.x * 0.5, u_fog.y, d) * 0.55);
    vec3 cloudColour = mix(u_cloudLo, u_cloudHi, n);
    //slow shadows of clouds passing overhead
    float shade = mix(1.0, 0.84, smoothstep(0.5, 0.78, cloud(v_pos.xy * 0.0065 + vec2(u_time * 0.012, -u_time * 0.007))));
    gl_FragColor = vec4(mix(c.rgb * u_dim * shade, cloudColour, clamp(fog, 0.0, 1.0)), c.a);
}
