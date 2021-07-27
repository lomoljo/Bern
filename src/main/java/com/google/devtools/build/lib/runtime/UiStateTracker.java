// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.runtime;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Comparators;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multiset;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionCompletionEvent;
import com.google.devtools.build.lib.actions.ActionExecutionMetadata;
import com.google.devtools.build.lib.actions.ActionProgressEvent;
import com.google.devtools.build.lib.actions.ActionScanningCompletedEvent;
import com.google.devtools.build.lib.actions.ActionStartedEvent;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CachingActionEvent;
import com.google.devtools.build.lib.actions.RemoteUploadEvent;
import com.google.devtools.build.lib.actions.RunningActionEvent;
import com.google.devtools.build.lib.actions.ScanningActionEvent;
import com.google.devtools.build.lib.actions.SchedulingActionEvent;
import com.google.devtools.build.lib.actions.StoppedScanningActionEvent;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.buildeventstream.AnnounceBuildEventTransportsEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventTransport;
import com.google.devtools.build.lib.buildeventstream.BuildEventTransportClosedEvent;
import com.google.devtools.build.lib.buildtool.ExecutionProgressReceiver;
import com.google.devtools.build.lib.buildtool.buildevent.BuildCompleteEvent;
import com.google.devtools.build.lib.buildtool.buildevent.ExecutionProgressReceiverAvailableEvent;
import com.google.devtools.build.lib.buildtool.buildevent.TestFilteringCompleteEvent;
import com.google.devtools.build.lib.clock.Clock;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.ExtendedEventHandler.FetchProgress;
import com.google.devtools.build.lib.pkgcache.LoadingPhaseCompleteEvent;
import com.google.devtools.build.lib.skyframe.ConfigurationPhaseStartedEvent;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetProgressReceiver;
import com.google.devtools.build.lib.skyframe.LoadingPhaseStartedEvent;
import com.google.devtools.build.lib.skyframe.PackageProgressReceiver;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.util.io.AnsiTerminalWriter;
import com.google.devtools.build.lib.util.io.PositionAwareAnsiTerminalWriter;
import com.google.devtools.build.lib.view.test.TestStatus.BlazeTestStatus;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

/** Tracks state for the UI. */
final class UiStateTracker {

  enum ProgressMode {
    OLDEST_ACTIONS,
    MNEMONIC_HISTOGRAM
  }

  private static final long SHOW_TIME_THRESHOLD_SECONDS = 3;
  private static final String ELLIPSIS = "...";
  private static final String FETCH_PREFIX = "    Fetching ";
  private static final String AND_MORE = " ...";
  private static final String NO_STATUS = "-----";
  private static final int STATUS_LENGTH = 5;

  private static final int NANOS_PER_SECOND = 1000000000;
  private static final String URL_PROTOCOL_SEP = "://";

  private ProgressMode progressMode = ProgressMode.OLDEST_ACTIONS;
  private int sampleSize = 3;

  private String status;
  private String additionalMessage;

  private final Clock clock;

  // Desired maximal width of the progress bar, if positive.
  // Non-positive values indicate not to aim for a particular width.
  private final int targetWidth;

  /**
   * Tracker of strategy names to unique IDs and viceversa.
   *
   * <p>The IDs generated by this class can be used to index bitmaps.
   *
   * <p>TODO(jmmv): Would be nice if the strategies themselves contained their IDs (initialized at
   * construction time) and we passed around those IDs instead of strings.
   */
  @ThreadSafe
  @VisibleForTesting
  static final class StrategyIds {
    /** Fallback name in case we exhaust our space for IDs. */
    static final String FALLBACK_NAME = "unknown";

    /** Counter of unique strategies seen so far. */
    private final AtomicInteger counter = new AtomicInteger(0);

    /** Mapping of strategy names to their unique IDs. */
    private final Map<String, Integer> strategyIds = new ConcurrentHashMap<>();

    /** Mapping of strategy unique IDs to their names. */
    private final Map<Integer, String> strategyNames = new ConcurrentHashMap<>();

    final Integer fallbackId;

    /** Constructs a new collection of strategy IDs. */
    StrategyIds() {
      fallbackId = getId(FALLBACK_NAME);
    }

    /** Computes the ID of a strategy given its name. */
    public Integer getId(String strategy) {
      Integer id =
          strategyIds.computeIfAbsent(
              strategy,
              (key) -> {
                int value = counter.getAndIncrement();
                if (value >= Integer.SIZE) {
                  return fallbackId;
                } else {
                  return 1 << value;
                }
              });
      strategyNames.putIfAbsent(id, strategy);
      return id;
    }

    /** Flattens a bitmap of strategy IDs into a human-friendly string. */
    String formatNames(int bitmap) {
      StringBuilder builder = new StringBuilder();
      int mask = 0x1;
      while (bitmap != 0) {
        int id = bitmap & mask;
        if (id != 0) {
          String name = checkNotNull(strategyNames.get(id), "Unknown strategy with id %s", id);
          builder.append(name);
          bitmap &= ~mask;
          if (bitmap != 0) {
            builder.append(", ");
          }
        }
        mask <<= 1;
      }
      return builder.toString();
    }
  }

  private static final StrategyIds strategyIds = new StrategyIds();

  /**
   * Tracks all details for an action that we have heard about.
   *
   * <p>We cannot make assumptions on the order in which action state events come in, so this class
   * takes care of always "advancing" the state of an action.
   */
  private static final class ActionState {
    /**
     * The action this state belongs to.
     *
     * <p>We assume that all events related to the same action refer to the same {@link
     * ActionExecutionMetadata} object.
     */
    final ActionExecutionMetadata action;

    /** Timestamp of the last state change. */
    long nanoStartTime;

    /**
     * Whether this action is in the scanning state or not.
     *
     * <p>If true, implies that {@link #schedulingStrategiesBitmap} and {@link
     * #runningStrategiesBitmap} are both zero. The opposite is not necessarily true: if false, the
     * bitmaps can be zero as well to represent that the action is still in the preparation stage.
     */
    boolean scanning;

