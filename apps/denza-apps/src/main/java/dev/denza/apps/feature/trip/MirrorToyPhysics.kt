package dev.denza.apps.feature.trip

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The rag-doll toy hanging from the mirror on a coil spring (left slot of the
 * trip panel). A direct port of the approved prototype; every constant here is
 * tuned against it — do not adjust them independently.
 *
 * Fifteen points in 3D (x right, y down, z toward the viewer) form the spring,
 * the head, the torso and four limbs. The body is held together by distance
 * constraints relaxed over [RELAXATION_PASSES]; the limbs' constraints are
 * deliberately slack, which is what makes them flail. The hanger is NOT a
 * constraint: [RELAXATION_PASSES] passes drive even a soft position constraint
 * to ~1.0, so the spring would behave like a rigid rod and bumps would register
 * as a twitch. It is a real Hooke spring ([SPRING_K], damping [SPRING_C]),
 * applied as forces before integration, so the toy sags under its own weight,
 * bobs at about one hertz, and keeps ringing after a bump.
 *
 * Verlet integration runs at a fixed 240 Hz substep behind an accumulator so
 * the swing is identical at any frame rate. The damping values are calibrated
 * to the exact integrator convention `p += (p - prev) * DRAG + a * dt * dt *
 * ACCEL_SCALE` — a per-second-velocity damping term would be 4x too strong here
 * and diverges.
 *
 * Pure Kotlin, no Android imports; JVM-testable.
 */
class MirrorToyPhysics {

    private val posX = REST_X.copyOf()
    private val posY = REST_Y.copyOf()
    private val posZ = REST_Z.copyOf()
    private val prevX = REST_X.copyOf()
    private val prevY = REST_Y.copyOf()
    private val prevZ = REST_Z.copyOf()
    private val accX = DoubleArray(POINT_COUNT)
    private val accY = DoubleArray(POINT_COUNT)
    private val accZ = DoubleArray(POINT_COUNT)
    private var carry = 0.0

    init {
        // Pre-settle so the panel opens on a toy already hanging at its sagged
        // equilibrium instead of dropping into it (the prototype warms up too).
        repeat(WARMUP_SUBSTEPS) { substep(SUBSTEP, 0.0, 0.0, 0.0) }
    }

    fun x(i: Int): Double = posX[i]
    fun y(i: Int): Double = posY[i]
    fun z(i: Int): Double = posZ[i]

    /**
     * Advance the toy by one rendered frame under the cabin's apparent gravity.
     * [lateral]/[longitudinal]/[vertical] are the engine's physics channels in
     * m/s^2 (positive lateral = left turn, negative longitudinal = braking).
     */
    fun step(frameDtSec: Double, lateral: Double, longitudinal: Double, vertical: Double) {
        // The cap bounds both a stalled frame and any accumulated debt, so a
        // hiccup can never queue an unbounded burst of substeps.
        carry = min(carry + frameDtSec.coerceAtLeast(0.0), MAX_FRAME_SECONDS)
        var steps = 0
        while (carry >= SUBSTEP && steps < MAX_SUBSTEPS_PER_FRAME) {
            carry -= SUBSTEP
            steps++
            substep(SUBSTEP, lateral, longitudinal, vertical)
        }
    }

    /** Snap every point back to the rest pose with zero velocity. */
    fun reset() {
        for (i in 0 until POINT_COUNT) {
            posX[i] = REST_X[i]
            posY[i] = REST_Y[i]
            posZ[i] = REST_Z[i]
            prevX[i] = REST_X[i]
            prevY[i] = REST_Y[i]
            prevZ[i] = REST_Z[i]
        }
    }

