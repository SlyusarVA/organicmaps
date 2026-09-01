package app.organicmaps.wear;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.Map;
import app.organicmaps.sdk.MapController;
import app.organicmaps.sdk.MapRenderingListener;
import app.organicmaps.sdk.MapView;
import app.organicmaps.sdk.OrganicMaps;
import java.io.IOException;

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

  // Fixed initial viewport for the hardware PoC: Terskol / Elbrus area.
  private static final double TEST_LAT = 43.2550;
  private static final double TEST_LON = 42.5150;
  private static final int TEST_ZOOM = 6;

  @NonNull
  private final LifecycleRegistry mLifecycleRegistry = new LifecycleRegistry(this);

  private OrganicMaps mOrganicMaps;
  private MapController mMapController;
  private FrameLayout mRoot;
  private TextView mStatus;

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

    mOrganicMaps = new OrganicMaps(this, BuildConfig.FLAVOR, getPackageName(), BuildConfig.VERSION_CODE,
                                   BuildConfig.VERSION_NAME);

    try
    {
      boolean started = mOrganicMaps.init(() -> runOnUiThread(this::showMap));
      if (!started && mOrganicMaps.arePlatformAndCoreInitialized())
        showMap();
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
    mLifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
    super.onDestroy();
  }

  private void showBootstrapUi()
  {
    mRoot = new FrameLayout(this);

    ProgressBar progress = new ProgressBar(this);
    FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
    mRoot.addView(progress, progressParams);

    mStatus = new TextView(this);
    mStatus.setGravity(Gravity.CENTER);
    mStatus.setText("Initializing\nOrganic Maps core…");
    mStatus.setTextSize(13);

    FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
    int padding = getResources().getDimensionPixelSize(R.dimen.screen_padding);
    statusParams.setMargins(padding, padding, padding, padding * 2);
    mRoot.addView(mStatus, statusParams);

    setContentView(mRoot);
  }

  private void showMap()
  {
    if (isFinishing() || isDestroyed() || mMapController != null)
      return;

    try
    {
      MapView mapView = new MapView(this);
      MapRenderingListener renderingListener = new MapRenderingListener()
      {
        @Override
        public void onRenderingCreated()
        {
          centerOnTestArea();
        }

        @Override
        public void onRenderingRestored()
        {
          centerOnTestArea();
        }

        @Override
        public void onRenderingInitializationFinished()
        {
          centerOnTestArea();
        }
      };

      mMapController = new MapController(mapView, mOrganicMaps.getLocationHelper(), renderingListener,
                                         () -> showFailure("Drape graphics API unsupported"), false);
      getLifecycle().addObserver(mMapController);

      mRoot.removeAllViews();
      mRoot.addView(mapView, new FrameLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

      TextView badge = new TextView(this);
      badge.setText("OM Drape P1");
      badge.setTextSize(10);
      badge.setGravity(Gravity.CENTER);
      int padding = Math.max(4, getResources().getDimensionPixelSize(R.dimen.screen_padding) / 3);
      badge.setPadding(padding, padding / 2, padding, padding / 2);
      FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
          ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
          Gravity.TOP | Gravity.CENTER_HORIZONTAL);
      badgeParams.topMargin = padding;
      mRoot.addView(badge, badgeParams);

      Log.i(TAG, "Organic Maps MapView attached; waiting for Drape renderer");
    }
    catch (RuntimeException e)
    {
      Log.e(TAG, "Failed to create Organic Maps MapView", e);
      showFailure("MapView failed\n" + e.getClass().getSimpleName());
    }
  }

  private void centerOnTestArea()
  {
    runOnUiThread(() -> {
      try
      {
        Framework.nativeSetViewportCenter(TEST_LAT, TEST_LON, TEST_ZOOM);
        Log.i(TAG, "Drape renderer active; viewport centered on Terskol test area");
      }
      catch (RuntimeException | UnsatisfiedLinkError e)
      {
        Log.e(TAG, "Failed to set test viewport", e);
      }
    });
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
      mRoot.addView(error, new FrameLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    });
  }
}
