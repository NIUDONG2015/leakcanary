package com.squareup.leakcanary;

import android.app.Application;
import android.content.Context;
import android.content.res.Resources;

import static com.squareup.leakcanary.RefWatcher.DISABLED;

public final class AndroidRefWatcherBuilder extends RefWatcher.Builder<AndroidRefWatcherBuilder> {

  private final Context context;

  AndroidRefWatcherBuilder(Context context) {
    this.context = context.getApplicationContext();
  }

  /**
   * Sets a custom {@link AbstractAnalysisResultService} to listen to analysis results. This
   * overrides any call to {@link #heapDumpListener(HeapDump.Listener)}.
   */
  public AndroidRefWatcherBuilder listenerServicejClass(
      Class<? extends AbstractAnalysisResultService> listenerServiceClass) {
    return heapDumpListener(new ServiceHeapDumpListener(context, listenerServiceClass));
  }

  /**
   * Creates a {@link RefWatcher} instance and starts watching activity references (on ICS+).
   */
  public RefWatcher buildAndInstall() {
    RefWatcher refWatcher = build();
    if (refWatcher != DISABLED) {
      LeakCanary.enableDisplayLeakActivity(context);
      ActivityRefWatcher.installOnIcsPlus((Application) context, refWatcher);
    }
    return refWatcher;
  }

  @Override protected boolean isDisabled() {
    return LeakCanary.isInAnalyzerProcess(context);
  }

  @Override protected HeapDumper defaultHeapDumper() {
    LeakDirectoryProvider leakDirectoryProvider = new DefaultLeakDirectoryProvider(context);
    return new AndroidHeapDumper(context, leakDirectoryProvider);
  }

  @Override protected DebuggerControl defaultDebuggerControl() {
    return new AndroidDebuggerControl();
  }

  @Override protected HeapDump.Listener defaultHeapDumpListener() {
    return new ServiceHeapDumpListener(context, DisplayLeakService.class);
  }

  @Override protected ExcludedRefs defaultExcludedRefs() {
    return AndroidExcludedRefs.createAppDefaults().build();
  }

  @Override protected WatchExecutor defaultWatchExecutor() {
    Resources resources = context.getResources();
    int watchDelayMillis = resources.getInteger(R.integer.leak_canary_watch_delay_millis);
    return new AndroidWatchExecutor(watchDelayMillis);
  }
}
