package com.example.android3dapp

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.filament.IndirectLight
import com.google.android.filament.Skybox
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WarshipFilament"

        init {
            Utils.init()
        }
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var modelViewer: ModelViewer

    private val choreographer = Choreographer.getInstance()
    private var isRockingEnabled = true
    private var startTimeNanos: Long = 0L
    private var isModelTransformed = false
    private var baseUnitCubeTransform = FloatArray(16)
    private var currentYawAngle = 0.0f
    private var currentHeave = 0.0f

    enum class LightingMode { DAY, SUNSET, NIGHT }
    private var currentLightingMode = LightingMode.DAY

    private var daySkybox: Skybox? = null
    private var sunsetSkybox: Skybox? = null
    private var nightSkybox: Skybox? = null

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            choreographer.postFrameCallback(this)

            if (startTimeNanos == 0L) {
                startTimeNanos = frameTimeNanos
            }

            val elapsedSeconds = (frameTimeNanos - startTimeNanos) / 1_000_000_000.0

            checkAndApplyUnitCubeTransform()

            if (isRockingEnabled) {
                currentHeave = (sin(elapsedSeconds * 1.5) * 0.05).toFloat()
            } else {
                currentHeave = 0.0f
            }

            updateRootTransform()
            modelViewer.render(frameTimeNanos)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.surface_view)
        modelViewer = ModelViewer(surfaceView)

        setupTouchListener()
        loadWarshipModel()
        setupLightingAndSkyboxes()
        setupUIControls()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener() {
        surfaceView.setOnTouchListener(modelViewer)
    }

    private fun loadWarshipModel() {
        try {
            val bytes = assets.open("models/warship.glb").use { it.readBytes() }

            // MUST use allocateDirect for native C++ gltfio JNI buffer compatibility!
            val directBuffer = ByteBuffer.allocateDirect(bytes.size)
            directBuffer.put(bytes)
            directBuffer.flip()

            modelViewer.loadModelGlb(directBuffer)

            val entityCount = modelViewer.asset?.entities?.size ?: 0
            Log.d(TAG, "Loaded warship.glb with $entityCount entities.")
            if (entityCount == 0) {
                Toast.makeText(this, "Warning: 0 entities loaded from model", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading 3D Warship model", e)
            Toast.makeText(this, "Error loading 3D Warship model: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkAndApplyUnitCubeTransform() {
        if (isModelTransformed) return
        val asset = modelViewer.asset ?: return
        val halfExtent = asset.boundingBox.halfExtent
        val maxExtent = maxOf(halfExtent[0], halfExtent[1], halfExtent[2])

        if (maxExtent > 0.001f) {
            modelViewer.transformToUnitCube()
            isModelTransformed = true

            val tm = modelViewer.engine.transformManager
            val instance = tm.getInstance(asset.root)
            if (instance != 0) {
                tm.getTransform(instance, baseUnitCubeTransform)
            }
            Log.d(TAG, "Unit cube transform applied successfully! maxExtent=$maxExtent")
        }
    }

    private fun updateRootTransform() {
        if (!isModelTransformed) return
        val asset = modelViewer.asset ?: return
        val tm = modelViewer.engine.transformManager
        val instance = tm.getInstance(asset.root)
        if (instance != 0) {
            val radY = Math.toRadians(currentYawAngle.toDouble()).toFloat()
            val cosY = cos(radY)
            val sinY = sin(radY)

            val rotAndHeave = floatArrayOf(
                cosY, 0f, -sinY, 0f,
                0f, 1f, 0f, 0f,
                sinY, 0f, cosY, 0f,
                0f, currentHeave, 0f, 1f,
            )

            val finalTransform = multiply4x4(baseUnitCubeTransform, rotAndHeave)
            tm.setTransform(instance, finalTransform)
        }
    }

    private fun multiply4x4(a: FloatArray, b: FloatArray): FloatArray {
        val result = FloatArray(16)
        for (row in 0..3) {
            for (col in 0..3) {
                var sum = 0f
                for (i in 0..3) {
                    sum += a[row + i * 4] * b[i + col * 4]
                }
                result[row + col * 4] = sum
            }
        }
        return result
    }

    private fun setupLightingAndSkyboxes() {
        val engine = modelViewer.engine
        val scene = modelViewer.scene

        // Create Indirect Light (Ambient IBL) so PBR materials are bright and visible from all angles
        val indirectLight = IndirectLight.Builder()
            .irradiance(1, floatArrayOf(0.7f, 0.7f, 0.7f))
            .intensity(35000.0f)
            .build(engine)
        scene.indirectLight = indirectLight

        // Adjust Sun directional light angle & intensity
        val lightManager = engine.lightManager
        val sunInstance = lightManager.getInstance(modelViewer.light)
        if (sunInstance != 0) {
            lightManager.setDirection(sunInstance, 0.6f, -0.8f, -0.4f)
            lightManager.setIntensity(sunInstance, 120000.0f)
        }

        // Day Sky (Caribbean sea daylight - clear cyan blue)
        daySkybox = Skybox.Builder()
            .color(0.45f, 0.70f, 0.92f, 1.0f)
            .build(engine)

        // Sunset Sky (Warm golden orange)
        sunsetSkybox = Skybox.Builder()
            .color(0.92f, 0.45f, 0.20f, 1.0f)
            .build(engine)

        // Night Sky (Deep nautical midnight navy)
        nightSkybox = Skybox.Builder()
            .color(0.04f, 0.08f, 0.18f, 1.0f)
            .build(engine)

        setLightingMode(LightingMode.DAY)
    }

    private fun setLightingMode(mode: LightingMode) {
        currentLightingMode = mode
        val scene = modelViewer.scene
        when (mode) {
            LightingMode.DAY -> scene.skybox = daySkybox
            LightingMode.SUNSET -> scene.skybox = sunsetSkybox
            LightingMode.NIGHT -> scene.skybox = nightSkybox
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setupUIControls() {
        findViewById<MaterialButton>(R.id.btn_cam_broadside).setOnClickListener {
            currentYawAngle = 90.0f
        }

        findViewById<MaterialButton>(R.id.btn_cam_stern).setOnClickListener {
            currentYawAngle = 180.0f
        }

        findViewById<MaterialButton>(R.id.btn_cam_deck).setOnClickListener {
            currentYawAngle = 45.0f
        }

        val btnLighting = findViewById<MaterialButton>(R.id.btn_mode_lighting)
        btnLighting.setOnClickListener {
            val nextMode = when (currentLightingMode) {
                LightingMode.DAY -> LightingMode.SUNSET
                LightingMode.SUNSET -> LightingMode.NIGHT
                LightingMode.NIGHT -> LightingMode.DAY
            }
            setLightingMode(nextMode)
            btnLighting.text = "Sky: ${nextMode.name}"
        }

        val btnRocking = findViewById<MaterialButton>(R.id.btn_toggle_rocking)
        btnRocking.setOnClickListener {
            isRockingEnabled = !isRockingEnabled
            btnRocking.text = if (isRockingEnabled) "Waves: ON" else "Waves: OFF"
        }

        findViewById<MaterialButton>(R.id.btn_fire_cannons).setOnClickListener {
            fireCannonBroadside()
        }
    }

    private fun fireCannonBroadside() {
        Toast.makeText(this, "BOOM! 17th Century Cannon Broadside Fired!", Toast.LENGTH_SHORT).show()

        thread {
            try {
                val sampleRate = 22050
                val durationSec = 1.2
                val numSamples = (durationSec * sampleRate).toInt()
                val buffer = ShortArray(numSamples)

                for (i in 0 until numSamples) {
                    val time = i.toDouble() / sampleRate
                    val freq = 45.0 + (35.0 * exp(-time * 5.0))
                    val sine = sin(2.0 * Math.PI * freq * time)
                    val noise = ((Math.random() * 2.0) - 1.0) * exp(-time * 4.0)
                    val envelope = exp(-time * 3.5)

                    val sample = (((sine * 0.6) + (noise * 0.4)) * envelope * 32767).toInt()
                    buffer[i] = sample.coerceIn(-32768, 32767).toShort()
                }

                val audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(numSamples * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                audioTrack.write(buffer, 0, numSamples)
                audioTrack.play()
                Thread.sleep((durationSec * 1000).toLong())
                audioTrack.release()
            } catch (e: Exception) {
                Log.e(TAG, "AudioTrack error", e)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        choreographer.postFrameCallback(frameCallback)
    }

    override fun onPause() {
        super.onPause()
        choreographer.removeFrameCallback(frameCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        choreographer.removeFrameCallback(frameCallback)
        modelViewer.destroy()
    }
}
