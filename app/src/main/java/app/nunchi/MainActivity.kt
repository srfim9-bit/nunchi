package app.nunchi

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import app.nunchi.ui.NunchiScreen
import app.nunchi.ui.NunchiTheme

class MainActivity : ComponentActivity() {
    private val viewModel: NunchiViewModel by viewModels()

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        viewModel.onCarPermissionsResult(result)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startCareService()
        setContent {
            NunchiTheme {
                NunchiScreen(viewModel)
            }
        }
        if (savedInstanceState == null) requestNeededPermissions()
    }

    /** 화면과 별개로 도는 상시 서비스 — 운전 중 화면이 막혀도 눈치는 계속 일한다. */
    private fun startCareService() {
        runCatching { startForegroundService(Intent(this, CareService::class.java)) }
            .onFailure { viewModel.diag("상시 서비스 시작 실패: ${it.javaClass.simpleName} ${it.message.orEmpty()}") }
    }

    /** 주행가능거리(CAR_ENERGY)·음성(TTS_SERVICE)·알림(Android 13+)은 런타임 허용이 필요할 수 있다. */
    private fun requestNeededPermissions() {
        val wanted = buildList {
            addAll(CAR_PERMISSIONS)
            if (Build.VERSION.SDK_INT >= 33) add("android.permission.POST_NOTIFICATIONS")
        }
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            viewModel.onCarPermissionsResult(wanted.associateWith { true })
        } else {
            permissionRequest.launch(missing.toTypedArray())
        }
    }

    private companion object {
        val CAR_PERMISSIONS = listOf(
            "android.car.permission.CAR_ENERGY",
            "android.car.permission.CAR_POWERTRAIN",
            "android.car.permission.CAR_EXTERIOR_ENVIRONMENT",
            // Pleos 음성(TTS) — 일반 권한이면 이미 허용돼 있어 팝업 없이 지나간다
            "pleos.car.permission.TTS_SERVICE",
            // Pleos 음성 인식 — 제안에 말로 대답하기(실험)
            "pleos.car.permission.STT_SERVICE",
        )
    }
}