    /**
     * Bitmap of strategies that are checking the cache of this action.
     *
     * <p>If non-zero, implies that {@link #scanning} is false.
     */
    int cachingStrategiesBitmap = 0;

    /**
     * Bitmap of strategies that are scheduling this action.
     *
     * <p>If non-zero, implies that {@link #scanning} is false.
     */
    int schedulingStrategiesBitmap = 0;

    /**
     * Bitmap of strategies that are running this action.
     *
     * <p>If non-zero, implies that {@link #scanning} is false.
     */
    int runningStrategiesBitmap = 0;

    private static class ProgressState {
      final String id;
      final long nanoStartTime;
      ActionProgressEvent latestEvent;

      private ProgressState(String id, long nanoStartTime) {
        this.id = id;
        this.nanoStartTime = nanoStartTime;
      }
    }

    @GuardedBy("this")
    private final LinkedHashMap<String, ProgressState> runningProgresses = new LinkedHashMap<>();

    /** Starts tracking the state of an action. */
    ActionState(ActionExecutionMetadata action, long nanoStartTime) {
      this.action = action;
      this.nanoStartTime = nanoStartTime;
    }

    /** Computes the weight of this action for the global active actions counter. */
    synchronized int countActions() {
      int activeStrategies =
          Integer.bitCount(schedulingStrategiesBitmap) + Integer.bitCount(runningStrategiesBitmap);
      return activeStrategies > 0 ? activeStrategies : 1;
    }

    /**
     * Marks the action as scanning.
     *
     * <p>Because we may receive events out of order, this does nothing if the action is already
     * scheduled or running.
     */
    synchronized void setScanning(long nanoChangeTime) {
      if (cachingStrategiesBitmap == 0
          && schedulingStrategiesBitmap == 0
          && runningStrategiesBitmap == 0) {
        scanning = true;
        nanoStartTime = nanoChangeTime;
      }
    }

    /**
     * Marks the action as no longer scanning.
     *
     * <p>Because we may receive events out of order, this does nothing if the action is already
     * scheduled or running.
     */
    synchronized void setStopScanning(long nanoChangeTime) {
      if (cachingStrategiesBitmap == 0
          && schedulingStrategiesBitmap == 0
          && runningStrategiesBitmap == 0) {
        scanning = false;
        nanoStartTime = nanoChangeTime;
      }
    }

    /**
     * Marks the action as caching with the given strategy.
     *
     * <p>Because we may receive events out of order, this does nothing if the action is already
     * scheduled or running with this strategy.
     */
    synchronized void setCaching(String strategy, long nanoChangeTime) {
      int id = strategyIds.getId(strategy);
      if ((schedulingStrategiesBitmap & id) == 0 && (runningStrategiesBitmap & id) == 0) {
        scanning = false;
        cachingStrategiesBitmap |= id;
        nanoStartTime = nanoChangeTime;
      }
    }

    /**
     * Marks the action as scheduling with the given strategy.
     *
     * <p>Because we may receive events out of order, this does nothing if the action is already
     * running with this strategy.
     */
    synchronized void setScheduling(String strategy, long nanoChangeTime) {
      int id = strategyIds.getId(strategy);
      if ((runningStrategiesBitmap & id) == 0) {
        scanning = false;
        cachingStrategiesBitmap &= ~id;
        schedulingStrategiesBitmap |= id;
        nanoStartTime = nanoChangeTime;
      }
    }

    /**
     * Marks the action as running with the given strategy.
     *
     * <p>Because "running" is a terminal state, this forcibly updates the state to running
     * regardless of any other events (which may come out of order).
     */
    synchronized void setRunning(String strategy, long nanoChangeTime) {
      scanning = false;
      int id = strategyIds.getId(strategy);
      cachingStrategiesBitmap &= ~id;
      schedulingStrategiesBitmap &= ~id;
      runningStrategiesBitmap |= id;
      nanoStartTime = nanoChangeTime;
    }

    /** Handles the progress event for the action. */
    synchronized void onProgressEvent(ActionProgressEvent event, long nanoChangeTime) {
      String id = event.progressId();
      if (event.finished()) {
        // a progress is finished, clean it up
        runningProgresses.remove(id);
        return;
      }

      ProgressState state =
          runningProgresses.computeIfAbsent(id, key -> new ProgressState(key, nanoChangeTime));
      state.latestEvent = event;
    }

    synchronized Optional<ProgressState> firstProgress() {
      if (runningProgresses.isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(runningProgresses.entrySet().iterator().next().getValue());
    }

    /** Generates a human-readable description of this action's state. */
    synchronized String describe() {
      if (runningStrategiesBitmap != 0) {
        return "Running";
      } else if (schedulingStrategiesBitmap != 0) {
        return "Scheduling";
      } else if (cachingStrategiesBitmap != 0) {
        return "Caching";
      } else if (scanning) {
        return "Scanning";
      } else {
        return "Preparing";
      }
    }
  }

  private final Map<Artifact, ActionState> activeActions;

  // running downloads are identified by the original URL they were trying to access.
  private final Deque<String> runningDownloads;
  private final Map<String, Long> downloadNanoStartTimes;
  private final Map<String, FetchProgress> downloads;

  /**
   * For each test, the list of actions (as identified by the primary output artifact) currently
   * running for that test (identified by its label), in the order they got started. A key is
   * present in the map if and only if that was discovered as a test.
   */
  private final Map<Label, Set<Artifact>> testActions;

  private final AtomicInteger actionsCompleted;
  private int totalTests;
  private int completedTests;
  private TestSummary mostRecentTest;
  private int failedTests;
  private boolean ok;
  private boolean buildComplete;

  // These are only null between the completion of analysis and the beginning of execution.
  @Nullable private String defaultStatus = "Loading";
  @Nullable private String defaultActivity = "loading...";

  private ExecutionProgressReceiver executionProgressReceiver;
  private PackageProgressReceiver packageProgressReceiver;
  private ConfiguredTargetProgressReceiver configuredTargetProgressReceiver;

