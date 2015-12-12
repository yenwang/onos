/*
 * Copyright 2015 Open Networking Laboratory
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

package org.onosproject.netconf.ctl;

import ch.ethz.ssh2.Connection;
import ch.ethz.ssh2.Session;
import com.google.common.base.Preconditions;
import org.onosproject.netconf.NetconfDeviceInfo;
import org.onosproject.netconf.NetconfDeviceOutputEvent;
import org.onosproject.netconf.NetconfDeviceOutputEventListener;
import org.onosproject.netconf.NetconfException;
import org.onosproject.netconf.NetconfSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * Implementation of a NETCONF session to talk to a device.
 */
public class NetconfSessionImpl implements NetconfSession {

    private static final Logger log = LoggerFactory
            .getLogger(NetconfSessionImpl.class);


    private static final int CONNECTION_TIMEOUT = 0;
    private static final String ENDPATTERN = "]]>]]>";
    private static final AtomicInteger MESSAGE_ID_INTEGER = new AtomicInteger(0);
    private static final String MESSAGE_ID_STRING = "message-id";
    private static final String HELLO = "hello";
    private static final String NEW_LINE = "\n";


    private Connection netconfConnection;
    private NetconfDeviceInfo deviceInfo;
    private Session sshSession;
    private boolean connectionActive;
    private PrintWriter out = null;
    private List<String> deviceCapabilities =
            Collections.singletonList("urn:ietf:params:netconf:base:1.0");
    private String serverCapabilities;
    private NetconfStreamHandler t;
    private Map<Integer, CompletableFuture<String>> replies;


    public NetconfSessionImpl(NetconfDeviceInfo deviceInfo) throws NetconfException {
        this.deviceInfo = deviceInfo;
        connectionActive = false;
        replies = new HashMap<>();
        startConnection();
    }


    private void startConnection() throws NetconfException {
        if (!connectionActive) {
            netconfConnection = new Connection(deviceInfo.ip().toString(), deviceInfo.port());
            try {
                netconfConnection.connect(null, CONNECTION_TIMEOUT, 5000);
            } catch (IOException e) {
                throw new NetconfException("Cannot open a connection with device" + deviceInfo, e);
            }
            boolean isAuthenticated;
            try {
                if (deviceInfo.getKeyFile() != null) {
                    isAuthenticated = netconfConnection.authenticateWithPublicKey(
                            deviceInfo.name(), deviceInfo.getKeyFile(),
                            deviceInfo.password());
                } else {
                    log.debug("Authenticating to device {} with username {}",
                              deviceInfo.getDeviceId(), deviceInfo.name(), deviceInfo.password());
                    isAuthenticated = netconfConnection.authenticateWithPassword(
                            deviceInfo.name(), deviceInfo.password());
                }
            } catch (IOException e) {
                log.error("Authentication connection to device " +
                                  deviceInfo.getDeviceId() + " failed:" +
                                  e.getMessage());
                throw new NetconfException("Authentication connection to device " +
                                                   deviceInfo.getDeviceId() + " failed", e);
            }

            connectionActive = true;
            Preconditions.checkArgument(isAuthenticated,
                                        "Authentication to device {} with username " +
                                                "{} Failed",
                                        deviceInfo.getDeviceId(), deviceInfo.name(),
                                        deviceInfo.password());
            startSshSession();
        }
    }

    private void startSshSession() throws NetconfException {
        try {
            sshSession = netconfConnection.openSession();
            sshSession.startSubSystem("netconf");
            out = new PrintWriter(sshSession.getStdin());
            t = new NetconfStreamThread(sshSession.getStdout(), sshSession.getStdin(),
                                        sshSession.getStderr(), deviceInfo,
                                        new NetconfSessionDelegateImpl());
            this.addDeviceOutputListener(new NetconfDeviceOutputEventListenerImpl(deviceInfo));
            sendHello();
        } catch (IOException e) {
            log.error("Failed to create ch.ethz.ssh2.Session session:" +
                              e.getMessage());
            throw new NetconfException("Failed to create ch.ethz.ssh2.Session session with device" +
                                               deviceInfo, e);
        }
    }

    private void sendHello() throws IOException {
        serverCapabilities = sendRequest(createHelloString());
    }

    private String createHelloString() {
        StringBuilder hellobuffer = new StringBuilder();
        hellobuffer.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        hellobuffer.append("<hello xmlns=\"urn:ietf:params:xml:ns:netconf:base:1.0\">\n");
        hellobuffer.append("  <capabilities>\n");
        deviceCapabilities.forEach(
                cap -> hellobuffer.append("    <capability>" + cap + "</capability>\n"));
        hellobuffer.append("  </capabilities>\n");
        hellobuffer.append("</hello>\n");
        hellobuffer.append(ENDPATTERN);
        return hellobuffer.toString();

    }

