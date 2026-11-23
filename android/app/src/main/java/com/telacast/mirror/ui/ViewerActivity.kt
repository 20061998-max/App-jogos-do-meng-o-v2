package com.telacast.mirror.ui

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.telacast.mirror.data.OfflineLicenseManager
import com.telacast.mirror.databinding.ActivityViewerBinding
import com.telacast.mirror.service.ScreenMirrorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

class ViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityViewerBinding
    private var streamJob: Job? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private var authMode: String = "offline"

    private fun isRunningOnTv(): Boolean {
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        return uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Proteção anti-retransmissão: impede captura de tela, gravação ou espelhamento da tela do player
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        authMode = intent.getStringExtra(MainActivity.EXTRA_AUTH_MODE) ?: "offline"
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnConectar.setOnClickListener {
            if (streamJob?.isActive == true) {
                desconectar()
            } else {
                if (!isRunningOnTv()) {
                    Toast.makeText(
                        this,
                        "Proteção ativa: retransmissão permitida somente em Smart TVs.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                val baseUrl = binding.inputEndereco.text?.toString().orEmpty().trim().trimEnd('/')
                val token = binding.inputToken.text?.toString().orEmpty().trim()
                if (baseUrl.isBlank() || token.isBlank()) {
                    Toast.makeText(this, "Informe o endereço IP e o token da sessão.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                conectarStream("$baseUrl/stream?token=$token")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (authMode == "offline" && !OfflineLicenseManager.getSavedLicenseState(this).ativo) {
            desconectar()
            Toast.makeText(this, "Assinatura vencida. Renove para assistir.", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun conectarStream(url: String) {
        binding.btnConectar.text = "Desconectar"
        binding.txtViewerStatus.text = "Conectando ao transmissor..."
        binding.txtViewerStatus.visibility = View.VISIBLE

        val isTv = isRunningOnTv()
        streamJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .addHeader(
                        ScreenMirrorService.APP_CLIENT_HEADER,
                        if (isTv) ScreenMirrorService.TV_CLIENT_SIGNATURE else ScreenMirrorService.APP_CLIENT_SIGNATURE
                    )
                    .addHeader(
                        ScreenMirrorService.DEVICE_TYPE_HEADER,
                        if (isTv) "tv" else "mobile"
                    )
                    .get()
                    .build()

                client.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) {
                        withContext(Dispatchers.Main) {
                            binding.txtViewerStatus.text = "Acesso negado (HTTP ${res.code}). Verifique o token."
                            binding.btnConectar.text = "Assistir transmissão"
                        }
                        return@launch
                    }

                    val inputStream: InputStream = res.body?.byteStream() ?: return@launch
                    withContext(Dispatchers.Main) {
                        binding.txtViewerStatus.visibility = View.GONE
                    }

                    lerQuadrosMjpeg(inputStream) { jpegBytes ->
                        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                        if (bitmap != null) {
                            withContext(Dispatchers.Main) {
                                binding.imgStream.setImageBitmap(bitmap)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.txtViewerStatus.text = "Conexão encerrada: ${e.localizedMessage ?: "erro"}"
                    binding.txtViewerStatus.visibility = View.VISIBLE
                    binding.btnConectar.text = "Assistir transmissão"
                }
            }
        }
    }

    private suspend fun lerQuadrosMjpeg(
        stream: InputStream,
        onFrame: suspend (ByteArray) -> Unit
    ) {
        val buffer = ByteArrayOutputStream()
        var prev = -1
        var insideJpeg = false

        while (coroutineContext.isActive) {
            val b = stream.read()
            if (b == -1) break

            if (!insideJpeg) {
                if (prev == 0xFF && b == 0xD8) {
                    insideJpeg = true
                    buffer.reset()
                    buffer.write(0xFF)
                    buffer.write(0xD8)
                }
            } else {
                buffer.write(b)
                if (prev == 0xFF && b == 0xD9) {
                    insideJpeg = false
                    onFrame(buffer.toByteArray())
                }
            }
            prev = b
        }
    }

    private fun desconectar() {
        streamJob?.cancel()
        streamJob = null
        binding.btnConectar.text = "Assistir transmissão"
        binding.txtViewerStatus.text = "Desconectado"
        binding.txtViewerStatus.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        desconectar()
        super.onDestroy()
    }
}
