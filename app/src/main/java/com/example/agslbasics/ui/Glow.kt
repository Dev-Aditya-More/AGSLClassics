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
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun Glow(modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Canvas(modifier.fillMaxSize()) { drawRect(androidx.compose.ui.graphics.Color.Black) }
    } else {
        GlowShader(modifier.fillMaxSize())
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun GlowShader(modifier: Modifier) {
    val agsl = remember {
        """
        uniform float2 uResolution;
        uniform float  uTime;
        // Finger glow
        uniform float2 uOrigin; // -1..1, x scaled
        uniform float  uPress;  // 0..1 ease-in
        uniform float  uDecay;  // 0..1 ease-out to 1
        uniform float  uIdleAlpha; // 0..1 minimal visibility when idle
        // Shots (comets)
        uniform float  uShotCount;
        uniform float2 uShotPos0; uniform float2 uShotDir0; uniform float uShotAge0;
        uniform float2 uShotPos1; uniform float2 uShotDir1; uniform float uShotAge1;
        uniform float2 uShotPos2; uniform float2 uShotDir2; uniform float uShotAge2;
        float softGlow(float2 d, float radius){
            return exp(-dot(d,d) / (radius*radius));
        }
        float lineGlow(float2 d, float2 dir, float radius, float lengthBoost){
            float along = dot(d, dir);
            float2 ortho = d - dir * along;
            float core = exp(-dot(ortho,ortho) / (radius*radius));
            float head = smoothstep(0.0, 1.0, along * 0.9 + 0.5);
            return core * (0.6 + 0.4*head) * lengthBoost;
        }
        half4 main(float2 frag){
            float2 res = uResolution;
            float2 nd = frag / res * 2.0 - 1.0;
            nd.x *= res.x / res.y;
            float3 bg = float3(0.05, 0.06, 0.10);
            // change glow color to red
            float3 glowColor = float3(1.00, 0.25, 0.25);

            // envelope (press vs decay), plus idle minimum alpha
            float env = smoothstep(0.0,1.0,uPress) * (1.0 - smoothstep(0.0,1.0,uDecay));
            float visible = max(env, uIdleAlpha);
            float field = 0.0;
            float2 d = nd - uOrigin;
            // tight core + two tiny micro-layers (very subtle)
            float t = uTime;
            field += softGlow(d, 0.28) * 0.95;
            float2 p1 = uOrigin + float2(cos(t*0.45), sin(t*0.45)) * 0.06;
            float2 p2 = uOrigin + float2(cos(t*0.68+1.2), sin(t*0.58+0.5)) * 0.08;
            field += softGlow(nd - p1, 0.22) * 0.30;
            field += softGlow(nd - p2, 0.22) * 0.28;
            // shots (comets)
            int count = int(uShotCount + 0.5);
            if (count > 0){
                float life0 = clamp(1.0 - uShotAge0/1.2, 0.0, 1.0);
                field += softGlow(nd - uShotPos0, 0.22) * 0.9 * life0;
                field += lineGlow(nd - uShotPos0, normalize(uShotDir0+1e-6), 0.10, 1.3) * 0.9 * life0;
            }
            if (count > 1){
                float life1 = clamp(1.0 - uShotAge1/1.2, 0.0, 1.0);
                field += softGlow(nd - uShotPos1, 0.20) * 0.8 * life1;
                field += lineGlow(nd - uShotPos1, normalize(uShotDir1+1e-6), 0.09, 1.2) * 0.8 * life1;
            }
            if (count > 2){
                float life2 = clamp(1.0 - uShotAge2/1.2, 0.0, 1.0);
                field += softGlow(nd - uShotPos2, 0.18) * 0.7 * life2;
                field += lineGlow(nd - uShotPos2, normalize(uShotDir2+1e-6), 0.08, 1.1) * 0.7 * life2;
            }
            // map to color
            float strength = smoothstep(0.00, 0.80, field * visible);
            float3 outColor = mix(bg, glowColor, strength);
            return half4(outColor, 1.0);
        }
        """.trimIndent()
    }

    val shader = remember { RuntimeShader(agsl) }

    var timeSec by remember { mutableStateOf(0f) }

    // Finger state
    var touching by remember { mutableStateOf(false) }
    var originPx by remember { mutableStateOf<Offset?>(null) }   // raw px
    var originSm by remember { mutableStateOf(Offset.Zero) }     // smoothed px
    var pressTarget by remember { mutableStateOf(0f) }           // 0..1
    var press by remember { mutableStateOf(0f) }                 // smoothed
    var decay by remember { mutableStateOf(1f) }                 // 0 active → 1 gone

    // Idle drift state
    var idleAnchor by remember { mutableStateOf<Offset?>(null) } // where we drift around
    var idlePhase by remember { mutableStateOf(0f) }             // radians
    var idleAlphaTarget by remember { mutableStateOf(0.10f) }    // minimal visibility when idle
    var idleAlpha by remember { mutableStateOf(0.10f) }

    // Comet shots
    data class Shot(var pos: Offset, var vel: Offset, var age: Float = 0f)

    val shots = remember { mutableStateListOf<Shot>() }

    LaunchedEffect(Unit) {
        var lastNs = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = (now - lastNs) / 1_000_000_000f
            lastNs = now
            timeSec += dt

            val aPress = 1f - exp(-dt * 7f)
            press += (pressTarget - press) * aPress
            if (pressTarget == 0f) {
                val aDecay = 1f - exp(-dt * 2.6f)
                decay = (decay + (1f - decay) * aDecay).coerceIn(0f, 1f)
            } else decay = 0f

            val aIdle = 1f - exp(-dt * if (touching) 10f else 3f)
            idleAlpha += (idleAlphaTarget - idleAlpha) * aIdle

            val aPos = 1f - exp(-dt * 9f)
            // If idle: drift originSm around idleAnchor with a tiny Lissajous path
            if (!touching) {
                idlePhase += dt * 0.25f // slow drift
                val anchor = idleAnchor ?: originSm
                val r = 18f // px radius of drift (small + elegant)
                val drift = Offset(
                    (cos(idlePhase.toDouble()) * r).toFloat(),
                    (sin((idlePhase * 0.85f + 1.1f).toDouble()) * r * 0.6f).toFloat()
                )
                val target = anchor + drift
                originSm += (target - originSm) * aPos
            } else {
                originPx?.let { raw -> originSm += (raw - originSm) * aPos }
            }

            val damping = 2.2f
            val it = shots.iterator()
            while (it.hasNext()) {
                val s = it.next()
                s.pos += s.vel * dt
                s.vel *= exp(-damping * dt)
                s.age += dt
                if (s.age > 1.25f) it.remove()
            }
        }
    }

    val pointerMod = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val vt = VelocityTracker()
            val down = awaitFirstDown()
            touching = true
            pressTarget = 1f
            idleAlphaTarget = 0f
            originPx = down.position
            idleAnchor = down.position
            vt.addPosition(down.uptimeMillis, down.position)

            drag(down.id) { change ->
                change.consume()
                originPx = change.position
                vt.addPosition(change.uptimeMillis, change.position)
                idleAnchor = change.position
            }

            // release
            touching = false
            pressTarget = 0f
            idleAlphaTarget = 0.10f         // drop to faint idle visibility

            val v = vt.calculateVelocity()
            val speed = hypot(v.x, v.y)
            if (speed > 900f) {
                shots.add(Shot(pos = originSm, vel = Offset(v.x, v.y) * 0.0016f))
                while (shots.size > 3) shots.removeAt(0)
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

        fun pxToNdc(p: Offset): FloatArray {
            val x01 = (p.x / w).coerceIn(0f, 1f)
            val y01 = (p.y / h).coerceIn(0f, 1f)
            return floatArrayOf((x01 * 2f - 1f) * aspect, y01 * 2f - 1f)
        }

        fun velDirNdc(velPx: Offset): FloatArray {
            val vx = (velPx.x / w) * 2f * aspect
            val vy = (velPx.y / h) * 2f
            val len = max(1e-6f, sqrt(vx * vx + vy * vy))
            return floatArrayOf(vx / len, vy / len)
        }

        shader.setFloatUniform("uTime", timeSec)

        val o = pxToNdc(originSm)
        shader.setFloatUniform("uOrigin", o[0], o[1])
        shader.setFloatUniform("uPress", press)
        shader.setFloatUniform("uDecay", decay)
        shader.setFloatUniform("uIdleAlpha", idleAlpha)

        // Shots uniforms (up to 3)
        val s0 = shots.getOrNull(shots.lastIndex)
        val s1 = shots.getOrNull(shots.lastIndex - 1)
        val s2 = shots.getOrNull(shots.lastIndex - 2)

        var count = 0f
        if (s0 != null) {
            val p = pxToNdc(s0.pos);
            val d = velDirNdc(s0.vel)
            shader.setFloatUniform("uShotPos0", p[0], p[1])
            shader.setFloatUniform("uShotDir0", d[0], d[1])
            shader.setFloatUniform("uShotAge0", s0.age)
            count += 1f
        } else {
            shader.setFloatUniform("uShotPos0", 10f, 10f)
            shader.setFloatUniform("uShotDir0", 1f, 0f)
            shader.setFloatUniform("uShotAge0", 99f)
        }
        if (s1 != null) {
            val p = pxToNdc(s1.pos);
            val d = velDirNdc(s1.vel)
            shader.setFloatUniform("uShotPos1", p[0], p[1])
            shader.setFloatUniform("uShotDir1", d[0], d[1])
            shader.setFloatUniform("uShotAge1", s1.age)
            count += 1f
        } else {
            shader.setFloatUniform("uShotPos1", 10f, 10f)
            shader.setFloatUniform("uShotDir1", 1f, 0f)
            shader.setFloatUniform("uShotAge1", 99f)
        }
        if (s2 != null) {
            val p = pxToNdc(s2.pos);
            val d = velDirNdc(s2.vel)
            shader.setFloatUniform("uShotPos2", p[0], p[1])
            shader.setFloatUniform("uShotDir2", d[0], d[1])
            shader.setFloatUniform("uShotAge2", s2.age)
            count += 1f
        } else {
            shader.setFloatUniform("uShotPos2", 10f, 10f)
            shader.setFloatUniform("uShotDir2", 1f, 0f)
            shader.setFloatUniform("uShotAge2", 99f)
        }
        shader.setFloatUniform("uShotCount", count)

        drawRect(brush = brush)
    }
}