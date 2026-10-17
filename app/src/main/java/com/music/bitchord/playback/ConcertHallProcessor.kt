package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.playback.audio.AudioBlock
import com.music.bitchord.playback.audio.PcmBoundary
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * PlayBeat: "Concert hall" — a Schroeder reverb tuned for a large room.
 *
 * Four parallel damped comb filters (30–44 ms) build the decaying body of the
 * hall; two allpass filters in series smear the reflections so the tail does
 * not ring at one pitch. The right channel's delays run a touch longer than
 * the left's, which decorrelates the tail without costing anything — the same
 * trick real halls get for free from their asymmetric walls.
 *
 * Same deal as [SpatialAudioProcessor]: O(1) per sample, no FFT or
 * convolution, cheap enough not to measure on a phone CPU, and it
 * self-bypasses before reading a single sample when off — which is what keeps
 * [com.music.bitchord.playback.audio.DspChain] byte-identical with every
 * stage idle.
 *
 * The wet signal feeds the stages after it with their headroom intact: the
 * loudness limiter is what catches whatever the added tail pushes over.
 */
@UnstableApi
class ConcertHallProcessor : BaseAudioProcessor() {

    @Volatile
    var enabled: Boolean = false

    /**
     * PlayBeat: intensidad de la sala, 0..1, regulable desde la pantalla del
     * equalizador. Escala el nivel del reverb, cuanto se apaga la senal seca
     * y cuanta cola viva dejan los combs — todo junto, para que subir la
     * intensidad sea subir la sala entera y no solo su volumen.
     *
     * Por defecto bien presente: la primera version sonaba timida.
     */
    @Volatile
    var intensity: Float = DEFAULT_INTENSITY

    /** One-pole damping inside each comb; dulls the tail like a real room. */
    private val combDamping = 0.30f

    private val allpassFeedback = 0.7f

    /** Schroeder's stagger, scaled up for a hall rather than a room. */
    private val combDelaysMs = floatArrayOf(29.7f, 37.1f, 41.1f, 43.7f)

    /** The right wall is a little further away than the left one. */
    private val rightScale = 1.09f

    private val allpassDelaysMs = floatArrayOf(5.0f, 1.7f)

    private var sampleRate = 0
    private var channelCount = 0

    private var combL = Array(COMBS) { FloatArray(0) }
    private var combR = Array(COMBS) { FloatArray(0) }
    private var combIdxL = IntArray(COMBS)
    private var combIdxR = IntArray(COMBS)
    private var combStoreL = FloatArray(COMBS)
    private var combStoreR = FloatArray(COMBS)

    private var allpassL = Array(ALLPASSES) { FloatArray(0) }
    private var allpassR = Array(ALLPASSES) { FloatArray(0) }
    private var allpassIdxL = IntArray(ALLPASSES)
    private var allpassIdxR = IntArray(ALLPASSES)

    /** Lazily grown scratch for the 16-bit [queueInput] path; reused, never reallocated per call. */
    private var scratch: AudioBlock? = null

    /**
     * Configures the Float32 DSP engine for [sampleRate] and [channelCount].
     */
    fun configure(sampleRate: Int, channelCount: Int) {
        this.sampleRate = sampleRate
        this.channelCount = channelCount
        if (channelCount != 2 || sampleRate <= 0) {
            releaseState()
            return
        }
        for (i in 0 until COMBS) {
            val nL = (sampleRate * combDelaysMs[i] / 1000f).roundToInt().coerceAtLeast(2)
            val nR = (sampleRate * combDelaysMs[i] * rightScale / 1000f).roundToInt().coerceAtLeast(2)
            if (combL[i].size != nL) {
                combL[i] = FloatArray(nL)
                combR[i] = FloatArray(nR)
            }
        }
        for (i in 0 until ALLPASSES) {
            val nL = (sampleRate * allpassDelaysMs[i] / 1000f).roundToInt().coerceAtLeast(2)
            val nR = (sampleRate * allpassDelaysMs[i] * rightScale / 1000f).roundToInt().coerceAtLeast(2)
            if (allpassL[i].size != nL) {
                allpassL[i] = FloatArray(nL)
                allpassR[i] = FloatArray(nR)
            }
        }
        onFlush()
    }