    private fun substep(dt: Double, lateral: Double, longitudinal: Double, vertical: Double) {
        // Apparent gravity inside the cabin: real gravity plus the car's
        // inertia. Braking (longitudinal < 0) throws the toy forward (+z), a
        // left turn (lateral > 0) throws it right.
        val ax = lateral * LATERAL_GAIN
        val ay = GRAVITY + vertical * VERTICAL_GAIN
        val az = -longitudinal * LONGITUDINAL_GAIN
        for (i in 0 until POINT_COUNT) {
            accX[i] = ax
            accY[i] = ay
            accZ[i] = az
        }
        applySpring(HOOK, STR, dt)
        applySpring(STR, HEAD, dt)

        for (i in 0 until POINT_COUNT) {
            if (INV_MASS[i] == 0.0) continue
            val px = posX[i]
            val py = posY[i]
            val pz = posZ[i]
            posX[i] += (px - prevX[i]) * DRAG + accX[i] * dt * dt * ACCEL_SCALE
            posY[i] += (py - prevY[i]) * DRAG + accY[i] * dt * dt * ACCEL_SCALE
            posZ[i] += (pz - prevZ[i]) * DRAG + accZ[i] * dt * dt * ACCEL_SCALE
            prevX[i] = px
            prevY[i] = py
            prevZ[i] = pz
        }

        // A toy hanging on a spring slowly untwists to face the cabin again.
        // Weak on purpose: a manoeuvre still spins it freely, it just does not
        // settle showing its side.
        val midX = (posX[SHL] + posX[SHR]) / 2.0
        val midZ = (posZ[SHL] + posZ[SHR]) / 2.0
        val dxS = posX[SHL] - posX[SHR]
        val dzS = posZ[SHL] - posZ[SHR]
        val halfW = sqrt(dxS * dxS + dzS * dzS) / 2.0
        val fk = FACING_RATE * dt
        posX[SHL] += ((midX - halfW) - posX[SHL]) * fk
        posZ[SHL] += (midZ - posZ[SHL]) * fk
        posX[SHR] += ((midX + halfW) - posX[SHR]) * fk
        posZ[SHR] += (midZ - posZ[SHR]) * fk

        for (i in 0 until POINT_COUNT) {
            if (!posX[i].isFinite() || !posY[i].isFinite() || !posZ[i].isFinite()) {
                reset()
                return
            }
        }

        repeat(RELAXATION_PASSES) {
            for (l in LINK_A.indices) {
                val a = LINK_A[l]
                val b = LINK_B[l]
                val wa = INV_MASS[a]
                val wb = INV_MASS[b]
                val wSum = wa + wb
                if (wSum == 0.0) continue
                val dx = posX[b] - posX[a]
                val dy = posY[b] - posY[a]
                val dz = posZ[b] - posZ[a]
                val d = max(sqrt(dx * dx + dy * dy + dz * dz), 1e-6)
                val corr = (d - LINK_REST[l]) / d * LINK_STIFFNESS[l]
                val fa = corr * (wa / wSum)
                val fb = corr * (wb / wSum)
                posX[a] += dx * fa
                posY[a] += dy * fa
                posZ[a] += dz * fa
                posX[b] -= dx * fb
                posY[b] -= dy * fb
                posZ[b] -= dz * fb
            }
        }
    }

    /** One Hooke spring as accelerations on both ends, with axial damping. */
    private fun applySpring(a: Int, b: Int, dt: Double) {
        val dx = posX[b] - posX[a]
        val dy = posY[b] - posY[a]
        val dz = posZ[b] - posZ[a]
        val d = max(sqrt(dx * dx + dy * dy + dz * dz), 1e-6)
        val nx = dx / d
        val ny = dy / d
        val nz = dz / d
        // Stretch pulls the ends together, compression pushes them apart.
        var f = SPRING_K * (d - SPRING_REST)
        // Damping along the axis, from the verlet velocities.
        val relX = ((posX[b] - prevX[b]) - (posX[a] - prevX[a])) / dt
        val relY = ((posY[b] - prevY[b]) - (posY[a] - prevY[a])) / dt
        val relZ = ((posZ[b] - prevZ[b]) - (posZ[a] - prevZ[a])) / dt
        f += SPRING_C * (relX * nx + relY * ny + relZ * nz)
        val ia = INV_MASS[a]
        val ib = INV_MASS[b]
        accX[a] += nx * f * ia
        accY[a] += ny * f * ia
        accZ[a] += nz * f * ia
        accX[b] -= nx * f * ib
        accY[b] -= ny * f * ib
        accZ[b] -= nz * f * ib
    }

