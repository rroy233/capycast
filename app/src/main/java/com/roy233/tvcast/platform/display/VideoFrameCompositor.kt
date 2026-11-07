package com.roy233.tvcast.platform.display

import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.hypot

/** 将解码器的 SurfaceTexture 帧绘入全屏 4K SurfaceView。 补偿和校准标记都在视频像素缓冲中完成，避免厂商视频图层忽略 View 矩形。 */
class VideoFrameCompositor(
    private val outputView: SurfaceView,
    private val onOutputFailure: () -> Unit = {},
) : SurfaceHolder.Callback {
    private val thread = HandlerThread("VideoFrameCompositor").apply { start() }
    private val handler = Handler(thread.looper)
    private val ready = CountDownLatch(1)
    private var inputSurface: Surface? = null
    private var inputTexture: SurfaceTexture? = null
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var pbuffer = EGL14.EGL_NO_SURFACE
    private var window = EGL14.EGL_NO_SURFACE
    private var config: EGLConfig? = null
    private var program = 0
    private var markerProgram = 0
    private var textureId = 0
    private var positionLocation = 0
    private var textureLocation = 0
    private var transformLocation = 0
    private var samplerLocation = 0
    private var markerPositionLocation = 0
    private var markerColorLocation = 0
    private var width = OUTPUT_WIDTH
    private var height = OUTPUT_HEIGHT
    private var hasFrame = false
    private var framePending = false
    private var released = false
    private var outputFailureReported = false
    private var quad = VideoQuad()
    private var contentAspect: Float? = null
    private var markerCorner = 0
    private val textureMatrix = FloatArray(16)

    init {
        outputView.setZOrderMediaOverlay(true)
        outputView.holder.setFormat(PixelFormat.RGBA_8888)
        outputView.holder.setFixedSize(OUTPUT_WIDTH, OUTPUT_HEIGHT)
        outputView.holder.addCallback(this)
        handler.post {
            try {
                initializeGl()
            } catch (error: Throwable) {
                LogHub.e(
                    TAG,
                    "4K compositor initialization failed: ${LogRedaction.reason(error)}",
                    error,
                    stack = true,
                )
                releaseGl()
            } finally {
                ready.countDown()
            }
        }
    }

    /** 仅由播放器输出绑定使用；初始化失败时返回 null，由宿主保留直出路径。 */
    fun decoderSurface(): Surface? {
        if (!ready.await(STARTUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            LogHub.e(TAG, "4K compositor initialization timed out")
            return null
        }
        return inputSurface
    }

    fun setQuad(value: VideoQuad) {
        handler.post {
            quad = value
            draw()
        }
    }

    fun setContentAspect(aspect: Float?) {
        handler.post {
            contentAspect = aspect?.takeIf { it.isFinite() && it > 0f }
            draw()
        }
    }

    /** 0 隐藏标记；1..4 高亮相应角。 */
    fun setMarkerCorner(corner: Int) {
        handler.post {
            markerCorner = corner.coerceIn(0, 4)
            draw()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        handler.post {
            if (!released && holder.surface.isValid && display != EGL14.EGL_NO_DISPLAY) {
                destroyWindow()
                window =
                    EGL14.eglCreateWindowSurface(
                        display,
                        checkNotNull(config),
                        holder.surface,
                        intArrayOf(EGL14.EGL_NONE),
                        0,
                    )
                if (window == EGL14.EGL_NO_SURFACE) {
                    LogHub.e(TAG, "4K output window creation failed egl=${EGL14.eglGetError()}")
                    reportOutputFailure()
                } else {
                    val size = IntArray(1)
                    EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, size, 0)
                    width = size[0]
                    EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, size, 0)
                    height = size[0]
                    LogHub.i(
                        TAG,
                        "output created buffer=${width}x$height view=${outputView.width}x${outputView.height}",
                    )
                    if (width != OUTPUT_WIDTH || height != OUTPUT_HEIGHT) {
                        LogHub.e(TAG, "4K output buffer mismatch actual=${width}x$height")
                        reportOutputFailure()
                        return@post
                    }
                    draw()
                }
            }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        handler.post { destroyWindow() }
    }

    fun release() {
        if (released) return
        released = true
        outputView.holder.removeCallback(this)
        handler.post {
            releaseGl()
            thread.quitSafely()
        }
    }

    private fun initializeGl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "EGL display unavailable" }
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0)) {
            "EGL initialize failed"
        }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes =
            intArrayOf(
                EGL14.EGL_RED_SIZE,
                8,
                EGL14.EGL_GREEN_SIZE,
                8,
                EGL14.EGL_BLUE_SIZE,
                8,
                EGL14.EGL_ALPHA_SIZE,
                8,
                EGL14.EGL_RENDERABLE_TYPE,
                EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE,
                EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
        check(
            EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0,
        ) {
            "EGL config unavailable"
        }
        config = checkNotNull(configs[0])
        context =
            EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0,
            )
        check(context != EGL14.EGL_NO_CONTEXT) { "EGL context unavailable" }
        pbuffer =
            EGL14.eglCreatePbufferSurface(
                display,
                config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
                0,
            )
        check(pbuffer != EGL14.EGL_NO_SURFACE && makeCurrent(pbuffer)) { "EGL pbuffer unavailable" }
        program = createProgram(VERTEX_SHADER.trimIndent(), FRAGMENT_SHADER.trimIndent())
        markerProgram =
            createProgram(MARKER_VERTEX_SHADER.trimIndent(), MARKER_FRAGMENT_SHADER.trimIndent())
        positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
        textureLocation = GLES20.glGetAttribLocation(program, "aTexCoord")
        transformLocation = GLES20.glGetUniformLocation(program, "uTexTransform")
        samplerLocation = GLES20.glGetUniformLocation(program, "uTexture")
        markerPositionLocation = GLES20.glGetAttribLocation(markerProgram, "aPosition")
        markerColorLocation = GLES20.glGetUniformLocation(markerProgram, "uColor")
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        check(textureId != 0) { "external texture unavailable" }
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        inputTexture =
            SurfaceTexture(textureId).also { texture ->
                texture.setDefaultBufferSize(OUTPUT_WIDTH, OUTPUT_HEIGHT)
                texture.setOnFrameAvailableListener(
                    {
                        if (!released) {
                            framePending = true
                            draw()
                        }
                    },
                    handler,
                )
                inputSurface = Surface(texture)
            }
        LogHub.i(TAG, "4K decoder input ready size=${OUTPUT_WIDTH}x$OUTPUT_HEIGHT")
    }

    private fun draw() {
        val target = window.takeIf { it != EGL14.EGL_NO_SURFACE } ?: pbuffer
        if (target == EGL14.EGL_NO_SURFACE || !makeCurrent(target)) return
        var frameUpdated = false
        if (framePending) {
            framePending = false
            try {
                inputTexture?.updateTexImage()
                inputTexture?.getTransformMatrix(textureMatrix)
                hasFrame = true
                frameUpdated = true
            } catch (error: RuntimeException) {
                LogHub.w(TAG, "decoder frame unavailable: ${LogRedaction.reason(error)}")
                hasFrame = false
            }
        }
        if (target != window || width <= 0 || height <= 0) return
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (hasFrame) drawVideo()
        if (hasFrame && markerCorner > 0) drawMarkers()
        if (frameUpdated) {
            inputTexture
                ?.timestamp
                ?.takeIf { it > 0L }
                ?.let {
                    EGLExt.eglPresentationTimeANDROID(display, window, it)
                }
        }
        if (!EGL14.eglSwapBuffers(display, window)) {
            LogHub.e(TAG, "4K output swap failed egl=${EGL14.eglGetError()}")
            reportOutputFailure()
        }
    }

    private fun reportOutputFailure() {
        if (outputFailureReported) return
        outputFailureReported = true
        outputView.post { if (!released) onOutputFailure() }
    }

    private fun drawVideo() {
        val safe = contentAspect?.let { quad.fitInside(it, width.toFloat() / height) } ?: quad
        val weights = safe.perspectiveWeights()
        val vertices =
            floatBuffer(
                floatArrayOf(
                    clipX(safe.bottomLeft.x),
                    clipY(safe.bottomLeft.y),
                    weights.bottomLeft,
                    0f,
                    0f,
                    clipX(safe.bottomRight.x),
                    clipY(safe.bottomRight.y),
                    weights.bottomRight,
                    1f,
                    0f,
                    clipX(safe.topLeft.x),
                    clipY(safe.topLeft.y),
                    weights.topLeft,
                    0f,
                    1f,
                    clipX(safe.topRight.x),
                    clipY(safe.topRight.y),
                    weights.topRight,
                    1f,
                    1f,
                ),
            )
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(samplerLocation, 0)
        GLES20.glUniformMatrix4fv(transformLocation, 1, false, textureMatrix, 0)
        vertices.position(0)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(positionLocation, 3, GLES20.GL_FLOAT, false, 20, vertices)
        vertices.position(3)
        GLES20.glEnableVertexAttribArray(textureLocation)
        GLES20.glVertexAttribPointer(textureLocation, 2, GLES20.GL_FLOAT, false, 20, vertices)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionLocation)
        GLES20.glDisableVertexAttribArray(textureLocation)
    }

    private fun drawMarkers() {
        val safe = quad
        GLES20.glUseProgram(markerProgram)
        GLES20.glEnableVertexAttribArray(markerPositionLocation)
        for (corner in 1..4) {
            val red = corner == markerCorner
            GLES20.glUniform4f(
                markerColorLocation,
                if (red) 1f else 0.78f,
                if (red) 0.12f else 0.78f,
                if (red) 0.12f else 0.78f,
                1f,
            )
            val current = safe.point(corner)
            val first = safe.point(if (corner == 4) 1 else corner + 1)
            val second = safe.point(if (corner == 1) 4 else corner - 1)
            drawEdgeMarker(current, first)
            drawEdgeMarker(current, second)
        }
        GLES20.glDisableVertexAttribArray(markerPositionLocation)
    }

    private fun drawEdgeMarker(start: VideoPoint, neighbor: VideoPoint) {
        val startX = start.x * width
        val startY = (1f - start.y) * height
        val dx = (neighbor.x - start.x) * width
        val dy = (start.y - neighbor.y) * height
        val length = hypot(dx, dy)
        if (length <= 0f) return
        val endX = startX + dx * (MARK_LENGTH / length)
        val endY = startY + dy * (MARK_LENGTH / length)
        val normalX = -dy / length * MARK_WIDTH / 2f
        val normalY = dx / length * MARK_WIDTH / 2f
        val vertices =
            floatBuffer(
                floatArrayOf(
                    pixelClipX(startX + normalX),
                    pixelClipY(startY + normalY),
                    pixelClipX(startX - normalX),
                    pixelClipY(startY - normalY),
                    pixelClipX(endX + normalX),
                    pixelClipY(endY + normalY),
                    pixelClipX(endX - normalX),
                    pixelClipY(endY - normalY),
                ),
            )
        GLES20.glVertexAttribPointer(markerPositionLocation, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun clipX(x: Float) = 2f * x - 1f

    private fun clipY(y: Float) = 1f - 2f * y

    private fun pixelClipX(x: Float) = 2f * x / width - 1f

    private fun pixelClipY(y: Float) = 2f * y / height - 1f

    private fun makeCurrent(surface: EGLSurface): Boolean =
        EGL14.eglMakeCurrent(display, surface, surface, context)

    private fun destroyWindow() {
        if (window == EGL14.EGL_NO_SURFACE) return
        if (pbuffer != EGL14.EGL_NO_SURFACE) makeCurrent(pbuffer)
        EGL14.eglDestroySurface(display, window)
        window = EGL14.EGL_NO_SURFACE
    }

    private fun releaseGl() {
        destroyWindow()
        inputTexture?.setOnFrameAvailableListener(null)
        inputSurface?.release()
        inputSurface = null
        inputTexture?.release()
        inputTexture = null
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (context != EGL14.EGL_NO_CONTEXT && pbuffer != EGL14.EGL_NO_SURFACE)
                makeCurrent(pbuffer)
            if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            if (program != 0) GLES20.glDeleteProgram(program)
            if (markerProgram != 0) GLES20.glDeleteProgram(markerProgram)
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        pbuffer = EGL14.EGL_NO_SURFACE
    }

    private fun createProgram(vertex: String, fragment: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertex)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragment)
        val linked = GLES20.glCreateProgram()
        GLES20.glAttachShader(linked, vertexShader)
        GLES20.glAttachShader(linked, fragmentShader)
        GLES20.glLinkProgram(linked)
        val status = IntArray(1)
        GLES20.glGetProgramiv(linked, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        check(status[0] != 0) { "video shader link failed: ${GLES20.glGetProgramInfoLog(linked)}" }
        return linked
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] != 0) {
            "video shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}"
        }
        return shader
    }

    private fun floatBuffer(values: FloatArray) =
        ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }

    companion object {
        private const val TAG = "VideoCompositor"
        const val OUTPUT_WIDTH = 3840
        const val OUTPUT_HEIGHT = 2160
        private const val STARTUP_TIMEOUT_MS = 3000L
        private const val MARK_LENGTH = 120
        private const val MARK_WIDTH = 12
        private const val VERTEX_SHADER =
            """
            attribute vec3 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexTransform;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition.xy * aPosition.z, 0.0, aPosition.z);
                vTexCoord = (uTexTransform * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """
        private const val FRAGMENT_SHADER =
            """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
        private const val MARKER_VERTEX_SHADER =
            """
            attribute vec2 aPosition;
            void main() { gl_Position = vec4(aPosition, 0.0, 1.0); }
        """
        private const val MARKER_FRAGMENT_SHADER =
            """
            precision mediump float;
            uniform vec4 uColor;
            void main() { gl_FragColor = uColor; }
        """
    }
}
