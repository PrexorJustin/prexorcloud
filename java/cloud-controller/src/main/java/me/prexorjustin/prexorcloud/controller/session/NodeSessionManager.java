package me.prexorjustin.prexorcloud.controller.session;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-safe management of active daemon sessions.
 */
public final class NodeSessionManager {

    private static final Logger logger = LoggerFactory.getLogger(NodeSessionManager.class);

    private final Map<String, NodeSession> sessionsBySessionId = new ConcurrentHashMap<>();
    private final Map<String, NodeSession> sessionsByNodeId = new ConcurrentHashMap<>();

    public Optional<NodeSession> register(NodeSession session) {
        sessionsBySessionId.put(session.sessionId(), session);
        NodeSession replaced = sessionsByNodeId.put(session.nodeId(), session);
        if (replaced != null && !replaced.sessionId().equals(session.sessionId())) {
            sessionsBySessionId.remove(replaced.sessionId(), replaced);
            logger.debug(
                    "Session {} replaced existing session {} for node {}",
                    session.sessionId(),
                    replaced.sessionId(),
                    session.nodeId());
        }
        logger.debug("Session registered: {} (node={})", session.sessionId(), session.nodeId());
        return Optional.ofNullable(replaced);
    }

    public boolean invalidate(String sessionId) {
        var session = sessionsBySessionId.remove(sessionId);
        if (session != null) {
            boolean removedCurrent = sessionsByNodeId.remove(session.nodeId(), session);
            logger.debug("Session invalidated: {} (node={})", sessionId, session.nodeId());
            return removedCurrent;
        }
        return false;
    }

    public Optional<NodeSession> getBySessionId(String sessionId) {
        return Optional.ofNullable(sessionsBySessionId.get(sessionId));
    }

    public Optional<NodeSession> getByNodeId(String nodeId) {
        return Optional.ofNullable(sessionsByNodeId.get(nodeId));
    }

    public Collection<NodeSession> allSessions() {
        return Collections.unmodifiableCollection(sessionsBySessionId.values());
    }

    public int sessionCount() {
        return sessionsBySessionId.size();
    }
}
