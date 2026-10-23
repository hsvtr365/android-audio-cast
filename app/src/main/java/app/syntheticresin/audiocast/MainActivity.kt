package app.syntheticresin.audiocast

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowInsetsControllerCompat
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("settings", 0) }
    private lateinit var headline: TextView
    private lateinit var session: TextView
    private lateinit var action: MaterialButton
    private lateinit var loadingSpinner: com.google.android.material.progressindicator.CircularProgressIndicator
    private lateinit var sourceStatus: TextView
    private lateinit var audioRing: AudioRingView
    private lateinit var description: TextView
    private lateinit var bufferProgress: com.google.android.material.progressindicator.LinearProgressIndicator
    private val visuals=object:Runnable {
        override fun run() {
            val bands=LiveService.levels.snapshot()
            audioRing.show(bands)
            handler.postDelayed(this,50)
        }
    }
    private lateinit var mediaAccess: MaterialButton
    private lateinit var captureChoices: LinearLayout
    private var choiceSignature=""
    private var sourceDialog: androidx.appcompat.app.AlertDialog? = null
    private lateinit var deviceName: TextView
    private lateinit var connection: TextView
    private lateinit var network: TextView
    private lateinit var networkValue: TextView
    private lateinit var networkIcon: ImageView
    private lateinit var networkBand: TextView
    private lateinit var networkCard: MaterialCardView
    private lateinit var deviceCard: MaterialCardView
    private lateinit var deviceBadge: ImageView
    private lateinit var deviceArrow: ImageView
    private lateinit var deviceLink: ImageView
    private lateinit var castButton: MediaRouteButton
    private var castIndicatorColor: Int? = null
    private lateinit var permissionButton: MaterialButton
    private var castButtonReady = false
    private var pendingMode: String? = null
    private var chooseCastAfterStart = false
    private var notificationAsked = false
    private var settingsDialog: BottomSheetDialog? = null
    private var languageDialog: androidx.appcompat.app.AlertDialog? = null
    private var logsDialog: androidx.appcompat.app.AlertDialog? = null
    private var privacyDialog: androidx.appcompat.app.AlertDialog? = null
    private var permissionGuide: androidx.appcompat.app.AlertDialog? = null
    private var donationDialog: com.google.android.material.bottomsheet.BottomSheetDialog? = null
    private val refresh = object : Runnable {
        override fun run() {
            val running = LiveService.running
            val connected = CastController.isConnected
            val delay = if (running) LiveService.targetDelay else prefs.getInt("buffer", 6).coerceIn(6,30)
            headline.text = Strings.get(when {
                running && !connected -> R.string.relay_waiting_speaker
                running && LiveService.ready -> R.string.relay_active
                running -> R.string.relay_preparing
                else -> R.string.idle
            })
            session.text = formatTime(if(running) LiveService.elapsedSeconds else 0)
            description.text=Strings.get(R.string.relay_delay_notice,delay)
            description.visibility=if (!running || !LiveService.ready) View.VISIBLE else View.GONE
            val preparing = running && !LiveService.ready
            bufferProgress.visibility = if (preparing) View.VISIBLE else View.GONE
            // HLS waits for whole segments before the playlist is ready.
            val preparationSeconds = kotlin.math.ceil(LiveService.targetDelay / HlsStream.SEGMENT_SECONDS) * HlsStream.SEGMENT_SECONDS
            bufferProgress.setProgressCompat(if (preparing) (LiveService.bufferedSeconds / preparationSeconds * 100).toInt().coerceIn(0,99) else 0,false)
            val actionLabel = if (running) Strings.get(R.string.stop_relay) else Strings.get(R.string.start_relay)
            action.contentDescription = actionLabel; action.tooltipText = actionLabel
            if (action.isActivated != running) { action.isActivated = running; action.setIconResource(if (running) R.drawable.ic_stop else R.drawable.ic_play) }
            action.isEnabled = !CaptureService.running
            action.iconTint=android.content.res.ColorStateList.valueOf(if(preparing) android.graphics.Color.TRANSPARENT else 0xff35205e.toInt())
            loadingSpinner.visibility=if(preparing) View.VISIBLE else View.GONE
            deviceName.text = if(connected && running) Strings.get(R.string.speaker_sending) else if(!connected) Strings.get(R.string.choose_speaker) else CastController.device
            connection.text = if(connected && running) Strings.get(R.string.speaker_delay_hint,delay) else if(connected) CastController.status else Strings.get(R.string.speaker_required_hint)
            if (!castButtonReady && CastController.context != null) {
                try { CastButtonFactory.setUpMediaRouteButton(this@MainActivity, castButton); castButtonReady = true }
                catch (e: Exception) { Diagnostics.log("Cast button: ${e.javaClass.simpleName}") }
            }
            castButton.isEnabled = localAllowed() && castButtonReady
            if (chooseCastAfterStart && running && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) && !supportFragmentManager.isStateSaved) {
                if (connected || CastController.isConnecting) chooseCastAfterStart = false
                else if (castButton.isEnabled) {
                    chooseCastAfterStart = false
                    castButton.showDialog()
                }
            }
            permissionButton.visibility = if (localAllowed()) View.GONE else View.VISIBLE
            val wifiAddress=LiveService.wifiAddress(this@MainActivity)
            val band=if(wifiAddress!=null) WifiNetworkInfo.band(this@MainActivity) else null
            val networkWarning=WifiNetworkInfo.needsWarning(wifiAddress!=null,band)
            val dark=resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
            val warningColor=if(dark) 0xffffd166.toInt() else 0xff815600.toInt()
            val warningBackground=if(dark) 0xff352b16.toInt() else 0xfffff9e8.toInt()
            val normalBorder=if(dark) 0xff4b4457.toInt() else 0xffded4ed.toInt()
            listOf(networkCard to networkWarning,deviceCard to !connected).forEach { (card,warning) ->
                card.setCardBackgroundColor(if(warning) warningBackground else if(card==deviceCard && dark) 0xff201e35.toInt() else if(dark) color(com.google.android.material.R.attr.colorSurfaceContainerLow) else 0xfffffdff.toInt())
                card.strokeColor=if(warning && !dark) 0xffe8c986.toInt() else androidx.core.graphics.ColorUtils.setAlphaComponent(if(warning) warningColor else normalBorder,if(dark) 128 else 255)
            }
            networkValue.setTextColor(if(networkWarning) warningColor else color(com.google.android.material.R.attr.colorOnSurface))
            networkIcon.imageTintList=android.content.res.ColorStateList.valueOf(if(networkWarning) warningColor else color(com.google.android.material.R.attr.colorOnSurfaceVariant))
            network.setTextColor(if(networkWarning) warningColor else color(com.google.android.material.R.attr.colorOnSurfaceVariant))
            val linkColor=if(!connected) warningColor else if(dark) 0xffc7a6f1.toInt() else 0xff6750a4.toInt()
            deviceName.setTextColor(linkColor)
            deviceName.paintFlags=deviceName.paintFlags and android.graphics.Paint.UNDERLINE_TEXT_FLAG.inv()
            connection.setTextColor(if(!connected) warningColor else color(com.google.android.material.R.attr.colorOnSurfaceVariant))
            deviceBadge.visibility=if(connected) View.VISIBLE else View.GONE
            deviceBadge.setImageResource(R.drawable.ic_wave)
            deviceBadge.imageTintList=android.content.res.ColorStateList.valueOf(if(!connected) 0xff493400.toInt() else 0xff3a245b.toInt())
            (deviceBadge.background as android.graphics.drawable.GradientDrawable).setColor(linkColor)
            deviceLink.imageTintList=android.content.res.ColorStateList.valueOf(linkColor)
            deviceArrow.imageTintList=android.content.res.ColorStateList.valueOf(linkColor)
            if(castIndicatorColor!=linkColor) {
                castIndicatorColor=linkColor
                castButton.setRemoteIndicatorDrawable(androidx.appcompat.content.res.AppCompatResources.getDrawable(this@MainActivity,R.drawable.ic_cast_static)?.mutate()?.apply { setTint(linkColor) })
            }
            if(connected) deviceCard.strokeColor=androidx.core.graphics.ColorUtils.setAlphaComponent(if(dark) 0xff63507f.toInt() else 0xffbba6d4.toInt(),128)
            networkValue.text=Strings.get(if(wifiAddress!=null) R.string.wifi_connected_status else R.string.wifi_disconnected_status)
            networkBand.text=band?.label ?: ""
            networkBand.visibility=if(band==null) View.GONE else View.VISIBLE
            network.text=if(wifiAddress==null) Strings.get(R.string.wifi_needed) else Strings.get(R.string.same_wifi_question)
            networkCard.contentDescription="${Strings.get(R.string.network_title)}, ${networkValue.text}, ${networkBand.text}, ${network.text}. ${Strings.get(R.string.open_wifi_settings)}"
            updateSourceApp()
            handler.postDelayed(this, 1000)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val light = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
        pendingMode = savedInstanceState?.getString("pendingMode")
        chooseCastAfterStart = savedInstanceState?.getBoolean("chooseCastAfterStart") ?: false
        notificationAsked = savedInstanceState?.getBoolean("notificationAsked") ?: false
        Diagnostics.init(this); CastController.init(this)
        val root = column().apply {
            setPadding(dp(20),dp(16),dp(20),dp(24))
            setBackgroundColor(if(light) 0xfffcfaff.toInt() else 0xff11111b.toInt())
        }
        val primary = column().apply { setPadding(dp(16), dp(12), dp(16), dp(12)) }
        primary.background=android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            if(resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
                intArrayOf(0xff30255f.toInt(),0xff201c39.toInt(),0xff191827.toInt())
            else intArrayOf(0xffeee5ff.toInt(),0xfff5eeff.toInt(),0xfffaf7ff.toInt())).apply { cornerRadius=dp(12).toFloat() }
        val cardHeader=row().apply { gravity=Gravity.CENTER_VERTICAL }
        headline=text("",11f,bold=true).apply {
            val icon=androidx.appcompat.content.res.AppCompatResources.getDrawable(this@MainActivity,R.drawable.ic_wave)?.mutate()?.apply { setBounds(0,0,dp(16),dp(16)) }
            setCompoundDrawablesRelative(icon,null,null,null)
            compoundDrawablePadding=dp(5)
            compoundDrawableTintList=android.content.res.ColorStateList.valueOf(if(light) 0xff493267.toInt() else 0xfffaf6ff.toInt())
            gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(30)
            setPadding(dp(10),dp(6),dp(10),dp(6))
            setTextColor(if(light) 0xff493267.toInt() else 0xfffaf6ff.toInt())
            background=android.graphics.drawable.GradientDrawable().apply {
                setColor(if(light) 0xffe7d4ff.toInt() else 0xff4b326d.toInt())
                cornerRadius=dp(12).toFloat()
            }
        }
        cardHeader.addView(headline,LinearLayout.LayoutParams(-2,-2))
        cardHeader.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
        cardHeader.addView(iconButton(Strings.get(R.string.settings),R.drawable.ic_settings) { showSettings() }.apply {
            iconSize=dp(20)
            backgroundTintList=android.content.res.ColorStateList.valueOf(if(light) 0xffe9dcff.toInt() else 0xff2b253e.toInt())
        },LinearLayout.LayoutParams(dp(48),dp(48)).apply { marginStart=dp(8) })
        primary.addView(cardHeader)
        val stage=FrameLayout(this)
        audioRing=AudioRingView(this,if(light) 0xff9561cf.toInt() else 0xffba84ff.toInt())
        stage.addView(audioRing,FrameLayout.LayoutParams(-1,dp(144)))
        stage.addView(text(Strings.get(R.string.relay_summary),10f).apply {
            setTextColor(if(light) 0xff71637f.toInt() else 0xffb3a9c5.toInt())
        },FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
        val controls=column().apply { gravity=Gravity.CENTER_HORIZONTAL }
        session=text("00:00",12f).apply {
            gravity=Gravity.CENTER;includeFontPadding=false
            setTextColor(if(light) 0xff6e627c.toInt() else 0xffc3bad5.toInt())
        }
        controls.addView(session)
        val dial=FrameLayout(this)
        fun halo(size:Int,alpha:Int) {
            dial.addView(View(this).apply {
                background=android.graphics.drawable.GradientDrawable().apply {
                    shape=android.graphics.drawable.GradientDrawable.OVAL
                    setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(0xffa575e8.toInt(),alpha))
                }
            },FrameLayout.LayoutParams(dp(size),dp(size),Gravity.CENTER))
        }
        halo(88,28);halo(80,42)
        dial.addView(View(this).apply {
            background=android.graphics.drawable.GradientDrawable().apply {
                shape=android.graphics.drawable.GradientDrawable.OVAL
                setColor(if(light) 0xffe3d1fa.toInt() else 0xff302449.toInt())
                setStroke(dp(1),androidx.core.graphics.ColorUtils.setAlphaComponent(if(light) 0xffad94ce.toInt() else 0xff76559d.toInt(),128))
            }
        },FrameLayout.LayoutParams(dp(72),dp(72),Gravity.CENTER))
        action=iconButton(Strings.get(R.string.start_relay),R.drawable.ic_play,filled=true) { toggleRelay() }.apply {
            cornerRadius=dp(32);iconSize=dp(28)
            iconTint=android.content.res.ColorStateList.valueOf(0xff35205e.toInt())
            backgroundTintList=null
            background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x336750a4),
                android.graphics.drawable.GradientDrawable().apply {
                    shape=android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if(light) 0xfff2e5ff.toInt() else 0xffcfadf6.toInt())
                },null)
        }
        dial.addView(action,FrameLayout.LayoutParams(dp(64),dp(64),Gravity.CENTER))
        loadingSpinner=com.google.android.material.progressindicator.CircularProgressIndicator(this).apply {
            isIndeterminate=true;indicatorSize=dp(30);trackThickness=dp(3);visibility=View.GONE
            setIndicatorColor(0xff35205e.toInt());trackColor=android.graphics.Color.TRANSPARENT
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnClickListener { toggleRelay() }
        }
        dial.addView(loadingSpinner,FrameLayout.LayoutParams(dp(30),dp(30),Gravity.CENTER))
        controls.addView(dial,LinearLayout.LayoutParams(dp(88),dp(88)))
        stage.addView(controls,FrameLayout.LayoutParams(dp(88),-2,Gravity.TOP or Gravity.START).apply { topMargin=dp(24) })
        description=text("",10f).apply {
            setTextColor(if(light) 0xff6e627c.toInt() else 0xffaaa5b8.toInt())
            gravity=Gravity.END;textAlignment=View.TEXT_ALIGNMENT_VIEW_END
        }
        stage.addView(description,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM or Gravity.END))
        primary.addView(stage,LinearLayout.LayoutParams(-1,dp(144)))
        val primaryBox=FrameLayout(this).apply {
            background=primary.background
            clipToOutline=true
        }
        primary.background=null
        primaryBox.addView(primary,FrameLayout.LayoutParams(-1,-2))
        bufferProgress=com.google.android.material.progressindicator.LinearProgressIndicator(this).apply {
            max=100;trackThickness=dp(5);trackCornerRadius=0;trackStopIndicatorSize=0
            setIndicatorColor(if(light) 0xff9561cf.toInt() else 0xffc7a6f1.toInt())
            trackColor=if(light) 0xffe4d8f6.toInt() else 0xff393351.toInt()
            contentDescription=Strings.get(R.string.relay_preparing)
            visibility=View.GONE
        }
        primaryBox.addView(bufferProgress,FrameLayout.LayoutParams(-1,dp(5),Gravity.BOTTOM))
        root.addView(card(primaryBox,primary=true).apply { clipToOutline=true });gap(root,10)
        val sources=column().apply { setPadding(dp(10),0,dp(10),dp(10)) }
        sources.addView(text(Strings.get(R.string.source_selection),12f,bold=true).apply { setPadding(dp(8),dp(12),0,dp(10)) })
        captureChoices=column(); sources.addView(captureChoices)
        sourceStatus=text("",10f,muted=true).apply { setPadding(dp(8),dp(8),dp(8),0) };sources.addView(sourceStatus)
        mediaAccess=button(Strings.get(R.string.media_access_settings)) { showMediaAccess() };sources.addView(mediaAccess)
        root.addView(card(sources).apply {
            if(!light) { setCardBackgroundColor(0xff17172c.toInt());strokeColor=0x80343046.toInt() }
        });gap(root,10)
        updateSourceApp()

        val device = column().apply { setPadding(dp(14), dp(8), dp(10), dp(8)) }
        val deviceRow = row().apply { gravity = Gravity.CENTER_VERTICAL }
        deviceBadge=ImageView(this).apply {
            setPadding(dp(7),dp(7),dp(7),dp(7));importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background=android.graphics.drawable.GradientDrawable().apply { shape=android.graphics.drawable.GradientDrawable.OVAL }
        }
        deviceRow.addView(deviceBadge,LinearLayout.LayoutParams(dp(32),dp(32)).apply { marginEnd=dp(12) })
        val names = column()
        deviceName = text("", 15f, bold = true); names.addView(deviceName)
        gap(names, 3); connection = text("", 10f, muted = true); names.addView(connection)
        castButton = MediaRouteButton(this).apply { contentDescription = Strings.get(R.string.choose_cast);setPadding(dp(12),dp(12),dp(12),dp(12)) }
        castButton.dialogFactory = object : androidx.mediarouter.app.MediaRouteDialogFactory() {
            override fun onCreateControllerDialogFragment() = SpeakerControllerDialogFragment()
        }
        names.setPadding(0,0,dp(12),0)
        deviceRow.addView(names,LinearLayout.LayoutParams(0,-2,1f))
        deviceLink=ImageView(this).apply { setImageResource(R.drawable.ic_phone_link);setPadding(dp(4),dp(4),dp(4),dp(4));importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val deviceLinkGroup=row().apply { gravity=Gravity.CENTER_VERTICAL }
        deviceLinkGroup.addView(deviceLink,LinearLayout.LayoutParams(dp(32),dp(32)))
        deviceArrow=ImageView(this).apply {
            setImageResource(R.drawable.ic_device_arrow)
            rotation=90f
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        deviceLinkGroup.addView(deviceArrow,LinearLayout.LayoutParams(dp(20),dp(20)).apply { marginStart=dp(3) })
        deviceRow.addView(deviceLinkGroup)
        deviceRow.addOnLayoutChangeListener { _,left,_,right,_,_,_,_,_ ->
            deviceLinkGroup.visibility=if(right-left<dp(320)) View.GONE else View.VISIBLE
        }
        deviceRow.addView(castButton, LinearLayout.LayoutParams(dp(48),dp(48)))
        device.addView(deviceRow)
        permissionButton = button(Strings.get(R.string.allow_lan)) { requestPermissions(arrayOf("android.permission.ACCESS_LOCAL_NETWORK"), 3) }
        device.addView(permissionButton, LinearLayout.LayoutParams(-1,dp(48)))
        deviceCard=card(device).apply { setOnClickListener { chooseCast() }; contentDescription=Strings.get(R.string.choose_cast) }
        deviceName.setOnClickListener { chooseCast() }; names.setOnClickListener { chooseCast() }
        deviceRow.setOnClickListener { chooseCast() }
        root.addView(deviceCard)
        gap(root, 10)

        val networkBox=row().apply { gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),dp(8),dp(10),dp(8)) }
        val networkNames=column().apply { setPadding(0,0,dp(8),0) }
        val networkStatus=row().apply { gravity=Gravity.CENTER_VERTICAL }
        networkValue=text("",15f,bold=true)
        networkStatus.addView(networkValue)
        networkBand=text("",10f).apply {
            setPadding(dp(6),dp(3),dp(6),dp(3))
            background=android.graphics.drawable.GradientDrawable().apply {
                setColor(color(com.google.android.material.R.attr.colorSurfaceContainerHigh));cornerRadius=dp(5).toFloat()
            }
        }
        networkStatus.addView(networkBand,LinearLayout.LayoutParams(-2,-2).apply { marginStart=dp(6) })
        networkNames.addView(networkStatus)
        gap(networkNames,3);network=text("",10f,muted=true);networkNames.addView(network)
        networkBox.addView(networkNames,LinearLayout.LayoutParams(0,-2,1f))
        networkIcon=ImageView(this).apply {
            setImageResource(R.drawable.ic_wifi);setPadding(dp(12),dp(12),dp(12),dp(12))
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        networkBox.addView(networkIcon,LinearLayout.LayoutParams(dp(48),dp(48)))
        networkCard=card(networkBox).apply {
            isFocusable=true
            setOnClickListener { startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)) }
        }
        root.addView(networkCard,LinearLayout.LayoutParams(-1,-2))
        gap(root,10)
        // Optional billing UI is preserved in optional-features/billing; disabled for the free release.
        val homeContainer=object:FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
                root.layoutParams.width=minOf(View.MeasureSpec.getSize(widthMeasureSpec),dp(600))
                super.onMeasure(widthMeasureSpec,heightMeasureSpec)
            }
        }.apply {
            setBackgroundColor(if(light) 0xfffcfaff.toInt() else 0xff11111b.toInt())
            addView(root,FrameLayout.LayoutParams(-1,-2,Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        }
        setContentView(ScrollView(this).apply { isFillViewport=true; addView(homeContainer) })
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view,insets ->
            val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            view.setPadding(dp(20)+bars.left,dp(16)+bars.top,dp(20)+bars.right,dp(24)+bars.bottom)
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(root)
        if(!prefs.getBoolean("initialPermissionsRequested",false)) root.post {
            prefs.edit().putBoolean("initialPermissionsRequested",true).apply()
            requestBasicPermissions()
        }
        else if(savedInstanceState==null) root.post { showInitialMediaAccess() }
    }
    private fun requestBasicPermissions() {
        val needed=mutableListOf<String>()
        if(!localAllowed()) needed.add("android.permission.ACCESS_LOCAL_NETWORK")
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.RECORD_AUDIO)
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if(needed.isNotEmpty()) requestPermissions(needed.toTypedArray(),4)
        else showInitialMediaAccess()
    }
    private fun showInitialMediaAccess() {
        if(prefs.getBoolean("initialMediaAccessRequested",false)) return
        prefs.edit().putBoolean("initialMediaAccessRequested",true).apply()
        if(!MediaAppInfo.allowed(this)) showMediaAccess()
    }
    private fun showPermissionGuide() {
        prefs.edit().putBoolean("permissionGuideSeen",true).apply()
        permissionGuide?.dismiss()
        permissionGuide=MaterialAlertDialogBuilder(this).setTitle(Strings.get(R.string.permission_guide_title))
            .setMessage(Strings.get(R.string.permission_guide_body))
            .setPositiveButton(Strings.get(R.string.permission_setup)) { _,_ ->
                requestBasicPermissions()
            }
            .setNeutralButton(Strings.get(R.string.app_permission_settings)) { _,_ ->
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName")))
            }
            .setNegativeButton(Strings.get(R.string.later),null).show()
    }
    private fun toggleRelay() {
        if(LiveService.running) { chooseCastAfterStart=false; startService(Intent(this,LiveService::class.java).setAction("STOP")) }
        else { pendingMode="CAPTURE"; continueStart() }
    }
    private fun updateSourceApp() {
        val allowed=MediaAppInfo.allowed(this)
        val packages=MediaAppInfo.packages(this)
        val options=(packages+listOfNotNull(LiveService.selectedPackage)).distinct()
        val signature=options.joinToString()+":"+LiveService.selectedUid+":"+allowed
        if(choiceSignature!=signature) {
            choiceSignature=signature;captureChoices.removeAllViews()
            fun choice(pkg:String?) {
                val app=pkg?.let { runCatching { packageManager.getApplicationInfo(it,0) }.getOrNull() }
                val chosen=if(pkg==null) LiveService.selectedUid<0 else LiveService.selectedPackage==pkg
                val label=app?.loadLabel(packageManager)?.toString() ?: pkg ?: Strings.get(R.string.capture_all)
                val detail=if(pkg==null) Strings.get(R.string.capture_all_hint) else Strings.get(R.string.capture_app_hint,label)
                val item=row().apply {
                    gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(58);setPadding(dp(10),dp(5),dp(4),dp(5))
                    isFocusable=true;isSelected=chosen
                    val dark=resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
                    background=android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                        if(dark) { if(chosen) intArrayOf(0xff3b285e.toInt(),0xff2d2447.toInt()) else intArrayOf(0xff222237.toInt(),0xff1d1e2f.toInt()) }
                        else { if(chosen) intArrayOf(0xffeadcff.toInt(),0xfff2e9ff.toInt()) else intArrayOf(0xfffaf7ff.toInt(),0xfffaf7ff.toInt()) }).apply {
                        cornerRadius=dp(10).toFloat()
                    }
                    contentDescription="$label. $detail"
                    androidx.core.view.ViewCompat.setAccessibilityDelegate(this,object:androidx.core.view.AccessibilityDelegateCompat() {
                        override fun onInitializeAccessibilityNodeInfo(host:View,info:androidx.core.view.accessibility.AccessibilityNodeInfoCompat) {
                            super.onInitializeAccessibilityNodeInfo(host,info);info.className="android.widget.RadioButton";info.isCheckable=true;info.isChecked=chosen
                        }
                    })
                    setOnClickListener { LiveService.selectedPackage=pkg;LiveService.selectedUid=app?.uid ?: -1;choiceSignature="";updateSourceApp() }
                }
                item.addView(ImageView(this).apply {
                    setImageResource(if(chosen) R.drawable.ic_check_circle_filled else R.drawable.ic_circle_outline)
                    imageTintList=android.content.res.ColorStateList.valueOf(if(chosen) 0xffb576e8.toInt() else 0xff8986a7.toInt())
                    importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },LinearLayout.LayoutParams(dp(20),dp(20)))
                item.addView(ImageView(this).apply {
                    setImageDrawable(app?.loadIcon(packageManager) ?: androidx.appcompat.content.res.AppCompatResources.getDrawable(this@MainActivity,R.drawable.ic_cast_source))
                    if(app==null) {
                        imageTintList=android.content.res.ColorStateList.valueOf(color(com.google.android.material.R.attr.colorOnSurface))
                        setPadding(dp(2),dp(2),dp(2),dp(2))
                    }
                    importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },LinearLayout.LayoutParams(dp(32),dp(32)).apply { marginStart=dp(14);marginEnd=dp(12) })
                item.addView(column().apply {
                    importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    addView(text(label,15f,bold=true));addView(text(detail,10f,muted=true).apply { setPadding(0,dp(2),0,0) })
                },LinearLayout.LayoutParams(0,-2,1f))
                if(pkg!=null) item.addView(iconButton(Strings.get(R.string.open_source_app,label),R.drawable.ic_chevron) { openPlayingApp(pkg) },LinearLayout.LayoutParams(dp(48),dp(48)))
                captureChoices.addView(item,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(4) })
            }
            choice(null);options.forEach { choice(it) }
        }
        sourceStatus.text=when {
            !allowed -> Strings.get(R.string.media_access_needed)
            packages.isEmpty() -> Strings.get(R.string.no_playing_app)
            else -> ""
        }
        sourceStatus.visibility=if(sourceStatus.text.isEmpty()) View.GONE else View.VISIBLE
        mediaAccess.visibility=if(allowed) View.GONE else View.VISIBLE
    }
    private fun chooseCast() {
        if(!localAllowed()) { requestPermissions(arrayOf("android.permission.ACCESS_LOCAL_NETWORK"),3); return }
        if(castButtonReady) castButton.performClick()
        else { CastController.init(this); Toast.makeText(this,Strings.get(R.string.cast_waiting),Toast.LENGTH_SHORT).show() }
    }
    private fun openPlayingApp(target:String) {
        try {
            val launch=packageManager.getLaunchIntentForPackage(target)
            if(launch!=null) startActivity(launch) else Toast.makeText(this,Strings.get(R.string.app_unavailable),Toast.LENGTH_SHORT).show()
        } catch(_:Exception) { Toast.makeText(this,Strings.get(R.string.app_unavailable),Toast.LENGTH_SHORT).show() }
    }
    private fun metricHeading(label:String,drawable:Int)=row().apply {
        gravity=Gravity.CENTER_VERTICAL
        addView(ImageView(this@MainActivity).apply {
            setImageResource(drawable); imageTintList=android.content.res.ColorStateList.valueOf(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },LinearLayout.LayoutParams(dp(18),dp(18)))
        addView(text(label,11f,muted=true).apply { setPadding(dp(6),0,0,0) },LinearLayout.LayoutParams(0,-2,1f))
    }
    private fun showMediaAccess() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    android.content.ComponentName(this,MediaAccessService::class.java).flattenToString()))
        } catch(_:android.content.ActivityNotFoundException) {
            startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }
    private fun showSettings() {
        val dialog = BottomSheetDialog(this)
        settingsDialog?.dismiss(); settingsDialog = dialog
        val body = column().apply { setPadding(dp(24),dp(16),dp(24),dp(32)) }
        val settingsSecondary=if(resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES) 0xffc3bad5.toInt() else 0xff6e627c.toInt()
        val heading = row().apply { gravity=Gravity.CENTER_VERTICAL }
        val versionName=packageManager.getPackageInfo(packageName,0).versionName.orEmpty()
        heading.addView(text(Strings.get(R.string.settings),22f,bold=true),LinearLayout.LayoutParams(0,-2,1f))
        heading.addView(iconButton(Strings.get(R.string.close), R.drawable.ic_close) { dialog.dismiss() },LinearLayout.LayoutParams(dp(48),dp(48))); body.addView(heading)
        val stackValues=resources.configuration.screenWidthDp<360 || resources.configuration.fontScale>1.2f
        fun secondary(value:String,size:Float)=text(value,size).apply { setTextColor(settingsSecondary) }
        var sectionContent=column()
        fun section(label: String) {
            gap(body,20)
            body.addView(text(label,14f,bold=true).apply { setTextColor(color(androidx.appcompat.R.attr.colorPrimary)) })
            gap(body,8)
            sectionContent=column().apply { setPadding(dp(12),dp(6),dp(12),dp(6)) }
            body.addView(MaterialCardView(this).apply {
                radius=dp(12).toFloat();cardElevation=0f;strokeWidth=0
                setCardBackgroundColor(color(com.google.android.material.R.attr.colorSurfaceContainerLow))
                addView(sectionContent,android.widget.FrameLayout.LayoutParams(-1,-2))
            },LinearLayout.LayoutParams(-1,-2))
        }
        fun divider() {
            if(sectionContent.childCount>0) sectionContent.addView(View(this).apply {
                setBackgroundColor(androidx.core.graphics.ColorUtils.setAlphaComponent(color(com.google.android.material.R.attr.colorOnSurface),32))
            },LinearLayout.LayoutParams(-1,dp(1)))
        }
        fun entry(label:String,value:String?=null,summary:String?=null,stackValue:Boolean=stackValues,enabled:Boolean=true,click:()->Unit) {
            divider()
            val entry=row().apply {
                gravity=Gravity.CENTER_VERTICAL; minimumHeight=dp(56)
                setPadding(0,dp(8),0,dp(8))
                isEnabled=enabled; alpha=if(enabled) 1f else 0.38f
                isFocusable=true
                val attrs=obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                background=attrs.getDrawable(0); attrs.recycle()
                setOnClickListener { click() }
                contentDescription=listOfNotNull(label,value,summary).joinToString(", ")
            }
            val labels=column().apply { importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
            val values=LinearLayout(this).apply { orientation=if(stackValue) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
            values.addView(text(label,15f,bold=true),LinearLayout.LayoutParams(if(stackValue) -1 else 0,-2,if(stackValue) 0f else 1f))
            if(value!=null) values.addView(secondary(value,14f).apply {
                gravity=(if(stackValue) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL
                setPadding(if(stackValue) 0 else dp(8),if(stackValue) dp(2) else 0,0,0)
            },LinearLayout.LayoutParams(if(stackValue) -1 else 0,-2,if(stackValue) 0f else 1f))
            labels.addView(values)
            if(summary!=null) labels.addView(secondary(summary,12f).apply { setPadding(0,dp(3),0,0) })
            entry.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
            entry.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_chevron)
                imageTintList=android.content.res.ColorStateList.valueOf(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },LinearLayout.LayoutParams(dp(20),dp(20)).apply { marginStart=dp(8) })
            sectionContent.addView(entry)
        }
        fun valueHeading(label:String,value:String):TextView {
            val line=LinearLayout(this).apply { orientation=if(stackValues) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
            line.addView(text(label,15f,bold=true),LinearLayout.LayoutParams(if(stackValues) -1 else 0,-2,if(stackValues) 0f else 1f))
            val current=secondary(value,14f).apply { gravity=(if(stackValues) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL }
            line.addView(current,LinearLayout.LayoutParams(if(stackValues) -1 else -2,-2))
            sectionContent.addView(line)
            return current
        }
        section(Strings.get(R.string.settings_general))
        val themeModes=intArrayOf(-1,1,2)
        val themeLabels=arrayOf(Strings.get(R.string.system_theme),Strings.get(R.string.light_theme),Strings.get(R.string.dark_theme))
        val currentTheme=themeModes.indexOf(prefs.getInt("theme",-1)).coerceAtLeast(0)
        entry(Strings.get(R.string.appearance),themeLabels[currentTheme]) {
            languageDialog=MaterialAlertDialogBuilder(this).setTitle(Strings.get(R.string.appearance))
                .setSingleChoiceItems(themeLabels,currentTheme) { picker,index ->
                    picker.dismiss(); dialog.dismiss()
                    prefs.edit().putInt("theme",themeModes[index]).apply()
                    androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(themeModes[index])
                }.setNegativeButton(Strings.get(R.string.close),null).show()
        }
        val languageTags = arrayOf("", "ko", "en", "ar", "bg", "bn", "ca", "cs", "da", "de", "el", "es", "et", "fa", "fi", "fr", "gu", "he", "hi", "hr", "hu", "id", "is", "it", "ja", "kn", "lt", "lv", "ml", "mr", "nl", "no", "pa", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "sw", "ta", "te", "th", "tr", "uk", "ur", "vi", "zh-CN", "zu")
        val currentLanguage = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag() ?: ""
        val languageLabels = arrayOf(Strings.get(R.string.system_language), "한국어", "English", *languageTags.drop(3).map { tag -> Locale.forLanguageTag(tag).getDisplayName(Locale.forLanguageTag(tag)) }.toTypedArray())
        entry(Strings.get(R.string.language),languageLabels[languageTags.indexOf(currentLanguage).coerceAtLeast(0)]) {
            languageDialog = MaterialAlertDialogBuilder(this).setTitle(Strings.get(R.string.language))
                .setSingleChoiceItems(languageLabels,languageTags.indexOf(currentLanguage).coerceAtLeast(0)) { picker,index ->
                    picker.dismiss(); dialog.dismiss()
                    androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.forLanguageTags(languageTags[index]))
                }.setNegativeButton(Strings.get(R.string.close),null).show()
        }
        var autoSwitch:MaterialSwitch?=null
        fun toggle(label:String,summary:String,key:String,default:Boolean):MaterialSwitch {
            divider()
            val control=MaterialSwitch(this).apply {
                text=android.text.SpannableString("$label\n$summary").apply {
                    setSpan(android.text.style.RelativeSizeSpan(0.85f),label.length+1,length,0)
                    setSpan(android.text.style.ForegroundColorSpan(settingsSecondary),label.length+1,length,0)
                }
                textSize=15f; isChecked=prefs.getBoolean(key,default); minHeight=dp(64)
                setPadding(0,dp(6),0,dp(6)); compoundDrawablePadding=dp(12)
                setOnCheckedChangeListener { _,checked ->
                    prefs.edit().putBoolean(key,checked).apply()
                    if(key=="remember"&&!checked) prefs.edit().remove("routeId").remove("routeName").apply()
                    if(key=="remember") autoSwitch?.isEnabled=checked
                    if(key=="auto"&&checked) { CastController.discovery(false); CastController.discovery(localAllowed()) }
                }
            }
            sectionContent.addView(control,LinearLayout.LayoutParams(-1,-2))
            return control
        }
        toggle(Strings.get(R.string.settings_remember),Strings.get(R.string.settings_remember_hint),"remember",true)
        autoSwitch=toggle(Strings.get(R.string.settings_auto),Strings.get(R.string.settings_auto_hint),"auto",false).apply { isEnabled=prefs.getBoolean("remember",true) }
        section(Strings.get(R.string.settings_audio))
        val bitrateValue=valueHeading(Strings.get(R.string.stream_quality),Strings.get(R.string.settings_bitrate_value,prefs.getInt("bitrate",192000)/1000))
        gap(sectionContent,8)
        val rates=intArrayOf(128000,192000,256000,320000)
        val bitrateRange = runCatching {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
                .first { it.isEncoder && it.supportedTypes.any { type -> type.equals("audio/mp4a-latm", true) } }
                .getCapabilitiesForType("audio/mp4a-latm").audioCapabilities?.bitrateRange
        }.getOrNull()
        val group=MaterialButtonToggleGroup(this).apply { isSingleSelection=true; isSelectionRequired=true }
        val ids= mutableMapOf<Int,Int>()
        rates.forEach { rate ->
            val item=button("${rate/1000}") {}.apply {
                id=View.generateViewId(); minWidth=0; minimumWidth=0; minHeight=dp(48)
                setPadding(0,dp(4),0,dp(4)); insetTop=dp(4); insetBottom=dp(4)
                isEnabled=bitrateRange?.contains(rate) ?: (rate<=256000); contentDescription="${rate/1000} kbps"
                val states=arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf(android.R.attr.state_checked),intArrayOf())
                backgroundTintList=android.content.res.ColorStateList(states,intArrayOf(color(com.google.android.material.R.attr.colorSurfaceContainerHigh),color(androidx.appcompat.R.attr.colorPrimary),color(com.google.android.material.R.attr.colorSurfaceContainerLow)))
                setTextColor(android.content.res.ColorStateList(states,intArrayOf(androidx.core.graphics.ColorUtils.setAlphaComponent(color(com.google.android.material.R.attr.colorOnSurface),97),color(com.google.android.material.R.attr.colorOnPrimary),color(com.google.android.material.R.attr.colorOnSurface))))
            }
            ids[item.id]=rate; group.addView(item,LinearLayout.LayoutParams(0,-2,1f))
            if(rate==prefs.getInt("bitrate",192000)) group.check(item.id)
        }
        group.addOnButtonCheckedListener { _,id,checked -> if(checked) ids[id]?.let { prefs.edit().putInt("bitrate",it).apply(); bitrateValue.text=Strings.get(R.string.settings_bitrate_value,it/1000) } }
        sectionContent.addView(group)
        gap(sectionContent,6)
        sectionContent.addView(secondary(Strings.get(R.string.settings_quality_hint),12f))
        gap(sectionContent,18)
        val seconds=prefs.getInt("buffer",6).coerceIn(6,30)
        val bufferLabel=valueHeading(Strings.get(R.string.delay_title),Strings.get(R.string.seconds,seconds))
        sectionContent.addView(Slider(this).apply {
            valueFrom=6f; valueTo=30f; stepSize=1f; value=seconds.toFloat(); contentDescription=Strings.get(R.string.buffer_delay)
            thumbRadius=dp(8); trackHeight=dp(4); isTickVisible=false
            thumbTintList=android.content.res.ColorStateList.valueOf(color(androidx.appcompat.R.attr.colorPrimary))
            trackActiveTintList=thumbTintList
            trackInactiveTintList=android.content.res.ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(color(androidx.appcompat.R.attr.colorPrimary),45))
            setLabelFormatter { Strings.get(R.string.seconds,it.toInt()) }
            addOnChangeListener { _,value,fromUser -> if(fromUser) { prefs.edit().putInt("buffer",value.toInt()).apply(); bufferLabel.text=Strings.get(R.string.seconds,value.toInt()) } }
        })
        sectionContent.addView(row().apply {
            addView(secondary(Strings.get(R.string.settings_delay_short),12f),LinearLayout.LayoutParams(0,-2,1f))
            addView(secondary(Strings.get(R.string.settings_delay_buffer),12f).apply { gravity=Gravity.END },LinearLayout.LayoutParams(0,-2,1f))
        },LinearLayout.LayoutParams(-1,-2).apply { topMargin=-dp(10) })
        gap(sectionContent,6)
        sectionContent.addView(secondary(Strings.get(R.string.settings_delay_hint),12f))
        section(Strings.get(R.string.settings_advanced))
        entry(Strings.get(R.string.test_sample),enabled=!LiveService.running) { dialog.dismiss(); pendingMode="SAMPLE"; continueStart() }
        entry(Strings.get(R.string.error_logs)) { showLogs() }
        entry(Strings.get(R.string.copy_url)) {
            val address=LiveService.streamUrl
            if(address==null) Toast.makeText(this,Strings.get(R.string.url_not_ready),Toast.LENGTH_SHORT).show()
            else { copy(address); Toast.makeText(this,Strings.get(R.string.url_copied),Toast.LENGTH_SHORT).show() }
        }
        gap(sectionContent,8)
        sectionContent.addView(secondary(Strings.get(R.string.mute_hint),12f))
        section(Strings.get(R.string.settings_about))
        entry(Strings.get(R.string.settings_permissions)) { dialog.dismiss();showPermissionGuide() }
        entry(Strings.get(R.string.settings_notification_access)) { dialog.dismiss();showMediaAccess() }
        entry(Strings.get(R.string.privacy_policy)) { showPrivacyPolicy() }
        gap(body,20)
        body.addView(text(Strings.get(R.string.settings_app_version,versionName),11f).apply {
            gravity=Gravity.CENTER
            setTextColor(settingsSecondary)
        },LinearLayout.LayoutParams(-1,-2))
        val settingsScroll=androidx.core.widget.NestedScrollView(this).apply {
            clipToPadding=false
            addView(body)
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { view,insets ->
                val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
                view.setPadding(bars.left,0,bars.right,bars.bottom)
                insets
            }
        }
        dialog.setContentView(settingsScroll)
        // Let gestures scroll settings immediately instead of expanding the sheet first.
        dialog.behavior.apply {
            isDraggable=false
            isDraggableOnNestedScroll=false
            skipCollapsed=true
            state=com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        }
        dialog.show()
    }
    private fun showPrivacyPolicy() {
        val policy=resources.openRawResource(R.raw.privacy_policy).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val content=text(policy,14f).apply {
            setPadding(dp(24),dp(12),dp(24),dp(24))
            setTextIsSelectable(true)
            android.text.util.Linkify.addLinks(this,android.text.util.Linkify.WEB_URLS or android.text.util.Linkify.EMAIL_ADDRESSES)
        }
        privacyDialog?.dismiss()
        privacyDialog=MaterialAlertDialogBuilder(this).setTitle(Strings.get(R.string.privacy_policy))
            .setView(androidx.core.widget.NestedScrollView(this).apply { addView(content) })
            .setPositiveButton(Strings.get(R.string.close),null).show()
    }
    private fun showLogs() {
        Diagnostics.save()
        val content=text("${LiveService.status}\n\n${Diagnostics.text().ifEmpty { Strings.get(R.string.no_logs) }}",12f).apply { setPadding(dp(16),dp(12),dp(16),dp(12)); setTextIsSelectable(true) }
        logsDialog?.dismiss()
        logsDialog = MaterialAlertDialogBuilder(this).setTitle(Strings.get(R.string.logs_title)).setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton(Strings.get(R.string.close),null).setNeutralButton(Strings.get(R.string.copy)) { _,_ -> copy("${LiveService.status}\n${Diagnostics.text()}") }.show()
    }
    private fun column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun row()=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
    private fun text(value:String,size:Float,muted:Boolean=false,bold:Boolean=false)=TextView(this).apply {
        text=value; textSize=size
        setTextColor(color(if(muted) com.google.android.material.R.attr.colorOnSurfaceVariant else com.google.android.material.R.attr.colorOnSurface))
        if(bold) typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
    }
    private fun button(label:String,primary:Boolean=false,click:()->Unit)=MaterialButton(this,null,
        if(primary) com.google.android.material.R.attr.materialButtonStyle else com.google.android.material.R.attr.materialButtonOutlinedStyle
    ).apply { text=label; textSize=14f; setOnClickListener { click() }; minHeight=dp(44) }
    private fun iconButton(label:String, drawable:Int, filled:Boolean=false, click:()->Unit)=button("",filled,click).apply {
        contentDescription=label; tooltipText=label; setIconResource(drawable)
        iconGravity=MaterialButton.ICON_GRAVITY_TEXT_START; iconPadding=0; iconSize=dp(24)
        minWidth=0; minimumWidth=0; setPadding(0,0,0,0)
        insetTop=0; insetBottom=0; cornerRadius=dp(24)
        if(!filled) { strokeWidth=0; backgroundTintList=android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT) }
    }
    private fun card(content:View,primary:Boolean=false)=MaterialCardView(this).apply {
        radius=dp(12).toFloat(); cardElevation=0f
        val dark=resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        setCardBackgroundColor(if(dark) color(if(primary) com.google.android.material.R.attr.colorPrimaryContainer else com.google.android.material.R.attr.colorSurfaceContainerLow) else if(primary) 0xfff5eeff.toInt() else 0xfffffdff.toInt())
        strokeWidth=dp(1); strokeColor=androidx.core.graphics.ColorUtils.setAlphaComponent(if(primary) {
            if(dark) 0xff443953.toInt() else 0xffd9c9ef.toInt()
        } else if(dark) 0xff4b4457.toInt() else 0xffded4ed.toInt(),if(dark) 128 else 255)
        addView(content,ViewGroup.LayoutParams(-1,-2))
    }
    private fun color(attribute:Int)=MaterialColors.getColor(this,attribute,0xff6750a4.toInt())
    private fun gap(parent:LinearLayout,height:Int) { parent.addView(View(this),LinearLayout.LayoutParams(1,dp(height))) }
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun formatTime(seconds:Long)=String.format(Locale.US,"%02d:%02d",seconds/60,seconds%60)
    private fun copy(value:String) { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(Strings.get(R.string.app_name),value)) }
    private fun localAllowed()=Build.VERSION.SDK_INT<37||checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")==PackageManager.PERMISSION_GRANTED
    private fun continueStart() {
        if(LiveService.running||CaptureService.running) { Toast.makeText(this,Strings.get(R.string.stop_first),Toast.LENGTH_SHORT).show(); pendingMode=null; return }
        if(!localAllowed()) { requestPermissions(arrayOf("android.permission.ACCESS_LOCAL_NETWORK"),3); return }
        if(pendingMode=="CAPTURE"&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),1); return }
        if(pendingMode=="SAMPLE") { startForegroundService(Intent(this,LiveService::class.java).setAction("SAMPLE")); chooseCastAfterStart=true; pendingMode=null }
        else if(pendingMode=="CAPTURE") {
            val manager=getSystemService(MediaProjectionManager::class.java)
            val intent=if(Build.VERSION.SDK_INT>=34) manager.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()) else manager.createScreenCaptureIntent()
            startActivityForResult(intent,10)
        }
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==4) { CastController.discovery(localAllowed());showInitialMediaAccess();return }
        if(requestCode==2||grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED) { CastController.discovery(localAllowed()); if(pendingMode!=null) continueStart() }
        else { pendingMode=null; Toast.makeText(this,Strings.get(R.string.permission_needed),Toast.LENGTH_LONG).show() }
    }
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==10) {
            pendingMode=null
            if(resultCode==RESULT_OK&&data!=null) {
                startForegroundService(Intent(this,LiveService::class.java).setAction("CAPTURE").putExtra("consent",data).putExtra("code",resultCode))
                chooseCastAfterStart=true
            }
        }
    }
    override fun onSaveInstanceState(outState:Bundle) { outState.putString("pendingMode",pendingMode); outState.putBoolean("chooseCastAfterStart",chooseCastAfterStart); outState.putBoolean("notificationAsked",notificationAsked); super.onSaveInstanceState(outState) }
    override fun onResume() { super.onResume(); LiveService.levels.setEnabled(true); handler.post(refresh); handler.post(visuals); CastController.discovery(localAllowed()) }
    override fun onPause() { LiveService.levels.setEnabled(false); handler.removeCallbacks(refresh); handler.removeCallbacks(visuals); CastController.discovery(false); super.onPause() }
    override fun onDestroy() { permissionGuide?.dismiss(); settingsDialog?.dismiss(); languageDialog?.dismiss(); sourceDialog?.dismiss(); logsDialog?.dismiss(); privacyDialog?.dismiss(); donationDialog?.dismiss(); super.onDestroy() }
}



