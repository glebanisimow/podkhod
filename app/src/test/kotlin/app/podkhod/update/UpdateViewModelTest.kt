package app.podkhod.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val source = FakeSource()
    private var workout = false
    private lateinit var vm: UpdateViewModel

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        vm = UpdateViewModel(source, { workout }, installedVersion = 99, enabled = true)
    }

    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun failedCheckCanBeRetriedAndEqualBuildIsCurrent() = runTest(dispatcher) {
        source.failure = IOException("offline")
        vm.check()
        advanceUntilIdle()
        assertEquals(UpdatePhase.IDLE, vm.state.value.phase)
        assertEquals(UpdateProblem.NETWORK, vm.state.value.problem)
        source.failure = null
        source.manifest = source.manifest.copy(versionCode = 99)
        vm.check()
        advanceUntilIdle()
        assertEquals(UpdatePhase.CURRENT, vm.state.value.phase)
        assertNull(vm.state.value.problem)
    }

    @Test fun activeWorkoutPreventsDownloading() = runTest(dispatcher) {
        vm.check()
        advanceUntilIdle()
        workout = true
        vm.download()
        advanceUntilIdle()
        assertEquals(0, source.downloads)
        assertEquals(UpdateProblem.ACTIVE_WORKOUT, vm.state.value.problem)
        assertNull(vm.state.value.installFile)
    }

    @Test fun workoutStartedDuringDownloadDefersInstallation() = runTest(dispatcher) {
        vm.check()
        advanceUntilIdle()
        source.onDownload = { workout = true }
        vm.download()
        advanceUntilIdle()
        assertEquals(UpdatePhase.READY, vm.state.value.phase)
        assertEquals(UpdateProblem.ACTIVE_WORKOUT, vm.state.value.problem)
        assertNull(vm.state.value.installFile)
        workout = false
        vm.install()
        advanceUntilIdle()
        assertEquals(source.file, vm.state.value.installFile)
        assertEquals(1, source.downloads)
    }

    @Test fun cancelledInstallerCanReopenWithoutDownloadingAgain() = runTest(dispatcher) {
        vm.check()
        advanceUntilIdle()
        vm.download()
        advanceUntilIdle()
        assertEquals(source.file, vm.state.value.installFile)
        vm.consumeInstallRequest()
        vm.report(UpdateProblem.INSTALL_PERMISSION)
        vm.install()
        advanceUntilIdle()
        assertEquals(source.file, vm.state.value.installFile)
        assertEquals(1, source.downloads)
        assertEquals(2, source.validations)
        assertNull(vm.state.value.problem)
    }

    @Test fun invalidPackageNeverRequestsInstallation() = runTest(dispatcher) {
        vm.check()
        advanceUntilIdle()
        source.onDownload = { source.failure = UpdateException(UpdateProblem.INVALID_APK) }
        vm.download()
        advanceUntilIdle()
        assertEquals(UpdateProblem.INVALID_APK, vm.state.value.problem)
        assertNull(vm.state.value.installFile)
    }

    private class FakeSource : UpdateSource {
        var manifest = UpdateManifest(
            100, "0.6.0", "app.podkhod", 29,
            "https://github.com/glebanisimow/podkhod/releases/download/android/podkhod-100-123-1.apk",
            "a".repeat(64), 10,
        )
        val file = File("test-update.apk")
        var failure: Exception? = null
        var downloads = 0
        var validations = 0
        var onDownload: () -> Unit = {}
        override suspend fun check(): UpdateManifest {
            failure?.let { throw it }
            return manifest
        }
        override suspend fun download(manifest: UpdateManifest, onProgress: (Int) -> Unit): File {
            downloads++
            onDownload()
            onProgress(100)
            return file
        }
        override suspend fun validateForInstall(apk: File, manifest: UpdateManifest) {
            validations++
            failure?.let { throw it }
        }
    }
}
