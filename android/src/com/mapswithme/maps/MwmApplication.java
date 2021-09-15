package com.mapswithme.maps;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Message;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.multidex.MultiDex;

import com.mapswithme.maps.background.AppBackgroundTracker;
import com.mapswithme.maps.background.NotificationChannelFactory;
import com.mapswithme.maps.background.NotificationChannelProvider;
import com.mapswithme.maps.background.Notifier;
import com.mapswithme.maps.base.MediaPlayerWrapper;
import com.mapswithme.maps.bookmarks.data.BookmarkManager;
import com.mapswithme.maps.downloader.CountryItem;
import com.mapswithme.maps.downloader.MapManager;
import com.mapswithme.maps.editor.Editor;
import com.mapswithme.maps.location.LocationHelper;
import com.mapswithme.maps.maplayer.isolines.IsolinesManager;
import com.mapswithme.maps.maplayer.subway.SubwayManager;
import com.mapswithme.maps.maplayer.traffic.TrafficManager;
import com.mapswithme.maps.routing.RoutingController;
import com.mapswithme.maps.search.SearchEngine;
import com.mapswithme.maps.settings.StoragePathManager;
import com.mapswithme.maps.sound.TtsPlayer;
import com.mapswithme.util.Config;
import com.mapswithme.util.ConnectionState;
import com.mapswithme.util.Counters;
import com.mapswithme.util.CrashlyticsUtils;
import com.mapswithme.util.SharedPropertiesUtils;
import com.mapswithme.util.StorageUtils;
import com.mapswithme.util.ThemeSwitcher;
import com.mapswithme.util.UiUtils;
import com.mapswithme.util.Utils;
import com.mapswithme.util.log.Logger;
import com.mapswithme.util.log.LoggerFactory;

import java.io.IOException;
import java.util.List;

public class MwmApplication extends Application implements AppBackgroundTracker.OnTransitionListener
{
  @SuppressWarnings("NotNullFieldNotInitialized")
  @NonNull
  private Logger mLogger;
  public final static String TAG = "MwmApplication";

  private AppBackgroundTracker mBackgroundTracker;
  @SuppressWarnings("NotNullFieldNotInitialized")
  @NonNull
  private SubwayManager mSubwayManager;

  @SuppressWarnings("NotNullFieldNotInitialized")
  @NonNull
  private IsolinesManager mIsolinesManager;

  private volatile boolean mFrameworkInitialized;
  private volatile boolean mPlatformInitialized;

  private Handler mMainLoopHandler;
  private final Object mMainQueueToken = new Object();
  @NonNull
  private final MapManager.StorageCallback mStorageCallbacks = new StorageCallbackImpl();
  @SuppressWarnings("NullableProblems")
  @NonNull
  private MediaPlayerWrapper mPlayer;
  private boolean mFirstLaunch;

  @NonNull
  public SubwayManager getSubwayManager()
  {
    return mSubwayManager;
  }

  @NonNull
  public IsolinesManager getIsolinesManager()
  {
    return mIsolinesManager;
  }

  public MwmApplication()
  {
    super();
  }

  @NonNull
  public static MwmApplication from(@NonNull Context context)
  {
    return (MwmApplication) context.getApplicationContext();
  }

  @NonNull
  public static AppBackgroundTracker backgroundTracker(@NonNull Context context)
  {
    return ((MwmApplication) context.getApplicationContext()).getBackgroundTracker();
  }

  @NonNull
  public static SharedPreferences prefs(@NonNull Context context)
  {
    String prefFile = context.getString(R.string.pref_file_name);
    return context.getSharedPreferences(prefFile, MODE_PRIVATE);
  }

  @Override
  protected void attachBaseContext(Context base)
  {
    super.attachBaseContext(base);
    MultiDex.install(this);
  }

  @SuppressWarnings("ResultOfMethodCallIgnored")
  @Override
  public void onCreate()
  {
    super.onCreate();
    LoggerFactory.INSTANCE.initialize(this);
    mLogger = LoggerFactory.INSTANCE.getLogger(LoggerFactory.Type.MISC);
    getLogger().d(TAG, "Application is created");
    // Set configuration directory as early as possible.
    // Other methods may explicitly use Config, which requires settingsDir to be set.
    final String settingsPath = StorageUtils.getSettingsPath(this);
    getLogger().d(TAG, "Settings path = " + settingsPath);
    try
    {
      StorageUtils.createDirectory(settingsPath);
      nativeSetSettingsDir(settingsPath);
    } catch (IOException e)
    {
      // We have nothing to do here.
      throw new AssertionError("Can't create settingsDir " + settingsPath);
    }
    mMainLoopHandler = new Handler(getMainLooper());
    ConnectionState.INSTANCE.initialize(this);
    CrashlyticsUtils.INSTANCE.initialize(this);
    
    initNotificationChannels();

    mBackgroundTracker = new AppBackgroundTracker(this);
    mSubwayManager = new SubwayManager(this);
    mIsolinesManager = new IsolinesManager(this);

    mPlayer = new MediaPlayerWrapper(this);
    WebView.setWebContentsDebuggingEnabled(Utils.isDebugOrBeta());
  }

  private void initNotificationChannels()
  {
    NotificationChannelProvider channelProvider = NotificationChannelFactory.createProvider(this);
    channelProvider.setDownloadingChannel();
  }

  /**
   * Initialize native core of application: platform and framework.
   *
   * @throws IOException - if failed to create directories. Caller must handle
   * the exception and do nothing with native code if initialization is failed.
   */
  public void init() throws IOException
  {
    initNativePlatform();
    initNativeFramework();
  }

