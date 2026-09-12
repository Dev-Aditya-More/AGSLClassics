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
import androidx.compose.runtime.mutableFloatStateOf
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
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Interactive metaball / liquid-metal effect.
 *
 * Unlike Aurora (fbm noise field) or Glow (radial glow + comets), this one is built from a
 * signed-distance field: a handful of circular blobs are combined with a smooth-min so they
 * merge like mercury droplets, then a normal is reconstructed from the SDF gradient to drive
 * fake specular lighting + a chromatic rim, giving it a molten/chrome look.
 */
@Composable
fun LiquidMetal(modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Canvas(modifier.fillMaxSize()) { drawRect(androidx.compose.ui.graphics.Color.Black) }
    } else {
        LiquidMetalShader(modifier.fillMaxSize())
    }
}

private const val FLOATER_COUNT = 4

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun LiquidMetalShader(modifier: Modifier) {
    val agsl = remember {
        """
        uniform float2 uResolution;
        uniform float  uTime;

        // 4 ambient floating blobs
        uniform float2 uPos0; uniform float uRad0;
        uniform float2 uPos1; uniform float uRad1;
        uniform float2 uPos2; uniform float uRad2;
        uniform float2 uPos3; uniform float uRad3;
        // 1 touch-controlled blob
        uniform float2 uPosT; uniform float uRadT;

        const float K = 0.22; // smooth-min blend radius

        float smin(float a, float b, float k){
            float h = clamp(0.5 + 0.5*(b-a)/k, 0.0, 1.0);
            return mix(b, a, h) - k*h*(1.0-h);
        }

        float sdf(float2 p){
            float d = length(p - uPos0) - uRad0;
            d = smin(d, length(p - uPos1) - uRad1, K);
            d = smin(d, length(p - uPos2) - uRad2, K);
            d = smin(d, length(p - uPos3) - uRad3, K);
            d = smin(d, length(p - uPosT) - uRadT, K);
            return d;
        }

        half4 main(float2 frag){
            float2 res = uResolution;
            float aspect = res.x / res.y;
            float2 uv = frag / res;
            float2 nd = uv*2.0 - 1.0;
            nd.x *= aspect;

            float f = sdf(nd);

            float eps = 0.0025;
            float dx = sdf(nd + float2(eps,0.0)) - sdf(nd - float2(eps,0.0));
            float dy = sdf(nd + float2(0.0,eps)) - sdf(nd - float2(0.0,eps));
            float3 normal = normalize(float3(dx, dy, eps*2.6));

            float3 lightDir = normalize(float3(0.55, -0.65, 0.75));
            float3 viewDir = float3(0.0, 0.0, 1.0);
            float3 halfVec = normalize(lightDir + viewDir);

            float diffuse = max(dot(normal, lightDir), 0.0);
            float spec = pow(max(dot(normal, halfVec), 0.0), 60.0);
            float fresnel = pow(1.0 - max(dot(normal, viewDir), 0.0), 3.0);

            // iridescent hue shift driven by surface orientation, like an oil-slick / chrome tint
            float hueShift = normal.x*0.5 + normal.y*0.3 + uTime*0.05;
            float3 tintA = float3(0.35, 0.55, 0.95);
            float3 tintB = float3(0.85, 0.35, 0.75);
            float3 metal = mix(tintA, tintB, 0.5 + 0.5*sin(hueShift*6.2831));

            float3 blobColor = metal * (0.25 + diffuse*0.85);
            blobColor += float3(1.0) * spec;
            blobColor += float3(0.7, 0.85, 1.0) * fresnel * 0.8;

            float3 bg = mix(float3(0.03,0.035,0.05), float3(0.07,0.06,0.10), uv.y);

            float mask = smoothstep(0.012, -0.012, f);
            // thin bright rim right at the surface boundary for a glassy edge highlight
            float rim = smoothstep(0.02, 0.0, abs(f)) * (1.0 - mask*0.4);

            float3 col = mix(bg, blobColor, mask);
            col += float3(0.6, 0.8, 1.0) * rim * 0.35;

            return half4(col, 1.0);
        }
        """.trimIndent()
    }

    val shader = remember { RuntimeShader(agsl) }

    var timeSec by remember { mutableFloatStateOf(0f) }
    var aspect by remember { mutableFloatStateOf(1f) }

    class Blob(var pos: Offset, var vel: Offset, val baseRadius: Float, val phase: Float)

    val floaters = remember {
        mutableListOf(
            Blob(Offset(-0.5f, -0.3f), Offset(0.18f, 0.12f), 0.20f, 0.0f),
            Blob(Offset(0.4f, 0.2f), Offset(-0.14f, 0.20f), 0.16f, 1.7f),
            Blob(Offset(-0.2f, 0.5f), Offset(0.10f, -0.16f), 0.18f, 3.1f),
            Blob(Offset(0.3f, -0.4f), Offset(-0.20f, -0.10f), 0.14f, 4.6f),
        )
    }

    // Touch blob state
    var touching by remember { mutableStateOf(false) }
    var touchTargetNdc by remember { mutableStateOf(Offset.Zero) }
    var touchSmNdc by remember { mutableStateOf(Offset(10f, 10f)) }
    var pressTarget by remember { mutableFloatStateOf(0f) }
    var press by remember { mutableFloatStateOf(0f) }

    fun pxToNdc(pos: Offset, size: Size): Offset {
        val x01 = pos.x / size.width
        val y01 = pos.y / size.height
        return Offset((x01 * 2f - 1f) * aspect, y01 * 2f - 1f)
    }

    LaunchedEffect(Unit) {
        var lastNs = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - lastNs) / 1_000_000_000f).coerceAtMost(0.05f)
            lastNs = now
            timeSec += dt

            val bx = aspect * 0.92f
            val by = 0.92f
            for (b in floaters) {
                // gentle organic wander on top of the base velocity
                val wobble = Offset(
                    sin(timeSec * 0.6f + b.phase) * 0.05f,
                    sin(timeSec * 0.5f + b.phase * 1.3f) * 0.05f
                )
                b.pos += (b.vel + wobble) * dt
                if (b.pos.x > bx || b.pos.x < -bx) {
                    b.vel = Offset(-b.vel.x, b.vel.y)
                    b.pos = Offset(b.pos.x.coerceIn(-bx, bx), b.pos.y)
                }
                if (b.pos.y > by || b.pos.y < -by) {
                    b.vel = Offset(b.vel.x, -b.vel.y)
                    b.pos = Offset(b.pos.x, b.pos.y.coerceIn(-by, by))
                }
            }

            val aPress = 1f - exp(-dt * 8f)
            press += (pressTarget - press) * aPress

            val aPos = 1f - exp(-dt * 12f)
            if (touching) {
                touchSmNdc += (touchTargetNdc - touchSmNdc) * aPos
            }
        }
    }

    val pointerMod = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val vt = VelocityTracker()
            val down = awaitFirstDown()
            touching = true
            pressTarget = 1f
            touchTargetNdc = pxToNdc(down.position, size.toSize())
            touchSmNdc = touchTargetNdc
            vt.addPosition(down.uptimeMillis, down.position)

            drag(down.id) { change ->
                change.consume()
                touchTargetNdc = pxToNdc(change.position, size.toSize())
                vt.addPosition(change.uptimeMillis, change.position)
            }

            touching = false
            pressTarget = 0f

            val v = vt.calculateVelocity()
            val speed = hypot(v.x, v.y)
            if (speed > 250f) {
                // launch the touch blob into the ambient pool, oldest floater gets replaced
                val launchVel = Offset(
                    (v.x / size.width) * aspect * 0.9f,
                    (v.y / size.height) * 0.9f
                )
                val replaced = floaters.removeAt(0)
                floaters.add(
                    Blob(
                        pos = touchSmNdc,
                        vel = launchVel,
                        baseRadius = replaced.baseRadius,
                        phase = Random.nextFloat() * 6.28f
                    )
                )
            } else {
                touchSmNdc = Offset(10f, 10f)
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
        aspect = size.width / size.height

        shader.setFloatUniform("uTime", timeSec)

        val slots = listOf("uPos0" to 0, "uPos1" to 1, "uPos2" to 2, "uPos3" to 3)
        for ((name, i) in slots) {
            val b = floaters.getOrNull(i)
            if (b != null) {
                val pulse = 1f + 0.08f * sin(timeSec * 1.4f + b.phase)
                shader.setFloatUniform(name, b.pos.x, b.pos.y)
                shader.setFloatUniform("uRad$i", b.baseRadius * pulse)
            }
        }

        val touchRadius = 0.24f * (0.3f + 0.7f * press)
        shader.setFloatUniform("uPosT", touchSmNdc.x, touchSmNdc.y)
        shader.setFloatUniform("uRadT", touchRadius)

        drawRect(brush = brush)
    }
}

private fun androidx.compose.ui.unit.IntSize.toSize(): Size = Size(width.toFloat(), height.toFloat())
