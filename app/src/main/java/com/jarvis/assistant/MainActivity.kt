package com.jarvis.assistant

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.Executor

class MainActivity : AppCompatActivity() {

    private lateinit var tvAppTitle: TextView
    private lateinit var tvActiveProfile: TextView
    private lateinit var btnSwitchProfile: ImageButton
    private lateinit var btnGhostMode: ImageButton
    private lateinit var btnWipeMemory: ImageButton
    private lateinit var btnLaunchFloating: ImageButton
    private lateinit var btnSettings: ImageButton

    private lateinit var tvStatus: TextView
    private lateinit var tvResponse: TextView
    private lateinit var tvUserTranscript: TextView
    private lateinit var hologramView: HologramView
    private lateinit var btnCopyResponse: Button

    // Tools & Actions
    private lateinit var btnCameraVision: Button
    private lateinit var btnScreenVision: Button
    private lateinit var btnReminders: Button
    private lateinit var btnTorch: Button
    private lateinit var btnStudyTimer: Button
    private lateinit var btnCalculator: Button
    private lateinit var btnNotes: Button
    private lateinit var btnToggleLoop: Button

    // Text Input & Voice Controls
    private lateinit var etUserInput: EditText
    private lateinit var btnSendText: ImageButton
    private lateinit var btnTalk: Button
    private lateinit var btnForceStop: Button

    private lateinit var prefs: SharedPreferences
    private lateinit var speechEngine: SpeechEngine
    private lateinit var geminiClient: GeminiClient
    private lateinit var cameraHelper: CameraHelper
    private lateinit var screenHelper: ScreenCaptureHelper
    private lateinit var reminderManager: ReminderManager

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var isContinuousLoop = false
    private var isGhostMode = false

    // Multi-turn conversation memory
    private val chatHistory = mutableListOf<ChatMessage>()
    private var lastVolumeDownTime = 0L

