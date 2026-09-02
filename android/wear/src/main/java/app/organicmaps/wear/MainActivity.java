package app.organicmaps.wear;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.util.Xml;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.MapController;
import app.organicmaps.sdk.MapRenderingListener;
import app.organicmaps.sdk.MapView;
import app.organicmaps.sdk.OrganicMaps;
import app.organicmaps.sdk.bookmarks.data.BookmarkCategory;
import app.organicmaps.sdk.bookmarks.data.BookmarkManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.xmlpull.v1.XmlPullParser;

/**
 * P0/P1 Wear OS proof of concept.
 *
 * The goal of this activity is deliberately narrow: initialize the existing Organic Maps SDK,
 * create the real native Drape renderer through {@link MapView}, and show an offline map surface
 * on a Wear OS device. Phone navigation mirroring, GPX, GPS follow mode and map transfer are kept
 * out of this first hardware feasibility test.
 */
public final class MainActivity extends Activity implements LifecycleOwner
{
  private static final String TAG = MainActivity.class.getSimpleName();

  private static final String TRACKS_DIRECTORY = "tracks";

  @NonNull
  private final LifecycleRegistry mLifecycleRegistry = new LifecycleRegistry(this);

  private OrganicMaps mOrganicMaps;
  private MapController mMapController;
  private FrameLayout mRoot;
  @Nullable
  private File mSelectedTrack;
  @Nullable
  private GpxViewport mSelectedTrackViewport;
  @Nullable
  private BookmarkManager.BookmarksLoadingListener mTrackImportListener;
  @Nullable
  private TextView mBadge;
  private boolean mInitialViewportApplied;
  private boolean mTrackImportStarted;

  @NonNull
  @Override
  public Lifecycle getLifecycle()
  {
    return mLifecycleRegistry;
  }

  @Override
  protected void onCreate(Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    mLifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);

    showBootstrapUi();

    mOrganicMaps =
        new OrganicMaps(this, BuildConfig.FLAVOR, getPackageName(), BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME);