    private void checkAndRestablishSession() throws NetconfException {
        if (sshSession.getState() != 2) {
            try {
                startSshSession();
            } catch (IOException e) {
                log.debug("The connection with {} had to be reopened", deviceInfo.getDeviceId());
                try {
                    startConnection();
                } catch (IOException e2) {
                    log.error("No connection {} for device, exception {}", netconfConnection, e2);
                    throw new NetconfException("Cannot re-open the connection with device" + deviceInfo, e);
                }
            }
        }
    }

    @Override
    public String requestSync(String request) throws NetconfException {
        String reply = sendRequest(request + NEW_LINE + ENDPATTERN);
        return checkReply(reply) ? reply : "ERROR " + reply;
    }

    @Override
    public CompletableFuture<String> request(String request) {
        CompletableFuture<String> ftrep = t.sendMessage(request);
        replies.put(MESSAGE_ID_INTEGER.get(), ftrep);
        return ftrep;
    }

    private String sendRequest(String request) throws NetconfException {
        checkAndRestablishSession();
        //FIXME find out a better way to enforce the presence of message-id
        if (!request.contains(MESSAGE_ID_STRING) && !request.contains(HELLO)) {
            request = request.replaceFirst("\">", "\" message-id=\""
                    + MESSAGE_ID_INTEGER.get() + "\"" + ">");
        }
        CompletableFuture<String> futureReply = request(request);
        MESSAGE_ID_INTEGER.incrementAndGet();
        String rp = futureReply.join();
        log.debug("Reply from device {}", rp);
        return rp;
    }

    @Override
    public String get(String request) throws NetconfException {
        return requestSync(request);
    }

    @Override
    public String getConfig(String targetConfiguration) throws NetconfException {
        return getConfig(targetConfiguration, null);
    }

    @Override
    public String getConfig(String targetConfiguration, String configurationSchema) throws NetconfException {
        StringBuilder rpc = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        rpc.append("<rpc message-id=\"" + MESSAGE_ID_INTEGER.get() + "\"  "
                           + "xmlns=\"urn:ietf:params:xml:ns:netconf:base:1.0\">\n");
        rpc.append("<get-config>\n");
        rpc.append("<source>\n");
        rpc.append("<" + targetConfiguration + "/>");
        rpc.append("</source>");
        if (configurationSchema != null) {
            rpc.append("<filter type=\"subtree\">\n");
            rpc.append(configurationSchema + "\n");
            rpc.append("</filter>\n");
        }
        rpc.append("</get-config>\n");
        rpc.append("</rpc>\n");
        rpc.append(ENDPATTERN);
        String reply = sendRequest(rpc.toString());
        return checkReply(reply) ? reply : "ERROR " + reply;
    }

    @Override
    public boolean editConfig(String newConfiguration) throws NetconfException {
        newConfiguration = newConfiguration + ENDPATTERN;
        return checkReply(sendRequest(newConfiguration));
    }

    @Override
    public boolean editConfig(String targetConfiguration, String mode, String newConfiguration)
            throws NetconfException {
        newConfiguration = newConfiguration.trim();
        StringBuilder rpc = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        rpc.append("<rpc message-id=\"" + MESSAGE_ID_INTEGER.get() + "\"  "
                           + "xmlns=\"urn:ietf:params:xml:ns:netconf:base:1.0\">\n");
        rpc.append("<edit-config>");
        rpc.append("<target>");
        rpc.append("<" + targetConfiguration + "/>");
        rpc.append("</target>");
        rpc.append("<default-operation>");
        rpc.append(mode);
        rpc.append("</default-operation>");
        rpc.append("<config>");
        rpc.append(newConfiguration);
        rpc.append("</config>");
        rpc.append("</edit-config>");
        rpc.append("</rpc>");
        rpc.append(ENDPATTERN);
        return checkReply(sendRequest(rpc.toString()));
    }

