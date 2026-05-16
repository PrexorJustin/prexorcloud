package me.prexorjustin.prexorcloud.api.module.message;

import java.util.List;
import java.util.UUID;

/**
 * Public service interface for the message module. Published to the
 * {@link me.prexorjustin.prexorcloud.api.module.service.ServiceRegistry} so
 * controller routes (proxy, plugin) can delegate to the module without a hard
 * compile-time dependency on the module JAR.
 */
public interface MessageDeliveryApi {

    String sendMessage(UUID fromUuid, String fromName, UUID toUuid, String toName, String content, String replyToId);

    List<PendingMessage> getPendingForProxy(String proxyInstanceId);

    void markDelivered(String messageId);

    void markRead(String messageId);

    boolean isBlocked(UUID senderUuid, UUID recipientUuid);

    record PendingMessage(
            String id, UUID toUuid, String toName, UUID fromUuid, String fromName, String content, String replyToId) {}
}