  // Set of build event protocol transports that need yet to be closed.
  private final Set<BuildEventTransport> bepOpenTransports = new HashSet<>();
  // The point in time when closing of BEP transports was started.
  private long bepTransportClosingStartTimeMillis;
  private final ActiveRemoteCacheIO activeRemoteCacheIO = new ActiveRemoteCacheIO();

  static class ActiveRemoteCacheIO {
    @GuardedBy("this")
    private final LinkedHashMap<String, RemoteCacheIOState> states = new LinkedHashMap<>();
    private long nanoWaitStartTime = 0;
    private int uploads;
    private int downloads;

    void onBuildComplete(long nanoChangeTime) {
      nanoWaitStartTime = nanoChangeTime;
    }

    synchronized void onRemoteUpload(RemoteUploadEvent event, long nanoChangeTime) {
      String id = event.resourceId();

      RemoteCacheIOState state = states.get(id);

      if (event.finished()) {
        if (state != null) {
          states.remove(id);
          uploads -= 1;
        }
      } else {
        if (state == null) {
          state = new RemoteCacheIOState(id, nanoChangeTime);
          states.put(id, state);
          uploads += 1;
        }

        state.latestUploadEvent = event;
      }
    }

    synchronized List<RemoteCacheIOState> firstNStates(int limit) {
      return states.values().stream().limit(limit).collect(Collectors.toList());
    }

    long waitSeconds(long nanoTime) {
      return NANOSECONDS.toSeconds(nanoTime - nanoWaitStartTime);
    }

    int numDownloads() {
      return downloads;
    }

    int numUploads() {
      return uploads;
    }

    synchronized int activeIOCount() {
      return states.size();
    }
  }

  static class RemoteCacheIOState {
    final String id;
    final long nanoStartTime;
    RemoteUploadEvent latestUploadEvent;

    RemoteCacheIOState(String id, long nanoStartTime) {
      this.id = id;
      this.nanoStartTime = nanoStartTime;
    }
  }


  UiStateTracker(Clock clock, int targetWidth) {
    this.activeActions = new ConcurrentHashMap<>();

    this.actionsCompleted = new AtomicInteger();
    this.testActions = new ConcurrentHashMap<>();
    this.runningDownloads = new ArrayDeque<>();
    this.downloads = new TreeMap<>();
    this.downloadNanoStartTimes = new TreeMap<>();
    this.ok = true;
    this.clock = clock;
    this.targetWidth = targetWidth;
  }

  UiStateTracker(Clock clock) {
    this(clock, 0);
  }

  /** Set the progress bar mode and sample size. */
  void setProgressMode(ProgressMode progressMode, int sampleSize) {
    this.progressMode = progressMode;
    this.sampleSize = Math.max(1, sampleSize);
  }

  void buildStarted() {
    status = "Loading";
    additionalMessage = "";
  }

  void loadingStarted(LoadingPhaseStartedEvent event) {
    status = null;
    packageProgressReceiver = event.getPackageProgressReceiver();
  }

  void configurationStarted(ConfigurationPhaseStartedEvent event) {
    configuredTargetProgressReceiver = event.getConfiguredTargetProgressReceiver();
  }

  void loadingComplete(LoadingPhaseCompleteEvent event) {
    int count = event.getLabels().size();
    status = "Analyzing";
    if (count == 1) {
      additionalMessage = "target " + Iterables.getOnlyElement(event.getLabels());
    } else {
      additionalMessage = count + " targets";
    }
  }

  /**
   * Make the state tracker aware of the fact that the analysis has finished. Return a summary of
   * the work done in the analysis phase.
   */
  synchronized String analysisComplete() {
    String workDone = "Analyzed " + additionalMessage;
    if (packageProgressReceiver != null) {
      Pair<String, String> progress = packageProgressReceiver.progressState();
      workDone += " (" + progress.getFirst();
      if (configuredTargetProgressReceiver != null) {
        workDone += ", " + configuredTargetProgressReceiver.getProgressString();
      }
      workDone += ")";
    }
    workDone += ".";
    status = null;
    packageProgressReceiver = null;
    configuredTargetProgressReceiver = null;
    defaultStatus = null;
    defaultActivity = null;
    return workDone;
  }

  synchronized void progressReceiverAvailable(ExecutionProgressReceiverAvailableEvent event) {
    executionProgressReceiver = event.getExecutionProgressReceiver();
    defaultStatus = "Building";
    defaultActivity = "checking cached actions";
  }

  void buildComplete(BuildCompleteEvent event) {
    buildComplete = true;
    // Build event protocol transports are closed right after the build complete event.
    bepTransportClosingStartTimeMillis = clock.currentTimeMillis();
    activeRemoteCacheIO.onBuildComplete(clock.nanoTime());

    if (event.getResult().getSuccess()) {
      status = "INFO";
      int actionsCompleted = this.actionsCompleted.get();
      if (failedTests == 0) {
        additionalMessage =
            "Build completed successfully, "
                + actionsCompleted
                + " total action"
                + (actionsCompleted == 1 ? "" : "s");
      } else {
        additionalMessage =
            "Build completed, "
                + failedTests
                + " test"
                + (failedTests == 1 ? "" : "s")
                + " FAILED, "
                + actionsCompleted
                + " total action"
                + (actionsCompleted == 1 ? "" : "s");
      }
    } else {
      ok = false;
      status = "FAILED";
      additionalMessage = "Build did NOT complete successfully";
    }
  }

  synchronized void downloadProgress(FetchProgress event) {
    String url = event.getResourceIdentifier();
    if (event.isFinished()) {
      // a download is finished, clean it up
      runningDownloads.remove(url);
      downloadNanoStartTimes.remove(url);
      downloads.remove(url);
    } else if (runningDownloads.contains(url)) {
      // a new progress update on an already known, still running download
      downloads.put(url, event);
    } else {
      // Start of a new download
      long nanoTime = clock.nanoTime();
      runningDownloads.add(url);
      downloads.put(url, event);
      downloadNanoStartTimes.put(url, nanoTime);
    }
  }