    @Override
    public boolean copyConfig(String targetConfiguration, String newConfiguration)
            throws NetconfException {
        newConfiguration = newConfiguration.trim();
        if (!newConfiguration.startsWith("<configuration>")) {
            newConfiguration = "<configuration>" + newConfiguration
                    + "</configuration>";
        }
        StringBuilder rpc = new StringBuilder("<?xml version=\"1.0\" " +
                                                      "encoding=\"UTF-8\"?>");
        rpc.append("<rpc>");
        rpc.append("<copy-config>");
        rpc.append("<target>");
        rpc.append("<" + targetConfiguration + "/>");
        rpc.append("</target>");
        rpc.append("<source>");
        rpc.append("<" + newConfiguration + "/>");
        rpc.append("</source>");
        rpc.append("</copy-config>");
        rpc.append("</rpc>");
        rpc.append(ENDPATTERN);
        return checkReply(sendRequest(rpc.toString()));
    }

    @Override
    public boolean deleteConfig(String targetConfiguration) throws NetconfException {
        if (targetConfiguration.equals("running")) {
            log.warn("Target configuration for delete operation can't be \"running\"",
                     targetConfiguration);
            return false;
        }
        StringBuilder rpc = new StringBuilder("<?xml version=\"1.0\" " +
                                                      "encoding=\"UTF-8\"?>");
        rpc.append("<rpc>");
        rpc.append("<delete-config>");
        rpc.append("<target>");
        rpc.append("<" + targetConfiguration + "/>");
        rpc.append("</target>");
        rpc.append("</delete-config>");
        rpc.append("</rpc>");
        rpc.append(ENDPATTERN);
        return checkReply(sendRequest(rpc.toString()));
    }

    @Override
    public boolean lock() throws NetconfException {
        StringBuilder rpc = new StringBuilder("<?xml version=\"1.0\" " +
                                                      "encoding=\"UTF-8\"?>");
        rpc.append("<rpc>");
        rpc.append("<lock>");
        rpc.append("<target>");
        rpc.append("<candidate/>");
        rpc.append("</target>");
        rpc.append("</lock>");
        rpc.append("</rpc>");
        rpc.append(ENDPATTERN);
        return checkReply(sendRequest(rpc.toString()));
    }

    @Override
    public boolean unlock() throws NetconfException {
        StringBuilder rpc = new StringBuilder("<?xml version=\"1.0\" " +
                                                      "encoding=\"UTF-8\"?>");
        rpc.append("<rpc>");
        rpc.append("<unlock>");
        rpc.append("<target>");
        rpc.append("<candidate/>");
        rpc.append("</target>");
        rpc.append("</unlock>");
        rpc.append("</rpc>");
        rpc.append(ENDPATTERN);
        return checkReply(sendRequest(rpc.toString()));
    }

    @Override
    public boolean close() throws NetconfException {
        return close(false);
    }

    private boolean close(boolean force) throws NetconfException {
        StringBuilder rpc = new StringBuilder();
        rpc.append("<rpc>");
        if (force) {
            rpc.append("<kill-configuration/>");
        } else {
            rpc.append("<close-configuration/>");
        }
        rpc.append("<close-configuration/>");
        rpc.append("</rpc>");
        rpc.append(ENDPATTERN);
        return checkReply(sendRequest(rpc.toString())) || close(true);
    }

    @Override
    public String getSessionId() {
        if (serverCapabilities.contains("<session-id>")) {
            String[] outer = serverCapabilities.split("<session-id>");
            Preconditions.checkArgument(outer.length != 1,
                                        "Error in retrieving the session id");
            String[] value = outer[1].split("</session-id>");
            Preconditions.checkArgument(value.length != 1,
                                        "Error in retrieving the session id");
            return value[0];
        } else {
            return String.valueOf(-1);
        }
    }

    @Override
    public String getServerCapabilities() {
        return serverCapabilities;
    }

    @Override
    public void setDeviceCapabilities(List<String> capabilities) {
        deviceCapabilities = capabilities;
    }

    @Override
    public void addDeviceOutputListener(NetconfDeviceOutputEventListener listener) {
        t.addDeviceEventListener(listener);
    }

    @Override
    public void removeDeviceOutputListener(NetconfDeviceOutputEventListener listener) {
        t.removeDeviceEventListener(listener);
    }

    private boolean checkReply(String reply) throws NetconfException {
        if (reply != null) {
            if (!reply.contains("<rpc-error>")) {
                return true;
            } else if (reply.contains("<ok/>")
                    || (reply.contains("<rpc-error>")
                    && reply.contains("warning"))) {
                return true;
            }
        }
        log.warn("Device " + deviceInfo + "has error in reply {}", reply);
        return false;
    }

    public class NetconfSessionDelegateImpl implements NetconfSessionDelegate {

        @Override
        public void notify(NetconfDeviceOutputEvent event) {
            CompletableFuture<String> completedReply = replies.get(event.getMessageID());
            completedReply.complete(event.getMessagePayload());
        }
    }


}
