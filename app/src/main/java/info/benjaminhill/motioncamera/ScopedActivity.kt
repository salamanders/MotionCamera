package info.benjaminhill.motioncamera

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.github.ajalt.timberkt.Timber
import com.github.ajalt.timberkt.i
import com.github.ajalt.timberkt.w
import kotlinx.coroutines.experimental.CoroutineScope
import kotlinx.coroutines.experimental.Dispatchers
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.android.Main
import kotlin.coroutines.experimental.CoroutineContext

abstract class ScopedActivity : AppCompatActivity(), CoroutineScope {
    private lateinit var job: Job
    override val coroutineContext: CoroutineContext
        get() = job + Dispatchers.Main

    override fun onStart() {
        super.onStart()
        Timber.plant(Timber.DebugTree())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        job = Job()
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (missingPermissions.isEmpty()) {
            i { "We already have all the permissions we needed, no need to get involved" }
        } else {
            w { "Requesting permissions, be back soon." }
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), SIMPLE_PERMISSION_ID)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    private val requiredPermissions
        get() = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions
                .filterNotNull().toSet()

    protected val missingPermissions
        get() = requiredPermissions
                .asSequence()
                .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
                .toSet()

    companion object {
        private const val SIMPLE_PERMISSION_ID = 4242
    }
}