package com.zero.v2.services.realtime

/**
 * 软件回声消除器 — D53 Phase 1（Step 6.11）。
 *
 * far-end 参考信号 AEC：WebRTC 通话同族算法的极简实现（NLMS 自适应滤波 + NLP 残余抑制）。
 * 原理：远端参考 x（我们写入扬声器的 PCM——回声的已知源头）经自适应滤波器 W 重建回声
 * 估计 y=Σw·x，从近端（麦克风）信号 d 中减除得残差 e=d-y；NLMS 按最小化 e 迭代更新 W，
 * 滤波器尾长覆盖播放通路延迟（AudioTrack 缓冲 + 声学路径），延迟对齐由滤波器自学习。
 *
 * 替代 OEM VOICE_COMMUNICATION 黑盒链（实测同机型既压制说话期用户语音又漏回声），
 * 音源用 MIC 原始流：回声消除权回到我们自己手里，ASR 精度同步受益。
 *
 * 线程模型：feedFar（TTS IO 线程）与 process（音频循环线程）并发——synchronized 串行化。
 */
class SoftwareAec(
    private val sampleRate: Int = 16000,
    tailMs: Int = 240
) {
    companion object {
        /** NLMS 步长（保守值：双讲期不易发散） */
        private const val MU = 0.3f
        /** 功率正则项（防除零） */
        private const val EPS = 1e-10f
        /** 远端静默阈值（RMS²）——参考无声时冻结滤波器更新（防噪声误学习） */
        private const val FAR_MIN_POWER = 1e-6f
    }

    private val taps: Int = sampleRate * tailMs / 1000
    /** 自适应滤波器系数 */
    private val w = FloatArray(taps)
    /** 远端参考环形缓冲（最新在 head-1） */
    private val farBuf = FloatArray(taps)
    private var farHead = 0

    // ERLE 观测（近端功率/残差功率的运行平均，dB——AEC 质量量化指标）
    private var nearPowAvg = 0f
    private var errPowAvg = 0f
    private var sampleCount = 0L
    private var farSeen = 0L  // 累计远端参考样本数——收敛期估计
    /** 回声路径比学习（v6.15）：echo-only 块的 残差/参考 功率比最小值——即扬声器→空气→麦
     *  的传递增益估计；真人近讲必高于该值一个量级，据此做块级通过/抑制门 */
    private var echoRatioMin = Float.MAX_VALUE
    private var gateSmooth = 1f

    /** 喂远端参考（16kHz 单声道 float；任意采样率先经 resampleTo16k） */
    @Synchronized
    fun feedFar(far16k: FloatArray) {
        for (s in far16k) {
            farBuf[farHead] = s
            farHead = (farHead + 1) % taps
        }
        farSeen += far16k.size
    }

    /**
     * 滤波器是否已收敛（累计远端参考 ≥8s）——NLMS 自适应需要时间，
     * 收敛前的信号不可信（VAD barge 应退回二道防线，v6.14 守卫）
     */
    fun hasConverged(): Boolean = farSeen >= sampleRate * 8L

    /**
     * 处理近端（麦克风）帧 → 回声消除后的干净信号（原位语义返回新数组）。
     * NLMS 逐样本：y=Σw·x；e=d-y；w+=μ·e·x/Σx²。
     */
    @Synchronized
    fun process(near: FloatArray): FloatArray {
        val out = FloatArray(near.size)
        var farPower = 0f
        // 当前远端窗口功率（本块粗估，用于更新门控）
        for (k in 0 until taps step 8) farPower += farBuf[k] * farBuf[k]
        farPower = farPower * 8f / taps
        val adapt = farPower > FAR_MIN_POWER

        var nearAcc = 0f
        var errAcc = 0f
        for (i in near.indices) {
            val d = near[i]
            // 滤波：y = Σ w[k]·x[n-k]，x 从环形缓冲 head 往回取
            var y = 0f
            var xPow = EPS
            var idx = farHead - 1  // x[n-0] 为最近参考（含通路延迟由系数空间吸收）
            if (idx < 0) idx += taps
            for (k in 0 until taps) {
                val xv = farBuf[idx]
                y += w[k] * xv
                xPow += xv * xv
                if (--idx < 0) idx = taps - 1
            }
            val e = d - y
            // NLMS 更新（仅远端活跃期）
            if (adapt) {
                val g = (MU * e) / xPow
                idx = farHead - 1
                if (idx < 0) idx += taps
                for (k in 0 until taps) {
                    w[k] += g * farBuf[idx]
                    if (--idx < 0) idx = taps - 1
                }
            }
            out[i] = e
            nearAcc += d * d
            errAcc += e * e
        }
        // v6.15 NLP：回声路径比学习门（块级）——取代逐样本软抑制（线性滤波消不掉的
        // 非线性回声残差由此兜底）。物理依据：扬声器→空气→麦有固定传递增益，echo-only
        // 块的 残差/参考 比即该增益下限；真人近讲必高于它一个量级。
        if (near.isNotEmpty()) {
            val blockRatio = (errAcc / near.size + EPS) / (farPower + EPS)
            if (farPower > FAR_MIN_POWER) {
                // 学习回声比下限（echo-only 块取更小值；缓慢上漂容忍音量变化）
                if (blockRatio < echoRatioMin) echoRatioMin = blockRatio
                else if (echoRatioMin < Float.MAX_VALUE)
                    echoRatioMin = echoRatioMin * 0.9997f + blockRatio * 0.0003f
            }
            val gateTarget: Float
            if (farPower <= FAR_MIN_POWER || echoRatioMin == Float.MAX_VALUE) {
                gateTarget = 1f  // 无参考/未学习：不抑制（纯收音期）
            } else {
                // 残差显著高于回声预期（≥4×echoRatioMin×farPower）= 真人语音 → 直通；否则抑制
                gateTarget = if (errAcc / near.size > 4f * echoRatioMin * farPower) 1f else 0.15f
            }
            gateSmooth = gateSmooth * 0.7f + gateTarget * 0.3f
            if (gateSmooth < 0.999f) for (i in out.indices) out[i] *= gateSmooth
        }
        // ERLE 观测累积（指数平均，含 NLP 后）
        if (near.isNotEmpty()) {
            var gAcc = 0f
            for (v in out) gAcc += v * v
            val nP = nearAcc / near.size
            val eP = gAcc / out.size
            nearPowAvg = if (nearPowAvg == 0f) nP else nearPowAvg * 0.98f + nP * 0.02f
            errPowAvg = if (errPowAvg == 0f) eP else errPowAvg * 0.98f + eP * 0.02f
        }
        sampleCount += near.size
        return out
    }

    /** 回声消除量（dB，近端/残差功率比）——≥10dB 视为有效；无数据返回 0 */
    fun erleDb(): Float {
        if (errPowAvg <= EPS || nearPowAvg <= EPS) return 0f
        return (10f * kotlin.math.log10(nearPowAvg / errPowAvg))
    }

    /** 观测周期（每 N 样本报告一次 ERLE，约 5s） */
    fun shouldReport(): Boolean = sampleCount >= sampleRate * 5L

    fun resetReport() { sampleCount = 0 }

    /** 滤波器复位（会话重建/引擎切换时） */
    @Synchronized
    fun reset() {
        w.fill(0f); farBuf.fill(0f); farHead = 0
        nearPowAvg = 0f; errPowAvg = 0f
        farSeen = 0
        echoRatioMin = Float.MAX_VALUE
        gateSmooth = 1f
    }

    /** 任意采样率 → 16kHz 线性重采样（分块调用相位误差 <1 样本，对 AEC 尾长无感） */
    fun resampleTo16k(input: FloatArray, srcRate: Int): FloatArray {
        if (srcRate == sampleRate) return input
        val ratio = sampleRate.toFloat() / srcRate
        val outLen = (input.size * ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val src = i / ratio
            val i0 = src.toInt().coerceAtMost(input.size - 1)
            val i1 = (i0 + 1).coerceAtMost(input.size - 1)
            val frac = src - i0
            out[i] = input[i0] + (input[i1] - input[i0]) * frac
        }
        return out
    }

    /** PCM16 小端字节 → float [-1,1] */
    fun pcm16ToFloat(bytes: ByteArray): FloatArray {
        val out = FloatArray(bytes.size / 2)
        for (i in out.indices) {
            val s = ((bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
            out[i] = s / 32768f
        }
        return out
    }
}
