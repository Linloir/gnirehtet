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
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public class Router {

    private static final String TAG = Router.class.getSimpleName();

    private final Client client;
    private final Selector selector;

    // HashMap for O(1) lookup. Under load a single client can accumulate thousands of flows,
    // so the original "few connections per client" assumption (List + linear scan) becomes the
    // hot path for every packet. LinkedHashMap keeps iteration order stable for debugging.
    private final Map<ConnectionId, Connection> connections = new LinkedHashMap<>();

    public Router(Client client, Selector selector) {
        this.client = client;
        this.selector = selector;
    }

    public void sendToNetwork(IPv4Packet packet) {
        if (!packet.isValid()) {
            Log.w(TAG, "Dropping invalid packet");
            if (Log.isVerboseEnabled()) {
                Log.v(TAG, Binary.buildPacketString(packet.getRaw()));
            }
            return;
        }
        try {
            Connection connection = getConnection(packet.getIpv4Header(), packet.getTransportHeader());
            connection.sendToNetwork(packet);
        } catch (IOException e) {
            Log.e(TAG, "Cannot create connection, dropping packet (active connections: " + connections.size() + ")", e);
        }
    }

    private Connection getConnection(IPv4Header ipv4Header, TransportHeader transportHeader) throws IOException {
        ConnectionId id = ConnectionId.from(ipv4Header, transportHeader);
        Connection connection = connections.get(id);
        if (connection == null) {
            connection = createConnection(id, ipv4Header, transportHeader);
            connections.put(id, connection);
        }
        return connection;
    }

    private Connection createConnection(ConnectionId id, IPv4Header ipv4Header, TransportHeader transportHeader) throws IOException {
        IPv4Header.Protocol protocol = id.getProtocol();
        if (protocol == IPv4Header.Protocol.UDP) {
            return new UDPConnection(id, client, selector, ipv4Header, (UDPHeader) transportHeader);
        }
        if (protocol == IPv4Header.Protocol.TCP) {
            return new TCPConnection(id, client, selector, ipv4Header, (TCPHeader) transportHeader);
        }
        throw new UnsupportedOperationException("Unsupported protocol: " + protocol);
    }

    public void clear() {
        for (Connection connection : connections.values()) {
            connection.disconnect();
        }
        connections.clear();
    }

    public void remove(Connection connection) {
        if (connections.remove(connection.getId()) == null) {
            throw new AssertionError("Removed a connection unknown from the router");
        }
    }

    public void cleanExpiredConnections() {
        int before = connections.size();
        int expired = 0;
        Iterator<Map.Entry<ConnectionId, Connection>> it = connections.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ConnectionId, Connection> entry = it.next();
            Connection connection = entry.getValue();
            if (connection.isExpired()) {
                Log.d(TAG, "Remove expired connection: " + connection.getId());
                connection.disconnect();
                it.remove();
                ++expired;
            }
        }
        if (expired > 0) {
            Log.i(TAG, "Expired connections cleaned: " + expired + " (active: " + connections.size() + ", was: " + before + ")");
        }
    }

    public int activeCount() {
        return connections.size();
    }

    /**
     * @return counts in the order {tcp, udp, other}
     */
    public int[] protocolCounts() {
        int tcp = 0;
        int udp = 0;
        int other = 0;
        for (ConnectionId id : connections.keySet()) {
            switch (id.getProtocol()) {
                case TCP:
                    ++tcp;
                    break;
                case UDP:
                    ++udp;
                    break;
                default:
                    ++other;
                    break;
            }
        }
        return new int[]{tcp, udp, other};
    }
}
