/*
 * Copyright (C) 2017 Genymobile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.genymobile.gnirehtet;

import android.util.Log;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Detects when the tunnel-to-device forwarding has been blocked in
 * {@code vpnInterface.write()} (or any other I/O call we instrument) for
 * longer than a threshold, and notifies a listener so the service can
 * recover by rebuilding the {@link android.net.VpnService}.
 *
 * <p>Long-running {@code VpnService} instances are known to occasionally
 * wedge in such a way that subsequent writes to the {@code tun} fd never
 * complete. Closing the {@link android.os.ParcelFileDescriptor} and calling
 * {@code Builder.establish()} again is the only known recovery.
 *
 * <p>Detection only fires when there is a write actually in flight (the
 * "double condition"): an idle device must not trip the watchdog. Time
 * since the last successful write is intentionally NOT used as the
 * signal -- that would also fire during legitimate quiet periods.
 *
 * <p>After firing, the watchdog enters a cooldown window so the recovery
 * code path has time to tear down and re-establish the tunnel without
 * being triggered repeatedly.
 */
public class StallWatchdog {

    public interface StallListener {
        void onStallDetected(long inflightMs);
    }

    private static final String TAG = StallWatchdog.class.getSimpleName();

    // An idle device's vpnInterface.write() should never take anywhere
    // near this long. 10s is well above any normal burst, GC pause, or
    // scheduler hiccup.
    private static final long DEFAULT_STALL_THRESHOLD_MS = 10_000L;
    private static final long DEFAULT_CHECK_INTERVAL_MS = 1_000L;
    // Once a restart is triggered, suppress further triggers until the
    // new VpnInterface has had a chance to come up.
    private static final long DEFAULT_COOLDOWN_MS = 30_000L;

    // 0 means "no write currently in flight"; otherwise it stores the
    // start timestamp of the inflight write (millis since epoch).
    private final AtomicLong lastWriteStart = new AtomicLong(0L);

    private final long stallThresholdMs;
    private final long checkIntervalMs;
    private final long cooldownMs;
    private final StallListener listener;

    private Thread watcherThread;
    private volatile boolean running;
    private long lastTriggerTime;

    public StallWatchdog(StallListener listener) {
        this(listener, DEFAULT_STALL_THRESHOLD_MS, DEFAULT_CHECK_INTERVAL_MS, DEFAULT_COOLDOWN_MS);
    }

    public StallWatchdog(StallListener listener, long stallThresholdMs,
                         long checkIntervalMs, long cooldownMs) {
        this.listener = listener;
        this.stallThresholdMs = stallThresholdMs;
        this.checkIntervalMs = checkIntervalMs;
        this.cooldownMs = cooldownMs;
    }

    /** Called by the writer thread immediately before the blocking I/O. */
    public void noteWriteStart() {
        lastWriteStart.set(System.currentTimeMillis());
    }

    /** Called by the writer thread immediately after the blocking I/O returns. */
    public void noteWriteEnd() {
        lastWriteStart.set(0L);
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        lastTriggerTime = 0L;
        watcherThread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "StallWatchdog");
        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (watcherThread != null) {
            watcherThread.interrupt();
            watcherThread = null;
        }
        // reset state so a future start() begins clean
        lastWriteStart.set(0L);
    }

    private void loop() {
        while (running) {
            try {
                Thread.sleep(checkIntervalMs);
            } catch (InterruptedException e) {
                return;
            }
            long start = lastWriteStart.get();
            if (start == 0L) {
                // no write in flight -> not stalled (idle case)
                continue;
            }
            long inflight = System.currentTimeMillis() - start;
            if (inflight <= stallThresholdMs) {
                continue;
            }
            long now = System.currentTimeMillis();
            if (now - lastTriggerTime < cooldownMs) {
                // already triggered recently; let recovery finish
                continue;
            }
            lastTriggerTime = now;
            Log.w(TAG, "tunnel-to-device write blocked for " + inflight
                    + "ms; triggering VPN restart");
            try {
                listener.onStallDetected(inflight);
            } catch (Throwable t) {
                Log.e(TAG, "Stall listener failed", t);
            }
        }
    }
}
