package com.example.agslbasics.ui

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import kotlin.math.hypot

@Preview(showBackground = true)
@Composable
fun Aurora(modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Canvas(modifier.fillMaxSize()) { drawRect(androidx.compose.ui.graphics.Color.Black) }
    } else {
        AuroraShader(modifier.fillMaxSize())
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun AuroraShader(modifier: Modifier) {
    val agsl = remember {
        """
        uniform float2 uResolution;
        uniform float  uTime;
        uniform float  uRippleCount;
        uniform float2 uRipplePos0; uniform float uRippleAge0;
        uniform float2 uRipplePos1; uniform float uRippleAge1;
        uniform float2 uRipplePos2; uniform float uRippleAge2;
        uniform float2 uRipplePos3; uniform float uRippleAge3;
        uniform float2 uRipplePos4; uniform float uRippleAge4;

        float hash21(float2 p){
            p = fract(p*float2(123.34, 456.21));
            p += dot(p, p+45.32);
            return fract(p.x*p.y);
        }

        float noise(float2 p){
            float2 i = floor(p);
            float2 f = fract(p);
            float a = hash21(i);
            float b = hash21(i+float2(1.0,0.0));
            float c = hash21(i+float2(0.0,1.0));
            float d = hash21(i+float2(1.0,1.0));
            float2 u = f*f*(3.0-2.0*f);
            return mix(mix(a,b,u.x), mix(c,d,u.x), u.y);
        }

        float fbm(float2 p){
            float v = 0.0;
            float amp = 0.5;
            for (int i=0; i<5; i++){
                v += amp*noise(p);
                p *= 2.02;
                amp *= 0.5;
            }
            return v;
        }

        float3 palette(float t){
            float3 a = float3(0.52,0.45,0.62);
            float3 b = float3(0.45,0.42,0.48);
            float3 c = float3(1.0,1.0,0.9);
            float3 d = float3(0.10,0.35,0.55);
            return a + b*cos(6.28318*(c*t+d));
        }

        float ripple(float2 nd, float2 pos, float age){
            float dist = length(nd - pos);
            float speed = 1.1;
            float wave = sin(dist*26.0 - age*9.0);
            float ring = exp(-pow(dist - age*speed, 2.0)*90.0);
            float fade = exp(-age*1.1);
            return wave*ring*fade;
        }

        half4 main(float2 frag){
            float2 res = uResolution;
            float2 uv = frag/res;
            float2 nd = uv*2.0 - 1.0;
            nd.x *= res.x/res.y;

            float2 p = nd*1.4 + float2(uTime*0.02, -uTime*0.015);

            float2 q = float2(fbm(p), fbm(p+float2(5.2,1.3)));
            float2 r = float2(
                fbm(p + 4.0*q + float2(1.7,9.2) + 0.15*uTime),
                fbm(p + 4.0*q + float2(8.3,2.8) + 0.126*uTime)
            );
            float f = fbm(p + 4.0*r);

            float glow = 0.0;
            int count = int(uRippleCount + 0.5);
            if (count > 0) glow += ripple(nd, uRipplePos0, uRippleAge0);
            if (count > 1) glow += ripple(nd, uRipplePos1, uRippleAge1);
            if (count > 2) glow += ripple(nd, uRipplePos2, uRippleAge2);
            if (count > 3) glow += ripple(nd, uRipplePos3, uRippleAge3);
            if (count > 4) glow += ripple(nd, uRipplePos4, uRippleAge4);

            float t = f + (r.x+r.y)*0.25 + glow*0.35;

            float3 col = palette(t*1.4 + uTime*0.05);
            col = mix(col*0.35, col, smoothstep(0.15, 0.9, f));
            col += palette(t*1.4 + 0.5) * glow * 0.6;

            float vign = smoothstep(1.35, 0.2, length(nd));
            col *= vign;

            return half4(col, 1.0);
        }
        """.trimIndent()
    }

    val shader = remember { RuntimeShader(agsl) }

    var timeSec by remember { mutableStateOf(0f) }

    data class Ripple(val pos: Offset, var age: Float = 0f)

    val ripples = remember { mutableStateListOf<Ripple>() }
    var lastSpawnPos by remember { mutableStateOf<Offset?>(null) }

    LaunchedEffect(Unit) {
        var lastNs = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = (now - lastNs) / 1_000_000_000f
            lastNs = now
            timeSec += dt

            val it = ripples.iterator()
            while (it.hasNext()) {
                val rp = it.next()
                rp.age += dt
                if (rp.age > 2.5f) it.remove()
            }
        }
    }

    fun spawnRipple(pos: Offset) {
        ripples.add(Ripple(pos))
        while (ripples.size > 5) ripples.removeAt(0)
        lastSpawnPos = pos
    }

    val pointerMod = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            spawnRipple(down.position)

            drag(down.id) { change ->
                change.consume()
                val last = lastSpawnPos
                val moved = last == null || hypot(
                    (change.position.x - last.x).toDouble(),
                    (change.position.y - last.y).toDouble()
                ) > 28.0
                if (moved) spawnRipple(change.position)
            }
        }
    }

    val brush = remember {
        object : androidx.compose.ui.graphics.ShaderBrush() {
            override fun createShader(size: Size): Shader {
                shader.setFloatUniform("uResolution", size.width, size.height)
                return shader
            }
        }
    }

    Canvas(modifier.then(pointerMod)) {
        val w = size.width
        val h = size.height
        val aspect = w / h

        fun pxToNdc(pos: Offset): FloatArray {
            val x01 = pos.x / w
            val y01 = pos.y / h
            return floatArrayOf((x01 * 2f - 1f) * aspect, y01 * 2f - 1f)
        }

        shader.setFloatUniform("uTime", timeSec)

        val slots = listOf(0, 1, 2, 3, 4)
        val active = ripples.takeLast(5)
        for (i in slots) {
            val rp = active.getOrNull(i)
            if (rp != null) {
                val n = pxToNdc(rp.pos)
                shader.setFloatUniform("uRipplePos$i", n[0], n[1])
                shader.setFloatUniform("uRippleAge$i", rp.age)
            } else {
                shader.setFloatUniform("uRipplePos$i", 10f, 10f)
                shader.setFloatUniform("uRippleAge$i", 99f)
            }
        }
        shader.setFloatUniform("uRippleCount", active.size.toFloat())

        drawRect(brush = brush)
    }
}