  private ActionState getActionState(
      ActionExecutionMetadata action, Artifact actionId, long nanoTimeNow) {
    return activeActions.computeIfAbsent(actionId, (key) -> new ActionState(action, nanoTimeNow));
  }

  void actionStarted(ActionStartedEvent event) {
    Action action = event.getAction();
    Artifact actionId = action.getPrimaryOutput();

    getActionState(action, actionId, event.getNanoTimeStart());

    if (action.getOwner() != null) {
      Label owner = action.getOwner().getLabel();
      if (owner != null) {
        Set<Artifact> testActionsForOwner = testActions.get(owner);
        if (testActionsForOwner != null) {
          testActionsForOwner.add(actionId);
        }
      }
    }
  }

  void scanningAction(ScanningActionEvent event) {
    ActionExecutionMetadata action = event.getActionMetadata();
    Artifact actionId = event.getActionMetadata().getPrimaryOutput();
    long now = clock.nanoTime();
    getActionState(action, actionId, now).setScanning(now);
  }

  void stopScanningAction(StoppedScanningActionEvent event) {
    Action action = event.getAction();
    Artifact actionId = action.getPrimaryOutput();
    long now = clock.nanoTime();
    getActionState(action, actionId, now).setStopScanning(now);
  }

  void cachingAction(CachingActionEvent event) {
    ActionExecutionMetadata action = event.action();
    Artifact actionId = action.getPrimaryOutput();
    long now = clock.nanoTime();
    getActionState(action, actionId, now).setCaching(event.strategy(), now);
  }

  void schedulingAction(SchedulingActionEvent event) {
    ActionExecutionMetadata action = event.getActionMetadata();
    Artifact actionId = event.getActionMetadata().getPrimaryOutput();
    long now = clock.nanoTime();
    getActionState(action, actionId, now).setScheduling(event.getStrategy(), now);
  }

  void runningAction(RunningActionEvent event) {
    ActionExecutionMetadata action = event.getActionMetadata();
    Artifact actionId = event.getActionMetadata().getPrimaryOutput();
    long now = clock.nanoTime();
    getActionState(action, actionId, now).setRunning(event.getStrategy(), now);
  }

  void actionProgress(ActionProgressEvent event) {
    ActionExecutionMetadata action = event.action();
    Artifact actionId = event.action().getPrimaryOutput();
    long now = clock.nanoTime();
    getActionState(action, actionId, now).onProgressEvent(event, now);
  }

  void actionCompletion(ActionScanningCompletedEvent event) {
    Action action = event.getAction();
    Artifact actionId = action.getPrimaryOutput();
    checkNotNull(activeActions.remove(actionId), "%s not active after %s", actionId, event);

    // As callers to the experimental state tracker assume we will fully report the new state once
    // informed of an action completion, we need to make sure the progress receiver is aware of the
    // completion, even though it might be called later on the event bus.
    if (executionProgressReceiver != null) {
      executionProgressReceiver.actionCompleted(event.getActionLookupData());
    }
  }

  void actionCompletion(ActionCompletionEvent event) {
    actionsCompleted.incrementAndGet();
    Action action = event.getAction();
    Artifact actionId = action.getPrimaryOutput();

    checkNotNull(activeActions.remove(actionId), "%s not active after %s", actionId, event);

    if (action.getOwner() != null) {
      Label owner = action.getOwner().getLabel();
      if (owner != null) {
        Set<Artifact> testActionsForOwner = testActions.get(owner);
        if (testActionsForOwner != null) {
          testActionsForOwner.remove(actionId);
        }
      }
    }

    // As callers to the experimental state tracker assume we will fully report the new state once
    // informed of an action completion, we need to make sure the progress receiver is aware of the
    // completion, even though it might be called later on the event bus.
    if (executionProgressReceiver != null) {
      executionProgressReceiver.actionCompleted(event.getActionLookupData());
    }
  }

  /** From a string, take a suffix of at most the given length. */
  static String suffix(String s, int len) {
    if (len <= 0) {
      return "";
    }
    int startPos = s.length() - len;
    if (startPos <= 0) {
      return s;
    }
    return s.substring(startPos);
  }

  /**
   * If possible come up with a human-readable description of the label that fits within the given
   * width; a non-positive width indicates not no restriction at all.
   */
  private static String shortenedLabelString(Label label, int width) {
    if (width <= 0) {
      return label.toString();
    }
    String name = label.toString();
    if (name.length() <= width) {
      return name;
    }
    name = suffix(name, width - ELLIPSIS.length());
    int slashPos = name.indexOf('/');
    if (slashPos >= 0) {
      return ELLIPSIS + name.substring(slashPos);
    }
    int colonPos = name.indexOf(':');
    if (slashPos >= 0) {
      return ELLIPSIS + name.substring(colonPos);
    }
    // no reasonable place found to shorten; as last resort, just truncate
    if (3 * ELLIPSIS.length() <= width) {
      return ELLIPSIS + suffix(label.toString(), width - ELLIPSIS.length());
    }
    return label.toString();
  }

  private static String shortenedString(String s, int maxWidth) {
    if (maxWidth <= 3 * ELLIPSIS.length() || s.length() <= maxWidth) {
      return s;
    }
    return s.substring(0, maxWidth - ELLIPSIS.length()) + ELLIPSIS;
  }