    private val REQ_OVERLAY = 301

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mpManager.getMediaProjection(result.resultCode, result.data!!)
            JarvisFloatingService.mediaProjectionInstance = projection
            captureAndAnalyzeScreen()
        } else {
            setStatus("SCREEN CAPTURE DENIED")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(JarvisFloatingService.PREFS_NAME, Context.MODE_PRIVATE)
        speechEngine = SpeechEngine(this)
        geminiClient = GeminiClient()
        cameraHelper = CameraHelper(this)
        screenHelper = ScreenCaptureHelper(this)
        reminderManager = ReminderManager(this)

        isContinuousLoop = prefs.getBoolean(JarvisFloatingService.KEY_CONTINUOUS_LOOP, false)
        isGhostMode = prefs.getBoolean(JarvisFloatingService.KEY_GHOST_MODE, false)

        initViews()
        initSpeechRecognizer()
        checkPermissions()
        refreshProfileHeader()

        lifecycleScope.launch {
            cameraHelper.initCamera(this@MainActivity)
        }

        // Check for API key on first startup
        val key = prefs.getString(JarvisFloatingService.KEY_API, "")
        if (key.isNullOrBlank()) {
            showSettingsDialog(isFirstTime = true)
        }

        // Handle camera request forwarded from overlay
        if (intent.getBooleanExtra("trigger_camera", false)) {
            captureAndAnalyzeCamera()
        }
    }

    private fun initViews() {
        tvAppTitle = findViewById(R.id.tvAppTitle)
        tvActiveProfile = findViewById(R.id.tvActiveProfile)
        btnSwitchProfile = findViewById(R.id.btnSwitchProfile)
        btnGhostMode = findViewById(R.id.btnGhostMode)
        btnWipeMemory = findViewById(R.id.btnWipeMemory)
        btnLaunchFloating = findViewById(R.id.btnLaunchFloating)
        btnSettings = findViewById(R.id.btnSettings)

        tvStatus = findViewById(R.id.tvStatus)
        tvResponse = findViewById(R.id.tvResponse)
        tvUserTranscript = findViewById(R.id.tvUserTranscript)
        hologramView = findViewById(R.id.hologramView)
        btnCopyResponse = findViewById(R.id.btnCopyResponse)

        btnCameraVision = findViewById(R.id.btnCameraVision)
        btnScreenVision = findViewById(R.id.btnScreenVision)
        btnReminders = findViewById(R.id.btnReminders)
        btnTorch = findViewById(R.id.btnTorch)
        btnStudyTimer = findViewById(R.id.btnStudyTimer)
        btnCalculator = findViewById(R.id.btnCalculator)
        btnNotes = findViewById(R.id.btnNotes)
        btnToggleLoop = findViewById(R.id.btnToggleLoop)

        etUserInput = findViewById(R.id.etUserInput)
        btnSendText = findViewById(R.id.btnSendText)
        btnTalk = findViewById(R.id.btnTalk)
        btnForceStop = findViewById(R.id.btnForceStop)

        // 1-Tap Quick Switch Profile
        btnSwitchProfile.setOnClickListener {
            toggleUserProfile()
        }

        // Ghost Mode Toggle
        btnGhostMode.setOnClickListener {
            isGhostMode = !isGhostMode
            prefs.edit().putBoolean(JarvisFloatingService.KEY_GHOST_MODE, isGhostMode).apply()
            updateGhostUi()
            Toast.makeText(this, if (isGhostMode) "🕶️ Ghost Mode Active (No history saved)" else "Normal Mode Active", Toast.LENGTH_SHORT).show()
        }

        // 1-Tap Wipe Memory
        btnWipeMemory.setOnClickListener {
            wipeMemory()
        }

        btnLaunchFloating.setOnClickListener {
            checkAndStartFloatingService()
        }

        btnSettings.setOnClickListener {
            verifyLockAndOpenSettings()
        }

        // Copy button
        btnCopyResponse.setOnClickListener {
            val text = tvResponse.text.toString().trim()
            if (text.isNotEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("JARVIS Response", text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Copied response to clipboard!", Toast.LENGTH_SHORT).show()
            }
        }

        // Tools
        btnCameraVision.setOnClickListener { captureAndAnalyzeCamera() }
        btnScreenVision.setOnClickListener { requestScreenCapture() }
        btnReminders.setOnClickListener { showReminderDialog() }
        btnTorch.setOnClickListener { toggleTorch() }
        btnStudyTimer.setOnClickListener { showStudyTimerDialog() }
        btnCalculator.setOnClickListener { OfflineToolsHelper.launchCalculator(this) }
        btnNotes.setOnClickListener { showNotesDialog() }

        // Continuous Loop Toggle
        btnToggleLoop.setOnClickListener {
            isContinuousLoop = !isContinuousLoop
            prefs.edit().putBoolean(JarvisFloatingService.KEY_CONTINUOUS_LOOP, isContinuousLoop).apply()
            updateLoopUi()
            Toast.makeText(this, if (isContinuousLoop) "Continuous Chat ON (Hands-free loop)" else "Continuous Chat OFF", Toast.LENGTH_SHORT).show()
            if (isContinuousLoop && !isListening && !speechEngine.isSpeaking()) {
                startListening()
            }
        }

        // Text & Talk Controls
        btnSendText.setOnClickListener { sendTypedMessage() }
        etUserInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                sendTypedMessage()
                true
            } else false
        }

        btnTalk.setOnClickListener { startListening() }
        btnForceStop.setOnClickListener { forceStop() }

        updateLoopUi()
        updateGhostUi()
    }

    private fun refreshProfileHeader() {
        val userName = prefs.getString(JarvisFloatingService.KEY_USER_NAME, "Aditya") ?: "Aditya"
        val assistantName = prefs.getString(JarvisFloatingService.KEY_ASSISTANT_NAME, "Jarvis") ?: "Jarvis"
        val relationshipStyle = prefs.getString(JarvisFloatingService.KEY_RELATIONSHIP, "girlfriend") ?: "girlfriend"

        tvAppTitle.text = assistantName.uppercase(Locale.ROOT)
        val styleLabel = if (relationshipStyle.equals("girlfriend", ignoreCase = true)) "GIRLFRIEND MODE" else "BEST FRIEND MODE"
        tvActiveProfile.text = "${userName.uppercase(Locale.ROOT)} • $styleLabel"
    }

    private fun toggleUserProfile() {
        val current = prefs.getString(JarvisFloatingService.KEY_USER_NAME, "Aditya") ?: "Aditya"
        if (current.equals("Aditya", ignoreCase = true)) {
            // Switch to Aditi
            prefs.edit()
                .putString(JarvisFloatingService.KEY_USER_NAME, "Aditi")
                .putString(JarvisFloatingService.KEY_ASSISTANT_NAME, "Jarvis")
                .putString(JarvisFloatingService.KEY_RELATIONSHIP, "bestfriend")
                .apply()
            Toast.makeText(this, "Switched to Aditi's Best-Friend Profile! 🌸", Toast.LENGTH_SHORT).show()
            speechEngine.speak("Hey Aditi! Ready whenever you are!")
        } else {
            // Switch to Aditya
            prefs.edit()
                .putString(JarvisFloatingService.KEY_USER_NAME, "Aditya")
                .putString(JarvisFloatingService.KEY_ASSISTANT_NAME, "Jarvis")
                .putString(JarvisFloatingService.KEY_RELATIONSHIP, "girlfriend")
                .apply()
            Toast.makeText(this, "Switched to Aditya's Companion Profile! 💙", Toast.LENGTH_SHORT).show()
            speechEngine.speak("Aap aa gaye Adi! Main bas tumhara hi wait kar rahi thi.")
        }
        refreshProfileHeader()
    }

    private fun updateLoopUi() {
        btnToggleLoop.setTextColor(if (isContinuousLoop) Color.parseColor("#00f0ff") else Color.parseColor("#789bbb"))
        btnToggleLoop.text = if (isContinuousLoop) "🔄 Loop: ON" else "🔄 Loop: OFF"
    }

    private fun updateGhostUi() {
        hologramView.setGhostMode(isGhostMode)
        btnGhostMode.setColorFilter(if (isGhostMode) Color.parseColor("#b388ff") else Color.parseColor("#789bbb"))
    }

    private fun toggleTorch() {
        val newState = OfflineToolsHelper.toggleTorch(this)
        btnTorch.text = if (newState) "🔦 ON" else "🔦 Torch"
        btnTorch.setTextColor(if (newState) Color.parseColor("#00ff88") else Color.parseColor("#ffb300"))
    }

    private fun wipeMemory() {
        chatHistory.clear()
        speechEngine.stop()
        val userName = prefs.getString(JarvisFloatingService.KEY_USER_NAME, "Aditya") ?: "Aditya"
        tvResponse.text = "Memory wiped clean, $userName! Standing by."
        setStatus("MEMORY WIPED", if (isGhostMode) HologramView.State.GHOST else HologramView.State.IDLE)
        speechEngine.speak("Memory wiped clean, $userName! What conversation? I don't remember a thing! 😉")
        Toast.makeText(this, "🧹 Memory wiped clean!", Toast.LENGTH_SHORT).show()
    }

    private fun sendTypedMessage() {
        val text = etUserInput.text.toString().trim()
        if (text.isNotEmpty()) {
            etUserInput.setText("")
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(etUserInput.windowToken, 0)

            tvUserTranscript.text = "\"$text\""
            processPrompt(text)
        }
    }

    private fun setStatus(status: String, state: HologramView.State = HologramView.State.IDLE) {
        tvStatus.text = status
        hologramView.setState(state)
    }

    // ==========================================
    // HARDWARE VOLUME DOWN DOUBLE-PRESS KILLSWITCH
    // ==========================================
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val now = System.currentTimeMillis()
            if (now - lastVolumeDownTime < 500) {
                forceStop()
                Toast.makeText(this, "🛑 Rapid Volume Killswitch Engaged", Toast.LENGTH_SHORT).show()
                return true
            }
            lastVolumeDownTime = now
        }
        return super.onKeyDown(keyCode, event)
    }

    // ==========================================
    // SPEECH RECOGNIZER & CONTINUOUS VOICE LOOP
    // ==========================================
    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    setStatus("LISTENING • SPEAK NOW", HologramView.State.LISTENING)
                    tvUserTranscript.text = "Listening to your voice..."
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    isListening = false
                    setStatus("PROCESSING NEURAL DATA...", HologramView.State.THINKING)
                }

                override fun onError(error: Int) {
                    isListening = false
                    setStatus("SYSTEM READY", if (isGhostMode) HologramView.State.GHOST else HologramView.State.IDLE)
                    if (isContinuousLoop && error == SpeechRecognizer.ERROR_NO_MATCH) {
                        lifecycleScope.launch {
                            delay(1200)
                            if (isContinuousLoop && !isListening && !speechEngine.isSpeaking()) {
                                startListening()
                            }
                        }
                    }
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                    tvUserTranscript.text = "\"$text\""
                    handleUserSpeech(text)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val pText = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                    tvUserTranscript.text = pText
                    val lower = pText.lowercase(Locale.ROOT)
                    if (lower.contains("stop") || lower.contains("chup") || lower.contains("quiet") || lower.contains("cancel") || lower.contains("ruk") || lower.contains("bas")) {
                        forceStop()
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startListening() {
        if (isListening) return
        speechEngine.stop()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        isListening = true
        setStatus("INITIALIZING MICROPHONE...", HologramView.State.LISTENING)
        try {
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {
            isListening = false
            setStatus("SYSTEM READY", if (isGhostMode) HologramView.State.GHOST else HologramView.State.IDLE)
        }
    }

    private fun forceStop() {
        isContinuousLoop = false
        prefs.edit().putBoolean(JarvisFloatingService.KEY_CONTINUOUS_LOOP, false).apply()
        updateLoopUi()

        speechEngine.stop()
        geminiClient.cancelActiveCall()
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (_: Exception) {}
        isListening = false
        setStatus("SYSTEM STOPPED • IDLE", if (isGhostMode) HologramView.State.GHOST else HologramView.State.IDLE)
        tvResponse.text = "Action cancelled. System standing by."
    }

    private fun handleUserSpeech(text: String) {
        val lower = text.lowercase(Locale.ROOT)

        if (lower == "stop" || lower == "chup" || lower == "quiet" || lower == "cancel" || lower == "ruk" || lower == "bas") {
            forceStop()
            return
        }

        if (lower.contains("wipe memory") || lower.contains("forget everything")) {
            wipeMemory()
            return
        }

        if (lower.contains("look at my screen") || lower.contains("see my screen")) {
            requestScreenCapture()
            return
        }

        if (lower.contains("look through camera") || lower.contains("what is this") || lower.contains("see this")) {
            captureAndAnalyzeCamera()
            return
        }

        if (lower.contains("torch on") || lower.contains("flashlight on")) {
            OfflineToolsHelper.toggleTorch(this, true)
            toggleTorch()
            speechEngine.speak("Flashlight turned on.")
            return
        }
        if (lower.contains("torch off") || lower.contains("flashlight off")) {
            OfflineToolsHelper.toggleTorch(this, false)
            toggleTorch()
            speechEngine.speak("Flashlight turned off.")
            return
        }

        processPrompt(text)
    }

    private fun processPrompt(prompt: String, imageBytes: ByteArray? = null) {
        val apiKey = prefs.getString(JarvisFloatingService.KEY_API, "") ?: ""
        val model = prefs.getString(JarvisFloatingService.KEY_MODEL, GeminiClient.DEFAULT_MODEL) ?: GeminiClient.DEFAULT_MODEL
        val userName = prefs.getString(JarvisFloatingService.KEY_USER_NAME, "Aditya") ?: "Aditya"
        val assistantName = prefs.getString(JarvisFloatingService.KEY_ASSISTANT_NAME, "Jarvis") ?: "Jarvis"
        val relationshipStyle = prefs.getString(JarvisFloatingService.KEY_RELATIONSHIP, "girlfriend") ?: "girlfriend"

        if (apiKey.isBlank()) {
            showSettingsDialog(isFirstTime = true)
            return
        }

        setStatus("NEURAL PROCESSING...", HologramView.State.THINKING)
        tvResponse.text = "Consulting quantum neural network..."

        lifecycleScope.launch {
            val result = geminiClient.generateContent(
                apiKey = apiKey,
                prompt = prompt,
                imageBytes = imageBytes,
                model = model,
                userName = userName,
                assistantName = assistantName,
                relationshipStyle = relationshipStyle,
                history = chatHistory
            )

            result.onSuccess { reply ->
                withContext(Dispatchers.Main) {
                    if (!isGhostMode) {
                        chatHistory.add(ChatMessage("user", prompt))
                        chatHistory.add(ChatMessage("model", reply))
                    }

                    tvResponse.text = reply
                    setStatus("VOCALIZING RESPONSE", HologramView.State.SPEAKING)
                    speechEngine.speak(
                        text = reply,
                        onDone = {
                            setStatus("SYSTEM READY", if (isGhostMode) HologramView.State.GHOST else HologramView.State.IDLE)
                            // Hands-free continuous loop
                            if (isContinuousLoop) {
                                lifecycleScope.launch {
                                    delay(400)
                                    if (isContinuousLoop && !isListening) {
                                        startListening()
                                    }
                                }
                            }
                        }
                    )
                }
            }.onFailure { err ->
                withContext(Dispatchers.Main) {
                    setStatus("ERROR DETECTED", if (isGhostMode) HologramView.State.GHOST else HologramView.State.IDLE)
                    tvResponse.text = "Error: ${err.message}"
                    speechEngine.speak("Network connection interrupted. Let's try again in a moment.")
                }
            }
        }
    }

    // ==========================================
    // VISION & TOOLS
    // ==========================================
    private fun captureAndAnalyzeCamera() {
        setStatus("SCANNING CAMERA SENSOR...", HologramView.State.THINKING)
        tvResponse.text = "Acquiring camera optics frame..."

        lifecycleScope.launch {
            val bytes = cameraHelper.capturePhotoBytes()
            if (bytes != null && bytes.isNotEmpty()) {
                processPrompt("Examine this camera image carefully and explain what you see with high detail.", bytes)
            } else {
                setStatus("CAMERA ERROR", if (isGhostMode) HologramView.State.GHOST else HologramView.State.IDLE)
                tvResponse.text = "Unable to acquire camera frame."
            }
        }
    }

    private fun requestScreenCapture() {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mpManager.createScreenCaptureIntent())
    }

    private fun captureAndAnalyzeScreen() {
        val projection = JarvisFloatingService.mediaProjectionInstance
        if (projection == null) {
            requestScreenCapture()
            return
        }

        setStatus("CAPTURING SCREEN BUFFER...", HologramView.State.THINKING)
        tvResponse.text = "Reading display framebuffer..."

        lifecycleScope.launch {
            val bytes = screenHelper.captureScreenBytes(projection)
            if (bytes != null && bytes.isNotEmpty()) {
                processPrompt("Analyze everything displayed on my screen and give me concise, actionable insights.", bytes)
            } else {
                setStatus("SCREEN CAPTURE FAILED", if (isGhostMode) HologramView.State.GHOST else HologramView.State.IDLE)
                tvResponse.text = "Unable to acquire screen pixels."
            }
        }
    }

    private fun showReminderDialog() {
        val input = EditText(this).apply {
            hint = "Reminder message (e.g. Call Rahul, Physics chapter 4)"
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        }

        val options = arrayOf("In 5 Minutes", "In 15 Minutes", "In 30 Minutes", "In 1 Hour", "In 2 Hours")
        var selectedMinutes = 5

        AlertDialog.Builder(this)
            .setTitle("Set JARVIS Reminder")
            .setView(input)
            .setSingleChoiceItems(options, 0) { _, which ->
                selectedMinutes = when (which) {
                    0 -> 5
                    1 -> 15
                    2 -> 30
                    3 -> 60
                    4 -> 120
                    else -> 5
                }
            }
            .setPositiveButton("SET ALARM") { _, _ ->
                val text = input.text.toString().trim().ifEmpty { "Important task reminder" }
                val triggerAt = System.currentTimeMillis() + (selectedMinutes * 60 * 1000)
                reminderManager.scheduleReminder(text, triggerAt)
                val msg = "Reminder set for $selectedMinutes minutes from now: $text"
                tvResponse.text = msg
                speechEngine.speak("Reminder logged! I'll alert you in $selectedMinutes minutes.")
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showStudyTimerDialog() {
        val options = arrayOf("25 Mins (Pomodoro)", "45 Mins (Deep Study)", "60 Mins (Board Exam Prep)")
        var duration = 25

        AlertDialog.Builder(this)
            .setTitle("📚 Study Focus Mode")
            .setSingleChoiceItems(options, 0) { _, which ->
                duration = when (which) {
                    0 -> 25
                    1 -> 45
                    2 -> 60
                    else -> 25
                }
            }
            .setPositiveButton("START STUDYING") { _, _ ->
                OfflineToolsHelper.startStudyMode(
                    context = this,
                    durationMinutes = duration,
                    onStage1Nudge = {
                        Toast.makeText(this, "📚 Focus check: Keep studying!", Toast.LENGTH_SHORT).show()
                    },
                    onStage2VoiceScold = {
                        val userName = prefs.getString(JarvisFloatingService.KEY_USER_NAME, "Aditya") ?: "Aditya"
                        speechEngine.speak("$userName! Phone rakh do aur padhai par dhyan do, exam aane wala hai!")
                    },
                    onCompleted = {
                        val userName = prefs.getString(JarvisFloatingService.KEY_USER_NAME, "Aditya") ?: "Aditya"
                        speechEngine.speak("Great job $userName! Study session completed successfully.")
                    }
                )
                tvResponse.text = "Study Focus Mode activated for $duration minutes."
                speechEngine.speak("Study Focus Mode started for $duration minutes. Let's do this!")
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showNotesDialog() {
        val notes = OfflineToolsHelper.getNotes(this)
        val input = EditText(this).apply {
            hint = "Type new note or thought..."
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        }

        AlertDialog.Builder(this)
            .setTitle("📝 Offline Scratchpad")
            .setMessage(notes)
            .setView(input)
            .setPositiveButton("SAVE NOTE") { _, _ ->
                val newNote = input.text.toString().trim()
                if (newNote.isNotEmpty()) {
                    OfflineToolsHelper.addNote(this, newNote)
                    Toast.makeText(this, "Note saved!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("CLEAR ALL") { _, _ ->
                OfflineToolsHelper.clearNotes(this)
                Toast.makeText(this, "Notes cleared.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("CLOSE", null)
            .show()
    }

    private fun checkAndStartFloatingService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Overlay Permission Required")
                .setMessage("To display the floating Arc Reactor over other apps (WhatsApp, YouTube, etc.), please grant 'Display over other apps' permission.")
                .setPositiveButton("OPEN SETTINGS") { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivityForResult(intent, REQ_OVERLAY)
                }
                .setNegativeButton("CANCEL", null)
                .show()
            return
        }

        val serviceIntent = Intent(this, JarvisFloatingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, "Arc Reactor Hologram Active over apps!", Toast.LENGTH_SHORT).show()
    }

    // ==========================================
    // SECURITY APP LOCK & SETTINGS
    // ==========================================
    private fun verifyLockAndOpenSettings() {
        val lockType = prefs.getString("lock_type", "NONE") ?: "NONE"
        val savedCode = prefs.getString("lock_code", "") ?: ""

        if (lockType == "BIOMETRIC") {
            authenticateBiometric { showSettingsDialog(isFirstTime = false) }
        } else if (lockType == "PIN" || lockType == "PASSWORD") {
            showLockChallengeDialog(lockType, savedCode) { showSettingsDialog(isFirstTime = false) }
        } else {
            showSettingsDialog(isFirstTime = false)
        }
    }

    private fun authenticateBiometric(onSuccess: () -> Unit) {
        val biometricManager = BiometricManager.from(this)
        if (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK) != BiometricManager.BIOMETRIC_SUCCESS) {
            // Fallback to direct settings if biometric not set on device
            onSuccess()
            return
        }

        val executor: Executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onSuccess()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                Toast.makeText(this@MainActivity, "Authentication failed: $errString", Toast.LENGTH_SHORT).show()
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock JARVIS Settings")
            .setSubtitle("Confirm your biometric identity")
            .setNegativeButtonText("Cancel")
            .build()

        prompt.authenticate(promptInfo)
    }

    private fun showLockChallengeDialog(type: String, expectedCode: String, onSuccess: () -> Unit) {
        val input = EditText(this).apply {
            hint = "Enter $type"
            if (type == "PIN") inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        AlertDialog.Builder(this)
            .setTitle("🔐 Enter $type")
            .setView(input)
            .setPositiveButton("UNLOCK") { _, _ ->
                val entered = input.text.toString().trim()
                if (entered == expectedCode) {
                    onSuccess()
                } else {
                    Toast.makeText(this, "Incorrect $type!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showSettingsDialog(isFirstTime: Boolean) {
        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        val etApiKey = EditText(this).apply {
            hint = "Free Gemini API Key (AIza...)"
            setText(prefs.getString(JarvisFloatingService.KEY_API, ""))
        }

        val etUserName = EditText(this).apply {
            hint = "Your Name (e.g. Aditya or Aditi)"
            setText(prefs.getString(JarvisFloatingService.KEY_USER_NAME, "Aditya"))
        }

        val etAssistantName = EditText(this).apply {
            hint = "Assistant Name (e.g. Jarvis)"
            setText(prefs.getString(JarvisFloatingService.KEY_ASSISTANT_NAME, "Jarvis"))
        }

        // Relationship Style Spinner
        val styleSpinner = Spinner(this)
        val styles = arrayOf("Girlfriend / Loving Companion", "Best Friend / Sisterly Companion")
        val styleAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, styles)
        styleSpinner.adapter = styleAdapter
        val currentStyle = prefs.getString(JarvisFloatingService.KEY_RELATIONSHIP, "girlfriend")
        styleSpinner.setSelection(if (currentStyle.equals("bestfriend", ignoreCase = true)) 1 else 0)

        // Lock Type Spinner
        val lockSpinner = Spinner(this)
        val locks = arrayOf("None", "PIN", "Password", "Biometric")
        val lockAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, locks)
        lockSpinner.adapter = lockAdapter
        val currentLock = prefs.getString("lock_type", "None") ?: "None"
        lockSpinner.setSelection(when (currentLock.uppercase(Locale.ROOT)) {
            "PIN" -> 1
            "PASSWORD" -> 2
            "BIOMETRIC" -> 3
            else -> 0
        })

        val etLockCode = EditText(this).apply {
            hint = "Set PIN or Password (if selected above)"
            setText(prefs.getString("lock_code", ""))
        }

        dialogLayout.addView(TextView(this).apply { text = "Gemini API Key:" })
        dialogLayout.addView(etApiKey)
        dialogLayout.addView(TextView(this).apply { text = "User Name:" })
        dialogLayout.addView(etUserName)
        dialogLayout.addView(TextView(this).apply { text = "Assistant Name:" })
        dialogLayout.addView(etAssistantName)
        dialogLayout.addView(TextView(this).apply { text = "Personality Style:" })
        dialogLayout.addView(styleSpinner)
        dialogLayout.addView(TextView(this).apply { text = "App Lock Protection:" })
        dialogLayout.addView(lockSpinner)
        dialogLayout.addView(TextView(this).apply { text = "PIN / Password Code:" })
        dialogLayout.addView(etLockCode)

        AlertDialog.Builder(this)
            .setTitle(if (isFirstTime) "Welcome to JARVIS: Setup" else "JARVIS System Configuration")
            .setView(dialogLayout)
            .setPositiveButton("SAVE") { _, _ ->
                val newKey = etApiKey.text.toString().trim()
                val newUserName = etUserName.text.toString().trim().ifEmpty { "Aditya" }
                val newAssistantName = etAssistantName.text.toString().trim().ifEmpty { "Jarvis" }
                val newStyle = if (styleSpinner.selectedItemPosition == 1) "bestfriend" else "girlfriend"
                val newLock = when (lockSpinner.selectedItemPosition) {
                    1 -> "PIN"
                    2 -> "PASSWORD"
                    3 -> "BIOMETRIC"
                    else -> "NONE"
                }
                val newLockCode = etLockCode.text.toString().trim()

                prefs.edit()
                    .putString(JarvisFloatingService.KEY_API, newKey)
                    .putString(JarvisFloatingService.KEY_USER_NAME, newUserName)
                    .putString(JarvisFloatingService.KEY_ASSISTANT_NAME, newAssistantName)
                    .putString(JarvisFloatingService.KEY_RELATIONSHIP, newStyle)
                    .putString("lock_type", newLock)
                    .putString("lock_code", newLockCode)
                    .putString(JarvisFloatingService.KEY_MODEL, GeminiClient.DEFAULT_MODEL)
                    .apply()

                refreshProfileHeader()
                speechEngine.speak("Settings updated, $newUserName! All systems nominal.")
                Toast.makeText(this, "Settings saved!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("CANCEL", null)
            .setCancelable(!isFirstTime)
            .show()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 101)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechEngine.shutdown()
        geminiClient.cancelActiveCall()
        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (_: Exception) {}
    }
}
