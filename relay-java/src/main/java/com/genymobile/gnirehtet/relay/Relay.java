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

package com.genymobile.gnirehtet.relay;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Set;

public class Relay {

    private static final String TAG = Relay.class.getSimpleName();

    private static final int CLEANING_INTERVAL = 60 * 1000;
    // Emit a periodic snapshot of per-client connection counts so that
    // saturation (approaching the fd rlimit) is observable without enabling
    // debug logs.
    private static final int STATS_INTERVAL = 5 * 60 * 1000;

    private final int port;

    public Relay(int port) {
        this.port = port;
    }

    public void run() throws IOException {
        Selector selector = Selector.open();

        // will register the socket on the selector
        TunnelServer tunnelServer = new TunnelServer(port, selector);

        Log.i(TAG, "Relay server started");

        long start = System.currentTimeMillis();
        long nextCleaningDeadline = start + UDPConnection.IDLE_TIMEOUT;
        long nextStatsDeadline = start + STATS_INTERVAL;
        while (true) {
            long now = System.currentTimeMillis();
            long nextDeadline = Math.min(nextCleaningDeadline, nextStatsDeadline);
            long timeout = Math.max(0, nextDeadline - now);
            selector.select(timeout);
            Set<SelectionKey> selectedKeys = selector.selectedKeys();

            now = System.currentTimeMillis();
            if (now >= nextCleaningDeadline || selectedKeys.isEmpty()) {
                tunnelServer.cleanUp();
                nextCleaningDeadline = now + CLEANING_INTERVAL;
            }
            if (now >= nextStatsDeadline) {
                tunnelServer.logStats();
                nextStatsDeadline = now + STATS_INTERVAL;
            }

            for (SelectionKey selectedKey : selectedKeys) {
                SelectionHandler selectionHandler = (SelectionHandler) selectedKey.attachment();
                selectionHandler.onReady(selectedKey);
            }
            // by design, we handled everything
            selectedKeys.clear();
        }
    }
}