  // Describe a group of actions running for the same test.
  private String describeTestGroup(
      Label owner, long nanoTime, int desiredWidth, Set<Artifact> allActions) {
    String prefix = "Testing ";
    String labelSep = " [";
    String postfix = " (" + allActions.size() + " actions)]";
    // Leave enough room for at least 3 samples of run times, each 4 characters
    // (a digit, 's', comma, and space).
    int labelWidth = desiredWidth - prefix.length() - labelSep.length() - postfix.length() - 12;
    StringBuilder message =
        new StringBuilder(prefix).append(shortenedLabelString(owner, labelWidth)).append(labelSep);

    // Compute the remaining width for the sample times, but if the desired width is too small
    // anyway, then show at least one sample.
    int remainingWidth = desiredWidth - message.length() - postfix.length();
    if (remainingWidth < 0) {
      remainingWidth = "[1s], ".length() + 1;
    }

    String sep = "";
    boolean allReported = true;
    for (Artifact actionId : allActions) {
      ActionState actionState = activeActions.get(actionId);
      if (actionState == null) {
        // This action must have completed while we were constructing this output. Skip it.
        continue;
      }
      long nanoRuntime = nanoTime - actionState.nanoStartTime;
      long runtimeSeconds = nanoRuntime / NANOS_PER_SECOND;
      String text =
          actionState.runningStrategiesBitmap == 0
              ? sep + "[" + runtimeSeconds + "s]"
              : sep + runtimeSeconds + "s";
      if (remainingWidth < text.length()) {
        allReported = false;
        break;
      }
      message.append(text);
      remainingWidth -= text.length();
      sep = ", ";
    }
    return message.append(allReported ? "]" : postfix).toString();
  }

  private String describeActionProgress(ActionState action, int desiredWidth) {
    Optional<ActionState.ProgressState> stateOpt = action.firstProgress();
    if (!stateOpt.isPresent()) {
      return "";
    }

    ActionState.ProgressState state = stateOpt.get();
    ActionProgressEvent event = state.latestEvent;
    String message = event.progress();
    if (message.isEmpty()) {
      message = state.id;
    }

    message = "; " + message;

    if (desiredWidth <= 0 || message.length() <= desiredWidth) {
      return message;
    }

    message = message.substring(0, desiredWidth - ELLIPSIS.length()) + ELLIPSIS;
    return message;
  }

  // Describe an action by a string of the desired length; if describing that action includes
  // describing other actions, add those to the to set of actions to skip in further samples of
  // actions.
  private String describeAction(
      ActionState actionState, long nanoTime, int desiredWidth, Set<Artifact> toSkip) {
    ActionExecutionMetadata action = actionState.action;
    if (action.getOwner() != null) {
      Label owner = action.getOwner().getLabel();
      if (owner != null) {
        Set<Artifact> allRelatedActions = testActions.get(owner);
        if (allRelatedActions != null && allRelatedActions.size() > 1) {
          if (toSkip != null) {
            toSkip.addAll(allRelatedActions);
          }
          return describeTestGroup(owner, nanoTime, desiredWidth, allRelatedActions);
        }
      }
    }

    String postfix = "";
    String prefix = "";
    long nanoRuntime = nanoTime - actionState.nanoStartTime;
    long runtimeSeconds = nanoRuntime / NANOS_PER_SECOND;
    String strategy = null;
    if (actionState.runningStrategiesBitmap != 0) {
      strategy = strategyIds.formatNames(actionState.runningStrategiesBitmap);
    } else if (actionState.cachingStrategiesBitmap != 0) {
      strategy = strategyIds.formatNames(actionState.cachingStrategiesBitmap);
    } else {
      String status = actionState.describe();
      if (status == null) {
        status = NO_STATUS;
      }
      if (status.length() > STATUS_LENGTH) {
        status = status.substring(0, STATUS_LENGTH);
      }
      prefix = prefix + "[" + status + "] ";
    }
    // To keep the UI appearance more stable, always show the elapsed
    // time if we also show a strategy (otherwise the strategy will jump in
    // the progress bar).
    if (strategy != null || runtimeSeconds > SHOW_TIME_THRESHOLD_SECONDS) {
      postfix = "; " + runtimeSeconds + "s";
    }
    if (strategy != null) {
      postfix += " " + strategy;
    }

    String message = action.getProgressMessage();
    if (message == null) {
      message = action.prettyPrint();
    }

    String progress = describeActionProgress(actionState, 0);

    if (desiredWidth <= 0
        || (prefix.length() + message.length() + progress.length() + postfix.length())
            <= desiredWidth) {
      return prefix + message + progress + postfix;
    }

    // We have to shorten the progress to fit into the line.
    int remainingWidthForProgress =
        desiredWidth - prefix.length() - message.length() - postfix.length();
    int minWidthForProgress = 7; // "; " + at least two character + "..."
    if (remainingWidthForProgress >= minWidthForProgress) {
      progress = describeActionProgress(actionState, remainingWidthForProgress);
      return prefix + message + progress + postfix;
    }

    // We have to skip the progress to fit into the line.
    if (prefix.length() + message.length() + postfix.length() <= desiredWidth) {
      return prefix + message + postfix;
    }

    // We have to shorten the message to fit into the line.

    if (action.getOwner() != null) {
      if (action.getOwner().getLabel() != null) {
        // First attempt is to shorten the package path string in the messge, if it occurs there
        String pathString = action.getOwner().getLabel().getPackageFragment().toString();
        int pathIndex = message.indexOf(pathString);
        if (pathIndex >= 0) {
          String start = message.substring(0, pathIndex);
          String end = message.substring(pathIndex + pathString.length());
          int pathTargetLength =
              desiredWidth - start.length() - end.length() - postfix.length() - prefix.length();
          // This attempt of shortening is reasonable if what is left from the label
          // is significantly longer (twice as long) as the ellipsis symbols introduced.
          if (pathTargetLength >= 3 * ELLIPSIS.length()) {
            String shortPath = suffix(pathString, pathTargetLength - ELLIPSIS.length());
            int slashPos = shortPath.indexOf('/');
            if (slashPos >= 0) {
              return prefix + start + ELLIPSIS + shortPath.substring(slashPos) + end + postfix;
            }
          }
        }

        // Second attempt: just take a shortened version of the label.
        String shortLabel =
            shortenedLabelString(
                action.getOwner().getLabel(), desiredWidth - postfix.length() - prefix.length());
        if (prefix.length() + shortLabel.length() + postfix.length() <= desiredWidth) {
          return prefix + shortLabel + postfix;
        }
      }
    }
    if (3 * ELLIPSIS.length() + postfix.length() + prefix.length() <= desiredWidth) {
      message =
          ELLIPSIS
              + suffix(
                  message, desiredWidth - ELLIPSIS.length() - postfix.length() - prefix.length());
    }

    return prefix + message + postfix;
  }

