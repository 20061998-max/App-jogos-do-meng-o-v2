package com.telacast.mirror.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.telacast.mirror.data.OfflineLicenseManager
import com.telacast.mirror.databinding.ActivityMainBinding
import com.telacast.mirror.service.ScreenMirrorService
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_USER_NAME = "extra_user_name"
        const val EXTRA_PLAN_NAME = "extra_plan_name"
        const val EXTRA_AUTH_MODE = "extra_auth_mode"
        const val EXTRA_AUTH_TOKEN = "extra_auth_token"
    }

    private data class NativeAppItem(
        val label: String,
        val packageName: String?,
        val fallbackUrl: String?
    )

    private val nativeApps = listOf(
        NativeAppItem("FlaTV+", "com.flamengo.flatv", "https://flatvmais.com.br"),
        NativeAppItem("Premiere", "com.globo.premierefc", "https://premiere.globo.com"),
        NativeAppItem("Globoplay", "com.globo.globotv", "https://globoplay.globo.com"),
        NativeAppItem("CazéTV / YouTube", "com.google.android.youtube", "https://www.youtube.com/@CazeTV"),
        NativeAppItem("ESPN / Disney+", "com.disney.disneyplus", "https://www.disneyplus.com"),
        NativeAppItem("Paramount+", "com.cbs.ca", "https://www.paramountplus.com"),
        NativeAppItem("Navegador Chrome", "com.android.chrome", null),
        NativeAppItem("Escolher outro app instalado...", null, null)
    )

    private lateinit var binding: ActivityMainBinding
    private lateinit var projectionManager: MediaProjectionManager
    private var isStreaming = false
    private var pendingLaunchAfterStart = false
    private var authMode: String = "offline"
    private var authToken: String = ""

    private val requestRuntimePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> }

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, ScreenMirrorService::class.java).apply {
                    action = ScreenMirrorService.ACTION_START
                    putExtra(ScreenMirrorService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenMirrorService.EXTRA_RESULT_DATA, result.data)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
                atualizarEstadoUi(ativo = true)
                if (pendingLaunchAfterStart) {
                    pendingLaunchAfterStart = false
                    abrirAppNativoSelecionado()
                }
            } else {
                pendingLaunchAfterStart = false
                Toast.makeText(this, "Permissão de captura negada.", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        authMode = intent.getStringExtra(EXTRA_AUTH_MODE) ?: "offline"
        authToken = intent.getStringExtra(EXTRA_AUTH_TOKEN) ?: ""
        val nome = intent.getStringExtra(EXTRA_USER_NAME) ?: "Assinante"
        val plano = intent.getStringExtra(EXTRA_PLAN_NAME) ?: "Mensal Pro"
        binding.txtAssinante.text = "$nome · $plano"

        solicitarPermissoesIniciais()
        configurarSeletorAppsNativos()
        atualizarEnderecoLocal()

        binding.btnIniciarEAbrirApp.setOnClickListener {
            if (isStreaming) {
                abrirAppNativoSelecionado()
            } else {
                pendingLaunchAfterStart = true
                iniciarSolicitacaoCaptura()
            }
        }

        binding.btnAbrirAppNativo.setOnClickListener {
            abrirAppNativoSelecionado()
        }

        binding.btnToggleStream.setOnClickListener {
            if (isStreaming) {
                pararEspelhamento()
            } else {
                pendingLaunchAfterStart = false
                iniciarSolicitacaoCaptura()
            }
        }

        binding.btnCopiarUrl.setOnClickListener {
            val url = binding.txtUrlLocal.text.toString()
            val token = ScreenMirrorService.sessionToken
            val payload = if (token.isNotBlank()) "$url · Token: $token" else url
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Jogos do Mengão App", payload))
            Toast.makeText(this, "Dados copiados para abrir na TV", Toast.LENGTH_SHORT).show()
        }

        binding.btnModoReceptor.setOnClickListener {
            val viewerIntent = Intent(this, ViewerActivity::class.java).apply {
                putExtra(EXTRA_AUTH_MODE, authMode)
                putExtra(EXTRA_AUTH_TOKEN, authToken)
            }
            startActivity(viewerIntent)
        }
    }

    private fun solicitarPermissoesIniciais() {
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.RECORD_AUDIO)
        }
        if (missing.isNotEmpty()) {
            requestRuntimePermissions.launch(missing.toTypedArray())
        }
    }

    private fun configurarSeletorAppsNativos() {
        binding.spinnerAppsNativos.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            nativeApps.map { it.label }
        )
    }

    private fun abrirAppNativoSelecionado() {
        val index = binding.spinnerAppsNativos.selectedItemPosition
        val item = nativeApps.getOrElse(index) { nativeApps.last() }
        val pkg = item.packageName

        if (!pkg.isNullOrBlank()) {
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                Toast.makeText(this, "Abrindo ${item.label}...", Toast.LENGTH_SHORT).show()
                return
            }
            if (!item.fallbackUrl.isNullOrBlank()) {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(item.fallbackUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(webIntent)
                Toast.makeText(
                    this,
                    "${item.label} não instalado; abrindo versão Web.",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
        }

        // Abre seletor nativo do Android com apps instalados no celular
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val chooser = Intent.createChooser(launcherIntent, "Escolha o app para abrir")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(chooser)
    }

    override fun onResume() {
        super.onResume()
        if (authMode == "offline") {
            val offlineState = OfflineLicenseManager.getSavedLicenseState(this)
            if (!offlineState.ativo) {
                pararEspelhamento()
                Toast.makeText(
                    this,
                    offlineState.motivo ?: "Assinatura vencida. Renove para continuar.",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
                return
            }
            binding.txtAssinante.text =
                "Aparelho ${offlineState.deviceCode} · ${offlineState.plano ?: "Mensal Pro"} · ${offlineState.diasRestantes}d offline"
        }
        atualizarEstadoUi(ScreenMirrorService.isRunning)
        atualizarEnderecoLocal()
    }

    private fun iniciarSolicitacaoCaptura() {
        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            projectionManager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForUserChoice()
            )
        } else {
            projectionManager.createScreenCaptureIntent()
        }
        screenCaptureLauncher.launch(captureIntent)
    }

    private fun pararEspelhamento() {
        val stopIntent = Intent(this, ScreenMirrorService::class.java).apply {
            action = ScreenMirrorService.ACTION_STOP
        }
        startService(stopIntent)
        atualizarEstadoUi(ativo = false)
    }

    private fun atualizarEstadoUi(ativo: Boolean) {
        isStreaming = ativo
        binding.btnToggleStream.text = if (ativo) "Parar espelhamento" else "Iniciar espelhamento"
        binding.btnIniciarEAbrirApp.text =
            if (ativo) "Abrir app selecionado agora" else "Transmitir e abrir app nativo"
        val token = ScreenMirrorService.sessionToken
        binding.txtStatusStream.text =
            if (ativo) "Somente Smart TV · Token: $token (~20 FPS)"
            else "Aguardando início da transmissão (somente TVs)"
    }

    private fun atualizarEnderecoLocal() {
        val ip = obterIpLocalWifi() ?: "127.0.0.1"
        binding.txtUrlLocal.text = "http://$ip:${ScreenMirrorService.HTTP_PORT}"
    }

    private fun obterIpLocalWifi(): String? {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { addr -> addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
