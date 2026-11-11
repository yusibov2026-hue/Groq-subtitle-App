package com.ejemplo.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class ActividadPrincipal : Activity() {

    companion object {
        private const val REQUEST_PICK_MEDIA = 101
        private const val PREFS_NAME = "groq_subtitle_prefs"
        private const val KEY_GROQ_API_KEY = "groq_api_key"
    }

    private lateinit var etApiKey: EditText
    private lateinit var btnGuardarKey: Button
    private lateinit var tvKeyStatus: TextView

    private lateinit var btnSeleccionarArchivo: Button
    private lateinit var tvArchivoInfo: TextView

    private lateinit var spWhisperModelo: Spinner
    private lateinit var spIdiomaOrigen: Spinner
    private lateinit var spIdiomaDestino: Spinner
    private lateinit var spModeloTraduccion: Spinner

    private lateinit var btnGenerar: Button
    private lateinit var progresoBarra: ProgressBar
    private lateinit var tvEstado: TextView

    private lateinit var seccionVistaPrevia: LinearLayout
    private lateinit var videoView: VideoView
    private lateinit var tvSubtituloOverlay: TextView
    private lateinit var btnPlayPause: Button
    private lateinit var btnPantallaCompleta: Button
    private lateinit var contenedorPrincipal: LinearLayout
    private lateinit var tvTituloReproductor: TextView
    private lateinit var contenedorVideo: FrameLayout

    private var isFullscreen = false
    private val hiddenViews = mutableListOf<Pair<View, Int>>()

    private lateinit var seccionResultado: LinearLayout
    private lateinit var tvTituloSegmentos: TextView
    private lateinit var btnExportarSrt: Button
    private lateinit var btnCopiarSrt: Button
    private lateinit var btnCompartir: Button
    private lateinit var contenedorSegmentos: LinearLayout

    private var selectedMediaUri: Uri? = null
    private var selectedFileName: String? = null
    private var currentSegments: MutableList<SubtitleSegment> = mutableListOf()

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isSubSyncActive = false

    private val languageCodes = listOf(
        "auto" to "Автоопределение",
        "ru" to "Русский (ru)",
        "en" to "English (en)",
        "es" to "Español (es)",
        "de" to "Deutsch (de)",
        "fr" to "Français (fr)",
        "zh" to "中文 (zh)",
        "ja" to "日本語 (ja)",
        "ar" to "العربية (ar)",
        "tr" to "Türkçe (tr)",
        "it" to "Italiano (it)",
        "pt" to "Português (pt)"
    )

    private val targetLanguages = listOf(
        "none" to "Без перевода (оригинал)",
        "ru" to "Русский (ru)",
        "en" to "English (en)",
        "es" to "Español (es)",
        "de" to "Deutsch (de)",
        "fr" to "Français (fr)",
        "zh" to "中文 (zh)",
        "ja" to "日本語 (ja)",
        "ar" to "العربية (ar)",
        "tr" to "Türkçe (tr)",
        "it" to "Italiano (it)",
        "pt" to "Português (pt)"
    )

    private val whisperModels = listOf(
        "whisper-large-v3-turbo",
        "whisper-large-v3"
    )

    private val llmModels = listOf(
        "llama-3.3-70b-versatile",
        "llama-3.1-8b-instant",
        "qwen-2.5-32b"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.actividad_principal)

        initViews()
        setupSpinners()
        loadSavedApiKey()
        setupListeners()
    }

    private fun initViews() {
        etApiKey = findViewById(R.id.et_api_key)
        btnGuardarKey = findViewById(R.id.btn_guardar_key)
        tvKeyStatus = findViewById(R.id.tv_key_status)

        btnSeleccionarArchivo = findViewById(R.id.btn_seleccionar_archivo)
        tvArchivoInfo = findViewById(R.id.tv_archivo_info)

        spWhisperModelo = findViewById(R.id.sp_whisper_modelo)
        spIdiomaOrigen = findViewById(R.id.sp_idioma_origen)
        spIdiomaDestino = findViewById(R.id.sp_idioma_destino)
        spModeloTraduccion = findViewById(R.id.sp_modelo_traduccion)

        btnGenerar = findViewById(R.id.btn_generar)
        progresoBarra = findViewById(R.id.progreso_barra)
        tvEstado = findViewById(R.id.tv_estado)

        seccionVistaPrevia = findViewById(R.id.seccion_vista_previa)
        videoView = findViewById(R.id.video_view)
        tvSubtituloOverlay = findViewById(R.id.tv_subtitulo_overlay)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        btnPantallaCompleta = findViewById(R.id.btn_pantalla_completa)
        contenedorPrincipal = findViewById(R.id.contenedor_principal)
        tvTituloReproductor = findViewById(R.id.tv_titulo_reproductor)
        contenedorVideo = findViewById(R.id.contenedor_video)

        seccionResultado = findViewById(R.id.seccion_resultado)
        tvTituloSegmentos = findViewById(R.id.tv_titulo_segmentos)
        btnExportarSrt = findViewById(R.id.btn_exportar_srt)
        btnCopiarSrt = findViewById(R.id.btn_copiar_srt)
        btnCompartir = findViewById(R.id.btn_compartir)
        contenedorSegmentos = findViewById(R.id.contenedor_segmentos)
    }

    private fun setupSpinners() {
        spWhisperModelo.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            whisperModels
        )

        spIdiomaOrigen.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            languageCodes.map { it.second }
        )

        spIdiomaDestino.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            targetLanguages.map { it.second }
        )

        spModeloTraduccion.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            llmModels
        )
    }

    private fun loadSavedApiKey() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedKey = prefs.getString(KEY_GROQ_API_KEY, "") ?: ""
        if (savedKey.isNotBlank()) {
            etApiKey.setText(savedKey)
            tvKeyStatus.text = getString(R.string.key_guardada)
            tvKeyStatus.setTextColor(getColor(R.color.verde_exito))
        } else {
            tvKeyStatus.text = getString(R.string.key_no_configurada)
            tvKeyStatus.setTextColor(getColor(R.color.texto_terciario))
        }
    }

    private fun setupListeners() {
        btnGuardarKey.setOnClickListener {
            val key = etApiKey.text.toString().trim()
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_GROQ_API_KEY, key).apply()
            if (key.isNotBlank()) {
                tvKeyStatus.text = getString(R.string.key_guardada)
                tvKeyStatus.setTextColor(getColor(R.color.verde_exito))
                Toast.makeText(this, "API ключ сохранён", Toast.LENGTH_SHORT).show()
            } else {
                tvKeyStatus.text = getString(R.string.key_no_configurada)
                tvKeyStatus.setTextColor(getColor(R.color.texto_terciario))
            }
        }

        btnSeleccionarArchivo.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*"))
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(Intent.createChooser(intent, "Выберите видео или аудио"), REQUEST_PICK_MEDIA)
        }

        btnGenerar.setOnClickListener {
            startSubtitleGeneration()
        }

        btnPlayPause.setOnClickListener {
            if (videoView.isPlaying) {
                videoView.pause()
                btnPlayPause.text = getString(R.string.btn_reproducir)
            } else {
                videoView.start()
                btnPlayPause.text = getString(R.string.btn_pausar)
                startSubtitleSyncLoop()
            }
        }

        btnPantallaCompleta.setOnClickListener {
            if (isFullscreen) {
                exitFullscreen()
            } else {
                enterFullscreen()
            }
        }

        btnCopiarSrt.setOnClickListener {
            if (currentSegments.isEmpty()) return@setOnClickListener
            val srt = SrtUtils.toSrt(currentSegments)
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Subtitles", srt))
            Toast.makeText(this, getString(R.string.copiado_al_portapapeles), Toast.LENGTH_SHORT).show()
        }

        btnCompartir.setOnClickListener {
            if (currentSegments.isEmpty()) return@setOnClickListener
            val srt = SrtUtils.toSrt(currentSegments)
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, srt)
                type = "text/plain"
            }
            startActivity(Intent.createChooser(sendIntent, "Поделиться субтитрами"))
        }

        btnExportarSrt.setOnClickListener {
            exportSrtToFile()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_MEDIA && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            selectedMediaUri = uri
            queryFileInfo(uri)
            setupVideoPreview(uri)
        }
    }

    private fun queryFileInfo(uri: Uri) {
        var displayName = "Медиафайл"
        var sizeBytes = 0L

        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) displayName = cursor.getString(nameIndex)
                if (sizeIndex != -1) sizeBytes = cursor.getLong(sizeIndex)
            }
        }

        selectedFileName = displayName
        val sizeMb = sizeBytes / (1024.0 * 1024.0)
        tvArchivoInfo.text = "$displayName (${String.format("%.2f", sizeMb)} МБ)"
        tvArchivoInfo.setTextColor(getColor(R.color.texto_principal))
    }

    private fun setupVideoPreview(uri: Uri) {
        val type = contentResolver.getType(uri) ?: ""
        if (type.startsWith("video/") || selectedFileName?.endsWith(".mp4", ignoreCase = true) == true) {
            seccionVistaPrevia.visibility = View.VISIBLE
            videoView.setVideoURI(uri)
            videoView.setOnPreparedListener { mp ->
                mp.isLooping = true
            }
        } else {
            seccionVistaPrevia.visibility = View.GONE
        }
    }

    private fun enterFullscreen() {
        if (isFullscreen) return
        isFullscreen = true

        hiddenViews.clear()
        for (index in 0 until contenedorPrincipal.childCount) {
            val child = contenedorPrincipal.getChildAt(index)
            if (child === seccionVistaPrevia) continue
            hiddenViews.add(child to child.visibility)
            child.visibility = View.GONE
        }

        contenedorPrincipal.setPadding(0, 0, 0, 0)
        val contentParams = contenedorPrincipal.layoutParams
        contentParams.height = ViewGroup.LayoutParams.MATCH_PARENT
        contenedorPrincipal.layoutParams = contentParams

        tvTituloReproductor.visibility = View.GONE

        val previewParams = seccionVistaPrevia.layoutParams as LinearLayout.LayoutParams
        previewParams.height = 0
        previewParams.weight = 1f
        previewParams.topMargin = 0
        previewParams.bottomMargin = 0
        previewParams.leftMargin = 0
        previewParams.rightMargin = 0
        seccionVistaPrevia.layoutParams = previewParams
        seccionVistaPrevia.setPadding(0, 0, 0, 0)
        seccionVistaPrevia.setBackgroundColor(Color.BLACK)

        val videoParams = contenedorVideo.layoutParams as LinearLayout.LayoutParams
        videoParams.height = 0
        videoParams.weight = 1f
        videoParams.topMargin = 0
        contenedorVideo.layoutParams = videoParams
        contenedorVideo.setBackgroundColor(Color.BLACK)

        btnPantallaCompleta.text = getString(R.string.btn_salir_pantalla_completa)
        applyImmersiveMode(true)
    }

    private fun exitFullscreen() {
        if (!isFullscreen) return
        isFullscreen = false

        for ((view, visibility) in hiddenViews) {
            view.visibility = visibility
        }
        hiddenViews.clear()

        contenedorPrincipal.setPadding(dp(16), dp(16), dp(16), dp(16))
        val contentParams = contenedorPrincipal.layoutParams
        contentParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        contenedorPrincipal.layoutParams = contentParams

        tvTituloReproductor.visibility = View.VISIBLE

        val previewParams = seccionVistaPrevia.layoutParams as LinearLayout.LayoutParams
        previewParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        previewParams.weight = 0f
        previewParams.topMargin = dp(16)
        seccionVistaPrevia.layoutParams = previewParams
        seccionVistaPrevia.setPadding(dp(14), dp(14), dp(14), dp(14))
        seccionVistaPrevia.setBackgroundResource(R.drawable.fondo_tarjeta)

        val videoParams = contenedorVideo.layoutParams as LinearLayout.LayoutParams
        videoParams.height = dp(220)
        videoParams.weight = 0f
        videoParams.topMargin = dp(10)
        contenedorVideo.layoutParams = videoParams

        btnPantallaCompleta.text = getString(R.string.btn_pantalla_completa)
        applyImmersiveMode(false)
    }

    private fun applyImmersiveMode(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController
            if (enabled) {
                window.setDecorFitsSystemWindows(false)
                controller?.hide(WindowInsets.Type.systemBars())
                controller?.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                window.setDecorFitsSystemWindows(true)
                controller?.show(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (enabled) {
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            } else {
                View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (isFullscreen) {
            exitFullscreen()
        } else {
            super.onBackPressed()
        }
    }

    private fun startSubtitleGeneration() {
        val apiKey = etApiKey.text.toString().trim()
        if (apiKey.isBlank()) {
            Toast.makeText(this, getString(R.string.error_sin_api_key), Toast.LENGTH_SHORT).show()
            return
        }

        val mediaUri = selectedMediaUri
        if (mediaUri == null) {
            Toast.makeText(this, getString(R.string.error_sin_archivo), Toast.LENGTH_SHORT).show()
            return
        }

        val whisperModel = whisperModels[spWhisperModelo.selectedItemPosition]
        val sourceLangPair = languageCodes[spIdiomaOrigen.selectedItemPosition]
        val sourceLangCode = if (sourceLangPair.first == "auto") null else sourceLangPair.first

        val targetLangPair = targetLanguages[spIdiomaDestino.selectedItemPosition]
        val translateNeeded = targetLangPair.first != "none"
        val targetLangName = targetLangPair.second
        val llmModel = llmModels[spModeloTraduccion.selectedItemPosition]

        // UI state for processing
        btnGenerar.isEnabled = false
        progresoBarra.visibility = View.VISIBLE
        tvEstado.text = "Подготовка аудиодорожки..."

        executor.execute {
            val tempFiles = mutableListOf<File>()
            try {
                // Step 1: Extract audio, splitting large media into chunks under the Groq limit
                mainHandler.post { tvEstado.text = "Подготовка аудиодорожки..." }
                val chunks = AudioExtractor.prepareAudioChunks(this, mediaUri, selectedFileName)
                tempFiles.addAll(chunks.map { it.file })

                // Step 2: Transcribe every chunk and restore original timestamps
                val allSegments = mutableListOf<SubtitleSegment>()
                for ((index, chunk) in chunks.withIndex()) {
                    val partLabel = if (chunks.size > 1) {
                        "часть ${index + 1}/${chunks.size}"
                    } else {
                        "аудио"
                    }
                    mainHandler.post {
                        tvEstado.text = "Отправка в Groq Whisper ($whisperModel): $partLabel..."
                    }

                    val partSegments = GroqApiClient.transcribeAudio(
                        audioFile = chunk.file,
                        apiKey = apiKey,
                        whisperModel = whisperModel,
                        sourceLanguage = sourceLangCode
                    )

                    for (segment in partSegments) {
                        allSegments.add(
                            SubtitleSegment(
                                id = 0,
                                start = segment.start + chunk.startOffsetSeconds,
                                end = segment.end + chunk.startOffsetSeconds,
                                text = segment.text
                            )
                        )
                    }

                    // Free space as soon as this chunk has been uploaded.
                    if (index < chunks.size - 1) {
                        chunk.file.delete()
                    }
                }

                if (allSegments.isEmpty()) {
                    throw IllegalStateException("Речь не обнаружена или пустой ответ от Whisper")
                }

                val segments = allSegments.mapIndexed { index, segment ->
                    segment.copy(id = index)
                }

                // Step 3: Translate if requested
                val finalSegments = if (translateNeeded) {
                    mainHandler.post {
                        tvEstado.text = "Перевод (${targetLangPair.first}) через $llmModel..."
                    }
                    GroqApiClient.translateSegments(
                        segments = segments,
                        targetLanguageName = targetLangName,
                        llmModel = llmModel,
                        apiKey = apiKey
                    )
                } else {
                    segments
                }

                mainHandler.post {
                    currentSegments = finalSegments.toMutableList()
                    showSegmentsResult(currentSegments)
                    tvEstado.text = "✓ Готово! Сегментов: ${currentSegments.size}"
                    btnGenerar.isEnabled = true
                    progresoBarra.visibility = View.GONE
                }

            } catch (e: Exception) {
                mainHandler.post {
                    tvEstado.text = "Ошибка: ${e.message}"
                    btnGenerar.isEnabled = true
                    progresoBarra.visibility = View.GONE
                    Toast.makeText(this@ActividadPrincipal, e.message, Toast.LENGTH_LONG).show()
                }
            } finally {
                tempFiles.forEach { it.delete() }
            }
        }
    }

    private fun showSegmentsResult(segments: List<SubtitleSegment>) {
        seccionResultado.visibility = View.VISIBLE
        tvTituloSegmentos.text = "Субтитры (сегментов: ${segments.size})"
        contenedorSegmentos.removeAllViews()

        val inflater = LayoutInflater.from(this)
        for ((idx, segment) in segments.withIndex()) {
            val itemView = inflater.inflate(R.layout.item_segmento, contenedorSegmentos, false)
            val tvTime = itemView.findViewById<TextView>(R.id.tv_segmento_tiempo)
            val etText = itemView.findViewById<EditText>(R.id.et_segmento_texto)

            tvTime.text = "#${idx + 1}  ${segment.timeRangeLabel()}"
            etText.setText(segment.text)

            etText.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    segment.text = s?.toString() ?: ""
                }
                override fun afterTextChanged(s: Editable?) {}
            })

            contenedorSegmentos.addView(itemView)
        }

        if (seccionVistaPrevia.visibility == View.VISIBLE) {
            tvSubtituloOverlay.visibility = View.VISIBLE
            startSubtitleSyncLoop()
        }
    }

    private fun startSubtitleSyncLoop() {
        if (isSubSyncActive) return
        isSubSyncActive = true

        val syncRunnable = object : Runnable {
            override fun run() {
                if (!videoView.isPlaying) {
                    isSubSyncActive = false
                    return
                }

                val currentSec = videoView.currentPosition / 1000.0
                val activeSeg = currentSegments.firstOrNull { currentSec >= it.start && currentSec <= it.end }

                if (activeSeg != null && activeSeg.text.isNotBlank()) {
                    tvSubtituloOverlay.text = activeSeg.text
                    tvSubtituloOverlay.visibility = View.VISIBLE
                } else {
                    tvSubtituloOverlay.text = ""
                    tvSubtituloOverlay.visibility = View.GONE
                }

                mainHandler.postDelayed(this, 100)
            }
        }
        mainHandler.post(syncRunnable)
    }

    private fun exportSrtToFile() {
        if (currentSegments.isEmpty()) return
        val srtContent = SrtUtils.toSrt(currentSegments)
        val baseName = selectedFileName?.substringBeforeLast('.') ?: "subtitles"
        val outputName = "${baseName}_subtitles.srt"

        try {
            val exportDir = getExternalFilesDir(null) ?: filesDir
            val srtFile = File(exportDir, outputName)
            FileOutputStream(srtFile).use { fos ->
                fos.write(srtContent.toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(this, "${getString(R.string.srt_guardado_exito)}:\n${srtFile.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка сохранения: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isSubSyncActive = false
        executor.shutdown()
    }
}
