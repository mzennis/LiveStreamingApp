package com.tokopedia.streamer

import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.hardware.Camera
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.tokopedia.streamer.databinding.ActivityMainBinding
import io.antmedia.android.broadcaster.ILiveVideoBroadcaster
import io.antmedia.android.broadcaster.LiveVideoBroadcaster
import io.antmedia.android.broadcaster.LiveVideoBroadcaster.LocalBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {

        private const val INGEST_URL = "rtmp://192.168.245.23/live/ryB398wIs"

    }

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        startService()

        binding.glSurfaceView.setEGLContextClientVersion(2)

        viewOnPrepare()
    }

    override fun onStart() {
        super.onStart()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        bindService()
    }

    override fun onPause() {
        super.onPause()
        pauseStreaming()
    }

    override fun onStop() {
        super.onStop()
        unbindService()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
            || newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            setDisplayOrientation()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == LiveVideoBroadcaster.PERMISSIONS_REQUEST) {
            setupPermission()
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    private fun viewOnPrepare() {
        binding.btnRecord.visibility = View.VISIBLE
        binding.viewLiveAction.visibility = View.GONE

        binding.btnRecord.setOnClickListener {
            doStartStreaming()
        }
    }

    private fun viewOnLive() {
        binding.btnRecord.visibility = View.GONE
        binding.viewLiveAction.visibility = View.VISIBLE

        binding.btnClose.setOnClickListener {
            stopStreamingDialog().show()
        }

        binding.btnMuteAudio.setOnClickListener {
            doMuteAudio()
        }

        binding.btnSwitchCamera.setOnClickListener {
            switchCamera() // todo: sometime can't switch camera
        }
    }

    private fun viewOnChangeStatus(message: String) {
        binding.tvStatus.text = message
    }

    private fun doStartStreaming() {
        viewOnChangeStatus("Connecting...")
        lifecycleScope.launch {
            try {
                val isSuccess = startStreaming(INGEST_URL)
                if (isSuccess) {
                    viewOnLive()
                    viewOnChangeStatus("LIVE")
                } else Snackbar.make(binding.root, "Failed to start. Please check Ingest URL", Snackbar.LENGTH_INDEFINITE).show()
            } catch (e: IllegalAccessException) {
                Snackbar.make(binding.root, e.message ?: "Oops this shouldn\'t be happened", Snackbar.LENGTH_INDEFINITE).show()
            }
        }
    }

    private fun stopStreamingDialog() = AlertDialog.Builder(this@MainActivity)
            .setTitle("End Live")
            .setMessage("Are you sure you wanna end live?")
            .setPositiveButton(android.R.string.ok) { _, _ ->
                stopStreaming()
                viewOnPrepare()
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                dialog.dismiss()
            }

    /*
    * todo: check functionality
     */
    private var mIsMuted = false
    private fun doMuteAudio() {
        mIsMuted = !mIsMuted
        muteAudio(mIsMuted)
        val audioRes =  if (mIsMuted) R.drawable.ic_mic_mute_off_24 else R.drawable.ic_mic_mute_on_24
        binding.btnMuteAudio.setImageDrawable(ContextCompat.getDrawable(this, audioRes))
    }

    /**
     * Live Broadcaster
     */
    private var mStreamerServiceIntent: Intent? = null
    private var mStreamer: ILiveVideoBroadcaster? = null

    private val mConnection: ServiceConnection = object : ServiceConnection {

        override fun onServiceConnected(
            className: ComponentName,
            service: IBinder
        ) {
            if (mStreamer == null) {
                mStreamer = (service as LocalBinder).service
                mStreamer?.init(this@MainActivity, binding.glSurfaceView)
                mStreamer?.setAdaptiveStreaming(true)
            }
            mStreamer?.openCamera(Camera.CameraInfo.CAMERA_FACING_BACK)
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            mStreamer = null
        }
    }

    private fun startService() {
        mStreamerServiceIntent = Intent(this, LiveVideoBroadcaster::class.java)
        startService(mStreamerServiceIntent)
    }

    private fun bindService() {
        bindService(mStreamerServiceIntent, mConnection, 0)
    }

    private fun unbindService() {
        unbindService(mConnection)
    }

    private suspend fun startStreaming(rtmpUrl: String) = withContext(Dispatchers.IO) {
        val streamer = mStreamer ?: throw IllegalAccessException("Oops this shouldn\'t be happened")
        if (streamer.isConnected) throw IllegalAccessException("Your previous broadcast still sends packets due to slow internet speed")
        streamer.startBroadcasting(rtmpUrl)
    }

    private fun stopStreaming() {
        mStreamer?.stopBroadcasting()
    }

    private fun pauseStreaming() {
        mStreamer?.pause()
    }

    private fun switchCamera() {
        mStreamer?.changeCamera()
    }

    private fun muteAudio(mute: Boolean) {
        mStreamer?.setAudioEnable(mute)
    }

    private fun setDisplayOrientation() {
        mStreamer?.setDisplayOrientation()
    }

    private fun setupPermission() {
        if (mStreamer?.isPermissionGranted == true) {
            mStreamer?.openCamera(Camera.CameraInfo.CAMERA_FACING_BACK)
        } else {
            if (ActivityCompat.shouldShowRequestPermissionRationale(
                    this,
                    Manifest.permission.CAMERA
                ) ||
                ActivityCompat.shouldShowRequestPermissionRationale(
                    this,
                    Manifest.permission.RECORD_AUDIO
                )
            ) {
                mStreamer?.requestPermission()
            } else {
                requestPermissionDialog().show()
            }
        }
    }

    private fun requestPermissionDialog() = AlertDialog.Builder(this@MainActivity)
        .setTitle(R.string.permission)
        .setMessage(getString(R.string.app_does_not_work_without_permissions))
        .setPositiveButton(android.R.string.ok) { _, _ ->
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data =
                            Uri.parse("package:" + applicationContext.packageName)
                    }
                )
            } catch (e: ActivityNotFoundException) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
                )
            }
        }
}