  private ActionState getOldestAction() {
    long minStart = Long.MAX_VALUE;
    ActionState result = null;
    for (ActionState action : activeActions.values()) {
      if (action.nanoStartTime < minStart) {
        minStart = action.nanoStartTime;
        result = action;
      }
    }
    return result;
  }

  private String countActions() {
    // TODO(djasper): Iterating over the actions here is slow, but it's only done once per refresh
    // and thus might be faster than trying to update these values in the critical path.
    // Re-investigate if this ever turns up in a profile.
    int actionsCount = 0;
    int executingActionsCount = 0;
    for (ActionState actionState : activeActions.values()) {
      actionsCount += actionState.countActions();
      executingActionsCount += Integer.bitCount(actionState.runningStrategiesBitmap);
    }

    if (actionsCount == 1) {
      return " 1 action running";
    } else if (actionsCount == executingActionsCount) {
      return actionsCount + " actions running";
    } else {
      return actionsCount + " actions, " + executingActionsCount + " running";
    }
  }

  private void printActionState(AnsiTerminalWriter terminalWriter) throws IOException {
    switch (progressMode) {
      case OLDEST_ACTIONS:
        sampleOldestActions(terminalWriter);
        break;
      case MNEMONIC_HISTOGRAM:
        showMnemonicHistogram(terminalWriter);
        break;
    }
  }

  private void showMnemonicHistogram(AnsiTerminalWriter terminalWriter) throws IOException {
    Multiset<String> mnemonicHistogram = HashMultiset.create();
    for (Map.Entry<Artifact, ActionState> action : activeActions.entrySet()) {
      mnemonicHistogram.add(action.getValue().action.getMnemonic());
    }
    List<Multiset.Entry<String>> sorted =
        mnemonicHistogram.entrySet().stream()
            .collect(
                Comparators.greatest(
                    sampleSize, Comparator.comparingLong(Multiset.Entry::getCount)));
    for (Multiset.Entry<String> entry : sorted) {
      terminalWriter.newline().append("    " + entry.getElement() + " " + entry.getCount());
    }
  }

  private void sampleOldestActions(AnsiTerminalWriter terminalWriter) throws IOException {
    // This method can only be called if 'activeActions.size()' was observed to be larger than 1 at
    // some point in the past. But the 'activeActions' map can grow and shrink concurrent with this
    // code here so we need to be very careful lest we fall victim to a check-then-act race.
    int racyActiveActionsCount = activeActions.size();
    PriorityQueue<Map.Entry<Artifact, ActionState>> priorityHeap =
        new PriorityQueue<>(
            // The 'initialCapacity' parameter must be positive.
            /*initialCapacity=*/ Math.max(racyActiveActionsCount, 1),
            Comparator.comparing(
                    (Map.Entry<Artifact, ActionState> entry) ->
                        entry.getValue().runningStrategiesBitmap == 0)
                .thenComparingLong(entry -> entry.getValue().nanoStartTime)
                .thenComparingInt(entry -> entry.getValue().hashCode()));
    priorityHeap.addAll(activeActions.entrySet());
    // From this point on, we no longer consume 'activeActions'. So now it's sound to look at how
    // many entries were added to 'priorityHeap' and act appropriately based on that count.
    int actualObservedActiveActionsCount = priorityHeap.size();

    int count = 0;
    int totalCount = 0;
    long nanoTime = clock.nanoTime();
    Set<Artifact> toSkip = new HashSet<>();

    while (!priorityHeap.isEmpty()) {
      Map.Entry<Artifact, ActionState> entry = priorityHeap.poll();
      totalCount++;
      if (toSkip.contains(entry.getKey())) {
        continue;
      }
      count++;
      if (count > sampleSize) {
        totalCount--;
        break;
      }
      int width =
          targetWidth
              - 4
              - ((count >= sampleSize && count < actualObservedActiveActionsCount)
                  ? AND_MORE.length()
                  : 0);
      terminalWriter
          .newline()
          .append("    " + describeAction(entry.getValue(), nanoTime, width, toSkip));
    }
    if (totalCount < actualObservedActiveActionsCount) {
      terminalWriter.append(AND_MORE);
    }
  }

  synchronized void testFilteringComplete(TestFilteringCompleteEvent event) {
    if (event.getTestTargets() != null) {
      totalTests = event.getTestTargets().size();
      for (ConfiguredTarget target : event.getTestTargets()) {
        if (target.getLabel() != null) {
          testActions.put(target.getLabel(), Sets.newConcurrentHashSet());
        }
      }
    }
  }

  void remoteUpload(RemoteUploadEvent event) {
    long now = clock.nanoTime();
    activeRemoteCacheIO.onRemoteUpload(event, now);
  }

  public synchronized void testSummary(TestSummary summary) {
    completedTests++;
    mostRecentTest = summary;
    if ((summary.getStatus() != BlazeTestStatus.PASSED)
        && (summary.getStatus() != BlazeTestStatus.FLAKY)) {
      failedTests++;
    }
  }

  synchronized void buildEventTransportsAnnounced(AnnounceBuildEventTransportsEvent event) {
    this.bepOpenTransports.addAll(event.transports());
  }

  synchronized void buildEventTransportClosed(BuildEventTransportClosedEvent event) {
    bepOpenTransports.remove(event.transport());
  }

  synchronized boolean shouldStopUpdateProgressBar() {
    return buildComplete
        && bepOpenTransports.size() == 0
        && activeRemoteCacheIO.activeIOCount() == 0;
  }

  /**
   * * Predicate indicating whether the contents of the progress bar can change, if the only thing
   * that happens is that time passes; this is the case, e.g., if the progress bar shows time
   * information relative to the current time.
   */
  boolean progressBarTimeDependent() {
    if (packageProgressReceiver != null) {
      return true;
    }
    if (runningDownloads.size() >= 1) {
      return true;
    }
    if (buildComplete && activeRemoteCacheIO.activeIOCount() > 0) {
      return true;
    }
    if (buildComplete && !bepOpenTransports.isEmpty()) {
      return true;
    }
    if (status != null) {
      return false;
    }
    return !activeActions.isEmpty();
  }