    /**
     * Processes interleaved Float32 audio samples in [block] in-place.
     * Preserves dynamic headroom without clamping to [-1.0f, +1.0f].
     */
    fun process(block: AudioBlock) {
        if (!enabled || block.frameCount == 0) return
        if (block.channelCount != 2) return // The hall is written in stereo
        if (combL[0].isEmpty()) return

        // La sala entera escala con la intensidad: el nivel del reverb, el
        // realce de la senal seca y la longitud de la cola.
        val t = intensity.coerceIn(0f, 1f)
        val wetNorm = (0.35f + 0.80f * t) / COMBS
        val dry = 0.85f - 0.35f * t
        val feedback = 0.80f + 0.12f * t

        val frames = block.frameCount
        val samples = block.samples
        val idxL = combIdxL.copyOf()
        val idxR = combIdxR.copyOf()
        val aIdxL = allpassIdxL.copyOf()
        val aIdxR = allpassIdxR.copyOf()
        val storeL = combStoreL.copyOf()
        val storeR = combStoreR.copyOf()
        var idx = 0

        while (idx < frames * 2) {
            val dryL = samples[idx]
            val dryR = samples[idx + 1]

            var wetLeft = 0f
            var wetRight = 0f
            for (i in 0 until COMBS) {
                val l = combL[i]
                val r = combR[i]
                val il = idxL[i]
                val ir = idxR[i]

                val fl = l[il] * (1f - combDamping) + storeL[i] * combDamping
                val fr = r[ir] * (1f - combDamping) + storeR[i] * combDamping
                storeL[i] = fl
                storeR[i] = fr
                l[il] = dryL + fl * feedback
                r[ir] = dryR + fr * feedback
                idxL[i] = (il + 1) % l.size
                idxR[i] = (ir + 1) % r.size
                wetLeft += fl
                wetRight += fr
            }

            var xl = wetLeft * wetNorm
            var xr = wetRight * wetNorm
            for (i in 0 until ALLPASSES) {
                val aL = allpassL[i]
                val aR = allpassR[i]
                val ail = aIdxL[i]
                val air = aIdxR[i]

                val bufL = aL[ail]
                val bufR = aR[air]
                aL[ail] = xl + bufL * allpassFeedback
                aR[air] = xr + bufR * allpassFeedback
                aIdxL[i] = (ail + 1) % aL.size
                aIdxR[i] = (air + 1) % aR.size
                xl = bufL - xl
                xr = bufR - xr
            }

            // Headroom is preserved: no clamping to [-1.0f, +1.0f]
            samples[idx] = dryL * dry + xl
            samples[idx + 1] = dryR * dry + xr
            idx += 2
        }

        combIdxL = idxL
        combIdxR = idxR
        allpassIdxL = aIdxL
        allpassIdxR = aIdxR
        combStoreL = storeL
        combStoreR = storeR
    }

    /**
     * Stereo 16-bit only, for exactly the reasons [SpatialAudioProcessor]
     * states: a hall written as a left and a right wall has nothing to say to
     * a mono voice note, and bowing out with
     * [AudioProcessor.AudioFormat.NOT_SET] rather than throwing keeps every
     * other format playable.
     */
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        return inputAudioFormat
    }

    override fun onFlush() {
        for (i in 0 until COMBS) {
            combL[i].fill(0f)
            combR[i].fill(0f)
        }
        for (i in 0 until ALLPASSES) {
            allpassL[i].fill(0f)
            allpassR[i].fill(0f)
        }
        combIdxL.fill(0)
        combIdxR.fill(0)
        allpassIdxL.fill(0)
        allpassIdxR.fill(0)
        combStoreL.fill(0f)
        combStoreR.fill(0f)
    }

    override fun onReset() {
        releaseState()
        channelCount = 0
        sampleRate = 0
    }

    override fun queueInput(inputBuffer: java.nio.ByteBuffer) {
        val frameCount = inputBuffer.remaining() / BYTES_PER_FRAME
        if (frameCount == 0) return
        val outputBuffer = replaceOutputBuffer(frameCount * BYTES_PER_FRAME)

        if (!enabled) {
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        inputBuffer.order(ByteOrder.nativeOrder())
        outputBuffer.order(ByteOrder.nativeOrder())

        if (combL[0].isEmpty) {
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        val block = scratch?.takeIf { it.capacityFrames >= frameCount }
            ?: AudioBlock(2, frameCount).also { scratch = it }
        val invScale = 1.0f / 32768.0f
        var si = 0
        repeat(frameCount) {
            block.samples[si++] = inputBuffer.short.toFloat() * invScale
            block.samples[si++] = inputBuffer.short.toFloat() * invScale
        }
        block.setFrameCount(frameCount)
        process(block)

        var so = 0
        repeat(frameCount) {
            outputBuffer.putShort(PcmBoundary.clamp16FromFloat(block.samples[so++]))
            outputBuffer.putShort(PcmBoundary.clamp16FromFloat(block.samples[so++]))
        }
        outputBuffer.flip()
    }

    private fun releaseState() {
        combL = Array(COMBS) { FloatArray(0) }
        combR = Array(COMBS) { FloatArray(0) }
        allpassL = Array(ALLPASSES) { FloatArray(0) }
        allpassR = Array(ALLPASSES) { FloatArray(0) }
        combIdxL = IntArray(COMBS)
        combIdxR = IntArray(COMBS)
        allpassIdxL = IntArray(ALLPASSES)
        allpassIdxR = IntArray(ALLPASSES)
        combStoreL = FloatArray(COMBS)
        combStoreR = FloatArray(COMBS)
    }

    companion object {
        private const val COMBS = 4
        private const val ALLPASSES = 2
        private const val BYTES_PER_FRAME = 4
        const val DEFAULT_INTENSITY = 0.75f
    }
}
