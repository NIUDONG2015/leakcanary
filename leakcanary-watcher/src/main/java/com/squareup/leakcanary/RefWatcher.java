/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.leakcanary;

import java.io.File;
import java.lang.ref.ReferenceQueue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

import static com.squareup.leakcanary.HeapDumper.RETRY_LATER;
import static com.squareup.leakcanary.Preconditions.checkNotNull;
import static com.squareup.leakcanary.Retryable.Result.DONE;
import static com.squareup.leakcanary.Retryable.Result.RETRY;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * Watches references that should become weakly reachable. When the {@link RefWatcher} detects that
 * a reference might not be weakly reachable when it should, it triggers the {@link HeapDumper}.
 *
 * <p>This class is thread-safe: you can call {@link #watch(Object)} from any thread.
 */
public final class RefWatcher {

  public static final RefWatcher DISABLED = new RefWatcher(new WatchExecutor() {
    @Override public void execute(Retryable retryable) {
    }
  }, new DebuggerControl() {
    @Override public boolean isDebuggerAttached() {
      // Skips watching.
      return true;
    }
  }, GcTrigger.DEFAULT, new HeapDumper() {
    @Override public File dumpHeap() {
      return RETRY_LATER;
    }
  }, new HeapDump.Listener() {
    @Override public void analyze(HeapDump heapDump) {
    }
  }, new ExcludedRefs.BuilderWithParams().build());

  private final WatchExecutor watchExecutor;
  private final DebuggerControl debuggerControl;
  private final GcTrigger gcTrigger;
  private final HeapDumper heapDumper;
  private final Set<String> retainedKeys;
  private final ReferenceQueue<Object> queue;
  private final HeapDump.Listener heapdumpListener;
  private final ExcludedRefs excludedRefs;

  public RefWatcher(WatchExecutor watchExecutor, DebuggerControl debuggerControl,
      GcTrigger gcTrigger, HeapDumper heapDumper, HeapDump.Listener heapdumpListener,
      ExcludedRefs excludedRefs) {
    this.watchExecutor = checkNotNull(watchExecutor, "watchExecutor");
    this.debuggerControl = checkNotNull(debuggerControl, "debuggerControl");
    this.gcTrigger = checkNotNull(gcTrigger, "gcTrigger");
    this.heapDumper = checkNotNull(heapDumper, "heapDumper");
    this.heapdumpListener = checkNotNull(heapdumpListener, "heapdumpListener");
    this.excludedRefs = checkNotNull(excludedRefs, "excludedRefs");
    retainedKeys = new CopyOnWriteArraySet<>();
    queue = new ReferenceQueue<>();
  }

  /**
   * Identical to {@link #watch(Object, String)} with an empty string reference name.
   *
   * @see #watch(Object, String)
   */
  public void watch(Object watchedReference) {
    watch(watchedReference, "");
  }

  /**
   * Watches the provided references and checks if it can be GCed. This method is non blocking,
   * the check is done on the {@link WatchExecutor} this {@link RefWatcher} has been constructed
   * with.
   *
   * @param referenceName An logical identifier for the watched object.
   */
  public void watch(Object watchedReference, String referenceName) {
    checkNotNull(watchedReference, "watchedReference");
    checkNotNull(referenceName, "referenceName");
    final long watchStartNanoTime = System.nanoTime();
    String key = UUID.randomUUID().toString();
    retainedKeys.add(key);
    final KeyedWeakReference reference =
        new KeyedWeakReference(watchedReference, key, referenceName, queue);

    ensureGoneAsync(watchStartNanoTime, reference);
  }

  private void ensureGoneAsync(final long watchStartNanoTime, final KeyedWeakReference reference) {
    watchExecutor.execute(new Retryable() {
      @Override public Retryable.Result run() {
        return ensureGone(reference, watchStartNanoTime);
      }
    });
  }

  Retryable.Result ensureGone(final KeyedWeakReference reference, final long watchStartNanoTime) {
    long gcStartNanoTime = System.nanoTime();
    long watchDurationMs = NANOSECONDS.toMillis(gcStartNanoTime - watchStartNanoTime);

    removeWeaklyReachableReferences();

    if (debuggerControl.isDebuggerAttached()) {
      // The debugger can create false leaks.
      return RETRY;
    }
    if (gone(reference)) {
      return DONE;
    }
    gcTrigger.runGc();
    removeWeaklyReachableReferences();
    if (!gone(reference)) {
      long startDumpHeap = System.nanoTime();
      long gcDurationMs = NANOSECONDS.toMillis(startDumpHeap - gcStartNanoTime);

      File heapDumpFile = heapDumper.dumpHeap();
      if (heapDumpFile == RETRY_LATER) {
        // Could not dump the heap.
        return RETRY;
      }
      long heapDumpDurationMs = NANOSECONDS.toMillis(System.nanoTime() - startDumpHeap);
      heapdumpListener.analyze(
          new HeapDump(heapDumpFile, reference.key, reference.name, excludedRefs, watchDurationMs,
              gcDurationMs, heapDumpDurationMs));
    }
    return DONE;
  }

  private boolean gone(KeyedWeakReference reference) {
    return !retainedKeys.contains(reference.key);
  }

  private void removeWeaklyReachableReferences() {
    // WeakReferences are enqueued as soon as the object to which they point to becomes weakly
    // reachable. This is before finalization or garbage collection has actually happened.
    KeyedWeakReference ref;
    while ((ref = (KeyedWeakReference) queue.poll()) != null) {
      retainedKeys.remove(ref.key);
    }
  }

  public static abstract class Builder<T extends Builder> {

    private ExcludedRefs excludedRefs;
    private HeapDump.Listener heapDumpListener;
    private DebuggerControl debuggerControl;
    private HeapDumper heapDumper;
    private WatchExecutor watchExecutor;
    private GcTrigger gcTrigger;

    /** TODO */
    public final T heapDumpListener(HeapDump.Listener heapDumpListener) {
      this.heapDumpListener = heapDumpListener;
      return self();
    }

    /** TODO */
    public final T excludedRefs(ExcludedRefs excludedRefs) {
      this.excludedRefs = excludedRefs;
      return self();
    }

    /** TODO */
    public final T heapDumper(HeapDumper heapDumper) {
      this.heapDumper = heapDumper;
      return self();
    }

    /** TODO */
    public final T debuggerControl(DebuggerControl debuggerControl) {
      this.debuggerControl = debuggerControl;
      return self();
    }

    /** TODO */
    public final T watchExecutor(WatchExecutor watchExecutor) {
      this.watchExecutor = watchExecutor;
      return self();
    }

    /** TODO */
    public final T gcTrigger(GcTrigger gcTrigger) {
      this.gcTrigger = gcTrigger;
      return self();
    }

    /** Creates a {@link RefWatcher}. */
    public final RefWatcher build() {
      if (isDisabled()) {
        return DISABLED;
      }

      ExcludedRefs excludedRefs = this.excludedRefs;
      if (excludedRefs == null) {
        excludedRefs = defaultExcludedRefs();
      }

      HeapDump.Listener heapDumpListener = this.heapDumpListener;
      if (heapDumpListener == null) {
        heapDumpListener = defaultHeapDumpListener();
      }

      DebuggerControl debuggerControl = this.debuggerControl;
      if (debuggerControl == null) {
        debuggerControl = defaultDebuggerControl();
      }

      HeapDumper heapDumper = this.heapDumper;
      if (heapDumper == null) {
        heapDumper = defaultHeapDumper();
      }

      WatchExecutor watchExecutor = this.watchExecutor;
      if (watchExecutor == null) {
        watchExecutor = defaultWatchExecutor();
      }

      GcTrigger gcTrigger = this.gcTrigger;
      if (gcTrigger == null) {
        gcTrigger = defaultGcTrigger();
      }

      return new RefWatcher(watchExecutor, debuggerControl, gcTrigger, heapDumper, heapDumpListener,
          excludedRefs);
    }

    protected boolean isDisabled() {
      return false;
    }

    protected GcTrigger defaultGcTrigger() {
      return GcTrigger.DEFAULT;
    }

    protected DebuggerControl defaultDebuggerControl() {
      return DebuggerControl.NONE;
    }

    protected ExcludedRefs defaultExcludedRefs() {
      return ExcludedRefs.builder().build();
    }

    protected abstract HeapDumper defaultHeapDumper();

    protected abstract HeapDump.Listener defaultHeapDumpListener();

    protected abstract WatchExecutor defaultWatchExecutor();

    protected final T self() {
      //noinspection unchecked
      return (T) this;
    }
  }
}