  /**
   * Maybe add a note about the last test that passed. Return true, if the note was added (and hence
   * a line break is appropriate if more data is to come. If a null value is provided for the
   * terminal writer, only return whether a note would be added.
   *
   * <p>The width parameter gives advice on to which length the description of the test should the
   * shortened to, if possible.
   */
  private boolean maybeShowRecentTest(
      AnsiTerminalWriter terminalWriter, boolean shortVersion, int width) throws IOException {
    final String prefix = "; last test: ";
    if (!shortVersion && mostRecentTest != null) {
      if (terminalWriter != null) {
        terminalWriter.normal().append(prefix);
        if (mostRecentTest.getStatus() == BlazeTestStatus.PASSED) {
          terminalWriter.okStatus();
        } else {
          terminalWriter.failStatus();
        }
        terminalWriter.append(
            shortenedLabelString(mostRecentTest.getLabel(), width - prefix.length()));
        terminalWriter.normal();
      }
      return true;
    } else {
      return false;
    }
  }

  private static String shortenUrl(String url, int width) {
    if (url.length() < width) {
      return url;
    }

    // Try to shorten to the form prot://host/.../rest/path/filename
    int protocolIndex = url.indexOf(URL_PROTOCOL_SEP);
    if (protocolIndex > 0) {
      String prefix = url.substring(0, protocolIndex + URL_PROTOCOL_SEP.length() + 1);
      url = url.substring(protocolIndex + URL_PROTOCOL_SEP.length() + 1);
      int hostIndex = url.indexOf('/');
      if (hostIndex > 0) {
        prefix = prefix + url.substring(0, hostIndex + 1);
        url = url.substring(hostIndex + 1);
        int targetLength = width - prefix.length();
        // accept this form of shortening, if what is left from the filename is
        // significantly longer (twice as long) as the ellipsis symbol introduced
        if (targetLength > 3 * ELLIPSIS.length()) {
          String shortPath = suffix(url, targetLength - ELLIPSIS.length());
          int slashPos = shortPath.indexOf('/');
          if (slashPos >= 0) {
            return prefix + ELLIPSIS + shortPath.substring(slashPos);
          } else {
            return prefix + ELLIPSIS + shortPath;
          }
        }
      }
    }

    // Last resort: just take a suffix
    if (width <= ELLIPSIS.length()) {
      // No chance to shorten anyway
      return "";
    }
    return ELLIPSIS + suffix(url, width - ELLIPSIS.length());
  }

  private void reportOnOneDownload(
      String url, long nanoTime, int width, AnsiTerminalWriter terminalWriter) throws IOException {

    String postfix = "";

    FetchProgress download = downloads.get(url);
    long nanoDownloadTime = nanoTime - downloadNanoStartTimes.get(url);
    long downloadSeconds = nanoDownloadTime / NANOS_PER_SECOND;

    String progress = download.getProgress();
    if (!progress.isEmpty()) {
      postfix = postfix + " " + progress;
    }
    if (downloadSeconds > SHOW_TIME_THRESHOLD_SECONDS) {
      postfix = postfix + " " + downloadSeconds + "s";
    }
    if (!postfix.isEmpty()) {
      postfix = ";" + postfix;
    }
    url = shortenUrl(url, Math.max(width - postfix.length(), 3 * ELLIPSIS.length()));
    terminalWriter.append(url + postfix);
  }

  private void reportOnDownloads(AnsiTerminalWriter terminalWriter) throws IOException {
    int count = 0;
    long nanoTime = clock.nanoTime();
    int downloadCount = runningDownloads.size();
    String suffix = AND_MORE + " (" + downloadCount + " fetches)";
    for (String url : runningDownloads) {
      if (count >= sampleSize) {
        break;
      }
      count++;
      terminalWriter.newline().append(FETCH_PREFIX);
      reportOnOneDownload(
          url,
          nanoTime,
          targetWidth
              - FETCH_PREFIX.length()
              - ((count >= sampleSize && count < downloadCount) ? suffix.length() : 0),
          terminalWriter);
    }
    if (count < downloadCount) {
      terminalWriter.append(suffix);
    }
  }

  /**
   * Display any BEP transports that are still open after the build. Most likely, because uploading
   * build events takes longer than the build itself.
   */
  private void maybeReportBepTransports(PositionAwareAnsiTerminalWriter terminalWriter)
      throws IOException {
    if (!buildComplete || bepOpenTransports.isEmpty()) {
      return;
    }
    long sinceSeconds =
        MILLISECONDS.toSeconds(clock.currentTimeMillis() - bepTransportClosingStartTimeMillis);
    if (sinceSeconds == 0) {
      // Special case for when bazel was interrupted, in which case we don't want to have
      // a BEP upload message.
      return;
    }
    int count = bepOpenTransports.size();
    // Can just use targetWidth, because we always write to a new line
    int maxWidth = targetWidth;

    String waitMessage = "Waiting for build events upload: ";
    String name = bepOpenTransports.iterator().next().name();
    String line = waitMessage + name + " " + sinceSeconds + "s";

    if (count == 1 && line.length() <= maxWidth) {
      terminalWriter.newline().append(line);
    } else if (count == 1) {
      waitMessage = "Waiting for: ";
      String waitSecs = " " + sinceSeconds + "s";
      int maxNameWidth = maxWidth - waitMessage.length() - waitSecs.length();
      terminalWriter.newline().append(waitMessage + shortenedString(name, maxNameWidth) + waitSecs);
    } else {
      terminalWriter.newline().append(waitMessage + sinceSeconds + "s");
      for (BuildEventTransport transport : bepOpenTransports) {
        name = "  " + transport.name();
        terminalWriter.newline().append(shortenedString(name, maxWidth));
      }
    }
  }