    companion object {
        // Point indices. Human proportions in units where the figure is about
        // 1.5 tall; distal parts are light on purpose — a real forearm and hand
        // carry little mass, which is exactly why they whip about the most.
        const val HOOK = 0 // mirror stalk, immovable
        const val STR = 1 // mid-spring
        const val HEAD = 2 // heavy head leads the swing
        const val NECK = 3
        const val SHL = 4
        const val SHR = 5
        const val ELL = 6 // upper arm 0.27
        const val ELR = 7
        const val HAL = 8 // forearm 0.21, wrist light
        const val HAR = 9
        const val HIP = 10
        const val KNL = 11 // thigh 0.34
        const val KNR = 12
        const val FTL = 13 // shin 0.34
        const val FTR = 14
        const val POINT_COUNT = 15

        private val REST_X = doubleArrayOf(
            0.0, 0.0, 0.0, 0.0, -0.15, 0.15, -0.16, 0.16, -0.17, 0.17, 0.0, -0.13, 0.13, -0.15, 0.15,
        )
        private val REST_Y = doubleArrayOf(
            -1.34, -1.06, -0.78, -0.54, -0.50, -0.50, -0.23, -0.23, -0.02, -0.02, -0.06, 0.28, 0.28, 0.62, 0.62,
        )
        private val REST_Z = DoubleArray(POINT_COUNT)
        private val INV_MASS = doubleArrayOf(
            0.0, 1 / 0.5, 1 / 1.7, 1 / 1.1, 1 / 0.7, 1 / 0.7, 1 / 0.34, 1 / 0.34,
            1 / 0.20, 1 / 0.20, 1 / 1.2, 1 / 0.55, 1 / 0.55, 1 / 0.4, 1 / 0.4,
        )

        // Distance constraints: stiff for the head/torso, slack for the limbs.
        private val LINK_A = intArrayOf(
            HEAD, NECK, NECK, NECK, SHL, SHL, SHR, HEAD, HEAD,
            SHL, ELL, SHR, ELR, HIP, KNL, HIP, KNR, KNL, FTL,
        )
        private val LINK_B = intArrayOf(
            NECK, HIP, SHL, SHR, SHR, HIP, HIP, SHL, SHR,
            ELL, HAL, ELR, HAR, KNL, FTL, KNR, FTR, KNR, FTR,
        )
        private val LINK_STIFFNESS = doubleArrayOf(
            0.9, 0.85, 0.9, 0.9, 0.8,
            0.6, 0.6, // torso keeps its shape
            0.25, 0.25, // a neck, not a free hinge
            0.34, 0.13, 0.34, 0.13, // shoulder loose, elbow almost free
            0.55, 0.22, 0.55, 0.22,
            0.16, 0.10, // just enough to stop the legs crossing through each other
        )
        private val LINK_REST = DoubleArray(LINK_A.size) { l ->
            val a = LINK_A[l]
            val b = LINK_B[l]
            val dx = REST_X[a] - REST_X[b]
            val dy = REST_Y[a] - REST_Y[b]
            val dz = REST_Z[a] - REST_Z[b]
            sqrt(dx * dx + dy * dy + dz * dz)
        }

        // Chosen so the toy sags a little under its own weight and bobs at
        // about one hertz — a real coil, lightly damped, so a bump keeps
        // ringing.
        private const val SPRING_K = 125.0
        private const val SPRING_C = 1.1
        private const val SPRING_REST = 0.28

        private const val SUBSTEP = 1.0 / 240.0
        private const val MAX_SUBSTEPS_PER_FRAME = 12
        private const val MAX_FRAME_SECONDS = 0.06
        private const val RELAXATION_PASSES = 14
        private const val DRAG = 0.9985
        private const val ACCEL_SCALE = 60.0
        private const val FACING_RATE = 1.6

        private const val GRAVITY = 9.8 * 0.12
        private const val LATERAL_GAIN = 0.34
        private const val LONGITUDINAL_GAIN = 0.34
        private const val VERTICAL_GAIN = 0.55

        /** 10 s of calm pre-settling at construction (the sag is ~0.16 units). */
        private const val WARMUP_SUBSTEPS = 2400
    }
}