    try
    {
      boolean started = mOrganicMaps.init(() -> runOnUiThread(this::showTrackPicker));
      if (!started && mOrganicMaps.arePlatformAndCoreInitialized())
        showTrackPicker();
    }
    catch (IOException | RuntimeException e)
    {
      Log.e(TAG, "Organic Maps core initialization failed", e);
      showFailure("Core init failed\n" + e.getClass().getSimpleName());
    }
  }

  @Override
  protected void onStart()
  {
    super.onStart();
    mLifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START);
  }

  @Override
  protected void onResume()
  {
    super.onResume();
    mLifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME);
  }

  @Override
  protected void onPause()
  {
    mLifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE);
    super.onPause();
  }

  @Override
  protected void onStop()
  {
    mLifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP);
    super.onStop();
  }

  @Override
  protected void onDestroy()
  {
    removeTrackImportListener();
    mLifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
    super.onDestroy();
  }

  private void showTrackPicker()
  {
    if (isFinishing() || isDestroyed())
      return;

    File tracksDirectory = getTracksDirectory();
    File[] tracks = tracksDirectory.listFiles(
        file -> file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".gpx"));
    if (tracks == null)
      tracks = new File[0];
    Arrays.sort(tracks, (left, right) -> left.getName().compareToIgnoreCase(right.getName()));

    mRoot.removeAllViews();

    LinearLayout list = new LinearLayout(this);
    list.setOrientation(LinearLayout.VERTICAL);
    list.setGravity(Gravity.CENTER_HORIZONTAL);
    int padding = getResources().getDimensionPixelSize(R.dimen.screen_padding);
    list.setPadding(padding, padding, padding, padding);

    TextView title = new TextView(this);
    title.setGravity(Gravity.CENTER);
    title.setText("Choose GPX track");
    title.setTextSize(15);
    list.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                       ViewGroup.LayoutParams.WRAP_CONTENT));

    if (tracks.length == 0)
    {
      TextView empty = new TextView(this);
      empty.setGravity(Gravity.CENTER);
      empty.setText("No GPX files\n\nCopy tracks to:\n" + tracksDirectory.getAbsolutePath() + "\n\nTap to refresh");
      empty.setTextSize(11);
      empty.setPadding(0, padding, 0, padding);
      empty.setOnClickListener(view -> showTrackPicker());
      list.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                         ViewGroup.LayoutParams.WRAP_CONTENT));
    }
    else
    {
      for (File track : tracks)
      {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(stripGpxExtension(track.getName()));
        button.setTextSize(11);
        button.setOnClickListener(view -> selectTrack(track));
        list.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                            ViewGroup.LayoutParams.WRAP_CONTENT));
      }
    }

    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.addView(list, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                      ViewGroup.LayoutParams.WRAP_CONTENT));
    mRoot.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                       ViewGroup.LayoutParams.MATCH_PARENT));
  }

  @NonNull
  private File getTracksDirectory()
  {
    File baseDirectory = getExternalFilesDir(null);
    if (baseDirectory == null)
      baseDirectory = getFilesDir();
    File tracksDirectory = new File(baseDirectory, TRACKS_DIRECTORY);
    if (!tracksDirectory.exists() && !tracksDirectory.mkdirs())
      Log.w(TAG, "Failed to create tracks directory: " + tracksDirectory);
    return tracksDirectory;
  }

  private void selectTrack(@NonNull File track)
  {
    showBusy("Reading\n" + stripGpxExtension(track.getName()));
    new Thread(() -> {
      try
      {
        GpxViewport viewport = readGpxViewport(track);
        runOnUiThread(() -> {
          if (isFinishing() || isDestroyed())
            return;
          mSelectedTrack = track;
          mSelectedTrackViewport = viewport;
          showMap();
        });
      }
      catch (IOException | RuntimeException e)
      {
        Log.e(TAG, "Failed to read GPX track " + track, e);
        showFailure("GPX read failed\n" + track.getName());
      }
    }, "WearGpxParser").start();
  }

  private void showBusy(@NonNull String message)
  {
    mRoot.removeAllViews();
    ProgressBar progress = new ProgressBar(this);
    FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
    mRoot.addView(progress, progressParams);

    TextView status = new TextView(this);
    status.setGravity(Gravity.CENTER);
    status.setText(message);
    status.setTextSize(12);
    FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
    int padding = getResources().getDimensionPixelSize(R.dimen.screen_padding);
    statusParams.setMargins(padding, padding, padding, padding * 2);
    mRoot.addView(status, statusParams);
  }

  private void showBootstrapUi()
  {
    mRoot = new FrameLayout(this);

    ProgressBar progress = new ProgressBar(this);
    FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
    mRoot.addView(progress, progressParams);

    TextView status = new TextView(this);
    status.setGravity(Gravity.CENTER);
    status.setText("Initializing\nOrganic Maps core…");
    status.setTextSize(13);

    FrameLayout.LayoutParams statusParams =
        new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                                     Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
    int padding = getResources().getDimensionPixelSize(R.dimen.screen_padding);
    statusParams.setMargins(padding, padding, padding, padding * 2);
    mRoot.addView(status, statusParams);

    setContentView(mRoot);
  }

  private void showMap()
  {
    if (isFinishing() || isDestroyed() || mMapController != null)
      return;

    try
    {
      MapView mapView = new MapView(this);
      MapRenderingListener renderingListener = new MapRenderingListener() {
        @Override
        public void onRenderingCreated()
        {
          centerOnSelectedTrack();
        }

        @Override
        public void onRenderingRestored()
        {
          centerOnSelectedTrack();
        }

        @Override
        public void onRenderingInitializationFinished()
        {
          centerOnSelectedTrack();
        }
      };

      mMapController = new MapController(mapView, mOrganicMaps.getLocationHelper(), renderingListener,
                                         () -> showFailure("Drape graphics API unsupported"), false);
      getLifecycle().addObserver(mMapController);

      mRoot.removeAllViews();
      mRoot.addView(mapView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                          ViewGroup.LayoutParams.MATCH_PARENT));

      mBadge = new TextView(this);
      mBadge.setText("Loading GPX…");
      mBadge.setTextSize(10);
      mBadge.setGravity(Gravity.CENTER);
      int padding = Math.max(4, getResources().getDimensionPixelSize(R.dimen.screen_padding) / 3);
      mBadge.setPadding(padding, padding / 2, padding, padding / 2);
      FrameLayout.LayoutParams badgeParams =
          new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                                       Gravity.TOP | Gravity.CENTER_HORIZONTAL);
      badgeParams.topMargin = padding;
      mRoot.addView(mBadge, badgeParams);

      Log.i(TAG, "Organic Maps MapView attached; waiting for Drape renderer");
    }
    catch (RuntimeException | UnsatisfiedLinkError e)
    {
      Log.e(TAG, "Failed to create Organic Maps MapView", e);
      showFailure("MapView failed\n" + e.getClass().getSimpleName());
    }
  }

  private void centerOnSelectedTrack()
  {
    runOnUiThread(() -> {
      if (mInitialViewportApplied || mSelectedTrack == null || mSelectedTrackViewport == null)
        return;
      mInitialViewportApplied = true;
      try
      {
        Framework.nativeSetViewportCenterImmediately(mSelectedTrackViewport.mLat, mSelectedTrackViewport.mLon,
                                                     mSelectedTrackViewport.mZoom);
        Log.i(TAG, "Drape renderer active; viewport centered on GPX " + mSelectedTrack.getName());
        importSelectedTrack();
      }
      catch (RuntimeException | UnsatisfiedLinkError e)
      {
        Log.e(TAG, "Failed to set GPX viewport", e);
      }
    });
  }

  private void importSelectedTrack()
  {
    if (mTrackImportStarted || mSelectedTrack == null)
      return;
    mTrackImportStarted = true;

    BookmarkManager manager = BookmarkManager.INSTANCE;
    Set<Long> existingTrackIds = collectTrackIds(manager);
    mTrackImportListener = new BookmarkManager.BookmarksLoadingListener() {
      private boolean mFileLoaded;

      @Override
      public void onBookmarksFileImportSuccessful()
      {
        mFileLoaded = true;
        setBadge("Opening GPX…");
      }

      @Override
      public void onBookmarksLoadingFinished()
      {
        if (!mFileLoaded)
          return;

        mRoot.post(MainActivity.this::removeTrackImportListener);
        long importedTrackId = findNewTrackId(manager, existingTrackIds);
        if (importedTrackId != 0)
        {
          manager.setTrackVisibility(importedTrackId, true);
          Framework.nativeShowTrackRect(importedTrackId);
          setBadge("GPX: " + stripGpxExtension(mSelectedTrack.getName()));
          Log.i(TAG, "GPX imported and shown, track id=" + importedTrackId);
          return;
        }

        setBadge("GPX imported");
        Log.w(TAG, "GPX import succeeded, but no new track id was found");
      }

      @Override
      public void onBookmarksFileImportFailed()
      {
        mRoot.post(MainActivity.this::removeTrackImportListener);
        setBadge("GPX import failed");
        Log.e(TAG, "GPX import failed: " + mSelectedTrack);
      }
    };
    manager.addLoadingListener(mTrackImportListener);
    manager.loadBookmarksFile(mSelectedTrack.getAbsolutePath(), false);
  }

  private void removeTrackImportListener()
  {
    if (mTrackImportListener == null)
      return;
    BookmarkManager.INSTANCE.removeLoadingListener(mTrackImportListener);
    mTrackImportListener = null;
  }

  private void setBadge(@NonNull String text)
  {
    if (mBadge != null)
      mBadge.setText(text);
  }

  @NonNull
  private static Set<Long> collectTrackIds(@NonNull BookmarkManager manager)
  {
    Set<Long> result = new HashSet<>();
    for (BookmarkCategory category : manager.getCategories())
    {
      for (long trackId : category.getTrackIds())
        result.add(trackId);
    }
    return result;
  }

  private static long findNewTrackId(@NonNull BookmarkManager manager, @NonNull Set<Long> existingTrackIds)
  {
    for (BookmarkCategory category : manager.getCategories())
    {
      for (long trackId : category.getTrackIds())
      {
        if (!existingTrackIds.contains(trackId))
          return trackId;
      }
    }
    return 0;
  }

  @NonNull
  private static String stripGpxExtension(@NonNull String name)
  {
    return name.toLowerCase(Locale.ROOT).endsWith(".gpx") ? name.substring(0, name.length() - 4) : name;
  }

  @NonNull
  private static GpxViewport readGpxViewport(@NonNull File track) throws IOException
  {
    double minLat = Double.POSITIVE_INFINITY;
    double maxLat = Double.NEGATIVE_INFINITY;
    double minLon = Double.POSITIVE_INFINITY;
    double maxLon = Double.NEGATIVE_INFINITY;

    try (FileInputStream input = new FileInputStream(track))
    {
      XmlPullParser parser = Xml.newPullParser();
      parser.setInput(input, null);
      for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next())
      {
        if (event != XmlPullParser.START_TAG)
          continue;
        String tag = parser.getName();
        if (!"trkpt".equals(tag) && !"rtept".equals(tag) && !"wpt".equals(tag))
          continue;

        String latValue = parser.getAttributeValue(null, "lat");
        String lonValue = parser.getAttributeValue(null, "lon");
        if (latValue == null || lonValue == null)
          continue;
        double lat = Double.parseDouble(latValue);
        double lon = Double.parseDouble(lonValue);
        minLat = Math.min(minLat, lat);
        maxLat = Math.max(maxLat, lat);
        minLon = Math.min(minLon, lon);
        maxLon = Math.max(maxLon, lon);
      }
    }
    catch (org.xmlpull.v1.XmlPullParserException e)
    {
      throw new IOException("Invalid GPX", e);
    }

    if (!Double.isFinite(minLat) || !Double.isFinite(minLon))
      throw new IOException("GPX contains no track points");

    double span = Math.max(maxLat - minLat, maxLon - minLon);
    return new GpxViewport((minLat + maxLat) / 2.0, (minLon + maxLon) / 2.0, zoomForSpan(span));
  }

  private static int zoomForSpan(double span)
  {
    if (span < 0.005)
      return 16;
    if (span < 0.02)
      return 15;
    if (span < 0.08)
      return 14;
    if (span < 0.3)
      return 12;
    if (span < 1.0)
      return 10;
    if (span < 4.0)
      return 8;
    return 6;
  }

  private static final class GpxViewport
  {
    final double mLat;
    final double mLon;
    final int mZoom;

    GpxViewport(double lat, double lon, int zoom)
    {
      mLat = lat;
      mLon = lon;
      mZoom = zoom;
    }
  }

  private void showFailure(@NonNull String message)
  {
    runOnUiThread(() -> {
      if (mRoot == null)
        return;
      mRoot.removeAllViews();
      TextView error = new TextView(this);
      error.setGravity(Gravity.CENTER);
      error.setText(message);
      error.setTextSize(13);
      int padding = getResources().getDimensionPixelSize(R.dimen.screen_padding);
      error.setPadding(padding, padding, padding, padding);
      mRoot.addView(error, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                        ViewGroup.LayoutParams.MATCH_PARENT));
    });
  }
}