  /**
   * Display any remote cache network I/Os that are still active after the build. Most likely,
   * because uploading/downloading takes longer than the build itself.
   */
  private void maybeReportActiveRemoteCacheIOs(PositionAwareAnsiTerminalWriter terminalWriter)
      throws IOException {
    if (!buildComplete || activeRemoteCacheIO.activeIOCount() == 0) {
      return;
    }

    int uploads = activeRemoteCacheIO.numUploads();
    int downloads = activeRemoteCacheIO.numDownloads();

    long now = clock.nanoTime();
    long waitSeconds = activeRemoteCacheIO.waitSeconds(now);
    if (waitSeconds == 0) {
      // Special case for when bazel was interrupted, in which case we don't want to have a message.
      return;
    }

    String suffix = "";
    if (waitSeconds > SHOW_TIME_THRESHOLD_SECONDS) {
      suffix = "; " + waitSeconds + "s";
    }

    String message = "Waiting for remote cache: ";
    if (uploads != 0) {
      if (uploads == 1) {
        message += "1 upload";
      } else {
        message += uploads + " uploads";
      }
    }

    if (downloads != 0) {
      if (uploads != 0) {
        message += ", ";
      }

      if (downloads == 1) {
        message += "1 download";
      } else {
        message += downloads + " downloads";
      }
    }

    terminalWriter.newline().append(message).append(suffix);
  }

  synchronized void writeProgressBar(
      AnsiTerminalWriter rawTerminalWriter, boolean shortVersion, String timestamp)
      throws IOException {
    PositionAwareAnsiTerminalWriter terminalWriter =
        new PositionAwareAnsiTerminalWriter(rawTerminalWriter);
    int actionsCount = activeActions.size();
    if (timestamp != null) {
      terminalWriter.append(timestamp);
    }
    if (status != null) {
      if (ok) {
        terminalWriter.okStatus();
      } else {
        terminalWriter.failStatus();
      }
      terminalWriter.append(status + ":").normal().append(" " + additionalMessage);
      if (packageProgressReceiver != null) {
        Pair<String, String> progress = packageProgressReceiver.progressState();
        terminalWriter.append(" (" + progress.getFirst());
        if (configuredTargetProgressReceiver != null) {
          terminalWriter.append(", " + configuredTargetProgressReceiver.getProgressString());
        }
        terminalWriter.append(")");
        if (!progress.getSecond().isEmpty() && !shortVersion) {
          terminalWriter.newline().append("    " + progress.getSecond());
        }
      }
      if (!shortVersion) {
        reportOnDownloads(terminalWriter);
        maybeReportActiveRemoteCacheIOs(terminalWriter);
        maybeReportBepTransports(terminalWriter);
      }
      return;
    }
    if (packageProgressReceiver != null) {
      Pair<String, String> progress = packageProgressReceiver.progressState();
      terminalWriter.okStatus().append("Loading:").normal().append(" " + progress.getFirst());
      if (!progress.getSecond().isEmpty()) {
        terminalWriter.newline().append("    " + progress.getSecond());
      }
      if (!shortVersion) {
        reportOnDownloads(terminalWriter);
      }
      return;
    }
    if (executionProgressReceiver != null) {
      terminalWriter.okStatus().append(executionProgressReceiver.getProgressString());
    } else if (defaultStatus == null) {
      return;
    } else {
      terminalWriter.okStatus().append(defaultStatus).append(":");
    }
    if (completedTests > 0) {
      terminalWriter.normal().append(" " + completedTests + " / " + totalTests + " tests");
      if (failedTests > 0) {
        terminalWriter.append(", ").failStatus().append(failedTests + " failed").normal();
      }
      terminalWriter.append(";");
    }
    // Get the oldest action. Note that actions might have finished in the meantime and thus there
    // might not be one.
    ActionState oldestAction = getOldestAction();
    if (actionsCount == 0 || oldestAction == null) {
      terminalWriter.normal().append(" ").append(defaultActivity);
      maybeShowRecentTest(terminalWriter, shortVersion, targetWidth - terminalWriter.getPosition());
    } else if (actionsCount == 1) {
      if (maybeShowRecentTest(null, shortVersion, targetWidth - terminalWriter.getPosition())) {
        // As we will break lines anyway, also show the number of running actions, to keep
        // things stay roughly in the same place (also compensating for the missing plural-s
        // in the word action).
        terminalWriter.normal().append("  1 action");
        maybeShowRecentTest(
            terminalWriter, shortVersion, targetWidth - terminalWriter.getPosition());
        String statusMessage =
            describeAction(oldestAction, clock.nanoTime(), targetWidth - 4, null);
        terminalWriter.normal().newline().append("    " + statusMessage);
      } else {
        String statusMessage =
            describeAction(
                oldestAction,
                clock.nanoTime(),
                targetWidth - terminalWriter.getPosition() - 1,
                null);
        terminalWriter.normal().append(" " + statusMessage);
      }
    } else {
      if (shortVersion) {
        String statusMessage =
            describeAction(
                oldestAction, clock.nanoTime(), targetWidth - terminalWriter.getPosition(), null);
        statusMessage += " ... (" + countActions() + ")";
        terminalWriter.normal().append(" " + statusMessage);
      } else {
        String statusMessage = countActions();
        terminalWriter.normal().append(" " + statusMessage);
        maybeShowRecentTest(
            terminalWriter, shortVersion, targetWidth - terminalWriter.getPosition());
        printActionState(terminalWriter);
      }
    }
    if (!shortVersion) {
      reportOnDownloads(terminalWriter);
      maybeReportActiveRemoteCacheIOs(terminalWriter);
      maybeReportBepTransports(terminalWriter);
    }
  }

  void writeProgressBar(AnsiTerminalWriter terminalWriter, boolean shortVersion)
      throws IOException {
    writeProgressBar(terminalWriter, shortVersion, null);
  }

  void writeProgressBar(AnsiTerminalWriter terminalWriter) throws IOException {
    writeProgressBar(terminalWriter, false);
  }
}