  private void initNativePlatform() throws IOException
  {
    if (mPlatformInitialized)
      return;

    final Logger log = getLogger();
    final String apkPath = StorageUtils.getApkPath(this);
    log.d(TAG, "Apk path = " + apkPath);
    // Note: StoragePathManager uses Config, which requires initConfig() to be called.
    final String writablePath = StoragePathManager.findMapsStorage(this);
    log.d(TAG, "Writable path = " + writablePath);
    final String privatePath = StorageUtils.getPrivatePath(this);
    log.d(TAG, "Private path = " + privatePath);
    final String tempPath = StorageUtils.getTempPath(this);
    log.d(TAG, "Temp path = " + tempPath);

    // If platform directories are not created it means that native part of app will not be able
    // to work at all. So, we just ignore native part initialization in this case, e.g. when the
    // external storage is damaged or not available (read-only).
    createPlatformDirectories(writablePath, privatePath, tempPath);

    nativeInitPlatform(apkPath,
                       writablePath,
                       privatePath,
                       tempPath,
                       BuildConfig.FLAVOR,
                       BuildConfig.BUILD_TYPE, UiUtils.isTablet(this));

    Config.setStatisticsEnabled(SharedPropertiesUtils.isStatisticsEnabled(this));

    Editor.init(this);
    mPlatformInitialized = true;
    log.i(TAG, "Platform initialized");
  }

  private void createPlatformDirectories(@NonNull String writablePath,
                                            @NonNull String privatePath,
                                            @NonNull String tempPath) throws IOException
  {
    SharedPropertiesUtils.emulateBadExternalStorage(this);

    StorageUtils.createDirectory(writablePath);
    StorageUtils.createDirectory(privatePath);
    StorageUtils.createDirectory(tempPath);
  }

  private void initNativeFramework()
  {
    if (mFrameworkInitialized)
      return;

    nativeInitFramework();

    MapManager.nativeSubscribe(mStorageCallbacks);

    initNativeStrings();
    ThemeSwitcher.INSTANCE.initialize(this);
    SearchEngine.INSTANCE.initialize(null);
    BookmarkManager.loadBookmarks();
    TtsPlayer.INSTANCE.initialize(this);
    ThemeSwitcher.INSTANCE.restart(false);
    LocationHelper.INSTANCE.initialize(this);
    RoutingController.get().initialize(null);
    TrafficManager.INSTANCE.initialize(null);
    SubwayManager.from(this).initialize(null);
    IsolinesManager.from(this).initialize(null);
    mBackgroundTracker.addListener(this);

    getLogger().i(TAG, "Framework initialized");
    mFrameworkInitialized = true;
  }

  private void initNativeStrings()
  {
    nativeAddLocalization("core_entrance", getString(R.string.core_entrance));
    nativeAddLocalization("core_exit", getString(R.string.core_exit));
    nativeAddLocalization("core_my_places", getString(R.string.core_my_places));
    nativeAddLocalization("core_my_position", getString(R.string.core_my_position));
    nativeAddLocalization("core_placepage_unknown_place", getString(R.string.core_placepage_unknown_place));
    nativeAddLocalization("postal_code", getString(R.string.postal_code));
    nativeAddLocalization("wifi", getString(R.string.wifi));
  }

  public boolean arePlatformAndCoreInitialized()
  {
    return mFrameworkInitialized && mPlatformInitialized;
  }

  @NonNull
  public AppBackgroundTracker getBackgroundTracker()
  {
    return mBackgroundTracker;
  }

  static
  {
    System.loadLibrary("mapswithme");
  }

  public static void onUpgrade(@NonNull Context context)
  {
    Counters.resetAppSessionCounters(context);
  }

  @SuppressWarnings("unused")
  void forwardToMainThread(final long taskPointer)
  {
    Message m = Message.obtain(mMainLoopHandler, new Runnable()
    {
      @Override
      public void run()
      {
        nativeProcessTask(taskPointer);
      }
    });
    m.obj = mMainQueueToken;
    mMainLoopHandler.sendMessage(m);
  }

  @NonNull
  public MediaPlayerWrapper getMediaPlayer()
  {
    return mPlayer;
  }

  private static native void nativeSetSettingsDir(String settingsPath);
  private native void nativeInitPlatform(String apkPath, String writablePath, String privatePath,
                                         String tmpPath, String flavorName, String buildType,
                                         boolean isTablet);
  private static native void nativeInitFramework();
  private static native void nativeProcessTask(long taskPointer);
  private static native void nativeAddLocalization(String name, String value);
  private static native void nativeOnTransit(boolean foreground);

  @NonNull
  public Logger getLogger()
  {
    return mLogger;
  }

  public boolean isFirstLaunch()
  {
    return mFirstLaunch;
  }

  @Override
  public void onTransit(boolean foreground)
  {
    nativeOnTransit(foreground);
  }

  private class StorageCallbackImpl implements MapManager.StorageCallback
  {
    @Override
    public void onStatusChanged(List<MapManager.StorageCallbackData> data)
    {
      Notifier notifier = Notifier.from(MwmApplication.this);
      for (MapManager.StorageCallbackData item : data)
        if (item.isLeafNode && item.newStatus == CountryItem.STATUS_FAILED)
        {
          if (MapManager.nativeIsAutoretryFailed())
          {
            notifier.notifyDownloadFailed(item.countryId, MapManager.nativeGetName(item.countryId));
          }

          return;
        }
    }

    @Override
    public void onProgress(String countryId, long localSize, long remoteSize) {}
  }
}
